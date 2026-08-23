# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 3 + Java 21 agent that runs an LLM-driven tool-use loop over a sandboxed workspace. Exposes a web UI, a REST API, and an SSE streaming endpoint. Pluggable LLM provider (`copilot` default; `anthropic` / `openai` / `ollama`) and pluggable session store (`memory` default; `sqlite` / `postgres`).

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

- `SecurityConfig` — HTTP Basic gated by `agent.auth.enabled`; `/api/health` is always open.
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

### Observability

- Metrics at `/actuator/prometheus`: `llm_calls_total{provider,outcome}`, `llm_tokens_total{provider,kind}`, `tool_calls_total{tool,outcome}`, `tool_call_duration_ms{tool}`, `sse_streams_active`, `sse_heartbeats_sent_total{outcome}`.
- `AuditLogger` persists every LLM call and tool invocation to `audit_events` (SQLite mode only). Owner-scoped read at `GET /api/sessions/{id}/audit`.
- JSON logs under `-Dspring.profiles.active=prod`; plain text otherwise. Structure is in `src/main/resources/logback-spring.xml`.

## Tests

- `FileToolsTest`, `ShellToolTest`, `ToolRegistryTest` — tool layer incl. path-traversal and ambiguous-edit guards.
- `AgentServiceTest` — tool-use loop with a scripted fake provider; token accounting.
- `AgentControllerIT`, `ChatStreamIT` — REST + SSE integration with a stub LLM.
- `JpaSessionStoreTest` — JPA slice against H2 (SQLite schema is validated via Flyway in prod only; H2 is used in tests).
- `SecurityConfigTest` — nested Spring Boot tests for auth on/off.
- `RateLimitFilterTest`, `RequestIdFilterTest`, `ErrorAdviceTest`, `LlmProviderHealthIndicatorTest`, `AgentMetricsTest`, `AuditLoggerTest` — cross-cutting concerns.

## Gotchas

- `spring.jpa` is declared unconditionally in `application.yml`, but JPA only activates when `AGENT_STORAGE_TYPE=sqlite` because `AgentApplication.main` excludes the relevant auto-configs in `memory` mode. Don't move this logic into a `@Configuration` class — it must run before auto-config.
- Hibernate `ddl-auto=none` in SQLite mode — Flyway owns the schema. New migrations go in `src/main/resources/db/migration/V{N}__*.sql`. SQLite does not support transactional DDL (`flyway.group: false`).
- SSE endpoint returns `event: done` (not `[DONE]`) as the terminator; the UI in `src/main/resources/static/` consumes it via `fetch()` + `ReadableStream` (EventSource can't POST).
- `SseEmitterRegistry` runs its own `ThreadPoolTaskScheduler` (not `@Scheduled`) so the heartbeat loop can be cleanly disabled by setting `agent.sse.heartbeat-interval=0`. Heartbeats are SSE comment frames — invisible to `EventSource`/`fetch` consumers but enough to keep proxies from dropping idle connections. Multi-replica deployments require `AGENT_STORAGE_TYPE=postgres` and sticky sessions at the LB (see `docs/adr/0002-multi-instance-sse.md`).
- CI (`.github/workflows/build.yml`) auto-bootstraps the Gradle wrapper if missing, then runs `./gradlew --no-daemon build`.
- `logback-spring.xml` defines its appender under `<springProfile name="!prod">`, **not** `default`. Activating any profile (`AGENT_STORAGE_TYPE=sqlite` turns on `storage-sqlite`) drops `default`, which would leave the root logger pointing at an undefined appender and silence every log line — including startup failures.
- SQLite mode pins `spring.datasource.hikari.maximum-pool-size=1`. SQLite takes one writer, and `AuditLogger` writes from a different thread than the agent loop, so a larger pool yields `SQLITE_BUSY` mid-conversation.
- Instant-backed columns are `INTEGER` in the SQLite migrations (V4) because the dialect writes epoch millis; declaring them `TEXT` makes every read fail with `Unparseable date`. Postgres keeps `TIMESTAMP WITH TIME ZONE`.
- Ollama: a model must emit Ollama's structured `tool_calls`. Several advertise `tools` and still return the call as prose, so `OllamaProvider` runs `TextToolCallParser` over the text when the structured field is empty (recovers Qwen's `<function=…>` XML and fenced JSON). Verified defaults and the containerised setup: `docs/offline-docker-compose.md`.
