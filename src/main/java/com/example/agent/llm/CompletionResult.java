package com.example.agent.llm;

import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;

/**
 * One LLM response: the produced assistant {@link ChatMessage} plus token usage.
 */
public record CompletionResult(ChatMessage message, TokenUsage usage) {
    public CompletionResult {
        if (usage == null) usage = TokenUsage.ZERO;
    }
}
