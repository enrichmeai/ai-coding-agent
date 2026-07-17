package com.example.agent.controller.dto;

import com.example.agent.model.Session;
import com.example.agent.model.TokenUsage;

import java.time.Instant;

public record SessionSummary(
        String id,
        String title,
        Instant createdAt,
        int messageCount,
        TokenUsage usage) {

    public static SessionSummary of(Session s) {
        return new SessionSummary(s.getId(), s.getTitle(), s.getCreatedAt(),
                s.getHistory().size(), s.getTotalUsage());
    }
}
