-- Phase 1.4: per-user session ownership (Postgres port).
-- Existing rows are attributed to "anonymous" so pre-auth data stays visible
-- when auth is disabled.

ALTER TABLE agent_sessions
    ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_session_user
    ON agent_sessions (user_id);
