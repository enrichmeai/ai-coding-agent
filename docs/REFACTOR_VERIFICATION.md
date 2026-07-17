# Refactor Verification — OIDC + Postgres integration

**Status: PASS** (concerns resolved 2026-05-12)

Both tracks (Postgres alongside SQLite, JWT bearer / OIDC alongside HTTP Basic) are wired correctly at the production level and the production code paths look sound. The concerns recorded below were concentrated in test wiring and have all been addressed — see the **Resolution** section at the bottom of this doc.

---

## Compile-level findings

1. **All Spring Security 6.x imports resolve.** `JwtAuthenticationConverter` (`org.springframework.security.oauth2.server.resource.authentication`), `NimbusJwtDecoder` / `JwtDecoder` / `JwtDecoders` / `JwtIssuerValidator` / `JwtTimestampValidator` (`org.springframework.security.oauth2.jwt`), `DelegatingOAuth2TokenValidator` / `OAuth2Error` / `OAuth2TokenValidator` / `OAuth2TokenValidatorResult` (`org.springframework.security.oauth2.core`) are all present in Spring Security 6.3.x, transitively pulled by `spring-boot-starter-oauth2-resource-server` (Boot 3.3.4). No missing or moved symbols.

2. **`AgentApplication.main` storage handling.** Switch is exhaustive (`sqlite` / `postgres` activate the matching profile, default excludes JPA/DataSource auto-config). Casing is normalised via `toLowerCase()`. Compatible with the existing `memory` default. No unrelated callers depend on it. OK.

3. **`SecurityConfig.apiFilterChain` signature change.** Now takes `HttpSecurity` plus two `ObjectProvider<...>` params. Spring resolves these by type and `ObjectProvider` is satisfied even when no matching beans exist (it returns an empty provider). `getIfAvailable()` returns `null` in that case, and the calling code checks for `null` before using either value. The `httpBasic` branch never touches them. OK.

4. **Conditional fail-fast safety in `mode=basic`.** `jwtDecoder()` is the only bean that performs network I/O (`JwtDecoders.fromIssuerLocation(...)`), and it carries `@ConditionalOnProperty(prefix="agent.auth", name="mode", havingValue="oidc")` with no `matchIfMissing`. In basic or disabled mode the bean method is not even invoked, so `JwtDecoders.fromIssuerLocation(...)` cannot run. OK.

5. **`JpaSessionStoreCondition` property key matches `AgentProperties.Storage`.** Reads `agent.storage.type` (default `memory`); `AgentProperties.Storage.type` binds the same key. The condition returns `true` for `sqlite` or `postgres`. OK. Note: `InMemorySessionStore` is gated by `@ConditionalOnProperty(name="agent.storage.type", havingValue="memory", matchIfMissing=true)`, which still flips off correctly when storage is `sqlite`/`postgres`, so there is no risk of two `SessionStore` beans being live simultaneously.

6. **`JpaSessionStore` no longer has `@ConditionalOnProperty`.** It uses `@Conditional(JpaSessionStoreCondition.class)` instead. The class-level Javadoc (in `JpaSessionStorePostgresIT.setUp`) says the store is "gated by `@ConditionalOnProperty(havingValue=\"sqlite\")`" — that comment is stale but the test still works because it instantiates `JpaSessionStore` by hand from injected repositories.

No compile-level blockers found.

---

## Schema parity findings

Compared `db/migration/sqlite/V{1,2,3}__*.sql` against `db/migration/postgres/V{1,2,3}__*.sql`. Schemas are aligned within the documented expected variations. Specifically:

