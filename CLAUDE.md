# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Penstock** (formerly `ai-coding-agent`; repo `enrichmeai/penstock`, image `ghcr.io/enrichmeai/penstock`). Spring Boot 3 + Java 21 agent that runs an LLM-driven tool-use loop over a sandboxed workspace. Exposes a web UI, a REST API, and an SSE streaming endpoint. Pluggable LLM provider (`copilot` default; `anthropic` / `openai` / `ollama`) and pluggable session store (`memory` default; `sqlite` / `postgres`).

## Commands

Gradle wrapper is not checked in — run `./bootstrap.sh` once to download it. Then:

```bash
./gradlew bootRun          # run the server on :8080
./gradlew build            # compile + test + assemble jar
./gradlew test             # run all tests
./gradlew test --tests com.example.agent.tools.FileToolsTest            # single class
./gradlew test --tests com.example.agent.service.AgentServiceTest.someMethod   # single method
docker compose up --build  # containerised run (SQLite persisted to ./data)
```

Local toolchain traps on this machine: Gradle 8.x cannot compile build scripts under the default JDK 25 — export `JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem` first (the failure, `Unsupported class file major version 69`, only appears when the build-script cache invalidates, so it looks like your change broke it). Docker is 20.10, whose legacy builder chokes on the Dockerfile's `$BUILDPLATFORM` — prefix image builds with `DOCKER_BUILDKIT=1`.

Minimum env for `bootRun`: either `GITHUB_COPILOT_TOKEN` (default provider), or `ANTHROPIC_API_KEY` + `AGENT_LLM_PROVIDER=anthropic` / `OPENAI_API_KEY` + `AGENT_LLM_PROVIDER=openai` / `AGENT_LLM_PROVIDER=ollama`. Set `AGENT_WORKSPACE=/abs/path` to the directory the agent is allowed to touch.

Enable SQLite persistence with `AGENT_STORAGE_TYPE=sqlite` — this activates the `storage-sqlite` Spring profile and Flyway migrations under `src/main/resources/db/migration/sqlite`. For production, use `AGENT_STORAGE_TYPE=postgres` — activates the `storage-postgres` profile, runs migrations under `src/main/resources/db/migration/postgres`, and reads `AGENT_DB_URL` / `AGENT_DB_USER` / `AGENT_DB_PASSWORD`. The JPA entities are vendor-agnostic; only the Flyway scripts diverge per vendor (any new migration needs a sibling in both directories). In `memory` mode, `AgentApplication.main` excludes JPA/DataSource auto-config before `SpringApplication.run` so Hibernate won't try to start.

## Architecture

The agent loop lives in `AgentService.runTurn` (`src/main/java/com/example/agent/service/AgentService.java`):

1. Append user message, persist via `SessionStore`, emit.
2. Call `LlmProvider.complete(systemPrompt, windowedHistory, toolSpecs, sessionId)`.
3. If assistant returned tool calls → invoke each via `ToolRegistry`, append TOOL message, loop.
4. Otherwise, assistant text is the final reply.
5. Enforced bounds: `max-turns-per-request`, `max-tokens-per-request`, `max-tokens-per-session`.

Streaming (`chatStreaming`) uses the same loop with a `Consumer<ChatMessage>` callback; `AgentController` relays each callback invocation as an SSE `message` event. Consumer exceptions propagate so the loop unwinds on client disconnect.

History windowing (`AgentService.windowedHistory`) applies `agent.llm.context.policy=last-n` but never starts the window on an orphaned `TOOL` message or an `ASSISTANT` tool-call without its matching `TOOL` response — violating this pattern breaks tool_use blocks on Anthropic/OpenAI.

### Key extension points

- **New tool**: implement `com.example.agent.tools.Tool` and annotate `@Component`. `ToolRegistry` auto-discovers `Tool` beans. See `JiraTool.java` as the reference pattern for integrating external systems.
- **New LLM provider**: implement `com.example.agent.llm.LlmProvider`, annotate `@Component` + `@ConditionalOnProperty(name="agent.llm.provider", havingValue="<your-name>")`, set `agent.llm.provider=<your-name>`. Providers live under `src/main/java/com/example/agent/llm/{copilot,anthropic,openai,ollama}/`.
- **Retries**: providers should route transient failures through `LlmRetry` (exponential backoff on 429/5xx, honours `Retry-After`).

### Cross-cutting concerns

Order matters — filters and advice in `com.example.agent.config`:

