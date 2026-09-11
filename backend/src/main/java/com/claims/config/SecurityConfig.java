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
 *
 * <p>The URL rules encode the whole surface's authorization matrix: the claimant surface
 * (FNOL + own-claim status + own claim history), the internal work surface (queue, full
 * view, reserve, notes, attachments), the decision endpoints (assigned adjuster vs
 * supervisor escalation), and the supervisor admin surface (escalation queue, authority
 * config, reassign, audit, dashboard stats).
 * Only {@code GET /api/health} and {@code GET /api/ready} are public; everything else is
 * authenticated, and object-level authorization (404 for non-assignee / cross-tenant)
 * lives in the services, not here.
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
                        // V28 (V4 S1): the policy-clause reference surface — internal
                        // staff only (claimant is 403 at the URL); the claim GET
                        // enforces assignee/supervisor inside the service
                        // (404-not-403). Declared before the generic
                        // /api/claims/* claimant rule so it is not shadowed.
                        .requestMatchers(HttpMethod.GET, "/api/clauses")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/policy-clauses")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        // V30 (V4 S2): AI advisory snapshots — internal staff
                        // only (claimant is 403 at the URL); assignee/
                        // supervisor enforced in the service (404-not-403).
                        // Before the generic claimant /api/claims/* rule.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/ai-analysis")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/ai-analysis")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        // AI chat (V4 S3): conversational advisory, every
                        // stage, stateless (POST only — no stored transcript
                        // to GET). Same staff gate as the advisory snapshots.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/ai-chat")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/claims/mine").hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.GET, "/api/claims/*").hasRole("CLAIMANT")
                        // V16: the claimant's NEED_INFO document upload (own claim,
                        // NEED_INFO only inside the service; 404 otherwise).
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/documents")
                                .hasRole("CLAIMANT")
                        // S3: the required-documents checklist — the GET admits both
                        // internal staff and the claimant (own-claim enforced inside
                        // the service: non-holder/non-assignee is 404); link/waive
                        // are the assignee's (supervisor via the same visibility;
                        // 404 otherwise).
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/required-documents")
                                .hasAnyRole("CLAIMANT", "ADJUSTER_L1", "ADJUSTER_L2",
                                        "ADJUSTER_L3", "SUPERVISOR")
                        .requestMatchers(HttpMethod.POST,
                                "/api/claims/*/required-documents/*/link",
                                "/api/claims/*/required-documents/*/waive")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/full",
                                "/api/claims/*/attachments/*", "/api/claims/*/timeline")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        // V16: the adjuster's per-stage document upload.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/attachments")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.PUT, "/api/claims/*/reserve")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/notes")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        // Slice 4: only the assigned adjuster decides. SUPERVISOR is excluded
                        // here on purpose — the supervisor half of the decision flow (the
                        // escalation-decision endpoint) is slice 5.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/decision")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3")
                        // Slice 5: the supervisor's escalation surface — deciding an
                        // ESCALATED_SUPERVISOR claim, and the queue of claims that need it.
                        // No adjuster may act on either (403, never revealing a claim).
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/escalation-decision")
                                .hasRole("SUPERVISOR")
                        // V2-4/V2-5/V2-6: the staged workflow — adjuster review,
                        // verifications, assessment, cover decision and explicit
                        // referral (assignee-only inside the service; 404 otherwise).
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/staged")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/review",
                                "/api/claims/*/verifications", "/api/claims/*/cover-decision",
                                "/api/claims/*/refer", "/api/claims/*/send-back")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3")
                        .requestMatchers(HttpMethod.PUT, "/api/claims/*/verifications/*",
                                "/api/claims/*/assessment")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3")
                        // V2-4: the claimant NEED_INFO response (own claim only; 404
                        // otherwise). Adjusters and supervisors are 403 here — the
                        // claimant round-trip is theirs alone.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/need-info-response")
                                .hasRole("CLAIMANT")
                        // V2-6: the supervisor's cover-decision on escalated claims
                        // (ungated, rationale required).
                        .requestMatchers(HttpMethod.POST,
                                "/api/claims/*/escalation-cover-decision")
                                .hasRole("SUPERVISOR")
                        // V22 (V3 S6): supervisor-only reopen of a closed claim.
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/reopen")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/escalations")
                                .hasRole("SUPERVISOR")
                        // Production dashboard: aggregate ops numbers, supervisor-only.
                        .requestMatchers(HttpMethod.GET, "/api/dashboard")
                                .hasRole("SUPERVISOR")
                        // Slice 7: the supervisor's compliance & admin surfaces — the
                        // authority config editor, claim reassignment, and the claim audit
                        // log. Only a supervisor; an adjuster or claimant is a 403 before
                        // any claim logic runs.
                        .requestMatchers(HttpMethod.GET, "/api/config/authority")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.PUT, "/api/config/authority/*")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/claims/*/reassign")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/claims/*/audit")
                                .hasRole("SUPERVISOR")
                        // V23 (V3 S8): regulator-ready CSV exports — the claim
                        // audit story + the closure list, supervisor only.
                        .requestMatchers(HttpMethod.GET, "/api/audit/export",
                                "/api/decisions/export")
                                .hasRole("SUPERVISOR")
                        // V24 (V3 S9): the privacy surface — the claimant's own
                        // export (own-sub enforced inside the service) and the
                        // supervisor's anonymize + retention report.
                        .requestMatchers(HttpMethod.GET, "/api/privacy/me/**")
                                .hasRole("CLAIMANT")                        .requestMatchers(HttpMethod.GET, "/api/admin/privacy/**")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/admin/privacy/**")
                                .hasRole("SUPERVISOR")
                        // V25 (V3 S10): the claimant's own notification center
                        // (own-sub enforced inside the service: another
                        // claimant's row id is a 404, never a 403).
                        .requestMatchers(HttpMethod.GET, "/api/notifications/**")
                                .hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.POST, "/api/notifications/**")
                                .hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.PUT, "/api/notifications/**")
                                .hasRole("CLAIMANT")
                        // V3 S7: the staff surface — list + active toggle,
                        // supervisor only (adjusters/claimants are 403 at the URL).
                        .requestMatchers(HttpMethod.GET, "/api/staff")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.PUT, "/api/staff/*/active")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/queue")
                                .hasAnyRole("ADJUSTER_L1", "ADJUSTER_L2", "ADJUSTER_L3",
                                        "SUPERVISOR")
                        // Legacy V1 list carries every customer's number + holder
                        // name: supervisor-only (the cockpit serves claimants
                        // their own rows). Order matters: /mine + /admin first —
                        // Spring matches in declaration order, and /admin would
                        // otherwise fall into the /* detail rule.
                        .requestMatchers(HttpMethod.GET, "/api/policies/mine")
                                .hasRole("CLAIMANT")
                        .requestMatchers(HttpMethod.GET, "/api/policies/admin")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/policies").hasRole("SUPERVISOR")
                        // V2-1 cockpit: own policy detail for claimants; internal roles
                        // keep coverage context via the same route.
                        .requestMatchers(HttpMethod.GET, "/api/policies/*")
                                .hasAnyRole("CLAIMANT", "ADJUSTER_L1", "ADJUSTER_L2",
                                        "ADJUSTER_L3", "SUPERVISOR")
                        // R1 policy admin + R2 outbox: supervisor-only surfaces. Adjusters and
                        // claimants are blocked before any policy/outbox logic runs (403).
                        .requestMatchers(HttpMethod.POST, "/api/policies",
                                "/api/policies/import", "/api/policies/*/retire")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/outbox")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/outbox/*/retry")
                                .hasRole("SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/ready").permitAll()
                        // R3: the metrics scrape is supervisor-scoped (never public;
                        // readiness/liveness stay public above). Claimants and adjusters are
                        // 403 — only supervisors may see aggregate operations numbers.
                        .requestMatchers(HttpMethod.GET, "/api/metrics")
                                .hasRole("SUPERVISOR")
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
