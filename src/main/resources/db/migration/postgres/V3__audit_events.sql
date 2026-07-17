-- Phase 2.5: audit log (Postgres port). Records tool_call and llm_call events
-- per session/user so we can answer "what did the agent do on my behalf?"
-- after the fact.

CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    timestamp   TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id     TEXT NOT NULL,
    session_id  TEXT,
    event_type  TEXT NOT NULL,     -- tool_call | llm_call
    detail_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_session  ON audit_events (session_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_user     ON audit_events (user_id, timestamp);
