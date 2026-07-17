package com.example.agent.llm.openai;

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

class OpenAiProviderWireTest {

    private MockWebServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        AgentProperties.OpenAi cfg = props.getLlm().getOpenai();
        cfg.setApiKey("test-key");
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("gpt-4o");

        provider = new OpenAiProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void completeReturnsTextResponse() throws Exception {
        String body = "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"},"
                + "\"finish_reason\":\"stop\""
                + "}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}"
                + "}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1");

        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().toolCalls()).isEmpty();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void completeAssemblesToolCallResponse() {
        String body = "{"
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
                .setBody(body));

        CompletionResult result = provider.complete(
                "system",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2");

        assertThat(result.message().toolCalls()).hasSize(1);
        ToolCall call = result.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("read_file");
        assertThat(call.arguments()).containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }
}
