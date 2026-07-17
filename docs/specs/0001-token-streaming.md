# Spec 0001 — Provider token streaming

| | |
|---|---|
| **Status** | Approved — open questions resolved in [ADR 0003](../adr/0003-streaming-design-choices.md) |
| **Date** | 2026-05-25 |
| **Driver** | Phase 4 of the roadmap — biggest single user-visible UX win |
| **Owner** | TBD |
| **Related** | [ADR 0003](../adr/0003-streaming-design-choices.md) (the four design decisions), Spec 0002 (provider wire tests), Spec 0003 (planning/approval mode) once landed |

## Goal

Stream assistant text tokens to the browser as the LLM produces them, instead of waiting for each complete message before flushing.

Today `AgentService.runTurn` calls `LlmProvider.complete()` which `.block()`s until the provider returns a full message; the SSE endpoint then emits one `message` event per complete `ChatMessage`. From the user's perspective, the chat freezes for the duration of every assistant turn — typically 2–20 seconds for a non-trivial response — then a wall of text appears at once.

After this spec lands, the user sees tokens arrive in real time, the same way they do in every modern chat UI.

## Non-goals

- Streaming tool-call arguments. The model emits tool calls as structured JSON; partial JSON is not renderable and not useful to the human. Tool calls remain message-granular: a complete `tool_call` shows up only when the model finishes the block.
- Live token-usage updates. Providers only emit final usage counts at end-of-stream; we keep the current behaviour of recording usage once per turn.
- Refactoring the providers. Each provider gets one new method; the existing `complete()` stays untouched.
- Streaming for the OpenAPI / Swagger view of the API. The new endpoint is SSE only.
- Ollama streaming. Coverage is uneven across local models; out of scope for v1, see *Out of scope* below.

## User-visible behaviour

1. New session, user types "explain X", hits send.
2. Within ~200ms the assistant message bubble appears with a blinking caret.
3. Tokens stream into the bubble at the same rate the provider emits them.
4. If the model calls a tool mid-response, the partial text so far is finalised, the tool call appears as a complete block, the tool result follows when ready, and the next assistant message starts streaming into a fresh bubble.
5. The Stop button cancels the in-flight stream cleanly (existing behaviour; should remain unaffected).
6. If streaming is unsupported (Ollama, or provider-side failure mid-stream), the user sees the existing per-message behaviour with no error surfaced.

## API contract

### `LlmProvider` interface

Add **one** default method alongside the existing `complete`:

```java
default CompletionResult completeStreaming(
        String systemPrompt,
        List<ChatMessage> history,
        List<ToolSpec> tools,
        String sessionId,
        Consumer<String> onToken) {
    // Default: no streaming support — delegate to non-streaming and ignore the consumer.
    return complete(systemPrompt, history, tools, sessionId);
}
```

Providers override this when they support streaming. The `Consumer<String>` is invoked once per token chunk (whatever granularity the upstream emits). The returned `CompletionResult` is identical in shape to the non-streaming path: same `ChatMessage`, same `TokenUsage`, same accounting.

### SSE wire format

The existing endpoint `POST /api/chat/stream` keeps its current event types (`message`, `done`, `shutdown`) and adds one:

- `event: token` with `data: {"text": "...partial...", "messageId": "<uuid>"}` — emitted per token chunk during an in-flight assistant message.
- `event: message` continues to be emitted exactly once per complete message, with the full text. The browser uses `token` events for live render, then reconciles against the `message` event when it arrives. This guarantees that a client that drops tokens (network blip) still ends with the correct full text.

No changes to existing event types. No breaking changes for existing SSE consumers.

### `AgentService` signature

`runTurn` gains an optional `Consumer<String>` parameter via overload, defaulting to a no-op for non-streaming callers. The streaming controller passes a consumer that emits `token` SSE events.

## Implementation plan (each step independently testable)

