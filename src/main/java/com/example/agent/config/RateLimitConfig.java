package com.example.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the RateLimitFilter in the servlet filter chain.
 * The filter runs AFTER Spring Security so that authenticated principals are available.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(AgentProperties props, ObjectMapper mapper) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>(
                new RateLimitFilter(props, mapper)
        );
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.LOWEST_PRECEDENCE - 100); // Run after Spring Security
        return bean;
    }
}
