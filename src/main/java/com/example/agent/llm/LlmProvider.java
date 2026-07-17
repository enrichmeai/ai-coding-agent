package com.example.agent.llm;

import com.example.agent.model.ChatMessage;
import com.example.agent.tools.ToolSpec;

import java.util.List;
import java.util.function.Consumer;

/**
 * Provider-agnostic interface for chatting with an LLM.
 *
 * Implementations translate between our internal model ({@link ChatMessage},
 * {@link com.example.agent.model.ToolCall}) and the provider's wire format.
 */
public interface LlmProvider {

    /** Human-readable identifier: "anthropic", "openai", "ollama", "copilot". */
    String name();

    /**
     * Send the full conversation to the model and return the assistant's next
     * message along with the token usage reported by the provider.
     *
     * The assistant message may contain text, tool calls, or both.
     *
     * @param sessionId id of the current session (used for audit logs); may be {@code null}
     *                  if the call is not associated with a session.
     */
    CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId);

    /**
     * Convenience overload for callers (mostly tests) that don't have a session id.
     */
    default CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools) {
        return complete(systemPrompt, history, tools, null);
    }

    /**
     * Streaming variant of {@link #complete}. Implementations invoke {@code onToken}
     * once per upstream chunk (a few characters of assistant text). Tool-call
     * arguments are NOT streamed — they appear only in the returned message once
     * the upstream tool-call block closes.
     *
     * Default implementation delegates to {@link #complete} and never invokes the
     * consumer. Providers without streaming support inherit this safely.
     */
    default CompletionResult completeStreaming(String systemPrompt,
                                               List<ChatMessage> history,
                                               List<ToolSpec> tools,
                                               String sessionId,
                                               Consumer<String> onToken) {
        return complete(systemPrompt, history, tools, sessionId);
    }
}
