package com.example.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Simple retry-with-exponential-backoff for LLM calls. Retries on:
 *   - HTTP 429 (rate-limited)
 *   - HTTP 5xx (server errors)
 *   - Network-level IO errors (connect reset, etc)
 * Does NOT retry on 4xx-other (bad request, auth, etc) since those are programmatic errors.
 */
public final class LlmRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmRetry.class);

    private LlmRetry() {}

    public static <T> T call(String providerName, Supplier<T> action) {
        return call(providerName, action, 3, Duration.ofMillis(500));
    }

    public static <T> T call(String providerName, Supplier<T> action,
                             int maxAttempts, Duration initialDelay) {
        RuntimeException last = null;
        Duration delay = initialDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (WebClientResponseException ex) {
                int status = ex.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == maxAttempts) {
                    throw new RuntimeException(providerName + " API error " + status
                            + ": " + ex.getResponseBodyAsString(), ex);
                }
                // Honor Retry-After header when present
                String retryAfter = ex.getHeaders().getFirst("Retry-After");
                Duration backoff = parseRetryAfter(retryAfter).orElse(delay);
                log.warn("{} API {} (attempt {}/{}), sleeping {}ms",
                        providerName, status, attempt, maxAttempts, backoff.toMillis());
                sleep(backoff);
                delay = delay.multipliedBy(2);
                last = ex;
            } catch (WebClientRequestException ex) {
                if (attempt == maxAttempts) {
                    throw new RuntimeException(providerName + " network error: " + ex.getMessage(), ex);
                }
                log.warn("{} network error (attempt {}/{}): {}", providerName, attempt, maxAttempts, ex.getMessage());
                sleep(delay);
                delay = delay.multipliedBy(2);
                last = ex;
            }
        }
        throw last == null ? new IllegalStateException("retry exhausted") : last;
    }

    private static java.util.Optional<Duration> parseRetryAfter(String v) {
        if (v == null || v.isBlank()) return java.util.Optional.empty();
        try { return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(v.trim()))); }
        catch (NumberFormatException ignore) { return java.util.Optional.empty(); }
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
