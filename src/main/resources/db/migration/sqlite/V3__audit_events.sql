-- Phase 2.5: audit log. Records tool_call and llm_call events per session/user
-- so we can answer "what did the agent do on my behalf?" after the fact.

CREATE TABLE IF NOT EXISTS audit_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   TEXT NOT NULL,
    user_id     TEXT NOT NULL,
    session_id  TEXT,
    event_type  TEXT NOT NULL,     -- tool_call | llm_call
    detail_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_session  ON audit_events (session_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_user     ON audit_events (user_id, timestamp);
