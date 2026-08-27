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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for issue #17: the CORS config reflected ANY origin in
 * Access-Control-Allow-Origin while also sending allow-credentials, so any
 * website could ride a logged-in browser session against the API.
 *
 * Policy under test:
 *  - default (no agent.cors.allowed-origins): locked — no cross-origin grants.
 *    The bundled UI is same-origin and needs none.
 *  - explicit origins: those origins are granted, with credentials.
 *  - "*": any origin granted, but credentials disabled — never both.
 */
class CorsPolicyTest {

    private static final String EVIL = "https://evil.example";
    private static final String APP = "https://app.example.com";

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

    // ---------- default: locked down ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-cors-default",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class DefaultLockedDown {
        @Autowired MockMvc mvc;

        @Test
        void foreignOriginGetsNoCorsGrant() throws Exception {
            mvc.perform(get("/api/health").header(ORIGIN, EVIL))
               .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN))
               .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        @Test
        void foreignPreflightIsRejected() throws Exception {
            mvc.perform(options("/api/chat")
                            .header(ORIGIN, EVIL)
                            .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
               .andExpect(status().isForbidden());
        }
    }

    // ---------- explicit allowed origins ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.cors.allowed-origins=" + APP,
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-cors-explicit",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class ExplicitAllowedOrigins {
        @Autowired MockMvc mvc;

        @Test
        void allowedOriginIsGrantedWithCredentials() throws Exception {
            mvc.perform(get("/api/health").header(ORIGIN, APP))
               .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, APP))
               .andExpect(header().string(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        void otherOriginsAreStillDenied() throws Exception {
            mvc.perform(get("/api/health").header(ORIGIN, EVIL))
               .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    // ---------- wildcard: any origin, but never with credentials ----------

    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = {
            "agent.cors.allowed-origins=*",
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-cors-wildcard",
            "agent.storage.type=memory",
            "agent.rate-limit.enabled=false"
    })
    @org.springframework.context.annotation.Import(StubCfg.class)
    @Nested class WildcardWithoutCredentials {
        @Autowired MockMvc mvc;

        @Test
        void anyOriginAllowedButCredentialsDisabled() throws Exception {
            mvc.perform(get("/api/health").header(ORIGIN, EVIL))
               .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
               .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }
}
