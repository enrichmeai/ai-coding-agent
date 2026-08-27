package com.example.agent;

import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for issue #12: the app failed to start in its DEFAULT storage
 * mode (memory) because the readiness health group unconditionally included the
 * {@code db} contributor, which only exists when a DataSource is configured.
 *
 * The blind spot this test closes: {@code AgentApplication.main()} applies the
 * memory-mode auto-config exclusions before Spring starts, but @SpringBootTest
 * never runs main() — and H2 sits on the test classpath, so every other test
 * context silently auto-configures a DataSource that production memory mode
 * never has. The other memory-mode ITs therefore pass vacuously. This test
 * replicates main()'s exclusions explicitly so it boots the context production
 * actually runs; on the broken config it fails at context refresh with
 * "Included health contributor 'db' in group 'readiness' does not exist".
 */
@SpringBootTest
@TestPropertySource(properties = {
        // The exact exclusions AgentApplication.main() sets for memory mode.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-memory-startup",
        "agent.llm.provider=stub",
        "agent.storage.type=memory",
        "agent.auth.enabled=false",
        "agent.rate-limit.enabled=false"
})
class MemoryModeStartupIT {

    @Autowired HealthEndpointGroups healthGroups;

    @TestConfiguration
    static class StubCfg {
        @Bean @Primary
        LlmProvider stub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist, List<ToolSpec> tools, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("ok"), new TokenUsage(1, 1));
                }
            };
        }
    }

    @Test
    void contextStartsWithoutDataSourceAndReadinessOmitsDb() {
        // Reaching this method at all is the core regression check — the broken
        // config aborts context refresh before any test can run.
        HealthEndpointGroup readiness = healthGroups.get("readiness");
        assertThat(readiness).isNotNull();
        assertThat(readiness.isMember("llmProvider")).isTrue();
        assertThat(readiness.isMember("db"))
                .as("readiness must not reference db when no DataSource exists")
                .isFalse();
    }
}
