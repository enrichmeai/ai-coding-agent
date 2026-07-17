# ADR 0001 — Move authentication to OIDC and session storage to Postgres

| | |
|---|---|
| **Status** | Implemented (Phases 1 + 2 landed; cutover pending) |
| **Date** | 2026-04-21 |
| **Driver** | Production-readiness (multi-user, multi-instance) |
| **Owners** | TBD |

## Context

The agent today authenticates with HTTP Basic against a single in-memory user (`SecurityConfig.users`) and persists sessions either in memory or in a single SQLite file (`AGENT_STORAGE_TYPE=sqlite`). Both choices are appropriate for a developer prototype and unworkable for production:

- HTTP Basic + one user can't represent a real organisation; password rotation, MFA, SSO, and per-user audit are all blocked.
- SQLite is single-writer and lives on a pod-local volume, so we cannot run more than one replica without divergent state.

The other production-readiness items (TLS, CORS lockdown, secret manager, container-level shell sandbox, per-user budgets) are independent and tracked separately. This ADR covers only auth and storage.

## Decision

1. Replace HTTP Basic with **OIDC / OAuth 2.0 JWT bearer authentication**, validated by Spring Security's resource-server support against the organisation's identity provider (issuer URL + JWKS).
2. Replace the SQLite session store with **Postgres** as the production storage backend. Keep SQLite as the developer/local default (`AGENT_STORAGE_TYPE=sqlite`); add a new option `AGENT_STORAGE_TYPE=postgres`. The in-memory store stays as a third option for tests.

Both changes are gated by `agent.*` properties and rolled out behind a feature flag so the existing Basic + SQLite path keeps working until cutover.

## Non-goals

- We are not introducing OAuth client-credentials flows, machine-to-machine tokens, or token issuance — we only *consume* JWTs from an existing IdP.
- We are not migrating audit events out of the relational store; they continue to live in the same DB and will be shipped to a SIEM in a follow-up ADR.
- We are not replacing the in-memory store used by tests.
- We are not adding multi-tenancy beyond the existing per-user `userId` stamping.

## Alternatives considered

**Keep Basic auth, add a JDBC user store.** Cheaper but solves none of the real problems (no SSO, no MFA, no central revocation). Rejected.

**Use opaque tokens via OAuth 2.0 introspection.** Works, but every authenticated request becomes a network hop to the IdP. JWTs validated locally are faster and Spring Security's defaults are JWT-shaped. Reject for now; revisit if our IdP doesn't issue JWTs.

**Stay on SQLite and run a single replica.** Considered. Blocks horizontal scaling and exposes us to data loss on pod eviction. Rejected.

**Switch to MySQL.** Equivalent to Postgres; pick on org standard. Default to Postgres unless the platform team has a strong preference.

## Plan

### Phase 0 — Pre-work (½ day)

- Confirm the IdP, expected `issuer-uri`, audience, and which claim to use as the user identity (`sub`, `preferred_username`, or `email`). The `CurrentUser.name()` value flows into `userId` on every session row, so this choice is durable — pick the stable one.
- Confirm the Postgres flavour and version (Cloud SQL / RDS / on-prem). Confirm whether migrations run in-app (Flyway on startup) or as a separate job.
- Decide cutover strategy for existing SQLite data: discard (acceptable if sessions are short-lived) vs export-and-import. Most teams discard.

### Phase 1 — Postgres (1–2 days)

**Dependencies**

```gradle
runtimeOnly 'org.postgresql:postgresql'                             // add
testImplementation 'org.testcontainers:postgresql:1.20.1'           // add
testImplementation 'org.testcontainers:junit-jupiter:1.20.1'        // add
// org.xerial:sqlite-jdbc + hibernate-community-dialects: keep for dev
```

**New profile and config**

`application.yml`:

```yaml
spring:
  config:
    activate:
      on-profile: storage-postgres
  datasource:
    url:      ${AGENT_DB_URL:jdbc:postgresql://localhost:5432/agent}
    username: ${AGENT_DB_USER:agent}
    password: ${AGENT_DB_PASSWORD:}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate.ddl-auto: none
    properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration/postgres
```