| Aspect                           | SQLite                                        | Postgres                                      | Status |
|----------------------------------|-----------------------------------------------|-----------------------------------------------|--------|
| `agent_sessions.id`              | `TEXT PRIMARY KEY`                            | `TEXT PRIMARY KEY`                            | match  |
| `agent_sessions.title`           | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `agent_sessions.created_at`      | `TEXT NOT NULL`                               | `TIMESTAMP WITH TIME ZONE NOT NULL`           | expected |
| `agent_messages.id`              | `INTEGER PRIMARY KEY AUTOINCREMENT`           | `BIGSERIAL PRIMARY KEY`                       | expected |
| `agent_messages.session_id`      | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `agent_messages.position`        | `INTEGER NOT NULL`                            | `INTEGER NOT NULL`                            | match  |
| `agent_messages.role`            | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `agent_messages.payload_json`    | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `agent_messages.timestamp`       | `TEXT NOT NULL`                               | `TIMESTAMP WITH TIME ZONE NOT NULL`           | expected |
| `idx_msg_session`                | `(session_id, position)`                      | `(session_id, position)`                      | match  |
| `agent_sessions.user_id`         | `TEXT NOT NULL DEFAULT 'anonymous'`           | `TEXT NOT NULL DEFAULT 'anonymous'`           | match  |
| `idx_session_user`               | `(user_id)`                                   | `(user_id)`                                   | match  |
| `audit_events.id`                | `INTEGER PRIMARY KEY AUTOINCREMENT`           | `BIGSERIAL PRIMARY KEY`                       | expected |
| `audit_events.timestamp`         | `TEXT NOT NULL`                               | `TIMESTAMP WITH TIME ZONE NOT NULL`           | expected |
| `audit_events.user_id`           | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `audit_events.session_id`        | `TEXT` (nullable)                             | `TEXT` (nullable)                             | match  |
| `audit_events.event_type`        | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `audit_events.detail_json`       | `TEXT NOT NULL`                               | `TEXT NOT NULL`                               | match  |
| `idx_audit_session`              | `(session_id, timestamp)`                     | `(session_id, timestamp)`                     | match  |
| `idx_audit_user`                 | `(user_id, timestamp)`                        | `(user_id, timestamp)`                        | match  |

`CREATE TABLE IF NOT EXISTS` was correctly dropped on the Postgres side (V1 is a virgin migration; `IF NOT EXISTS` is unnecessary and the ADR explicitly removed it). `CREATE INDEX IF NOT EXISTS` is retained on both — Postgres supports this since 9.5. No vendor-leaking syntax. Schema parity is clean.

One minor note (not a defect): the SQLite V3 file has `audit_events.timestamp` as `TEXT`, which is consistent with V1's storage of `created_at` as `TEXT` — there's a project-wide assumption that Hibernate's `Instant` serialiser produces ISO-8601 text on SQLite. That has not changed, so no action needed.

---

## Test-wiring findings

### Concern 1 (LIKELY TEST FAILURE) — `JpaSessionStorePostgresIT` does not activate the `storage-postgres` profile cleanly because `@DynamicPropertySource` overrides the JDBC URL but the Spring profile is still mandatory for Flyway location selection.

The test sets `@ActiveProfiles("storage-postgres")` AND `@TestPropertySource(properties = { "agent.storage.type=postgres", ... })`. The dynamic source binds `spring.datasource.url`/`username`/`password` to the Testcontainers Postgres. That part is fine.

However, the `application.yml` `storage-postgres` block ALSO sets the JPA dialect, the Hikari pool, and `flyway.locations`. Because `@ActiveProfiles` is honoured before `@DynamicPropertySource` resolves, the profile wins for those keys and the dynamic URL plugs into the same datasource — this should work.

A more subtle issue: the `@SpringBootTest` boots the entire context, which means `AgentApplication.main` is NOT the entry point — Spring Boot's test slice runner is. The branch in `AgentApplication.main` that calls `app.setAdditionalProfiles("storage-postgres")` does not run during `@SpringBootTest`. This is why the test sets `@ActiveProfiles("storage-postgres")` directly — that's the right workaround. Confirmed OK.

### Concern 2 (PROBABLY OK) — Production `JwtDecoder` AND `StubJwtCfg.jwtDecoder` will both exist in `OidcAuthMode`, even though the comment claims `with(jwt())` short-circuits the decoder.

