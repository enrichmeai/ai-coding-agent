package com.example.agent.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an exception class whose message is safe to expose to clients.
 * Exceptions without this annotation will have their messages sanitized.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeMessage {
}