- `SecurityConfig` — HTTP Basic/OIDC gated by `agent.auth.enabled`; `/api/health` is always open. No shipped password: unset (or the refused historical `change-me`) generates a random password logged once at startup. CORS is default-locked — `agent.cors.allowed-origins` grants listed origins *with* credentials, `*` grants any origin *without* credentials; the credentialed-wildcard combination is deliberately inexpressible (the bundled UI is same-origin and needs no CORS at all).
- `RateLimitFilter` + `RateLimitConfig` — Bucket4j, separate buckets for `/api/chat*` vs `/api/**`, keyed by principal-else-IP, emits `429 Retry-After`.
- `RequestIdFilter` — reads/generates `X-Request-Id`, stamps MDC (`requestId`, `userId`, `sessionId`), echoes back on response.
- `ErrorAdvice` — `@RestControllerAdvice` that returns `ApiError` JSON; messages are sanitised unless the exception class is annotated `@SafeMessage`.
- `SseEmitterRegistry` — tracks in-flight emitters; on `ContextClosedEvent` emits a terminating `shutdown` event and completes each emitter (avoids client socket resets on graceful shutdown).
- `LlmProviderHealthIndicator` — config-only check (no external calls); wired into `/actuator/health/readiness` so misconfigured providers pull the instance from load balancers without killing `/liveness`.

### Sandboxing invariants

- Every file path in every tool resolves through `WorkspacePath` — rooted at `agent.workspace`, rejects traversal outside. If you add a new tool that touches the filesystem, route its paths through `WorkspacePath` or you will break this guarantee.
- `ShellTool` applies `agent.tools.shell.blocked-patterns` (regex) and an optional `allowed-commands` allow-list. Kill switch: `AGENT_TOOLS_SHELL_ENABLED=false`. Default block-list already covers `curl`/`wget`/`nc`/`ssh`/`scp`/`rsync`/`ftp`/`nohup`/`rm -rf /`/`mkfs`/`dd if=`/`/etc/shadow`/`~/.ssh` — prefer extending the list over narrowing it.
- `GitTool` whitelists a small subcommand set; no `push`, `reset --hard`, or `rebase`.

### Session ownership

Sessions are stamped with the authenticated user (or `anonymous` when auth is off). Cross-user access returns `404` — never leak existence via `403`. When touching `SessionStore` or controllers, preserve this behaviour.

### Identity on background threads

The SSE agent loop runs on `sseTaskExecutor` and audit writes are `@Async` — neither thread has a `SecurityContext`, so `CurrentUser`/`SecurityContextHolder` reads there silently return `anonymous`. The invariant: identity is resolved once on the request thread (it *is* `session.getUserId()`, stamped at `create()` and verified by the owner-scoped `get()`) and passed explicitly — `AuditLogger.toolCall/llmCall` take a `userId` parameter, `ToolRegistry.invoke` has a 3-arg overload, and `JpaSessionStore.update` scopes by the session's own userId. The `llm_call` audit lives centrally in `AgentService`, **not** in providers — a new provider must not call `AuditLogger`. `IdentityPropagationIT` guards the executor boundary; a stub-injected unit test cannot catch this class of bug.

### Observability

- Metrics at `/actuator/prometheus`: `llm_calls_total{provider,outcome}`, `llm_tokens_total{provider,kind}`, `tool_calls_total{tool,outcome}`, `tool_call_duration_ms{tool}`, `sse_streams_active`, `sse_heartbeats_sent_total{outcome}`.
- `AuditLogger` persists every LLM call and tool invocation to `audit_events` (JPA modes only), attributed to the explicit `userId` its callers pass (see *Identity on background threads*). Owner-scoped read at `GET /api/sessions/{id}/audit`.
- JSON logs under `-Dspring.profiles.active=prod`; plain text otherwise. Structure is in `src/main/resources/logback-spring.xml`.

## Tests

- `FileToolsTest`, `ShellToolTest`, `ToolRegistryTest` — tool layer incl. path-traversal and ambiguous-edit guards.
- `AgentServiceTest` — tool-use loop with a scripted fake provider; token accounting.
- `AgentControllerIT`, `ChatStreamIT` — REST + SSE integration with a stub LLM.
- `JpaSessionStoreTest` — JPA slice against H2 (SQLite schema is validated via Flyway in prod only; H2 is used in tests).
- `SecurityConfigTest` — nested Spring Boot tests for auth on/off.
- `RateLimitFilterTest`, `RequestIdFilterTest`, `ErrorAdviceTest`, `LlmProviderHealthIndicatorTest`, `AgentMetricsTest`, `AuditLoggerTest` — cross-cutting concerns.

## Releasing