**`AgentApplication.main`**

The current main excludes JPA / DataSource auto-config when storage is `memory`. Extend the check so that auto-config is only excluded for `memory`, not for `sqlite` or `postgres`. Add a guard for the unknown-storage case.

**Migrations**

Split the existing `src/main/resources/db/migration/` directory by vendor:

```
db/migration/
  sqlite/
    V1__initial_schema.sql       # current files, unchanged
    V2__session_user_id.sql
    V3__audit_events.sql
  postgres/
    V1__initial_schema.sql       # ported
    V2__session_user_id.sql
    V3__audit_events.sql
```

Postgres ports in summary:
- `TEXT` → keep, Postgres has it.
- `INTEGER PRIMARY KEY AUTOINCREMENT` → `BIGSERIAL PRIMARY KEY` (or `GENERATED ALWAYS AS IDENTITY`).
- `DATETIME` (SQLite leniency) → `TIMESTAMP WITH TIME ZONE`.
- Drop the SQLite-specific `flyway.group: false` setting; Postgres does transactional DDL.
- Indexes port directly.

Update the `storage-sqlite` profile to point at `db/migration/sqlite`.

**Code**

`JpaSessionStore`, `SessionEntity`, `AuditEventEntity` are JPA — they should not need changes. Spot-check that `Instant` columns map to `TIMESTAMP WITH TIME ZONE` cleanly (set `hibernate.jdbc.time_zone: UTC` in the profile to be safe).

**Tests**

- `JpaSessionStoreTest` stays as-is — H2 is appropriate for the unit slice.
- New `JpaSessionStorePostgresIT` using `@Testcontainers` + `@Container PostgreSQLContainer<?>` to validate that Flyway migrations apply cleanly and the store works end-to-end.
- CI: a single Testcontainers job is enough; no need to run all integration tests against Postgres unless behaviour diverges.

**Documentation**

- Update `CLAUDE.md` and `README.md` to list Postgres as a supported store, with the env vars.
- Add a one-page operator note: required env vars, expected schema, how to run Flyway out-of-band if you don't want it on startup.

### Phase 2 — OIDC (1–2 days)

**Dependencies**

```gradle
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
// Keep spring-boot-starter-security (already present).
```

**Config**

Extend `AgentProperties.Auth`:

```java
public static class Auth {
    private boolean enabled = false;
    private String mode = "basic";        // basic | oidc | disabled
    // basic-mode (legacy, transitional):
    private String username;
    private String password;
    // oidc-mode:
    private Oidc oidc = new Oidc();
}

public static class Oidc {
    private String issuerUri;             // e.g. https://accounts.google.com
    private String audience;              // optional, validated if set
    private String principalClaim = "sub";  // sub | preferred_username | email
}
```

`application.yml`:

```yaml
agent:
  auth:
    enabled: true
    mode: oidc
    oidc:
      issuer-uri: ${AGENT_OIDC_ISSUER_URI}
      audience:   ${AGENT_OIDC_AUDIENCE:}
      principal-claim: ${AGENT_OIDC_PRINCIPAL_CLAIM:sub}
```

**`SecurityConfig` rewrite**

