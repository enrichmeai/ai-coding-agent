package com.example.agent.controller;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
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

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.storage.type=memory",
        "agent.rate-limit.enabled=false"
})
class AgentControllerIT {

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class StubConfig {
        @Bean @Primary
        LlmProvider stubProvider() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("Hello from stub"),
                            new TokenUsage(42, 7));
                }
            };
        }
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.provider").value("stub"));
    }

    @Test
    void chatCreatesSessionAndReturnsReply() throws Exception {
        mvc.perform(post("/api/chat")
                   .contentType(APPLICATION_JSON)
                   .content("{\"message\":\"hi\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sessionId").isNotEmpty())
           // history[0] is the user message, history[1] is the stub's reply
           .andExpect(jsonPath("$.history[1].text").value("Hello from stub"));
    }

    @Test
    void toolsEndpointListsRegisteredTools() throws Exception {
        mvc.perform(get("/api/tools"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.name=='read_file')]").exists())
           .andExpect(jsonPath("$[?(@.name=='shell')]").exists());
    }
}
