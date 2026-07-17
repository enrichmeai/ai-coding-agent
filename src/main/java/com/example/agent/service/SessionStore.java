package com.example.agent.service;

import com.example.agent.controller.dto.SessionSummary;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.Session;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pluggable persistence layer for {@link Session}.
 *
 * Implementations:
 *  - {@link InMemorySessionStore} (default, agent.storage.type=memory)
 *  - {@link com.example.agent.service.persistence.JpaSessionStore} (agent.storage.type=sqlite)
 */
public interface SessionStore {

    /** Create and return a new empty session (already persisted). */
    Session create();

    Optional<Session> get(String id);

    Collection<Session> list();

    void delete(String id);

    /**
     * Record a newly-added message. In-memory stores can no-op (the Session already holds it);
     * persistent stores use this hook to write through.
     */
    void appendMessage(Session session, ChatMessage message);

    /** Update session metadata (e.g. title). */
    void update(Session session);

    /**
     * Search the current user's own sessions by case-insensitive substring match
     * on session title OR any message text. Sorted by createdAt DESC, capped at
     * {@code limit}. Cross-user matches MUST NOT appear; empty list on no match.
     */
    List<SessionSummary> search(String q, int limit);
}
