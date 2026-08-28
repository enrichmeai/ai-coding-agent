package com.example.agent.tools;

/**
 * Who a tool call is acting for.
 *
 * Resolved once on the request thread (see CLAUDE.md, "Identity on background
 * threads") and passed explicitly down the call chain — tools must NEVER read
 * thread-local security state, because the agent loop runs on pooled threads
 * where the SecurityContext is absent.
 *
 * A tool that talks to an external system can use {@link #userId()} to act
 * with a per-user credential (see {@code CredentialResolver}); tools that
 * don't care simply ignore the context.
 */
public record ToolContext(String userId, String sessionId) {

    public static final String ANONYMOUS = "anonymous";

    public ToolContext {
        userId = (userId == null || userId.isBlank()) ? ANONYMOUS : userId;
    }

    /** For callers with no authenticated user (tests, direct invocations). */
    public static ToolContext anonymous() {
        return new ToolContext(ANONYMOUS, null);
    }
}
