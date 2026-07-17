package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link ClientRegistrationRepository} from env vars at startup. A
 * provider is included only if its credentials are present, so partial
 * configuration (e.g. GitHub-only) doesn't trip Spring Boot's auto-validation
 * of empty registration blocks.
 *
 * Env vars (all empty by default):
 *   GITHUB_OAUTH_CLIENT_ID / GITHUB_OAUTH_CLIENT_SECRET
 *   GOOGLE_CLIENT_ID       / GOOGLE_CLIENT_SECRET
 *   OKTA_CLIENT_ID         / OKTA_CLIENT_SECRET / OKTA_ISSUER_URI
 *
 * Returns {@code null} when no provider is configured; SecurityConfig treats
 * that as "OAuth disabled, Basic only".
 */
@Configuration
public class OAuthClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientConfig.class);

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        List<ClientRegistration> regs = new ArrayList<>();

        String ghId     = env.getProperty("GITHUB_OAUTH_CLIENT_ID", "");
        String ghSecret = env.getProperty("GITHUB_OAUTH_CLIENT_SECRET", "");
        if (!ghId.isBlank() && !ghSecret.isBlank()) {
            regs.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(ghId).clientSecret(ghSecret)
                    .scope("read:user", "user:email")
                    .build());
        }

        String ggId     = env.getProperty("GOOGLE_CLIENT_ID", "");
        String ggSecret = env.getProperty("GOOGLE_CLIENT_SECRET", "");
        if (!ggId.isBlank() && !ggSecret.isBlank()) {
            regs.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(ggId).clientSecret(ggSecret)
                    .build());
        }

        String okId      = env.getProperty("OKTA_CLIENT_ID", "");
        String okSecret  = env.getProperty("OKTA_CLIENT_SECRET", "");
        String okIssuer  = env.getProperty("OKTA_ISSUER_URI", "");
        if (!okId.isBlank() && !okSecret.isBlank() && !okIssuer.isBlank()) {
            regs.add(ClientRegistrations.fromIssuerLocation(okIssuer)
                    .registrationId("okta")
                    .clientId(okId).clientSecret(okSecret)
                    .clientName("Okta")
                    .scope("openid", "email", "profile")
                    .build());
        }

        if (regs.isEmpty()) {
            log.info("OAuth: no providers configured (set GITHUB_OAUTH_CLIENT_ID, GOOGLE_CLIENT_ID or OKTA_CLIENT_ID to enable).");
            return new EmptyClientRegistrationRepository();
        }
        log.info("OAuth: registered providers {}", regs.stream().map(ClientRegistration::getRegistrationId).toList());
        return new InMemoryClientRegistrationRepository(regs);
    }

    /**
     * Sentinel returned when no provider is configured. Spring Boot's
     * OAuth2 autoconfiguration requires this bean to exist whenever the
     * oauth2-client starter is on the classpath, even if unused.
     */
    private static final class EmptyClientRegistrationRepository implements ClientRegistrationRepository {
        @Override public ClientRegistration findByRegistrationId(String id) { return null; }
    }
}
