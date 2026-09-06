package com.claims.config;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OIDC resource server. Roles come from the Keycloak realm ({@code realm_access.roles}:
 * claimant, adjuster_l1, adjuster_l2, supervisor) and map to ROLE_* authorities.
 * Slice 1 protects the FNOL endpoint for CLAIMANT; the skeleton page stays public.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/claims").hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.GET, "/api/queue")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/policies").permitAll()
                        .anyRequest().authenticated())
                // CSRF is disabled because this is a stateless bearer-token API: there are no
                // cookies to forge, so CSRF protection would only reject legitimate clients.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            Object roles = realmAccess == null ? List.of() : realmAccess.getOrDefault("roles", List.of());
            if (!(roles instanceof List<?> roleList)) {
                return List.of();
            }
            return roleList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .toList();
        });
        return converter;
    }
}
