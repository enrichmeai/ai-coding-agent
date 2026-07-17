package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private AgentProperties props;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        mapper = new ObjectMapper();

        // Configure rate limit for testing
        AgentProperties.RateLimit rateLimit = new AgentProperties.RateLimit();
        rateLimit.setEnabled(true);

        // Chat bucket: capacity 3; refill rate matches capacity per hour so no
        // refill occurs during the sub-second test window. Bucket4j rejects
        // refillTokens=0, so the bucket starts full and stays effectively full.
        rateLimit.setChat(new AgentProperties.Bucket(3, 3, Duration.ofHours(1)));

        // API bucket: capacity 10; same refill semantics.
        rateLimit.setApi(new AgentProperties.Bucket(10, 10, Duration.ofHours(1)));

        props.setRateLimit(rateLimit);
        filter = new RateLimitFilter(props, mapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chatEndpointEnforcesBurstLimit() throws ServletException, IOException {
        // Setup: chat endpoint with IP 192.168.1.1
        String ip = "192.168.1.1";

        // First 3 requests should succeed
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = createRequest("/api/chat/send", ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus(), "Request " + (i + 1) + " should be allowed");
        }

        // 4th request should be rate limited
        MockHttpServletRequest request = createRequest("/api/chat/send", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertEquals(429, response.getStatus(), "4th request should return 429");
        assertTrue(response.containsHeader("Retry-After"), "Should contain Retry-After header");

        // Verify response body
        String contentType = response.getContentType();
        assertTrue(contentType.contains("application/json"), "Content-Type should be JSON");

        String body = response.getContentAsString();
        assertNotNull(body);
        assertTrue(body.contains("rate_limited"), "Body should contain rate_limited code");
        assertTrue(body.contains("retryAfterSeconds"), "Body should contain retryAfterSeconds");
    }

    @Test
    void differentPrincipalsHaveIndependentBuckets() throws ServletException, IOException {
        // IP 192.168.1.1 makes 3 requests (saturates its chat bucket)
        String ip1 = "192.168.1.1";
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = createRequest("/api/chat/send", ip1);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();
            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus());
        }

        // IP 192.168.1.2 should have its own bucket and be allowed
        String ip2 = "192.168.1.2";
        MockHttpServletRequest request = createRequest("/api/chat/send", ip2);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus(), "Different IP should have independent bucket");
    }

    @Test
    void disabledShortCircuits() throws ServletException, IOException {
        props.getRateLimit().setEnabled(false);
        filter = new RateLimitFilter(props, mapper);

        String ip = "192.168.1.1";

        // Make 1000 requests without consuming the bucket
        for (int i = 0; i < 1000; i++) {
            MockHttpServletRequest request = createRequest("/api/chat/send", ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus(), "All requests should pass when disabled");
        }
    }

    @Test
    void authenticatedPrincipalTakesPriorityOverIp() throws ServletException, IOException {
        // Set up authenticated principal
        String principalName = "testuser";
        // Use the 3-arg constructor so the token is flagged as authenticated;
        // the 2-arg form leaves isAuthenticated()=false and resolveKey falls
        // through to IP-based keying (which would defeat this test).
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principalName, "password", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        // Make 3 requests with different IPs but same principal
        // They should all use the same bucket (keyed by principal name)
        for (int i = 0; i < 3; i++) {
            String ip = "192.168.1." + (i + 1);
            MockHttpServletRequest request = createRequest("/api/chat/send", ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus(), "Request " + (i + 1) + " with principal should be allowed");
        }

        // 4th request should be rate limited because principal bucket is exhausted
        MockHttpServletRequest request = createRequest("/api/chat/send", "192.168.1.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertEquals(429, response.getStatus(), "4th request with same principal should be rate limited");
    }

    @Test
    void nonApiPathsBypassFilter() throws ServletException, IOException {
        // Make unlimited requests to non-/api path
        for (int i = 0; i < 100; i++) {
            MockHttpServletRequest request = createRequest("/health", "192.168.1.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus(), "Non-/api requests should bypass rate limiting");
        }
    }

    @Test
    void apiPathsEnforceRateLimit() throws ServletException, IOException {
        // Configure API bucket with low capacity
        AgentProperties.RateLimit rateLimit = props.getRateLimit();
        rateLimit.setApi(new AgentProperties.Bucket(2, 2, Duration.ofHours(1)));
        filter = new RateLimitFilter(props, mapper);

        String ip = "192.168.1.1";

        // First 2 requests to /api/something should succeed
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = createRequest("/api/something", ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus());
        }

        // 3rd request should be rate limited
        MockHttpServletRequest request = createRequest("/api/something", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void xForwardedForHeaderIsRespected() throws ServletException, IOException {
        String ip1 = "203.0.113.1, 198.51.100.2";  // X-Forwarded-For with multiple IPs
        String expectedKey = "203.0.113.1";         // Should use first IP

        // Make 3 requests with same X-Forwarded-For
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = createRequest("/api/chat/send", "127.0.0.1");
            request.addHeader("X-Forwarded-For", ip1);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();

            filter.doFilterInternal(request, response, chain);
            assertEquals(200, response.getStatus());
        }

        // 4th request with same X-Forwarded-For should be rate limited
        MockHttpServletRequest request = createRequest("/api/chat/send", "127.0.0.1");
        request.addHeader("X-Forwarded-For", ip1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertEquals(429, response.getStatus());
    }

    @Test
    void retryAfterHeaderContainsValidSeconds() throws ServletException, IOException {
        String ip = "192.168.1.1";

        // Exhaust the bucket
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = createRequest("/api/chat/send", ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mockChain();
            filter.doFilterInternal(request, response, chain);
        }

        // Next request should be rate limited with valid Retry-After
        MockHttpServletRequest request = createRequest("/api/chat/send", ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mockChain();

        filter.doFilterInternal(request, response, chain);
        assertTrue(response.containsHeader("Retry-After"));

        String retryAfter = response.getHeader("Retry-After");
        assertNotNull(retryAfter);
        long seconds = Long.parseLong(retryAfter);
        assertTrue(seconds > 0, "Retry-After should be positive");
        assertTrue(seconds <= 3600, "Retry-After should not exceed refill period");
    }

    // Helper methods

    private MockHttpServletRequest createRequest(String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private FilterChain mockChain() {
        return new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                ((HttpServletResponse) response).setStatus(200);
            }
        };
    }
}
