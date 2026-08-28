package com.example.agent.tools;

/**
 * Resolves the outbound credential a tool should use when acting for a user.
 *
 * Part C of the identity work: by default a tool authenticates as ITSELF with
 * its configured service credential, and the far system's rules decide what
 * the agent may touch. Deployments that provision per-user credentials get
 * per-user attribution at the far end with no tool-code changes — the tool
 * asks here first and falls back to its service credential.
 */
public interface CredentialResolver {

    /**
     * @param service logical service name, e.g. "cistern"
     * @param context who the call acts for
     * @return the credential configured for this user on this service, or
     *         {@code null} when none is — callers fall back to their service
     *         credential, never treat null as an error
     */
    String resolve(String service, ToolContext context);
}
