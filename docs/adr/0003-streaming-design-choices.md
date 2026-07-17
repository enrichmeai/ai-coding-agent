# ADR 0003 — Token streaming: scope, granularity, reconciliation, render cadence

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-05-25 |
| **Driver** | Spec 0001 (`docs/specs/0001-token-streaming.md`) — four open questions blocking implementation |
| **Owners** | TBD |
| **Supersedes** | — |

## Context

Spec 0001 introduces per-token streaming from the LLM provider through the SSE endpoint to the browser. The spec is otherwise mechanical, but four decisions shape the public contract and the implementation cost. They are recorded here together because they are coupled — changing one usually changes the others.

## Decisions

### 1. Provider scope at v1 — Anthropic, OpenAI, Copilot. Ollama remains no-op.

The four providers split cleanly by wire format:

| Provider | Streaming wire format | Notes |
|---|---|---|
| Anthropic | Messages SSE (`content_block_delta`, etc.) | Mature; well-documented |
| OpenAI | `chat.completions` SSE | Same parser usable for Copilot |
| Copilot | OpenAI-compatible | Shares parser with OpenAI |
| Ollama | Per-model NDJSON; variable quality | Many local models stream poorly or not at all |

Shipping all three OpenAI-family providers together is roughly 2× the cost of Anthropic-only and gets the feature to roughly 100% of cloud users. Ollama support is deferred — the default `LlmProvider.completeStreaming` implementation delegates to `complete()`, so Ollama users see exactly the current behaviour (no regression).

*Rejected:* Anthropic only — leaves OpenAI/Copilot users with the same jarring "wall of text" UX even though the cost of adding them is small and the parser is shared.

### 2. Token-event granularity — one SSE event per upstream chunk. No client-side buffering.

The provider already coalesces tokens into delta events (usually a few characters each). Buffering on our side would add latency and a non-trivial state machine for a problem we do not yet have. If the `sse_*` Prometheus counters later show write-rate pressure, we can introduce a flush window (e.g. 50ms / 32 chars) without changing the SSE contract.

*Rejected:* Time/char-based buffering on the server — premature optimisation, harder to test, and obscures provider-side behaviour during diagnosis.

### 3. Keep `event: message` after streaming completes.

The current contract emits one `message` event per finished assistant message carrying the full text. Streaming makes this strictly redundant when every `token` event arrives intact. We keep it anyway, for two reasons:

- A single dropped `token` event (network blip, dropped frame in a proxy) silently corrupts the rendered message. The trailing `message` lets the client overwrite the bubble with the authoritative full text.
- Existing SSE consumers (the audit log replay tool, anything someone has scripted against the API) continue to work unchanged. Streaming is purely additive at the wire-format level.

Cost is roughly 1 KB per turn — three orders of magnitude smaller than the LLM call itself. Not worth optimising.

*Rejected:* Dropping `message` for streamed responses — saves bandwidth nobody is counting, breaks idempotency, and is impossible to reverse without a wire-format break.

### 4. Markdown render only on `message`. Streaming tokens render as plain text.

Marked → DOMPurify → highlight.js (from Batch A, Spec 0001's prerequisite) is the expensive path. Running it on every token event would re-parse the entire message-so-far ~30 times per second on a fast stream, and would syntax-highlight half-formed code blocks during streaming — visually noisy.

Plain text during streaming, full markdown render once at end-of-message, matches the behaviour of every other production chat UI (Claude.ai, ChatGPT). The transition is barely noticeable when the final render fires.

*Rejected:*
- Re-render every Nth token / every 200 chars — adds heuristics and still renders half-formed code blocks.
- Re-render every token — measurable CPU burn; jank in long responses.

## Consequences

- The SSE contract gains one event type (`token`) and no removals. Spec 0001's "no breaking changes for existing SSE consumers" is honoured.
- Each new provider implementation has roughly the same shape: parse SSE/NDJSON, emit text deltas via `Consumer<String>`, defer tool-call assembly until block close.
- Ollama users see no UI difference. When Ollama streaming is added later it slots in via the same `completeStreaming` override without any wire-format change.
- A new metric `sse_token_events_sent_total{provider}` is the natural canary for Q2 — if it grows much faster than `llm_calls_total`, revisit chunk buffering.
- The browser-side render path stays simple: append-on-token, replace-on-message. No partial-markdown logic, no diff/patch.

## Follow-ups

- If Ollama streaming becomes user-visible (a user asks), open a sibling ADR rather than amending this one.
- Reassess Q2 (buffering) after one quarter of production data on `sse_token_events_sent_total`.
