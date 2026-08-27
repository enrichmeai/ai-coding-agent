package com.example.agent.llm.ollama;

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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ollama chat endpoint implementation.
 *
 * Docs: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 * Ollama's tool support is OpenAI-compatible for models that advertise it.
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "ollama")
public class OllamaProvider implements LlmProvider {

    private final AgentProperties.Ollama cfg;
    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public OllamaProvider(AgentProperties props,
                          WebClient.Builder webClientBuilder,
                          ObjectMapper mapper,
                          AgentMetrics metrics) {
        this.cfg = props.getLlm().getOllama();
        this.mapper = mapper;
        this.metrics = metrics;
        this.webClient = webClientBuilder
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "ollama"; }

    @Override
    public CompletionResult complete(String systemPrompt, List<ChatMessage> history, List<ToolSpec> tools, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("stream", false);
        body.put("messages", convertHistory(systemPrompt, history));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", convertTools(tools));
        }

        JsonNode response;
        try {
            response = com.example.agent.llm.LlmRetry.call(name(), () -> webClient.post()
                    .uri("/api/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(10))
                    .block());
        } catch (Exception e) {
            metrics.recordLlmCall(name(), false, 0, 0);
            throw e;
        }

        CompletionResult result = parseResponse(response);
        metrics.recordLlmCall(name(), true, result.usage().inputTokens(), result.usage().outputTokens());
        return result;
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
                        calls.add(Map.of(
                                "function", Map.of(
                                        "name", tc.name(),
                                        "arguments", tc.arguments() == null ? Map.of() : tc.arguments()
                                )
                        ));
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
        if (response == null) {
            throw new IllegalStateException("Empty response from Ollama API");
        }
        JsonNode message = response.path("message");
        String text = message.path("content").asText("");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText();
                JsonNode argNode = call.path("function").path("arguments");
                Map<String, Object> args;
                if (argNode.isObject()) {
                    args = mapper.convertValue(argNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } else if (argNode.isTextual()) {
                    try {
                        args = mapper.readValue(argNode.asText(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        args = new HashMap<>();
                    }
                } else {
                    args = new HashMap<>();
                }
                // Ollama doesn't always return IDs — synthesise one.
                toolCalls.add(new ToolCall("call_" + UUID.randomUUID(), name, args));
            }
        }
        // Ollama uses prompt_eval_count / eval_count at the top level.
        TokenUsage tokens = new TokenUsage(
                response.path("prompt_eval_count").asInt(0),
                response.path("eval_count").asInt(0));
        return new CompletionResult(ChatMessage.assistant(text, toolCalls), tokens);
    }
}
