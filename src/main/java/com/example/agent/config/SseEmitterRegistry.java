package com.example.agent.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Tracks live {@link SseEmitter}s so we can flush them cleanly on shutdown
 * instead of letting the JVM cut the connection mid-stream.
 *
 * <p>Also runs a periodic heartbeat loop: every {@code agent.sse.heartbeat-interval}
 * a comment-frame (per the SSE spec, lines beginning with {@code :}) is sent
 * to every active emitter. Idle proxies and cloud load balancers usually drop
 * TCP connections that have been silent for 30-60 seconds; a long LLM call or
 * shell tool invocation will exceed that, and the heartbeat keeps the stream
 * alive. Configurable via {@code agent.sse.heartbeat-interval}; setting it to
 * {@link Duration#ZERO} disables the loop entirely (useful for tests).
 *
 * <p>See {@code docs/adr/0002-multi-instance-sse.md} for the design rationale.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** SSE comment frame body. Clients' EventSource layer ignores comments. */
    private static final String HEARTBEAT_COMMENT = "hb";

    private final Set<SseEmitter> active = ConcurrentHashMap.newKeySet();
    private final Duration heartbeatInterval;
    private final AgentMetrics metrics;

    /** Owned by this bean — shut down in {@link #shutdownScheduler()}. */
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> heartbeatFuture;

    public SseEmitterRegistry(AgentProperties props, AgentMetrics metrics) {
        this.heartbeatInterval = props.getSse().getHeartbeatInterval() == null
                ? Duration.ZERO
                : props.getSse().getHeartbeatInterval();
        this.metrics = metrics;
    }

    @PostConstruct
    void startHeartbeats() {
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            log.info("SSE heartbeats disabled (agent.sse.heartbeat-interval={})", heartbeatInterval);
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sse-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        heartbeatFuture = scheduler.schedule(this::sendHeartbeats, new PeriodicTrigger(heartbeatInterval));
        log.info("SSE heartbeats every {}", heartbeatInterval);
    }

    @PreDestroy
    void shutdownScheduler() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * Register an emitter and auto-remove it on completion/timeout/error.
     */
    public SseEmitter register(SseEmitter emitter) {
        active.add(emitter);
        emitter.onCompletion(() -> active.remove(emitter));
        emitter.onTimeout(() -> active.remove(emitter));
        emitter.onError(ex -> active.remove(emitter));
        return emitter;
    }

    public int activeCount() { return active.size(); }

    /**
     * Send a comment-frame heartbeat to every active emitter. An emitter that
     * throws on send is already broken — remove it so we don't accumulate dead
     * references and so the recorded {@code outcome=error} metric is bounded.
     *
     * <p>Package-private for testability.
     */
    void sendHeartbeats() {
        if (active.isEmpty()) return;
        for (Iterator<SseEmitter> it = active.iterator(); it.hasNext(); ) {
            SseEmitter e = it.next();
            try {
                e.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                if (metrics != null) metrics.recordSseHeartbeat(true);
            } catch (IOException | IllegalStateException ex) {
                // Underlying response closed (proxy timeout, client gone) —
                // drop the emitter. onError/onCompletion will not fire again.
                it.remove();
                if (metrics != null) metrics.recordSseHeartbeat(false);
                log.debug("Dropping SSE emitter after failed heartbeat: {}", ex.toString());
            } catch (Exception ex) {
                // Unexpected — log louder so it shows up in dashboards but
                // still drop so we don't loop forever on this emitter.
                it.remove();
                if (metrics != null) metrics.recordSseHeartbeat(false);
                log.warn("Unexpected error sending SSE heartbeat; dropping emitter", ex);
            }
        }
    }

    /**
     * On context close, send a final "shutdown" event and complete every emitter
     * so clients learn the stream is done rather than seeing a socket reset.
     */
    @EventListener
    public void onShutdown(ContextClosedEvent event) {
        int n = active.size();
        if (n == 0) return;
        log.info("Completing {} in-flight SSE stream(s) on shutdown", n);
        for (SseEmitter e : active) {
            try {
                e.send(SseEmitter.event().name("shutdown").data(Map.of("reason", "server_shutdown")));
            } catch (IOException ignored) {}
            try {
                e.complete();
            } catch (Exception ignored) {}
        }
        active.clear();
    }
}
