package com.example.agent.service.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Audit log entry for tool calls and LLM calls.
 * Records what the agent did on behalf of a user/session.
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_session", columnList = "session_id,timestamp"),
        @Index(name = "idx_audit_user", columnList = "user_id,timestamp")
})
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 255)
    private String userId;

    @Column(length = 36)
    private String sessionId;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Lob
    @Column(name = "detail_json", nullable = false)
    private String detailJson;

    protected AuditEventEntity() {}

    public AuditEventEntity(Instant timestamp, String userId, String sessionId, String eventType, String detailJson) {
        this.timestamp = timestamp;
        this.userId = userId;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.detailJson = detailJson;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getEventType() { return eventType; }
    public String getDetailJson() { return detailJson; }
}
