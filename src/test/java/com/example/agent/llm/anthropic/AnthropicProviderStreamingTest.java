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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProviderStreamingTest {

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
    void streamsPlainTextAndAssemblesFinalMessage() {
        String sse = String.join("",
                sseEvent("message_start",
                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\"," +
                                "\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}"),
                sseEvent("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0," +
                                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                sseEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}"),
                sseEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}"),
                sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":0}"),
                sseEvent("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                                "\"usage\":{\"output_tokens\":5}}"),
                sseEvent("message_stop",
                        "{\"type\":\"message_stop\"}"));

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 16));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "you are helpful",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1",
                tokens::add);

        assertThat(tokens).containsExactly("Hello ", "world");
        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().hasToolCalls()).isFalse();
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
    }

    @Test
    void bufferedPartialJsonToolCallNeverEmitsTokens() {
        String sse = String.join("",
                sseEvent("message_start",
                        "{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"role\":\"assistant\"," +
                                "\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}}"),
                sseEvent("content_block_start",
                        "{\"type\":\"content_block_start\",\"index\":0," +
                                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_abc\",\"name\":\"read_file\",\"input\":{}}}"),
                sseEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pa\"}}"),
                sseEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"th\\\":\"}}"),
                sseEvent("content_block_delta",
                        "{\"type\":\"content_block_delta\",\"index\":0," +
                                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"foo.txt\\\"}\"}}"),
                sseEvent("content_block_stop",
                        "{\"type\":\"content_block_stop\",\"index\":0}"),
                sseEvent("message_delta",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}," +
                                "\"usage\":{\"output_tokens\":11}}"),
                sseEvent("message_stop",
                        "{\"type\":\"message_stop\"}"));

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 16));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "you are helpful",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2",
                tokens::add);

        assertThat(tokens).isEmpty();
        assertThat(result.message().text()).isEmpty();
        assertThat(result.message().toolCalls()).hasSize(1);
        assertThat(result.message().toolCalls().get(0).id()).isEqualTo("toolu_abc");
        assertThat(result.message().toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(result.message().toolCalls().get(0).arguments())
                .containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }

    private static String sseEvent(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }
}
