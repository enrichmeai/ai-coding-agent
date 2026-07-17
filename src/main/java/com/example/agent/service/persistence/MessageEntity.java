package com.example.agent.service.persistence;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single message, serialised as JSON in {@code payload_json}.
 * Using a JSON blob keeps the schema simple (no join table for tool calls/results).
 */
@Entity
@Table(name = "agent_messages", indexes = {
        @Index(name = "idx_msg_session", columnList = "session_id,position")
})
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** Monotonically increasing index within a session, starting at 0. */
    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 32)
    private String role;

    // @Lob would make Hibernate use Postgres OID large-object binding, which
    // requires non-autocommit transactions. Both SQLite and Postgres migrations
    // declare the column as TEXT, so plain String binding works on every vendor.
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private Instant timestamp;

    protected MessageEntity() {}

    public MessageEntity(String sessionId, int position, String role, String payloadJson, Instant timestamp) {
        this.sessionId = sessionId;
        this.position = position;
        this.role = role;
        this.payloadJson = payloadJson;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public int getPosition() { return position; }
    public String getRole() { return role; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getTimestamp() { return timestamp; }
}
