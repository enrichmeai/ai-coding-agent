package com.example.agent.llm.openai;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolResult;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions API implementation.
 *
 * Docs: https://platform.openai.com/docs/api-reference/chat
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "openai")
public class OpenAiProvider implements LlmProvider {

    private final AgentProperties.OpenAi cfg;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public OpenAiProvider(AgentProperties props,
                          WebClient.Builder webClientBuilder,
                          ObjectMapper mapper,
                          AgentMetrics metrics) {
        this.cfg = props.getLlm().getOpenai();
        this.mapper = mapper;
        this.metrics = metrics;
        this.webClient = webClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + (cfg.getApiKey() == null ? "" : cfg.getApiKey()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "openai"; }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured. Set env var OPENAI_API_KEY or agent.llm.openai.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        JsonNode response;
        try {
            response = com.example.agent.llm.LlmRetry.call(name(), () -> webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(5))
                    .block());
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        CompletionResult result = parseResponse(response);
        metrics.recordLlmCall(name(), true, result.usage().inputTokens(), result.usage().outputTokens());
        return result;
    }

    @Override
    public CompletionResult completeStreaming(String systemPrompt,
                                              List<ChatMessage> history,
                                              List<ToolSpec> tools,
                                              String sessionId,
                                              Consumer<String> onToken) {
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured. Set env var OPENAI_API_KEY or agent.llm.openai.api-key.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));

        StringBuilder textBuf = new StringBuilder();
        TreeMap<Integer, ToolCallBuf> toolBufs = new TreeMap<>();
        AtomicReference<TokenUsage> usageRef = new AtomicReference<>(TokenUsage.ZERO);

        // Bypass LlmRetry on the streaming path: a mid-stream retry would
        // re-emit already-delivered tokens via onToken. Matches Anthropic and
        // Copilot streaming behaviour. See ADR 0003 + spec 0001.
        try {
            webClient.post()
                    .uri("/v1/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(Duration.ofMinutes(5))
                    .toStream()
                    .forEach(line -> handleStreamEvent(line, textBuf, toolBufs, usageRef, onToken));
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallBuf b : toolBufs.values()) {
            Map<String, Object> args;
            String argJson = b.arguments.length() == 0 ? "{}" : b.arguments.toString();
            try {
                args = mapper.readValue(argJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                args = new HashMap<>();
            }
            toolCalls.add(new ToolCall(b.id == null ? "" : b.id, b.name == null ? "" : b.name, args));
        }

        TokenUsage usage = usageRef.get();
        CompletionResult result = new CompletionResult(
                ChatMessage.assistant(textBuf.toString(), toolCalls), usage);
        metrics.recordLlmCall(name(), true, usage.inputTokens(), usage.outputTokens());
        return result;
    }

    private void handleStreamEvent(String data,
                                   StringBuilder textBuf,
                                   TreeMap<Integer, ToolCallBuf> toolBufs,
                                   AtomicReference<TokenUsage> usageRef,
                                   Consumer<String> onToken) {
        if (data == null || data.isEmpty()) return;
        if ("[DONE]".equals(data)) return;
        JsonNode evt;
        try {
            evt = mapper.readTree(data);
        } catch (Exception e) {
            return;
        }
        JsonNode usage = evt.path("usage");
        if (usage.isObject() && !usage.isMissingNode()) {
            usageRef.set(new TokenUsage(
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0)));
        }
        JsonNode choices = evt.path("choices");
        if (!choices.isArray() || choices.size() == 0) return;
        JsonNode delta = choices.path(0).path("delta");

        JsonNode contentNode = delta.path("content");
        if (contentNode.isTextual()) {
            String chunk = contentNode.asText();
            if (!chunk.isEmpty()) {
                textBuf.append(chunk);
                onToken.accept(chunk);
            }
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                int index = tc.path("index").asInt(0);
                ToolCallBuf buf = toolBufs.computeIfAbsent(index, k -> new ToolCallBuf());
                if (tc.path("id").isTextual()) buf.id = tc.path("id").asText();
                JsonNode fn = tc.path("function");
                if (fn.path("name").isTextual()) {
                    String n = fn.path("name").asText();
                    if (!n.isEmpty()) buf.name = n;
                }
                if (fn.path("arguments").isTextual()) {
                    buf.arguments.append(fn.path("arguments").asText());
                }
            }
        }
    }

    private static final class ToolCallBuf {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private List<Map<String, Object>> convertHistory(String systemPrompt, List<ChatMessage> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            out.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (ChatMessage m : history) {
            if (m.role() == Role.SYSTEM) continue;

            if (m.role() == Role.USER) {
                out.add(Map.of("role", "user", "content", m.text() == null ? "" : m.text()));
            } else if (m.role() == Role.ASSISTANT) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                msg.put("content", m.text() == null ? "" : m.text());
                if (!m.toolCalls().isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        try {
                            calls.add(Map.of(
                                    "id", tc.id(),
                                    "type", "function",
                                    "function", Map.of(
                                            "name", tc.name(),
                                            "arguments", mapper.writeValueAsString(
                                                    tc.arguments() == null ? Map.of() : tc.arguments())
                                    )
                            ));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    msg.put("tool_calls", calls);
                }
                out.add(msg);
            } else if (m.role() == Role.TOOL) {
                for (ToolResult r : m.toolResults()) {
                    out.add(Map.of(
                            "role", "tool",
                            "tool_call_id", r.callId(),
                            "content", r.content()
                    ));
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> convertTools(List<ToolSpec> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec t : tools) {
            out.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", t.name(),
                            "description", t.description(),
                            "parameters", t.inputSchema()
                    )
            ));
        }
        return out;
    }

    private CompletionResult parseResponse(JsonNode response) {
        if (response == null || !response.has("choices")) {
            throw new IllegalStateException("Empty or invalid response from OpenAI API");
        }
        JsonNode message = response.path("choices").path(0).path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String id   = call.path("id").asText();
                String name = call.path("function").path("name").asText();
                String argJson = call.path("function").path("arguments").asText("{}");
                Map<String, Object> args;
                try {
                    args = mapper.readValue(argJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    args = new HashMap<>();
                }
                toolCalls.add(new ToolCall(id, name, args));
            }
        }
        JsonNode usage = response.path("usage");
        TokenUsage tokens = new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
        return new CompletionResult(ChatMessage.assistant(text, toolCalls), tokens);
    }
}
