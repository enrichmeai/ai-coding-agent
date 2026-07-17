-- Initial schema (Postgres port). Tables match the JPA entities in
-- com.example.agent.service.persistence.*

CREATE TABLE agent_sessions (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE agent_messages (
    id            BIGSERIAL PRIMARY KEY,
    session_id    TEXT    NOT NULL,
    position      INTEGER NOT NULL,
    role          TEXT    NOT NULL,
    payload_json  TEXT    NOT NULL,
    timestamp     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_msg_session
    ON agent_messages (session_id, position);
