package com.example.agent.controller;

import com.example.agent.config.SafeMessage;

/**
 * Thrown when a session lookup returns nothing — either because the id is
 * unknown OR because the caller doesn't own it. We deliberately don't
 * distinguish the two cases (to avoid leaking existence).
 */
@SafeMessage
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String id) {
        super("Session not found: " + id);
    }
}
