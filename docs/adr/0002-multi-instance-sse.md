# ADR 0002 — Multi-instance SSE and heartbeats

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-05-12 |
| **Driver** | Phase 3.3 of the roadmap — run more than one replica behind a load balancer for real users |
| **Owners** | TBD |

## Context

Today the agent runs as a single instance with a single in-memory or single-file (SQLite) session store. Two changes unblocked horizontal scaling on paper: Postgres support landed in ADR 0001 (multi-writer, durable), and the API auth layer now accepts JWT bearer tokens (stateless). The remaining blockers for "two replicas behind a load balancer" are concentrated in the streaming path:

1. The chat-streaming endpoint (`POST /api/chat/stream`) returns an `SseEmitter` bound to the JVM that handled the original request. There is no in-band reconnection: a client that gets routed to a different pod after the initial handshake sees a fresh, empty emitter and the in-flight turn on the original pod becomes orphaned.
2. SSE streams are long-lived (10-minute timeout in `AgentController.chatStream`) and often idle for tens of seconds while the LLM thinks or a shell command runs. Most ingress controllers, cloud LBs, and corporate proxies kill idle TCP connections at 30s–120s. The client sees a half-closed socket and the agent loop on the server keeps burning tokens until it tries to flush.
3. `SseEmitterRegistry` only tracks emitters for graceful shutdown — there is no periodic activity, so even an otherwise-healthy network path will drop the stream once the LLM call exceeds the proxy's idle timeout.
4. SQLite is single-writer and pod-local. It cannot back more than one replica.

The session-store side of the failover question is already mostly handled. `JpaSessionStore.appendMessage` is atomic per message: every user message, tool call, tool response, and final assistant message is its own row insert. A pod death mid-turn loses only the in-flight assistant response that hadn't been written yet; on reconnect the client sees the persisted prefix and can ask the agent to resume. No work needed there beyond confirming the invariant and enforcing Postgres in multi-instance deployments.

## Decision

We will land Phase 3.3 as three pieces, in this order:

### 1. Keep SSE; do not move to WebSockets

WebSockets would solve heartbeats (the protocol has native ping/pong) and would, in principle, allow the server to acknowledge a reconnection on a different pod with the same session id. But:

- Spring's WebSocket support and security wiring are a meaningful detour — separate handshake, separate auth, separate framing. SSE rides on top of plain HTTP and reuses every interceptor, filter, and metric we already have.
- The browser UI in `src/main/resources/static/index.html` uses `fetch()` + `ReadableStream` because `EventSource` cannot POST. Switching to a `WebSocket` would mean rewriting the request path AND introducing a stateful protocol upgrade.
- Even on WebSockets, an in-flight turn that's running on pod A cannot be teleported to pod B mid-turn; the agent loop holds a `Thread`, an `HttpClient` to the LLM, and unsynchronised state. A reconnect on B would have to either kill the turn on A (graceful only if A is still alive) or start a new turn from the last persisted message. The latter is the only honest answer, and it works equally well with SSE plus a client-side reconnect-and-resume.

We are explicitly choosing to keep SSE and address the two real problems (idle drops, sticky routing) directly.

### 2. SSE heartbeats

`SseEmitterRegistry` becomes a scheduled component. On a fixed interval (default 15s, configurable via `agent.sse.heartbeat-interval`; `0` disables the feature) it iterates over the active emitter set and sends an SSE *comment frame* (`: hb\n\n`) to each one. Comment frames are an explicit part of the SSE spec — clients ignore them at the EventSource layer — so we keep the wire format unchanged for existing consumers. `SseEmitter.send(SseEventBuilder.comment(...))` is the right call; it does not require a named event.

Failures from `emitter.send(...)` indicate the underlying response has been closed; we catch and remove the emitter from the set rather than letting it accumulate. We also increment a new `sse_heartbeats_sent_total{outcome}` counter so we can alert on a sudden spike in `outcome=error` (proxy churn) or `outcome=ok` going flat (scheduler stuck).

Spring's `@EnableScheduling` is not currently active anywhere in the project, so it is added on a new `SchedulingConfig` class. The scheduler's default single-thread pool is fine — heartbeats are I/O against already-open sockets and the loop completes in milliseconds even for hundreds of emitters.