Replace the `httpBasic(...)` branch with a `mode`-aware filter chain:

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    if (!props.getAuth().isEnabled() || "disabled".equals(props.getAuth().getMode())) {
        return permitAllChain(http);
    }

    http.csrf(c -> c.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/health",
                             "/actuator/health/**", "/actuator/prometheus",
                             "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .anyRequest().authenticated());

    if ("oidc".equals(props.getAuth().getMode())) {
        http.oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                .jwtAuthenticationConverter(jwtAuthConverter(props))));
    } else { // basic, transitional
        http.httpBasic(Customizer.withDefaults());
    }
    return http.build();
}
```

`jwtAuthConverter` reads the configured `principal-claim` and exposes it as the principal name so `Authentication.getName()` (and therefore `CurrentUser.name()`) returns the right thing without further changes.

The `UserDetailsService` and `AuthenticationManager` beans become Basic-mode-only — wrap them in `@ConditionalOnProperty(name="agent.auth.mode", havingValue="basic")`. The OIDC path doesn't need either.

**`CurrentUser`**

No code change required as long as the `principal-claim` mapping is done at filter level. Add a unit test that exercises a `JwtAuthenticationToken` with `sub=alice` and asserts `CurrentUser.name()` returns `alice`.

**Tests**

- `SecurityConfigTest` — split into three nested classes: `AuthDisabled`, `BasicAuthMode`, `OidcAuthMode`. The OIDC class uses Spring Security's test support: `SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("alice"))` to mint a token that flows through the converter.
- `AgentControllerIT`, `ChatStreamIT`, `ErrorAdviceTest` — auth is currently disabled in their config; leave that. Add one new IT that asserts a request without a JWT is `401` and one with a valid JWT for user `alice` returns `200` and creates a session owned by `alice`.
- New `JwtPrincipalClaimTest` covering each value of `principal-claim`.

### Phase 3 — Cutover (1 day, plus a staging soak)

1. Merge with `mode=basic` and `storage=sqlite` in `application.yml`. Existing behaviour, no surprises.
2. In staging, set `mode=oidc` and `storage=postgres`. Run smoke tests + the integration suite. Soak for 24–48 hours.
3. In prod, stage behind a flag flip:
   - First flip: `storage=postgres` (auth still Basic). Confirm sessions write/read.
   - Second flip: `mode=oidc`. Confirm auth.
4. After two weeks of clean run, remove the Basic-mode code path and simplify `SecurityConfig`. The legacy `username`/`password` properties stay only as deprecated config keys until the next minor version, then they go.

## Testing strategy

- **Unit**: JPA mapping + repository slice tests (H2). JWT principal-claim resolution. SecurityConfig nested tests for the three modes.
- **Integration**: One Testcontainers Postgres test confirming migrations apply and sessions round-trip. One MockMvc test confirming JWT enforcement.
- **Manual / staging**: Hit the running service with a real IdP token via `curl -H "Authorization: Bearer ${TOKEN}"`. Tail logs and confirm `userId` matches the JWT principal claim.
- **Regression**: All existing tests continue to pass with `mode=basic` for the duration of the dual-mode period.

## Rollback

- Auth: flip `agent.auth.mode=basic` (or `=disabled`). The Basic code path is still present until two weeks post-cutover.
- Storage: keep the SQLite Flyway path and connection details valid in config. Rolling back means flipping `AGENT_STORAGE_TYPE=sqlite`. Note: any sessions written to Postgres after cutover are not migrated back; this is an accepted loss.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Flyway migrations diverge between SQLite and Postgres and drift over time | Vendor-specific migration paths from day one; CI runs migrations against both. New columns require a sibling migration in both directories or a docs-policy decision to drop SQLite. |
| `Instant` ↔ `TIMESTAMP WITH TIME ZONE` mapping surprises | Set `hibernate.jdbc.time_zone: UTC` and add an explicit assertion in the IT that read-back instants are identical bytes. |
| JWT clock skew rejects valid tokens | Configure a small skew tolerance (`30s`) on `JwtTimestampValidator`. |
| `principal-claim` choice is wrong (returns email when org wanted username) | All session rows are stamped with this value. If wrong, fix forward and accept a brief mismatch period; don't mass-update existing rows unless it's policy. |
| Dual-mode auth doubles the attack surface during transition | Time-box the transition to two weeks and remove Basic afterwards. |

## Estimated effort

- Phase 0: ½ day, blocked on platform/IdP confirmations.
- Phase 1 (Postgres): 1–2 days engineering + ½ day CI plumbing.
- Phase 2 (OIDC): 1–2 days engineering + ½ day testing.
- Phase 3 (cutover): 1 day work, 2-week observation period.

Total: roughly one engineer-week for the change, plus the soak.

## Open questions

- Which IdP and which `principal-claim`?
- Does the org require client-side cert auth in addition to OIDC?
- Should audit events stay in the same Postgres database, or move to a separate write-only schema with stricter access control? (Defer; out of scope here, raise as ADR 0002.)
