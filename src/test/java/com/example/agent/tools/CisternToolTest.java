package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import com.example.agent.model.ToolResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part C: the pod tool prefers a per-user credential when one is configured
 * for the acting user, and falls back to its own service credential otherwise.
 * Also pins the tool's refusal contract (403 → successful "refused" result).
 */
class CisternToolTest {

    private MockWebServer pod;
    private CisternTool tool;

    @BeforeEach
    void setUp() throws Exception {
        pod = new MockWebServer();
        pod.start();

        AgentProperties props = new AgentProperties();
        props.getTools().getCistern().setBaseUrl(pod.url("/").toString());
        props.getTools().getCistern().setToken("service-token");
        props.getCredentials().setPerUser(Map.of(
                "cistern", Map.of("alice", "alice-token")));

        tool = new CisternTool(props, WebClient.builder(), new ConfiguredCredentialResolver(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        pod.shutdown();
    }

    @Test
    void actsWithThePerUserCredentialWhenOneIsConfigured() throws Exception {
        pod.enqueue(new MockResponse().setBody("doc"));

        ToolResult r = tool.execute("c1", Map.of("type", "read", "path", "/notes/a.ttl"),
                new ToolContext("alice", "s1"));

        assertFalse(r.isError());
        RecordedRequest req = pod.takeRequest();
        assertEquals("Bearer alice-token", req.getHeader("Authorization"));
    }

    @Test
    void fallsBackToTheServiceCredentialForUsersWithoutOne() throws Exception {
        pod.enqueue(new MockResponse().setBody("doc"));

        ToolResult r = tool.execute("c2", Map.of("type", "read", "path", "/notes/a.ttl"),
                new ToolContext("bob", "s2"));

        assertFalse(r.isError());
        assertEquals("Bearer service-token", pod.takeRequest().getHeader("Authorization"));
    }

    @Test
    void anonymousContextUsesTheServiceCredential() throws Exception {
        pod.enqueue(new MockResponse().setBody("doc"));

        tool.execute("c3", Map.of("type", "read", "path", "/x"), ToolContext.anonymous());

        assertEquals("Bearer service-token", pod.takeRequest().getHeader("Authorization"));
    }

    @Test
    void refusalComesBackAsASuccessfulResultNotAnError() {
        pod.enqueue(new MockResponse().setResponseCode(403));

        ToolResult r = tool.execute("c4", Map.of("type", "read", "path", "/private/x"),
                new ToolContext("alice", "s1"));

        assertFalse(r.isError(), "403 is the owner's decision, not a malfunction");
        assertTrue(r.content().contains("Refused"));
    }
}
