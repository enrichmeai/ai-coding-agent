package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Role;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
import com.example.agent.service.persistence.AuditEventEntity;
import com.example.agent.service.persistence.AuditEventRepository;
import com.example.agent.service.persistence.SessionRepository;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for identity propagation across async boundaries.
 *
 * The defect: the agent loop runs on the bare sseTaskExecutor for /chat/stream,
 * and audit writes are {@code @Async} on both chat paths — neither thread has a
 * SecurityContext, so identity read from thread-local state resolves to
 * "anonymous". This misattributed every audit event and silently dropped the
 * session-title update in the JPA store. A stub-injected unit test cannot catch
 * this: the bug lives in the threading, so these tests run the full context
 * with auth enabled and cross the real executor boundary.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-identity",
        "agent.llm.provider=stub",
        "agent.auth.enabled=true",
        "agent.auth.username=alice",
        "agent.auth.password=secret",
        "agent.rate-limit.enabled=false",
        // JPA store on H2: activates JpaSessionStore + the audit repository
        // without a real SQLite file. Flyway stays off; Hibernate owns the
        // schema for the test.
        "agent.storage.type=sqlite",
        "spring.datasource.url=jdbc:h2:mem:identity-prop;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class IdentityPropagationIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":\"([0-9a-f-]+)\"");

    @Autowired MockMvc mvc;
    @Autowired AuditEventRepository auditRepo;
    @Autowired SessionRepository sessionRepo;

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider scriptedStub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    // Stateless script: a fresh user message triggers one tool
                    // call; the follow-up (after the TOOL result) ends the turn.
                    ChatMessage last = hist.get(hist.size() - 1);
                    if (last.role() == Role.USER) {
                        return new CompletionResult(
                                ChatMessage.assistant("Checking.",
                                        List.of(new ToolCall("tc1", "list_dir", Map.of()))),
                                new TokenUsage(10, 5));
                    }
                    return new CompletionResult(ChatMessage.assistantText("All done."),
                            new TokenUsage(7, 3));
                }
            };
        }
    }

    @Test
    void syncChatAuditEventsCarryTheAuthenticatedUser() throws Exception {
        MvcResult result = mvc.perform(post("/api/chat")
                        .with(httpBasic("alice", "secret"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"sync audit attribution\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String sessionId = extractSessionId(result.getResponse().getContentAsString());

        // llm_call ×2 + tool_call ×1; writes are @Async, so wait for them.
        List<AuditEventEntity> events = awaitAuditEvents(sessionId, 3);
        assertThat(events).hasSize(3);
        assertThat(events).extracting(AuditEventEntity::getEventType)
                .containsExactlyInAnyOrder("llm_call", "tool_call", "llm_call");
        // The defective code stamped these "anonymous": the @Async audit thread
        // has no SecurityContext to read the principal from.
        assertThat(events).extracting(AuditEventEntity::getUserId)
                .containsOnly("alice");
    }

    @Test
    void streamingChatPersistsTitleAndAuditUserAcrossTheSseExecutor() throws Exception {
        MvcResult async = mvc.perform(post("/api/chat/stream")
                        .with(httpBasic("alice", "secret"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"stream identity check\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:done");
        String sessionId = extractSessionId(body);

        // Title is set from the first message inside the agent loop, which runs
        // on the agent-sse-* executor. The defective store scoped the update by
        // the calling thread's identity ("anonymous" there) and dropped it.
        assertThat(sessionRepo.findById(sessionId).orElseThrow().getTitle())
                .isEqualTo("stream identity check");

        List<AuditEventEntity> events = awaitAuditEvents(sessionId, 3);
        assertThat(events).extracting(AuditEventEntity::getEventType)
                .contains("llm_call", "tool_call");
        assertThat(events).extracting(AuditEventEntity::getUserId)
                .containsOnly("alice");
    }

    private static String extractSessionId(String body) {
        Matcher m = SESSION_ID.matcher(body);
        assertThat(m.find()).as("response contains a sessionId: %s", body).isTrue();
        return m.group(1);
    }

    private List<AuditEventEntity> awaitAuditEvents(String sessionId, int minEvents) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        List<AuditEventEntity> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            events = auditRepo.findBySessionIdOrderByTimestampAsc(sessionId);
            if (events.size() >= minEvents) return events;
            Thread.sleep(50);
        }
        return events;
    }
}
