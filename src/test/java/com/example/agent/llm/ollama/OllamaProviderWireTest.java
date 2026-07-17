package com.example.agent.llm.ollama;

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

class OllamaProviderWireTest {

    private MockWebServer server;
    private OllamaProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        AgentProperties.Ollama cfg = props.getLlm().getOllama();
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("llama3");

        provider = new OllamaProvider(
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
        String body = "{\"model\":\"llama3\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"Hello world\"}," +
                "\"done\":true,\"prompt_eval_count\":12,\"eval_count\":5}";

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
        String body = "{\"model\":\"llama3\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"," +
                "\"tool_calls\":[{\"function\":{\"name\":\"read_file\"," +
                "\"arguments\":{\"path\":\"foo.txt\"}}}]}," +
                "\"done\":true,\"prompt_eval_count\":7,\"eval_count\":11}";

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));

        CompletionResult result = provider.complete(
                "you are helpful",
                List.of(ChatMessage.user("read foo.txt")),
                List.of(),
                "session-2");

        assertThat(result.message().text()).isEmpty();
        assertThat(result.message().toolCalls()).hasSize(1);
        assertThat(result.message().toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(result.message().toolCalls().get(0).arguments())
                .containsEntry("path", "foo.txt");
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(11);
    }
}