### 3. Sticky sessions at the load balancer

Document the requirement and let the deployment template enforce it. Two options:

- **Cookie-based affinity** on the LB (recommended). Most cloud LBs and ingress controllers can hash a cookie they inject themselves; the agent does not need to know. This is the cleanest because it survives the client's IP changing (e.g., laptop on a wifi handoff).
- **Source-IP hash** (fallback). Works without the LB injecting state but breaks when the client is behind a NAT shared with other users of the agent.

The `deploy/` directory will pick up an annotated Ingress / Service template in Phase 3.5; this ADR commits to documenting the requirement in `README.md` and the deployment notes today, even though the template lands later.

### 4. Storage requirement

Multi-instance deployments **MUST** use `AGENT_STORAGE_TYPE=postgres`. SQLite is single-writer; running two replicas against the same SQLite file (whether NFS-mounted or bind-mounted from the same volume) causes silent corruption. `LlmProviderHealthIndicator` already gates readiness on a reachable DB; we add a startup log line that warns when `storage.type=sqlite` AND the replica count cannot be ascertained from the environment. (Kubernetes deployments will surface a replica count via `KUBERNETES_PORT` + a label on the pod; outside k8s we can only warn, not enforce.)

## Consequences

**Positive**
- Existing UI, controller, registry, and metrics keep working unchanged for single-instance deployments. Heartbeats are a strict addition.
- Heartbeats also serve as an in-band liveness check: a client that stops receiving them within ~3× the interval can confidently reconnect without waiting for the 10-minute server-side timeout.
- The decision to *not* migrate to WebSockets keeps the scope of Phase 3.3 to roughly one day of work.

**Negative**
- Sticky sessions complicate canary deploys: in-flight streams continue against the old pod until they finish. Operators need to drain SSE streams before terminating a replica. The graceful-shutdown path in `SseEmitterRegistry.onShutdown` already sends a `shutdown` event; UI work to handle that gracefully (auto-reconnect and resume) is deferred to Phase 4.
- Heartbeat traffic adds a small constant overhead per active stream. At the default 15s interval and a few-hundred concurrent streams, this is in the low-single-digit packets-per-second range and not worth optimising.

**Open questions**
- Should the heartbeat interval default to 15s or 10s? 15s is comfortably under the 30s lower bound for most proxies; 10s adds safety margin at the cost of double the keepalive traffic. We default to 15s; operators can shorten via `agent.sse.heartbeat-interval` if a specific proxy is more aggressive.
- Do we want a per-emitter heartbeat (timer-per-stream) or a single scheduled loop iterating all emitters? The latter is simpler and scales to the volumes we expect (hundreds of streams, not thousands). Revisit if active-stream counts grow past ~1000.

## Implementation checklist

- [ ] Add `agent.sse.heartbeat-interval` (Duration) to `AgentProperties.Sse` with a 15-second default. `Duration.ZERO` disables.
- [ ] Add `SchedulingConfig` with `@EnableScheduling` (or annotate `AgentApplication`).
- [ ] Add `SseEmitterRegistry.sendHeartbeats()` as a `@Scheduled(fixedDelayString = "${agent.sse.heartbeat-interval:PT15S}")` method that iterates `active` and sends `SseEmitter.event().comment("hb")`. Skip work when the configured interval is zero. Remove emitters whose `send(...)` throws.
- [ ] Wire `sse_heartbeats_sent_total{outcome}` into `AgentMetrics`.
- [ ] Add a unit test that registers an emitter, advances time, and asserts heartbeats were sent (use a `TaskScheduler` test double or a tight interval like `PT100MS`).
- [ ] Document the sticky-session requirement and `storage.type=postgres` requirement in `README.md` under a new "Running multiple replicas" section.
- [ ] Update `ROADMAP.md` to tick Phase 3.3 once the above lands.

## Out of scope (deferred)

- A real reconnect-and-resume protocol on the client side (Phase 4 — needs a session-cursor design).
- Per-pod SSE quotas to protect against one client hogging the stream pool (Phase 4 / observability follow-up).
- The Kubernetes manifest itself (Phase 3.5 — this ADR only commits to documenting the constraint).
