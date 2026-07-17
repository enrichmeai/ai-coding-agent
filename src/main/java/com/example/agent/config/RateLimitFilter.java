package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rate-limiting filter that applies different bucket shapes to /api/chat/* and /api/* paths.
 * Keying strategy: authenticated principal name > X-Forwarded-For > request.getRemoteAddr()
 * Non-/api paths bypass the filter entirely.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Cache<String, Bucket> bucketCache;

    public RateLimitFilter(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.bucketCache = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.getRateLimit().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // Non-/api paths bypass rate limiting entirely
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // Health probes always bypass — otherwise k8s liveness checks would
        // trip the limit at high cadence and cause cascading restarts.
        if (path.equals("/api/health")) {
            chain.doFilter(request, response);
            return;
        }

        // Resolve key: principal name > X-Forwarded-For > remoteAddr
        String key = resolveKey(request);

        // Determine bucket shape: chat or generic api
        Bucket bucket = bucketCache.get(key, k -> createBucket(
                path.startsWith("/api/chat")
                        ? props.getRateLimit().getChat()
                        : props.getRateLimit().getApi()
        ));

        // Try to consume 1 token
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
        } else {
            // Rate limited
            long nanosToWait = probe.getNanosToWaitForRefill();
            long secondsToWait = (nanosToWait + 999_999_999L) / 1_000_000_000L; // Ceiling division

            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(secondsToWait));

            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("error", "Too Many Requests");
            errorBody.put("code", "rate_limited");
            errorBody.put("retryAfterSeconds", secondsToWait);

            response.getWriter().write(mapper.writeValueAsString(errorBody));
        }
    }

    private String resolveKey(HttpServletRequest request) {
        // Priority 1: authenticated principal
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null) {
            String principal = auth.getPrincipal().toString();
            if (principal != null && !principal.isEmpty() && !"anonymousUser".equals(principal)) {
                return principal;
            }
        }

        // Priority 2: X-Forwarded-For header
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // Get the first IP in the chain
            int commaIndex = xForwardedFor.indexOf(',');
            return commaIndex > 0 ? xForwardedFor.substring(0, commaIndex).trim() : xForwardedFor.trim();
        }

        // Priority 3: remote address
        return request.getRemoteAddr();
    }

    private Bucket createBucket(AgentProperties.Bucket cfg) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(cfg.getCapacity())
                        .refillGreedy(cfg.getRefillTokens(), cfg.getRefillPeriod())
                        .build())
                .build();
    }
}
