package com.example.agent.tools;

import java.util.Map;

/**
 * A JSON-Schema description of a tool, suitable for sending to an LLM.
 *
 * @param name         Tool identifier the model will use.
 * @param description  Prose description of when/how to use the tool.
 * @param inputSchema  JSON-Schema object describing the parameters.
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) { }
