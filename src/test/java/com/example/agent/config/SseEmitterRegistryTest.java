package com.example.agent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for {@link SseEmitterRegistry} — focused on the heartbeat loop
 * and dead-emitter eviction. The scheduling subsystem itself is Spring's, so
 * we use a tight 100ms interval and Awaitility rather than spinning a real
 * {@code @SpringBootTest}.
 */
class SseEmitterRegistryTest {

    private SseEmitterRegistry registry;

    @AfterEach
    void cleanup() {
        if (registry != null) registry.shutdownScheduler();
    }

    @Test
    void heartbeatsAreSentToActiveEmitters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(meters);
        registry = new SseEmitterRegistry(propsWith(Duration.ofMillis(100)), metrics);
        registry.startHeartbeats();

        CountingEmitter emitter = new CountingEmitter();
        registry.register(emitter);

        // Wait for at least three heartbeats; tight enough that flakiness is rare.
        await().atMost(Duration.ofSeconds(2))
                .until(() -> emitter.heartbeats.get() >= 3);

        Counter ok = meters.find("sse_heartbeats_sent_total").tag("outcome", "ok").counter();
        assertThat(ok).isNotNull();
        assertThat(ok.count()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void emitterFailingDuringHeartbeatIsRemoved() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(meters);
        registry = new SseEmitterRegistry(propsWith(Duration.ofMillis(100)), metrics);
        registry.startHeartbeats();

        ThrowingEmitter emitter = new ThrowingEmitter();
        registry.register(emitter);

        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.activeCount() == 0);

        Counter err = meters.find("sse_heartbeats_sent_total").tag("outcome", "error").counter();
        assertThat(err).isNotNull();
        assertThat(err.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void zeroIntervalDisablesScheduler() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(meters);
        registry = new SseEmitterRegistry(propsWith(Duration.ZERO), metrics);
        registry.startHeartbeats();

        CountingEmitter emitter = new CountingEmitter();
        registry.register(emitter);

        // Give a real scheduler plenty of time — none should run.
        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        assertThat(emitter.heartbeats.get()).isZero();
        assertThat(meters.find("sse_heartbeats_sent_total").counters()).isEmpty();
    }

    @Test
    void emittersWithNoActivityDoNothing() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(meters);
        registry = new SseEmitterRegistry(propsWith(Duration.ofMillis(50)), metrics);
        registry.startHeartbeats();

        // Don't register anything — sendHeartbeats should be a no-op and the
        // scheduler shouldn't increment the ok counter (the registry early-exits
        // when active is empty).
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        assertThat(meters.find("sse_heartbeats_sent_total").counters()).isEmpty();
    }

    // ---- helpers ----

    private static AgentProperties propsWith(Duration heartbeatInterval) {
        AgentProperties p = new AgentProperties();
        p.getSse().setHeartbeatInterval(heartbeatInterval);
        return p;
    }

    // ---- fakes ----

    /** SseEmitter that records every {@code send(...)} call without writing anywhere. */
    private static class CountingEmitter extends SseEmitter {
        final AtomicInteger heartbeats = new AtomicInteger();
        final List<Object> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
            heartbeats.incrementAndGet();
        }
    }

    /** SseEmitter whose send always throws — simulates a closed downstream socket. */
    private static class ThrowingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("downstream gone");
        }
    }
}
