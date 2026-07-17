package com.example.agent.service.persistence;

import com.example.agent.config.CurrentUser;
import com.example.agent.controller.dto.SessionSummary;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for {@link JpaSessionStore#search(String, int)} against H2.
 * Exercises own-match, cross-user isolation, case-insensitivity, and limit.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class JpaSessionStoreSearchTest {

    @Autowired SessionRepository sessionRepo;
    @Autowired MessageRepository messageRepo;

    private JpaSessionStore store;

    static class StubUser extends CurrentUser {
        volatile String who = CurrentUser.ANONYMOUS;
        @Override public String name() { return who; }
    }
    private StubUser user;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());
        user = new StubUser();
        store = new JpaSessionStore(sessionRepo, messageRepo, mapper, user);
    }

    @Test
    void matchesByTitle_ownedOnly() {
        user.who = "alice";
        Session s = store.create();
        s.setTitle("Refactor invoice service");
        store.update(s);

        List<SessionSummary> hits = store.search("invoice", 20);
        assertThat(hits).extracting(SessionSummary::id).containsExactly(s.getId());
    }

    @Test
    void matchesByMessageText_ownedOnly() {
        user.who = "alice";
        Session s = store.create();
        store.appendMessage(s, ChatMessage.user("please fix the pagination bug"));

        List<SessionSummary> hits = store.search("pagination", 20);
        assertThat(hits).extracting(SessionSummary::id).containsExactly(s.getId());
    }

    @Test
    void crossUserMatchesAreInvisible() {
        user.who = "alice";
        Session aliceSession = store.create();
        store.appendMessage(aliceSession, ChatMessage.user("secret deploy notes"));

        user.who = "bob";
        // Bob has no matching sessions of his own.
        assertThat(store.search("secret", 20)).isEmpty();
        assertThat(store.search("deploy", 20)).isEmpty();

        // Bob's own session with the same needle stays his alone.
        Session bobSession = store.create();
        store.appendMessage(bobSession, ChatMessage.user("secret bob note"));
        List<SessionSummary> bobHits = store.search("secret", 20);
        assertThat(bobHits).extracting(SessionSummary::id).containsExactly(bobSession.getId());
    }

    @Test
    void searchIsCaseInsensitive() {
        user.who = "alice";
        Session s = store.create();
        s.setTitle("Migrate Database");
        store.update(s);
        store.appendMessage(s, ChatMessage.user("Add COLUMN to USERS"));

        assertThat(store.search("database", 20)).extracting(SessionSummary::id).contains(s.getId());
        assertThat(store.search("DATABASE", 20)).extracting(SessionSummary::id).contains(s.getId());
        assertThat(store.search("column", 20)).extracting(SessionSummary::id).contains(s.getId());
    }

    @Test
    void limitIsHonoured() {
        user.who = "alice";
        for (int i = 0; i < 5; i++) {
            Session s = store.create();
            s.setTitle("ticket-" + i + " widget");
            store.update(s);
        }
        assertThat(store.search("widget", 2)).hasSize(2);
        assertThat(store.search("widget", 10)).hasSize(5);
    }
}
