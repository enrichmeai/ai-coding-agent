package com.example.agent.llm.ollama;

import com.example.agent.config.AgentMetrics;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.mockwebserver.RecordedRequest;
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
    private AgentProperties.Ollama cfg;
    private com.example.agent.llm.ToolCallFormatObserver observer;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        AgentProperties props = new AgentProperties();
        cfg = props.getLlm().getOllama();
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setModel("llama3");

        observer = new com.example.agent.llm.ToolCallFormatObserver();
        registry = new SimpleMeterRegistry();
        provider = new OllamaProvider(
                props,
                WebClient.builder(),
                new ObjectMapper(),
                new AgentMetrics(registry),
                observer);
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

    /** Body of the single request the provider sent. */
    private JsonNode sentBody() throws Exception {
        RecordedRequest recorded = server.takeRequest();
        return new ObjectMapper().readTree(recorded.getBody().readUtf8());
    }

    private void enqueueOk(int promptEvalCount) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"model\":\"llama3\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"done\":true,\"prompt_eval_count\":" + promptEvalCount + ",\"eval_count\":1}"));
    }

    @Test
    void sendsConfiguredNumCtxOnEveryRequest() throws Exception {
        enqueueOk(10);

        provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "s1");

        // Ollama otherwise caps the prompt at its own default and silently drops
        // the overflow, taking the system prompt and tool schemas with it.
        assertThat(sentBody().path("options").path("num_ctx").asInt())
                .isEqualTo(cfg.getNumCtx());
    }

    @Test
    void numCtxIsConfigurable() throws Exception {
        cfg.setNumCtx(4321);
        enqueueOk(10);

        provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "s2");

        assertThat(sentBody().path("options").path("num_ctx").asInt()).isEqualTo(4321);
    }

    @Test
    void numCtxZeroOmitsTheOptionSoTheServerDefaultApplies() throws Exception {
        cfg.setNumCtx(0);
        enqueueOk(10);

        provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "s3");

        assertThat(sentBody().has("options")).isFalse();
    }

    @Test
    void warnsOncePerSessionWhenThePromptCrowdsTheContextWindow() {
        cfg.setNumCtx(1000);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OllamaProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 850 of 1000 is past the 80% mark; a real truncation pins it at num_ctx.
            enqueueOk(850);
            enqueueOk(850);
            provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "noisy-session");
            provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "noisy-session");

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("850")
                    .contains("1000")
                    .contains("num-ctx");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void staysQuietWhenThePromptFitsComfortably() {
        cfg.setNumCtx(1000);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OllamaProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            enqueueOk(100);
            provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "quiet-session");

            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private double formatCount(String format) {
        io.micrometer.core.instrument.Counter c = registry.find("llm_tool_call_format_total")
                .tag("format", format).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void structuredToolCallIsRecordedAsStructured() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\",\"content\":\"\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}}]},"
                        + "\"done\":true,\"prompt_eval_count\":1,\"eval_count\":1}"));

        provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "s1");

        assertThat(observer.structuredCount()).isEqualTo(1);
        assertThat(formatCount("structured")).isEqualTo(1d);
        assertThat(observer.verdict()).contains("structured tool calls seen");
    }

    @Test
    void aCallEmittedAsTextIsRecordedAsRecovered() {
        // qwen3-coder's XML form, which the loop cannot see without the parser.
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\",\"content\":"
                        + "\"<function=list_dir>\\n<parameter=path>\\n.\\n</parameter>\\n</function>\"},"
                        + "\"done\":true,\"prompt_eval_count\":1,\"eval_count\":1}"));

        provider.complete("sys", List.of(ChatMessage.user("hi")), List.of(), "s2");

        assertThat(observer.recoveredCount()).isEqualTo(1);
        assertThat(formatCount("recovered")).isEqualTo(1d);
        // The operator-facing point: it works, but only via best-effort parsing.
        assertThat(observer.verdict()).contains("NO structured tool calls");
    }

    @Test
    void aPlainAnswerIsRecordedAsNone() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"The answer is 4.\"},"
                        + "\"done\":true,\"prompt_eval_count\":1,\"eval_count\":1}"));

        provider.complete("sys", List.of(ChatMessage.user("2+2")), List.of(), "s3");

        assertThat(observer.noneCount()).isEqualTo(1);
        assertThat(formatCount("none")).isEqualTo(1d);
        assertThat(observer.verdict()).contains("no tool call has ever been produced");
    }

    @Test
    void verdictDistinguishesNothingSeenYetFromModelCannotDoIt() {
        // The two states call for opposite reactions: wait, versus change the model.
        assertThat(observer.verdict()).isEqualTo("no completions yet");
    }
}
