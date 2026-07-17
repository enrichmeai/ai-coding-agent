package com.example.agent.llm.anthropic;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderWireTest {

    private MockWebServer server;
    private AnthropicProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        AgentProperties.Anthropic cfg = props.getLlm().getAnthropic();
        cfg.setApiKey("test-key");
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("claude-test");
        cfg.setMaxTokens(64);

        provider = new AnthropicProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void completeReturnsTextResponse() {
        String body = "{"
                + "\"id\":\"msg_01\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":\"claude-test\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hello world\"}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":5}"
                + "}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        CompletionResult result = provider.complete(
                "you are helpful",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1");

        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().hasToolCalls()).isFalse();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void completeAssemblesToolCallResponse() {
        String body = "{"
                + "\"id\":\"msg_02\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":\"claude-test\","
                + "\"content\":["
                + "  {\"type\":\"text\",\"text\":\"reading\"},"
                + "  {\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"read_file\",\"input\":{\"path\":\"foo.txt\"}}"
                + "],"
                + "\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":7,\"output_tokens\":11}"
                + "}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        CompletionResult result = provider.complete(
                "you are helpful",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2");

        assertThat(result.message().text()).isEqualTo("reading");
        assertThat(result.message().toolCalls()).hasSize(1);
        assertThat(result.message().toolCalls().get(0).id()).isEqualTo("toolu_01");
        assertThat(result.message().toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(result.message().toolCalls().get(0).arguments())
                .containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }
}
