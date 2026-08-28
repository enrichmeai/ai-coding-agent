package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the user's own documents through a Cistern pod, as this agent.
 *
 * <p>The difference between this and {@code read_file} is the whole reason it exists. A file
 * tool reads whatever the filesystem hands it, which is everything the process can see. This
 * asks a server that knows who is asking: the agent presents its <em>own</em> credential, the
 * pod resolves it to this application's WebID, and Web Access Control decides — against rules
 * the owner wrote, not rules the agent was trusted to respect. Every allow and every deny is
 * receipted on the owner's side, and revoking the grant stops this agent on its next request
 * without touching anything else.
 *
 * <p><strong>A refusal is an answer, not a failure.</strong> {@code 403} means the owner did
 * not grant this, and the model needs to understand that as a fact about permission it should
 * report, rather than as a malfunction it should retry or work around. Only genuine faults —
 * a misconfigured credential, an unreachable pod — are returned as errors.
 */
@Component
public class CisternTool implements Tool {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String baseUrl;
    private final String token;
    private final WebClient webClient;

    public CisternTool(AgentProperties props, WebClient.Builder webClientBuilder) {
        this.baseUrl = trimTrailingSlash(props.getTools().getCistern().getBaseUrl());
        this.token = props.getTools().getCistern().getToken();
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String name() {
        return "pod";
    }

    @Override
    public String description() {
        return "Read, list and write the user's own documents in their Cistern pod. "
            + "Operations: 'read' (fetch a resource), 'list' (contents of a container), "
            + "'write' (create or replace a resource), 'receipts' (what this agent has "
            + "accessed, if permitted). Access is decided by the owner's rules: a refusal "
            + "means you were not granted that resource — report it, do not work around it. "
            + "Large responses are truncated to the tool output cap (16 KB by default, "
            + "head and tail kept with an omission marker) — a truncated read is NOT the "
            + "full document; say so rather than treating it as complete. "
            + "Requires agent.tools.cistern.base-url and .token.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of("read", "list", "write", "receipts"),
                    "description", "Operation to perform"),
                "path", Map.of("type", "string",
                    "description", "Path within the pod, e.g. /notes/meeting.ttl or /notes/"),
                "content", Map.of("type", "string",
                    "description", "Body to store. Required for 'write'."),
                "contentType", Map.of("type", "string",
                    "description", "Media type for 'write'. Defaults to text/turtle.")),
            "required", List.of("type", "path"));
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> arguments) {
        if (baseUrl.isBlank() || token.isBlank()) {
            return ToolResult.error(callId,
                "No pod configured. Set agent.tools.cistern.base-url and .token.");
        }
        String type = string(arguments, "type");
        String path = string(arguments, "path");
        if (path.isBlank()) {
            return ToolResult.error(callId, "path is required");
        }
        String uri = baseUrl + (path.startsWith("/") ? path : "/" + path);

        try {
            return switch (type) {
                case "read", "list" -> ToolResult.ok(callId, get(uri));
                case "receipts" -> ToolResult.ok(callId, get(uri + "?receipts"));
                case "write" -> {
                    String content = string(arguments, "content");
                    String contentType = string(arguments, "contentType");
                    yield ToolResult.ok(callId, put(uri,
                        content, contentType.isBlank() ? "text/turtle" : contentType));
                }
                default -> ToolResult.error(callId, "Unknown operation: " + type);
            };
        } catch (WebClientResponseException e) {
            return describe(callId, uri, e);
        } catch (RuntimeException e) {
            return ToolResult.error(callId, "Could not reach the pod at " + baseUrl + ": " + e.getMessage());
        }
    }

    /**
     * Turns the pod's answer into something the model can act on correctly.
     *
     * <p>The distinction that matters: {@code 403} is the owner's decision and belongs in the
     * conversation as such, so it comes back as a successful tool result carrying a refusal.
     * {@code 401} and {@code 404} are the agent's problem or the caller's, and are errors.
     */
    private static ToolResult describe(String callId, String uri, WebClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == HttpStatus.FORBIDDEN) {
            return ToolResult.ok(callId, "Refused: the owner has not granted this agent access to "
                + uri + ". This is a permission decision, not an error — report it to the user "
                + "and do not attempt another route to the same content.");
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return ToolResult.error(callId, "The pod did not recognise this agent's credential. "
                + "Check agent.tools.cistern.token against the pod's configuration.");
        }
        if (status == HttpStatus.NOT_FOUND) {
            return ToolResult.error(callId, "No such resource: " + uri);
        }
        return ToolResult.error(callId, "Pod returned " + e.getStatusCode() + " for " + uri);
    }

    private String get(String uri) {
        return webClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .block();
    }

    private String put(String uri, String content, String contentType) {
        webClient.put()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .bodyValue(content)
            .retrieve()
            .toBodilessEntity()
            .timeout(TIMEOUT)
            .block();
        return "Stored " + uri;
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : value.toString();
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : (url == null ? "" : url);
    }
}
