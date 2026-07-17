package com.example.agent.llm.copilot;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level non-streaming tests for {@link CopilotProvider#complete}.
 *
 * Copilot's {@code /chat/completions} endpoint is OpenAI-compatible: the response
 * carries {@code choices[0].message.content} (or {@code tool_calls}) plus a
 * {@code usage} block. These tests verify the JSON parsing path and confirm that
 * the non-streaming request does NOT set {@code stream:true} and carries the
 * Copilot-specific headers ({@code Authorization}, {@code Copilot-Integration-Id},
 * {@code Editor-Version}).
 */
class CopilotProviderWireTest {

    private MockWebServer server;
    private CopilotProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        AgentProperties.Copilot cfg = props.getLlm().getCopilot();
        cfg.setApiKey("test-token");
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("gpt-4o");

        provider = new CopilotProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry())
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void completeReturnsTextResponse() throws Exception {
        String json = "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}"
                + "}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(json));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1");

        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().toolCalls()).isEmpty();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(request.getHeader("Copilot-Integration-Id")).isEqualTo("vscode-chat");
        assertThat(request.getHeader("Editor-Version")).isEqualTo("spring-coding-agent/0.1.0");
        String reqBody = request.getBody().readUtf8();
        assertThat(reqBody).doesNotContain("\"stream\":true");
    }

    @Test
    void completeAssemblesToolCallResponse() throws Exception {
        String json = "{"
                + "\"choices\":[{"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{"
                + "\"id\":\"call_1\","
                + "\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"foo.txt\\\"}\"}"
                + "}]"
                + "},"
                + "\"finish_reason\":\"tool_calls\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":11,\"total_tokens\":18}"
                + "}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(json));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-1");

        assertThat(result.message().toolCalls()).hasSize(1);
        ToolCall call = result.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.arguments()).containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(request.getHeader("Copilot-Integration-Id")).isEqualTo("vscode-chat");
        String reqBody = request.getBody().readUtf8();
        assertThat(reqBody).doesNotContain("\"stream\":true");
    }
}
