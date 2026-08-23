-- Fix Instant round-tripping on SQLite.
--
-- V1/V3 declared the Instant-backed columns as TEXT. Hibernate's SQLite dialect
-- writes an Instant as epoch millis, so the value lands as "1787309586507", and
-- on read sqlite-jdbc sees a TEXT column and tries to parse it as a formatted
-- timestamp -> java.text.ParseException: Unparseable date. Every read of a
-- session, message or audit row failed with a 500.
--
-- INTEGER is the type sqlite-jdbc reads back as epoch millis, which is what the
-- dialect wrote. SQLite cannot ALTER a column type, so each table is rebuilt.
-- Existing values are epoch-millis-as-text, so CAST recovers them exactly.
-- Postgres already used TIMESTAMP WITH TIME ZONE and needs no change.

CREATE TABLE agent_sessions_new (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    user_id     TEXT NOT NULL DEFAULT 'anonymous'
);
INSERT INTO agent_sessions_new (id, title, created_at, user_id)
    SELECT id, title, CAST(created_at AS INTEGER), user_id FROM agent_sessions;
DROP TABLE agent_sessions;
ALTER TABLE agent_sessions_new RENAME TO agent_sessions;
CREATE INDEX IF NOT EXISTS idx_session_user ON agent_sessions (user_id);

CREATE TABLE agent_messages_new (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT    NOT NULL,
    position      INTEGER NOT NULL,
    role          TEXT    NOT NULL,
    payload_json  TEXT    NOT NULL,
    timestamp     INTEGER NOT NULL
);
INSERT INTO agent_messages_new (id, session_id, position, role, payload_json, timestamp)
    SELECT id, session_id, position, role, payload_json, CAST(timestamp AS INTEGER) FROM agent_messages;
DROP TABLE agent_messages;
ALTER TABLE agent_messages_new RENAME TO agent_messages;
CREATE INDEX IF NOT EXISTS idx_msg_session ON agent_messages (session_id, position);

CREATE TABLE audit_events_new (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   INTEGER NOT NULL,
    user_id     TEXT NOT NULL,
    session_id  TEXT,
    event_type  TEXT NOT NULL,
    detail_json TEXT NOT NULL
);
INSERT INTO audit_events_new (id, timestamp, user_id, session_id, event_type, detail_json)
    SELECT id, CAST(timestamp AS INTEGER), user_id, session_id, event_type, detail_json FROM audit_events;
DROP TABLE audit_events;
ALTER TABLE audit_events_new RENAME TO audit_events;
CREATE INDEX IF NOT EXISTS idx_audit_session ON audit_events (session_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_user    ON audit_events (user_id, timestamp);
