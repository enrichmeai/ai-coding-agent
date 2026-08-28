package com.example.agent.service;

import com.example.agent.config.AgentProperties;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.*;
import com.example.agent.tools.Tool;
import com.example.agent.tools.ToolRegistry;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTest {

    @Test
    void loopExecutesToolsAndReturnsFinalMessage() {
        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "echo"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                              "properties", Map.of("text", Map.of("type", "string")),
                              "required", List.of("text"));
            }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                return ToolResult.ok(id, "echoed: " + args.get("text"));
            }
        };

        AgentProperties props = new AgentProperties();
        ToolRegistry registry = new ToolRegistry(List.of(echo), props);

        LlmProvider scripted = new LlmProvider() {
            int call = 0;
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                call++;
                if (call == 1) {
                    return new CompletionResult(
                            ChatMessage.assistant("thinking…",
                                    List.of(new ToolCall("tc1", "echo", Map.of("text", "hi")))),
                            new TokenUsage(100, 10));
                }
                assertTrue(hist.stream().anyMatch(m -> m.role() == Role.TOOL));
                return new CompletionResult(ChatMessage.assistantText("done"), new TokenUsage(120, 5));
            }
        };

        props.getLlm().setMaxTurnsPerRequest(5);
        SessionStore store = new InMemorySessionStore();
        AgentService svc = new AgentService(scripted, registry, props, store);

        Session s = svc.createSession();
        List<ChatMessage> produced = svc.chat(s, "please echo hi");

        assertEquals("done", produced.get(produced.size() - 1).text());
        assertTrue(s.getHistory().stream()
                .flatMap(m -> m.toolResults().stream())
                .anyMatch(r -> r.content().equals("echoed: hi")));

        // Usage accumulated across both LLM calls
        assertEquals(220, s.getTotalUsage().inputTokens());
        assertEquals(15,  s.getTotalUsage().outputTokens());
    }

    @Test
    void stopsWhenPerSessionBudgetExceeded() {
        Tool dummy = new Tool() {
            @Override public String name() { return "dummy"; }
            @Override public String description() { return "dummy"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                return ToolResult.ok(id, "done");
            }
        };

        AgentProperties props = new AgentProperties();
        props.getLlm().setMaxTokensPerSession(100);  // Very low limit
        ToolRegistry registry = new ToolRegistry(List.of(dummy), props);

        LlmProvider scripted = new LlmProvider() {
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                // First call reports usage that exceeds the session budget
                return new CompletionResult(
                        ChatMessage.assistantText("response"),
                        new TokenUsage(60, 50));  // total = 110, exceeds limit of 100
            }
        };

        SessionStore store = new InMemorySessionStore();
        AgentService svc = new AgentService(scripted, registry, props, store);

        Session s = svc.createSession();
        List<ChatMessage> produced = svc.chat(s, "test");

        // Should have budget exceeded message
        assertTrue(produced.stream()
                .anyMatch(m -> m.text() != null && m.text().contains("Budget exceeded for this session")));
    }

    /** Spec 0001 acceptance criterion 3: streaming-enabled=false routes through complete(), not completeStreaming(). */
    @Test
    void killSwitchRoutesThroughNonStreamingPath() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setStreamingEnabled(false);
        ToolRegistry registry = new ToolRegistry(List.of(), props);

        AtomicInteger completeCalls = new AtomicInteger();
        AtomicInteger streamingCalls = new AtomicInteger();

        LlmProvider scripted = new LlmProvider() {
            @Override public String name() { return "scripted"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                completeCalls.incrementAndGet();
                return new CompletionResult(ChatMessage.assistantText("ok"), new TokenUsage(1, 1));
            }
            @Override public CompletionResult completeStreaming(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId, Consumer<String> onToken) {
                streamingCalls.incrementAndGet();
                return complete(sys, hist, tools, sessionId);
            }
        };

        SessionStore store = new InMemorySessionStore();
        AgentService svc = new AgentService(scripted, registry, props, store);
        svc.chatStreaming(svc.createSession(), "hi", m -> {}, s -> {});

        assertEquals(1, completeCalls.get(), "non-streaming path should be taken");
        assertEquals(0, streamingCalls.get(), "streaming path should be skipped");
    }

    /** A provider that always asks for another tool call, so the loop runs to its cap. */
    private static LlmProvider alwaysCallsATool() {
        return new LlmProvider() {
            @Override public String name() { return "looping"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist,
                                                       List<ToolSpec> tools, String sessionId) {
                return new CompletionResult(
                        ChatMessage.assistant("again",
                                List.of(new ToolCall("tc", "echo", Map.of("text", "x")))),
                        new TokenUsage(10, 1));
            }
        };
    }

    private static Tool echoTool() {
        return new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "echo"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ToolResult execute(String id, Map<String, Object> args) {
                return ToolResult.ok(id, "echoed");
            }
        };
    }

    @Test
    void stoppingAtMaxTurnsExplainsHowToContinueAndPersistsTheSession() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setMaxTurnsPerRequest(3);
        ToolRegistry registry = new ToolRegistry(List.of(echoTool()), props);
        SessionStore store = new InMemorySessionStore();
        AgentService svc = new AgentService(alwaysCallsATool(), registry, props, store);

        Session s = svc.createSession();
        List<ChatMessage> produced = svc.chat(s, "loop forever please");

        String last = produced.get(produced.size() - 1).text();
        assertTrue(last.contains("reached max turns (3)"), last);
        // The bound is useless if the user cannot tell what to do next.
        assertTrue(last.contains("Send another message to continue"), last);

        // The session must survive the bail-out, or the history needed to continue is lost.
        assertNotNull(store.get(s.getId()).orElse(null));
        assertEquals(s.getHistory().size(), store.get(s.getId()).get().getHistory().size());
    }

    @Test
    void aFollowUpMessageContinuesWithTheEarlierHistoryIntact() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setMaxTurnsPerRequest(2);
        ToolRegistry registry = new ToolRegistry(List.of(echoTool()), props);
        SessionStore store = new InMemorySessionStore();

        // First request exhausts its turn budget; the second answers immediately.
        LlmProvider provider = new LlmProvider() {
            int request = 0;
            List<ChatMessage> historySeenOnSecondRequest;
            @Override public String name() { return "two-phase"; }
            @Override public CompletionResult complete(String sys, List<ChatMessage> hist,
                                                       List<ToolSpec> tools, String sessionId) {
                if (hist.stream().filter(m -> m.role() == Role.USER).count() > 1) {
                    historySeenOnSecondRequest = hist;
                    // The whole point: the earlier turn's work is still visible.
                    assertTrue(hist.stream().anyMatch(m -> m.role() == Role.TOOL),
                            "the follow-up must still see the earlier tool results");
                    return new CompletionResult(ChatMessage.assistantText("finished"),
                            new TokenUsage(5, 1));
                }
                return new CompletionResult(
                        ChatMessage.assistant("again",
                                List.of(new ToolCall("tc", "echo", Map.of()))),
                        new TokenUsage(10, 1));
            }
        };

        AgentService svc = new AgentService(provider, registry, props, store);
        Session s = svc.createSession();
        svc.chat(s, "start the work");
        List<ChatMessage> second = svc.chat(s, "continue");

        assertEquals("finished", second.get(second.size() - 1).text());
    }
}
