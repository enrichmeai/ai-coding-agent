package com.example.agent.model;

import java.util.Map;

/**
 * A tool-use request emitted by the LLM.
 *
 * @param id         Provider-assigned id used to correlate the result.
 * @param name       Name of the tool to invoke (matches {@link com.example.agent.tools.Tool#name()}).
 * @param arguments  JSON object of arguments.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) { }