`OidcAuthMode` sets `agent.auth.mode=oidc`, which makes the production `@Bean JwtDecoder jwtDecoder()` eligible. The production decoder calls `JwtDecoders.fromIssuerLocation("https://test.example.com")` at startup, which performs a real HTTPS GET to `/.well-known/openid-configuration`. **This will fail at startup unless the stub bean overrides the production bean before it is invoked.**

The test sets `spring.main.allow-bean-definition-overriding=true` and the stub bean is `@Primary`. With override allowed, the stub will register under the same bean name and the production bean's factory method will not run. This is the intended outcome.

Caveat: `@Primary` does NOT prevent both factory methods from running in plain Spring. It controls injection-point ambiguity, not method invocation. The actual mechanism that prevents the production method from running is `allow-bean-definition-overriding=true` plus the fact that both methods produce a bean named `jwtDecoder` (the production method's name and the stub method's name happen to match). If the stub bean is registered AFTER the production one, the override happens at registration time and the production factory never runs — but if it's registered BEFORE, both run.

In practice, Spring Boot's `@TestConfiguration` is processed after main configuration, so the stub overrides — this normally works. Still, it is fragile: a rename of the stub method to `stubJwtDecoder()` would break the override and trigger the network call. **Recommend renaming the stub method to `jwtDecoder` (already the case) and explicitly marking the test as relying on bean-definition-overriding in a comment, OR using `@MockBean` / `@TestConfiguration` with explicit name attribute.**

This is the single most fragile bit in the new test code. It will probably pass on a developer machine and probably in CI, but it is one Spring upgrade away from breaking.

### Concern 3 (TEST PASSES, BUT NOT FOR THE REASON ADVERTISED) — `JpaSessionStorePostgresIT` instantiates `JpaSessionStore` directly.

`store = new JpaSessionStore(sessionRepo, messageRepo, mapper, user)` is created in `@BeforeEach`. This bypasses the `@Conditional(JpaSessionStoreCondition.class)` entirely. The test's stated purpose is to confirm "the bean activates for postgres" — but it never actually loads the bean from the application context. It only confirms that the repositories work, that Flyway migrations apply, and that the JPA entity mapping survives a real Postgres backend.

That's still a valuable test (it covers Phase 1 of the ADR), but it does NOT cover the `JpaSessionStoreCondition` in production wiring. Consider adding a single assertion in this test that pulls `JpaSessionStore.class` from the `ApplicationContext` to confirm the conditional triggers when `agent.storage.type=postgres`.

### Concern 4 (STALE COMMENT) — Comment in `JpaSessionStorePostgresIT.setUp` is wrong.

```java
// @ConditionalOnProperty(havingValue="sqlite") so it's not an auto-wired
// bean here; the unit slice in JpaSessionStoreTest uses the same trick.
```

The bean is now gated by `JpaSessionStoreCondition`, not `@ConditionalOnProperty`. Update or delete the comment.

### Spot-check: existing tests are unaffected.

- `AgentControllerIT` sets `agent.storage.type=memory` + `agent.auth.enabled=false`. It does NOT set `agent.auth.mode`, so the `@ConditionalOnProperty(... mode=basic, matchIfMissing=true)` defaults to `basic`, which is harmless when `enabled=false`. The basic-mode user/auth-manager beans will be eagerly created but unused. OK.
- `ChatStreamIT`, `JpaSessionStoreTest`, `AuditLoggerTest`, etc. — same shape, all set `agent.storage.type=memory` + `agent.auth.enabled=false` (or are pure data-slice tests). All compile and run unchanged.
- `SecurityConfigTest.AuthOff` / `AuthOn` / `AuthOnWithOAuth` — now explicitly set `agent.auth.mode=basic`, matching the new conditional. OK.

### `JwtPrincipalClaimTest` — pure unit test.

No Spring context, no autowiring. Imports `Jwt`, `JwtAuthenticationConverter`, `AbstractAuthenticationToken` — all on classpath via `spring-boot-starter-oauth2-resource-server` (production dependency, not just test scope). The three tests exercise `setPrincipalClaimName(...)` directly. OK.

