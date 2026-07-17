package com.example.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduling subsystem so {@code @Scheduled} methods
 * elsewhere in the app are picked up. Nothing currently uses {@code @Scheduled}
 * — the SSE heartbeat in {@link SseEmitterRegistry} runs its own
 * {@code ThreadPoolTaskScheduler} so it can be enabled/disabled per-interval
 * at construction time without Spring's annotation-time placeholder
 * resolution — but having this in place means a future maintainer can drop in
 * a {@code @Scheduled} method anywhere and it will just work.
 *
 * <p>Kept as a standalone {@code @Configuration} (rather than annotating
 * {@code AgentApplication}) so test slices that don't need scheduling can
 * exclude this class via {@code @ContextConfiguration(excludeFilters=...)}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