1. **Interface change.** Add `completeStreaming` default method to `LlmProvider`. No behavioural change yet. Build + tests stay green.
2. **AgentService streaming overload.** Add the `Consumer<String>` parameter; route through `completeStreaming`. Default callers get the no-op consumer. Unit test: existing `AgentServiceTest` still passes; one new test confirming the consumer is invoked when the provider streams.
3. **AnthropicProvider streaming.** Implement `completeStreaming` using the Messages SSE format. Assemble the response message from `content_block_delta` events; emit each text delta to the consumer; defer tool-call assembly until `content_block_stop`. Unit test with a recorded SSE fixture.
4. **OpenAiProvider streaming.** Same shape, OpenAI's `chat.completions` stream. Tool calls arrive as `delta.tool_calls[*]` fragments; buffer until `finish_reason: tool_calls`.
5. **CopilotProvider streaming.** Wire format is OpenAI-compatible; reuse the OpenAI parser if practical, otherwise a thin variant.
6. **OllamaProvider.** Keep the default no-op implementation. Document the gap. Defer to a follow-up.
7. **SSE endpoint.** `AgentController.chatStream` builds the token-emitting consumer and passes it to the streaming `runTurn`. Emit `token` events; keep existing `message` events as-is.
8. **UI.** Browser handles `token` events by appending to the current bubble; on `message`, replace the bubble's text with the full message (idempotency / dropped-token recovery). Use the existing markdown re-render after each `message` (don't re-render on every token — too expensive with marked + highlight.js).
9. **Configuration.** `agent.llm.streaming.enabled` (default `true`). Kill switch in case a provider misbehaves in prod.

## Test plan

- **Per-provider unit test** with a `MockWebServer` and a recorded SSE fixture. Asserts: tokens flow through the consumer in order; the assembled final message matches a recorded full response; tool calls are intact and atomic.
- **`AgentServiceStreamingTest`** — scripted fake provider that emits N tokens then a tool call then more tokens; verifies the consumer received exactly the text chunks and the final `ChatMessage` matches.
- **`ChatStreamIT` extension** — drives a streaming request end-to-end through the controller, asserts the SSE stream contains `token` events followed by a `message` event with matching text.
- **Stop-button behaviour** — existing IT must still pass; verify token-stream cleanup on client disconnect.

## Acceptance criteria

1. With `AGENT_LLM_PROVIDER=anthropic` and a real API key, typing a long question in the UI produces visible token-by-token rendering with no observable freeze.
2. With the same provider and a question that triggers a tool call, the partial text appears live, then the tool call+result render atomically, then the next response streams.
3. `agent.llm.streaming.enabled=false` reverts to the current message-granular behaviour. All existing tests still pass.
4. Per-provider unit tests cover Anthropic, OpenAI, Copilot at minimum.
5. Ollama continues to work (via default no-op), with no UI regression.

## Open questions — resolved

See [ADR 0003](../adr/0003-streaming-design-choices.md) for the four decisions and their rationale:

1. Provider scope: Anthropic + OpenAI + Copilot; Ollama stays no-op.
2. Token granularity: one SSE event per upstream chunk; no client-side buffering.
3. `event: message` after streaming: kept, for client-side reconciliation.
4. Markdown render: only on `message`, not on each `token`.

## Risks

- **Partial tool-call rendering bugs.** Per-provider buffering of tool-call fragments is the trickiest part. Wrong buffering produces malformed JSON in the audit log. Mitigation: each provider's unit test includes a tool-call-with-streaming fixture.
- **Backpressure.** If the SSE client is slow (or the browser tab is backgrounded), `SseEmitter.send` can block the agent loop. Mitigation: existing `chatStream` already runs in a bounded executor; failures propagate as `IOException` and unwind the loop (existing behaviour).
- **Token-count drift.** Some providers don't include usage in streaming responses unless you opt in. Mitigation: per-provider request includes `stream_options: {include_usage: true}` (OpenAI/Copilot) and the Anthropic equivalent.
- **Audit log shape.** `AuditLogger.recordLlmCall` runs after the stream completes — semantics unchanged. No streaming-specific audit events.

## Out of scope (separate follow-ups)

- **Spec 0002 — Provider wire tests.** Recorded fixtures + `MockWebServer` for the non-streaming path. Should land at the same time as this spec since the streaming tests use the same machinery; tracked separately to avoid scope creep.
- **Ollama streaming.** Tracked as a follow-up issue; revisit once one user actually needs it.
- **Spec 0003 — Planning/approval mode.** Built on top of streaming (gates each tool call); not needed for streaming itself.
- **Live token-usage in the UI.** Show running token count as the response streams. Requires provider-side support for in-stream usage events; defer.

## Estimate

~370 LOC across 4 providers + service + controller + UI + tests. Roughly 1–2 focused days once the open questions are resolved.
