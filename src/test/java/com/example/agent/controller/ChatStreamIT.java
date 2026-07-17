package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.model.ToolCall;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-sse",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.storage.type=memory",
        "agent.rate-limit.enabled=false"
})
class ChatStreamIT {

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider scriptedStub() {
            return new LlmProvider() {
                int call = 0;
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    call++;
                    if (call == 1) {
                        // Turn 1 — emit a tool call so the controller emits a tool-result event too.
                        return new CompletionResult(
                                ChatMessage.assistant("Let me check.",
                                        List.of(new ToolCall("tc1", "list_dir", Map.of()))),
                                new TokenUsage(10, 5));
                    }
                    return new CompletionResult(ChatMessage.assistantText("All good."),
                            new TokenUsage(7, 3));
                }
            };
        }
    }

    @Test
    void streamsSessionMessageDoneEvents() throws Exception {
        MvcResult async = mvc.perform(post("/api/chat/stream")
                        .contentType(APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Initial and final session events
        assertThat(body).contains("event:session");
        // Three messages: assistant-with-tool-call, tool-result, final assistant
        int msgCount = body.split("event:message").length - 1;
        assertThat(msgCount).isGreaterThanOrEqualTo(3);
        // Done terminator
        assertThat(body).contains("event:done");
        // Tokens accumulated across both calls
        assertThat(body).contains("\"total\":25");
    }
}
