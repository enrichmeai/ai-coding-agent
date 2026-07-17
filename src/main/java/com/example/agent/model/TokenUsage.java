package com.example.agent.model;

/**
 * Token usage for a single LLM call, or accumulated across a session.
 * Zero is used for providers that don't report a given field.
 */
public record TokenUsage(int inputTokens, int outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public int total() { return inputTokens + outputTokens; }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) return this;
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
