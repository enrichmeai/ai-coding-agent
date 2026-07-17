-- Initial schema. Tables match the JPA entities in
-- com.example.agent.service.persistence.*

CREATE TABLE IF NOT EXISTS agent_sessions (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_messages (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT    NOT NULL,
    position      INTEGER NOT NULL,
    role          TEXT    NOT NULL,
    payload_json  TEXT    NOT NULL,
    timestamp     TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_msg_session
    ON agent_messages (session_id, position);
