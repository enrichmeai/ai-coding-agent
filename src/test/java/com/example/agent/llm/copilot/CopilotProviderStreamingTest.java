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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level streaming tests for {@link CopilotProvider#completeStreaming}.
 *
 * The Copilot endpoint speaks the OpenAI {@code chat.completions} streaming
 * format: text deltas in {@code choices[0].delta.content}, tool-call
 * fragments in {@code delta.tool_calls[*]}, terminated by {@code data: [DONE]}
 * and (optionally) a final {@code usage} event.
 */
class CopilotProviderStreamingTest {

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
    void streamsPlainTextDeltasAndAssemblesFinalMessage() throws Exception {
        String sse = String.join("",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello \"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}\n\n",
                "data: [DONE]\n\n"
        );

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "system",
                List.of(ChatMessage.user("hi")),
                List.of(),
                "session-1",
                tokens::add);

        assertThat(tokens).containsExactly("Hello ", "world");
        assertThat(result.message().text()).isEqualTo("Hello world");
        assertThat(result.message().toolCalls()).isEmpty();
        assertThat(result.usage().inputTokens()).isEqualTo(11);
        assertThat(result.usage().outputTokens()).isEqualTo(2);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/chat/completions");
        assertThat(request.getHeader("Accept")).contains("text/event-stream");
        String reqBody = request.getBody().readUtf8();
        assertThat(reqBody).contains("\"stream\":true");
        assertThat(reqBody).contains("\"include_usage\":true");
    }

    @Test
    void streamsToolCallFragmentsWithoutInvokingTokenConsumer() {
        String sse = String.join("",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_42\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Berlin\\\"}\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":7,\"total_tokens\":47}}\n\n",
                "data: [DONE]\n\n"
        );

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));

        List<String> tokens = new ArrayList<>();
        CompletionResult result = provider.completeStreaming(
                "system",
                List.of(ChatMessage.user("weather in berlin?")),
                List.of(),
                "session-1",
                tokens::add);

        assertThat(tokens).isEmpty();
        assertThat(result.message().text()).isEmpty();
        assertThat(result.message().toolCalls()).hasSize(1);
        ToolCall call = result.message().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_42");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).containsEntry("city", "Berlin");
        assertThat(result.usage().inputTokens()).isEqualTo(40);
        assertThat(result.usage().outputTokens()).isEqualTo(7);
    }
}
