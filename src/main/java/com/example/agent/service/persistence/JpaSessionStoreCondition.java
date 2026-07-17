package com.example.agent.service.persistence;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Activates the JPA-backed {@link JpaSessionStore} when
 * {@code agent.storage.type} is either {@code sqlite} or {@code postgres}.
 *
 * Spring Boot's {@code @ConditionalOnProperty} only matches a single
 * {@code havingValue}, so we use a custom condition for the "OR" case rather
 * than writing two beans or relying on profile activation.
 */
public class JpaSessionStoreCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String type = context.getEnvironment().getProperty("agent.storage.type", "memory");
        return "sqlite".equalsIgnoreCase(type) || "postgres".equalsIgnoreCase(type);
    }
}
