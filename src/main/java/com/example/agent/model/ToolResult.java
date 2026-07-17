package com.example.agent.model;

/**
 * Result of executing a {@link ToolCall}.
 *
 * @param callId   The id of the originating tool call.
 * @param content  The textual content to return to the model.
 * @param isError  Whether the tool failed.
 */
public record ToolResult(String callId, String content, boolean isError) {
    public static ToolResult ok(String callId, String content) {
        return new ToolResult(callId, content, false);
    }
    public static ToolResult error(String callId, String message) {
        return new ToolResult(callId, message, true);
    }
}
