package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void toolReturningExactMaxOutputBytesIsNotTruncated() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                // Return exactly maxBytes of content
                String content = "x".repeat(maxBytes);
                return ToolResult.ok(id, content);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Should not be truncated
        assertEquals("x".repeat(maxBytes), result.content());
    }

    @Test
    void toolReturning3xMaxOutputBytesIsTruncated() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                // Return 3x the max bytes
                String content = "x".repeat(maxBytes * 3);
                return ToolResult.ok(id, content);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Should be truncated
        assertNotEquals("x".repeat(maxBytes * 3), result.content());
        assertTrue(result.content().contains("[output truncated"));

        // Check that the total length is reasonable (within maxBytes + marker overhead)
        int actualBytes = result.content().getBytes(StandardCharsets.UTF_8).length;
        int markerOverheadAllowance = 150;
        assertTrue(actualBytes <= maxBytes + markerOverheadAllowance,
                "Truncated output (" + actualBytes + " bytes) should be <= " + (maxBytes + markerOverheadAllowance));

        // isError should be preserved
        assertFalse(result.isError());
    }

    @Test
    void truncationPreservesErrorFlag() {
        AgentProperties props = new AgentProperties();
        int maxBytes = props.getTools().getMaxOutputBytes();

        Tool tool = new Tool() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "test"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                String errorMsg = "x".repeat(maxBytes * 2);
                return ToolResult.error(id, errorMsg);
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(tool), props);
        ToolCall call = new ToolCall("call1", "test", Map.of());
        ToolResult result = registry.invoke(call);

        // Error flag should be preserved
        assertTrue(result.isError());
        assertTrue(result.content().contains("[output truncated"));
    }
}
