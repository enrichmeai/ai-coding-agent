package com.example.agent.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * A single message in a conversation. Can carry plain text and/or tool-use content.
 *
 * - Role.USER      → "text" is the user's prompt.
 * - Role.ASSISTANT → "text" is the model's free-form reply; "toolCalls" may also be set.
 * - Role.TOOL      → "toolResults" holds outputs for prior tool calls.
 * - Role.SYSTEM    → single "text" used as a system instruction.
 */
public record ChatMessage(
        Role role,
        String text,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults,
        Instant timestamp
) {
    public ChatMessage {
        toolCalls   = toolCalls   == null ? Collections.emptyList() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? Collections.emptyList() : List.copyOf(toolResults);
        timestamp   = timestamp   == null ? Instant.now() : timestamp;
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, null, null, Instant.now());
    }
    public static ChatMessage assistantText(String text) {
        return new ChatMessage(Role.ASSISTANT, text, null, null, Instant.now());
    }
    public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, text, toolCalls, null, Instant.now());
    }
    public static ChatMessage tool(List<ToolResult> results) {
        return new ChatMessage(Role.TOOL, null, null, results, Instant.now());
    }
    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text, null, null, Instant.now());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
