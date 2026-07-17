package com.example.agent.tools;

import com.example.agent.model.ToolResult;

import java.util.Map;

/**
 * A tool exposed to the LLM. Each tool is a single capability
 * (read_file, write_file, shell, etc).
 */
public interface Tool {

    /** Unique tool name, e.g. "read_file". */
    String name();

    /** Description shown to the LLM. */
    String description();

    /** JSON-Schema for {@code arguments}. */
    Map<String, Object> inputSchema();

    /** Execute the tool with the given arguments and return a result. */
    ToolResult execute(String callId, Map<String, Object> arguments);

    /** Provider-neutral spec for the LLM. */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
