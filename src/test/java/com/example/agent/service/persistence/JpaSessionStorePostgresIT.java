package com.example.agent.service.persistence;

import com.example.agent.config.CurrentUser;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for JpaSessionStore against a real Postgres via Testcontainers.
 *
 * Validates:
 *  - Flyway migrations under {@code db/migration/postgres} apply cleanly.
 *  - JpaSessionStore.create() then get() round-trips a session.
 *  - appendMessage() persists and re-hydrates as ChatMessage.
 *  - Cross-user isolation still holds (Bob can't see Alice's session).
 *
 * Skipped automatically when Docker isn't available (Testcontainers' default).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")  // gates context-load too; @Testcontainers only skips test methods
@SpringBootTest
@ActiveProfiles("storage-postgres")
@TestPropertySource(properties = {
        "agent.workspace=${java.io.tmpdir}/agent-test-workspace-pgit",
        "agent.llm.provider=stub",
        "agent.auth.enabled=false",
        "agent.storage.type=postgres",
        "agent.rate-limit.enabled=false"
})
class JpaSessionStorePostgresIT {

    @SuppressWarnings("unused")
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // Shared singleton container — reuse across tests in this class.
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agent")
            .withUsername("agent")
            .withPassword("agent")
            .withReuse(true);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class StubCfg {
        // AgentService requires an LlmProvider bean; provide a no-op stub so the
        // full @SpringBootTest context can boot without a real provider configured.
        @Bean @Primary
        LlmProvider stubProvider() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> hist,
                                                           List<ToolSpec> tools, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("stub"), new TokenUsage(0, 0));
                }
            };
        }
    }

    @Autowired SessionRepository sessionRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationContext context;

    private JpaSessionStore store;

    /** Mutable stub so we can switch the "current user" between steps. */
    static class StubUser extends CurrentUser {
        volatile String who = CurrentUser.ANONYMOUS;
        @Override public String name() { return who; }
    }
    private StubUser user;

    @BeforeEach
    void setUp() {
        // Arrange: instantiate the store directly so we can swap CurrentUser between
        // assertions. JpaSessionStore is also auto-wired into the context via
        // JpaSessionStoreCondition (asserted in conditionalActivatesForPostgres) —
        // we just don't use that bean here because it captures the live CurrentUser.
        ObjectMapper mapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());
        user = new StubUser();
        store = new JpaSessionStore(sessionRepo, messageRepo, mapper, user);
    }

    @Test
    void conditionalActivatesForPostgres() {
        // Confirms that JpaSessionStoreCondition triggers when
        // agent.storage.type=postgres — the store bean is in the context.
        assertThat(context.getBeansOfType(JpaSessionStore.class)).isNotEmpty();
    }

    @Test
    void flywayMigrationsApplyAndSessionRoundTrips() {
        // Arrange
        user.who = "alice";

        // Act: create a session, then re-fetch it.
        Session created = store.create();
        Session loaded = store.get(created.getId()).orElseThrow();

        // Assert
        assertThat(loaded.getId()).isEqualTo(created.getId());
        assertThat(loaded.getUserId()).isEqualTo("alice");
        assertThat(sessionRepo.findById(created.getId())).isPresent();
    }

    @Test
    void appendMessagePersistsAndHydrates() {
        // Arrange
        user.who = "alice";
        Session s = store.create();

        // Act
        ChatMessage userMsg = ChatMessage.user("hello");
        ChatMessage botMsg = ChatMessage.assistantText("world");
        s.add(userMsg);   store.appendMessage(s, userMsg);
        s.add(botMsg);    store.appendMessage(s, botMsg);

        // Assert
        Session loaded = store.get(s.getId()).orElseThrow();
        List<ChatMessage> h = loaded.getHistory();
        assertThat(h).hasSize(2);
        assertThat(h.get(0).text()).isEqualTo("hello");
        assertThat(h.get(1).text()).isEqualTo("world");
    }

    @Test
    void crossUserAccessIsHidden() {
        // Arrange: Bob creates a session.
        user.who = "bob";
        Session bobs = store.create();
        store.appendMessage(bobs, ChatMessage.user("bob's secret"));

        // Act: Alice lists/gets — she shouldn't see anything of Bob's.
        user.who = "alice";

        // Assert
        assertThat(store.list()).extracting(Session::getId).doesNotContain(bobs.getId());
        assertThat(store.get(bobs.getId())).isEmpty();

        // And Bob still sees his own session intact.
        user.who = "bob";
        assertThat(store.get(bobs.getId())).isPresent();
        assertThat(store.get(bobs.getId()).get().getHistory()).hasSize(1);
    }
}
