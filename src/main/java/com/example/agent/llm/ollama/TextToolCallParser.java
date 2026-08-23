package com.example.agent.llm.ollama;

import com.example.agent.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers tool calls that a local model emitted as plain text instead of in
 * Ollama's structured {@code tool_calls} field.
 *
 * <p>Several models advertise {@code tools} but only sometimes route a call
 * through the structured field; the rest of the time they fall back to whatever
 * format they were fine-tuned on and it arrives as assistant prose. Observed
 * with Ollama 0.x:
 *
 * <ul>
 *   <li>qwen3-coder / derivatives emit Qwen's XML form —
 *       {@code <function=list_dir><parameter=path>.</parameter></function>},
 *       sometimes with a stray {@code </tool_call>} trailer.</li>
 *   <li>qwen2.5-coder emits a fenced JSON object
 *       {@code {"name": ..., "arguments": {...}}}.</li>
 * </ul>
 *
 * <p>Without this the agent loop sees no tool calls, treats the text as the
 * final answer, and the turn ends having done nothing.
 *
 * <p>Parsing is deliberately conservative: it only runs when the structured
 * field was empty, and only accepts text whose whole tool-call block matches one
 * of the shapes below. Prose that merely mentions a tool is left alone.
 */
final class TextToolCallParser {

    /** {@code <function=name> ... </function>}, optionally inside <tool_call> tags. */
    private static final Pattern XML_CALL = Pattern.compile(
            "<function=([A-Za-z0-9_.-]+)\\s*>(.*?)</function\\s*>", Pattern.DOTALL);

    /** {@code <parameter=key> value </parameter>} inside an XML call. */
    private static final Pattern XML_PARAM = Pattern.compile(
            "<parameter=([A-Za-z0-9_.-]+)\\s*>(.*?)</parameter\\s*>", Pattern.DOTALL);

    /** A bare or fenced JSON object carrying name + arguments. */
    private static final Pattern JSON_CALL = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\"\\s*,\\s*\"(?:arguments|parameters)\"\\s*:\\s*(\\{.*})\\s*}",
            Pattern.DOTALL);

    private final ObjectMapper mapper;

    TextToolCallParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The text with any recovered tool-call markup removed, plus the calls found. */
    record Result(String text, List<ToolCall> toolCalls) {}

    Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Result(text, List.of());
        }

        List<ToolCall> calls = new ArrayList<>();
        String remaining = text;

        Matcher xml = XML_CALL.matcher(text);
        StringBuilder stripped = new StringBuilder();
        int last = 0;
        while (xml.find()) {
            Map<String, Object> args = new LinkedHashMap<>();
            Matcher param = XML_PARAM.matcher(xml.group(2));
            while (param.find()) {
                args.put(param.group(1), param.group(2).trim());
            }
            calls.add(newCall(xml.group(1), args));
            stripped.append(text, last, xml.start());
            last = xml.end();
        }
        if (!calls.isEmpty()) {
            stripped.append(text.substring(last));
            return new Result(cleanup(stripped.toString()), calls);
        }

        Matcher json = JSON_CALL.matcher(remaining);
        if (json.find()) {
            Map<String, Object> args = readArgs(json.group(2));
            if (args != null) {
                calls.add(newCall(json.group(1), args));
                String rest = remaining.substring(0, json.start()) + remaining.substring(json.end());
                return new Result(cleanup(rest), calls);
            }
        }

        return new Result(text, List.of());
    }

    private Map<String, Object> readArgs(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) return null;
            return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private ToolCall newCall(String name, Map<String, Object> args) {
        // Ollama synthesises no id for these, and the loop needs one to pair the
        // TOOL response back to the call.
        return new ToolCall("call_" + UUID.randomUUID(), name, args);
    }

    /** Drops the fence and {@code </tool_call>} debris models leave behind. */
    private String cleanup(String text) {
        return text.replaceAll("(?s)</?tool_call\\s*>", "")
                .replaceAll("```(?:json|xml)?", "")
                .strip();
    }
}
