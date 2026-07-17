package com.example.agent.config;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies SecurityConfig gating: auth off = everything open; auth on = 401 without
 * credentials, 200 with valid Basic auth.
 */
class SecurityConfigTest {

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider stub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> h, List<ToolSpec> t, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("ok"), TokenUsage.ZERO);
                }
            };
        }
    }

    // ---------- auth OFF ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-off",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOff {
        @Autowired MockMvc mvc;

        @Test
        void anyEndpointIsOpen() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isOk());
            mvc.perform(get("/api/sessions")).andExpect(status().isOk());
        }
    }

    // ---------- auth ON ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=alice",
            "agent.auth.password=wonderland",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-on",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOn {
        @Autowired MockMvc mvc;

        @Test
        void healthIsAlwaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }

        @Test
        void unauthenticatedCallsAreRejected() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void validBasicAuthSucceeds() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:wonderland".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isOk());
        }

        @Test
        void wrongPasswordIsRejected() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:nope".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isUnauthorized());
        }
    }

    // ---------- auth ON + OAuth registered ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=basic",
            "agent.auth.username=alice",
            "agent.auth.password=wonderland",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-oauth",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false",
            // OAuthClientConfig reads these via Environment (any property source works)
            "GITHUB_OAUTH_CLIENT_ID=test-id",
            "GITHUB_OAUTH_CLIENT_SECRET=test-secret"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class AuthOnWithOAuth {
        @Autowired MockMvc mvc;

        @Test
        void apiBasicAuthStillWorks() throws Exception {
            String creds = java.util.Base64.getEncoder()
                    .encodeToString("alice:wonderland".getBytes());
            mvc.perform(get("/api/tools").header(AUTHORIZATION, "Basic " + creds))
               .andExpect(status().isOk());
        }

        @Test
        void apiUnauthenticatedStillReturns401() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void oauthAuthorizationEndpointRedirectsToProvider() throws Exception {
            String location = mvc.perform(get("/oauth2/authorization/github"))
               .andExpect(status().is3xxRedirection())
               .andReturn().getResponse().getHeader("Location");
            assertNotNull(location, "Location header must be set");
            assertTrue(location.startsWith("https://github.com/login/oauth/authorize?"),
                    "Expected GitHub authorize URL but got: " + location);
            assertTrue(location.contains("client_id=test-id"),
                    "Authorize URL should embed configured client id: " + location);
        }
    }

    // ---------- auth ON, mode=oidc ----------

    /**
     * OIDC mode: API requires a JWT bearer token. We replace the production
     * {@link JwtDecoder} with a {@link MockBean} so the resource server does
     * NOT try to fetch JWKS from {@code https://test.example.com/.well-known/...}
     * at startup. Tests then use Spring Security's
     * {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt()}
     * helper to inject an authenticated context — that path short-circuits the
     * decoder, so the {@code @MockBean} only exists to satisfy the bean
     * dependency graph at startup.
     */
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=true",
            "agent.auth.mode=oidc",
            "agent.auth.oidc.issuer-uri=https://test.example.com",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-sec-oidc",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class OidcAuthMode {
        @Autowired MockMvc mvc;
        @MockBean JwtDecoder jwtDecoder;

        @Test
        void unauthenticatedReturns401() throws Exception {
            mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        }

        @Test
        void validJwtSucceeds() throws Exception {
            mvc.perform(get("/api/tools").with(jwt().jwt(j -> j.subject("alice"))))
               .andExpect(status().isOk());
        }

        @Test
        void healthIsAlwaysOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }
    }
}
