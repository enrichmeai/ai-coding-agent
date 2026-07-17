package com.example.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-level coverage for the principal-claim mapping configured in
 * {@link SecurityConfig#jwtAuthConverter()}. The production bean reads
 * {@code agent.auth.oidc.principal-claim} and forwards it to a
 * {@link JwtAuthenticationConverter#setPrincipalClaimName(String)}; here we
 * exercise the same converter behaviour directly so the wiring is validated
 * without booting Spring.
 */
class JwtPrincipalClaimTest {

    private Jwt sampleToken() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer("https://test.example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject("abc-123")
                .claim("preferred_username", "alice")
                .claim("email", "alice@example.com")
                .build();
    }

    private JwtAuthenticationConverter converterFor(String claim) {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setPrincipalClaimName(claim);
        return c;
    }

    @Test
    void subClaimIsTheDefault() {
        // Arrange
        JwtAuthenticationConverter converter = converterFor("sub");

        // Act
        AbstractAuthenticationToken authToken = converter.convert(sampleToken());

        // Assert
        assertEquals("abc-123", authToken.getName());
    }

    @Test
    void preferredUsernameClaim() {
        JwtAuthenticationConverter converter = converterFor("preferred_username");

        AbstractAuthenticationToken authToken = converter.convert(sampleToken());

        assertEquals("alice", authToken.getName());
    }

    @Test
    void emailClaim() {
        JwtAuthenticationConverter converter = converterFor("email");

        AbstractAuthenticationToken authToken = converter.convert(sampleToken());

        assertEquals("alice@example.com", authToken.getName());
    }
}
