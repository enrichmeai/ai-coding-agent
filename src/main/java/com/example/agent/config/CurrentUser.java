package com.example.agent.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the current user's name for session ownership.
 *
 *  - Auth disabled (or no security context set): returns "anonymous".
 *  - Auth enabled + authenticated: returns the principal name (e.g. "alice").
 *
 * All sessions are stamped with a userId derived from this value; store
 * implementations use it to filter queries so users only see their own data.
 */
@Component
public class CurrentUser {

    public static final String ANONYMOUS = "anonymous";

    public String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return ANONYMOUS;
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? ANONYMOUS : name;
    }
}
