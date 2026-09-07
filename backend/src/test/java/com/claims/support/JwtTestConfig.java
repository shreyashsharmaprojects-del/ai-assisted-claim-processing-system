package com.claims.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Test JWT support: an in-context HS256 {@link JwtDecoder} so integration tests can send
 * real signed bearer tokens over real HTTP without a running Keycloak. Claims mirror what
 * Keycloak issues ({@code realm_access.roles}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class JwtTestConfig {

    public static final SecretKeySpec SECRET = new SecretKeySpec(
            "test-secret-for-local-jwt-signing-0123456789".getBytes(), "HmacSHA256");

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(SECRET).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public static String tokenFor(String subject, String... roles) {
        return tokenWithClaims(subject, null, roles);
    }

    /**
     * V2-1 cockpit tests need an {@code email} claim (ownership is by holder email).
     * Pass entries as {@code "email:addr@example.test"} alongside roles; anything
     * without the {@code email:} prefix stays a role.
     */
    public static String tokenWithClaims(String subject, String email, String... roles) {
        List<String> roleList = new java.util.ArrayList<>();
        String emailClaim = email;
        for (String role : roles) {
            if (role.startsWith("email:")) {
                emailClaim = role.substring("email:".length());
            } else {
                roleList.add(role);
            }
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(SECRET));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("test")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.copyOf(roleList)));
        if (emailClaim != null && !emailClaim.isBlank()) {
            builder.claim("email", emailClaim);
        }
        return encoder.encode(JwtEncoderParameters.from(header, builder.build()))
                .getTokenValue();
    }
}
