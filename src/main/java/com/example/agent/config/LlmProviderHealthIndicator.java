package com.example.agent.config;

import com.example.agent.llm.LlmProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for the LLM provider.
 *
 * Performs a local, config-only check (no external API calls) to determine
 * if the configured LLM provider is ready to serve requests.
 *
 * - For 'anthropic', 'openai', 'copilot': checks that the API key/token is non-blank
 * - For 'ollama': always reports UP (uses default base-url, no key needed)
 *
 * This indicator is included in the readiness probe but NOT in liveness.
 * A down provider should pull the pod out of rotation, but should not trigger a restart.
 */
@Component("llmProvider")
public class LlmProviderHealthIndicator implements HealthIndicator {

    private final LlmProvider provider;
    private final AgentProperties props;

    public LlmProviderHealthIndicator(LlmProvider provider, AgentProperties props) {
        this.provider = provider;
        this.props = props;
    }

    @Override
    public Health health() {
        String providerName = provider.name();
        boolean isUsable = isProviderConfigUsable(providerName);

        if (isUsable) {
            return Health.up()
                    .withDetail("provider", providerName)
                    .build();
        } else {
            return Health.down()
                    .withDetail("provider", providerName)
                    .withDetail("reason", getDownReason(providerName))
                    .build();
        }
    }

    /**
     * Checks if the provider configuration is usable (local check only).
     */
    private boolean isProviderConfigUsable(String providerName) {
        return switch (providerName) {
            case "anthropic" -> isAnthropicConfigUsable();
            case "openai" -> isOpenAiConfigUsable();
            case "copilot" -> isCopilotConfigUsable();
            case "ollama" -> true; // Ollama always has a base-url default
            default -> false;
        };
    }

    /**
     * Anthropic is usable if its API key is non-blank.
     */
    private boolean isAnthropicConfigUsable() {
        String apiKey = props.getLlm().getAnthropic().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * OpenAI is usable if its API key is non-blank.
     */
    private boolean isOpenAiConfigUsable() {
        String apiKey = props.getLlm().getOpenai().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * GitHub Copilot is usable if a token is configured.
     */
    private boolean isCopilotConfigUsable() {
        String apiKey = props.getLlm().getCopilot().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Returns a human-readable reason for why the provider is down.
     */
    private String getDownReason(String providerName) {
        return switch (providerName) {
            case "anthropic" -> "Anthropic API key not configured";
            case "openai" -> "OpenAI API key not configured";
            case "copilot" -> "GitHub Copilot token not configured (set GITHUB_COPILOT_TOKEN)";
            default -> "Provider is not ready";
        };
    }
}