Push a `v*` tag → `.github/workflows/release.yml` builds and tests the jar (version comes from the tag via `-PreleaseVersion`; bump the `build.gradle` default in the same PR for consistency), smoke-tests the amd64 image *as shipped* (bare `docker run`, `/api/health`) before anything publishes, pushes a multi-arch image to `ghcr.io/enrichmeai/penstock` (`:<version>` + `:latest`; the Dockerfile build stage is pinned to `$BUILDPLATFORM` so tests never run under QEMU), and creates a GitHub Release with the jar, a CycloneDX SBOM, and a Trivy report. Lore: GitHub Actions pins for `aquasecurity/trivy-action` are `v`-prefixed; a failed run publishes nothing, so moving the tag and re-pushing is safe then. `build.gradle` carries a `tomcat.version` override (CVE fix above the managed BOM) — remove it when a Boot BOM managing ≥ that version is adopted. The Trivy report scans the whole image including `/opt/gradle-seed` and the bundled Gradle distribution, so its totals overstate the runtime-reachable surface — decompose by location (`agent.jar` vs seed/dist jars) before reacting to headline numbers.

## Gotchas

- `spring.jpa` is declared unconditionally in `application.yml`, but JPA only activates when `AGENT_STORAGE_TYPE=sqlite` because `AgentApplication.main` excludes the relevant auto-configs in `memory` mode. Don't move this logic into a `@Configuration` class — it must run before auto-config.
- Hibernate `ddl-auto=none` in SQLite mode — Flyway owns the schema. New migrations go in `src/main/resources/db/migration/V{N}__*.sql`. SQLite does not support transactional DDL (`flyway.group: false`).
- SSE endpoint returns `event: done` (not `[DONE]`) as the terminator; the UI in `src/main/resources/static/` consumes it via `fetch()` + `ReadableStream` (EventSource can't POST).
- `SseEmitterRegistry` runs its own `ThreadPoolTaskScheduler` (not `@Scheduled`) so the heartbeat loop can be cleanly disabled by setting `agent.sse.heartbeat-interval=0`. Heartbeats are SSE comment frames — invisible to `EventSource`/`fetch` consumers but enough to keep proxies from dropping idle connections. Multi-replica deployments require `AGENT_STORAGE_TYPE=postgres` and sticky sessions at the LB (see `docs/adr/0002-multi-instance-sse.md`).
- CI (`.github/workflows/build.yml`) auto-bootstraps the Gradle wrapper if missing, then runs `./gradlew --no-daemon build`.
- **Memory-mode tests pass vacuously unless they replicate `main()`'s exclusions.** `@SpringBootTest` never runs `AgentApplication.main`, and H2 on the test classpath auto-configures a DataSource that production memory mode never has — so a test can set `agent.storage.type=memory` and still boot a context production can't produce. Any test of true memory-mode behaviour must set the same `spring.autoconfigure.exclude` list main() does; `MemoryModeStartupIT` is the reference pattern. Related: the readiness health group includes `db` only under the storage profiles — adding `db` to the default group crashes memory-mode startup (`NoSuchHealthContributorException`).
- `logback-spring.xml` defines its appender under `<springProfile name="!prod">`, **not** `default`. Activating any profile (`AGENT_STORAGE_TYPE=sqlite` turns on `storage-sqlite`) drops `default`, which would leave the root logger pointing at an undefined appender and silence every log line — including startup failures.
- SQLite mode pins `spring.datasource.hikari.maximum-pool-size=1`. SQLite takes one writer, and `AuditLogger` writes from a different thread than the agent loop, so a larger pool yields `SQLITE_BUSY` mid-conversation.
- Instant-backed columns are `INTEGER` in the SQLite migrations (V4) because the dialect writes epoch millis; declaring them `TEXT` makes every read fail with `Unparseable date`. Postgres keeps `TIMESTAMP WITH TIME ZONE`.
- **This checkout is often shared by multiple Claude sessions.** Before starting: record the current branch and confirm `git status` is clean; before finishing: restore the branch you found (or say you couldn't). Never `git add -A` in a tree you haven't just inspected — a sibling repo lost four merged PRs to exactly that (compiling green, because the reverted files included the tests). A stash labelled with someone else's branch/commit is probably their bookkeeping, not lost work — ask before dropping, inspect before assuming.
- Ollama: a model must emit Ollama's structured `tool_calls`. Several advertise `tools` and still return the call as prose, so `OllamaProvider` runs `TextToolCallParser` over the text when the structured field is empty (recovers Qwen's `<function=…>` XML and fenced JSON). Verified defaults and the containerised setup: `docs/offline-docker-compose.md`.