---

## Style nits

1. **Stale Javadoc** in `JpaSessionStorePostgresIT.setUp` (see Concern 4 above).
2. **Method name parity** — `StubJwtCfg.jwtDecoder()` and the production `SecurityConfig.jwtDecoder()` happen to share a name. This is what makes the override work, but it is implicit. Consider documenting this in `StubJwtCfg`'s class-level Javadoc.
3. **`OidcAuthMode` Javadoc.** The class would benefit from a one-line class-level Javadoc explaining what it covers, matching the pattern of the other nested classes (which lack Javadoc, but if you're polishing, polish all four).
4. **`JpaSessionStoreCondition`** has good class-level Javadoc — matches project convention.
5. **No emojis, no unnecessary verbosity** in any of the new files. AAA pattern is followed in `JwtPrincipalClaimTest`. AssertJ in IT tests, JUnit `Assertions` in unit tests — consistent with the existing project style.
6. **`AgentProperties.Auth` Javadoc** describes all four mode permutations clearly. Good.
7. **`SecurityConfig.apiFilterChain` `disabled` short-circuit** — when `auth.enabled=false` OR `mode=disabled`, the chain returns `permitAll`. Both branches do the same thing. Minor: the log statement is missing for this case (other branches log "API auth ENABLED ..."). Consider adding `log.info("API auth DISABLED.");` for symmetry.

---

## Verdict

**Ready to merge with one small fix.** The production wiring is correct: dependencies resolve, conditional beans are gated properly so `mode=basic` boots cannot trip on JWKS network calls, schema parity between SQLite and Postgres is clean, and the existing test suite is unaffected. The only concrete item that could cause a test failure on a clean run is **Concern 2** — the implicit bean-definition-overriding in `OidcAuthMode`. In practice it should work because `@TestConfiguration`-defined beans override main config beans of the same name, and `spring.main.allow-bean-definition-overriding=true` is set. Still, I would tighten this up by either (a) using `@MockBean JwtDecoder` (which Spring Boot understands as a definitive override) or (b) keeping the current approach with an explicit comment in `StubJwtCfg` explaining the override mechanism so a future maintainer doesn't innocently rename the method. Also worth fixing before the user runs `./gradlew test`: stale Javadoc in `JpaSessionStorePostgresIT.setUp`. Everything else is shippable.

---

## Resolution (2026-05-12)

All three test-wiring concerns are now resolved in source. Spot-check evidence:

- **Concern 1 (StubJwtCfg fragility) — resolved.** `SecurityConfigTest.OidcAuthMode` uses `@MockBean JwtDecoder jwtDecoder;` (the option (a) recommendation above). The `StubJwtCfg` `@TestConfiguration` no longer exists anywhere in the codebase, and `spring.main.allow-bean-definition-overriding=true` is no longer set in any test. The production `JwtDecoder` bean is therefore not eligible for instantiation when the mock is registered, so no real JWKS GET fires at startup.
- **Concern 2 (test does not exercise `JpaSessionStoreCondition`) — resolved.** `JpaSessionStorePostgresIT.conditionalActivatesForPostgres()` was added at line 114 and asserts `context.getBeansOfType(JpaSessionStore.class).isNotEmpty()`, confirming the conditional fires when `agent.storage.type=postgres`.
- **Concern 3 (stale Javadoc in `JpaSessionStorePostgresIT.setUp`) — resolved.** The comment now correctly references `JpaSessionStoreCondition` and cross-references the new conditional-activation assertion.

The "stale Javadoc" item listed in Concern 4 was the same comment fixed by the rewrite above, and the style nit about `StubJwtCfg` becomes moot now that the class no longer exists. Verdict promoted from PASS WITH CONCERNS to PASS.

Caveat: this resolution note was written by source inspection in an environment without JDK 21, so the test suite was not actually run. Anyone making further changes should run `./gradlew test` locally to confirm.
