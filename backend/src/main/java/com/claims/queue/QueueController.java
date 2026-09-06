package com.claims.queue;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.staff.AppUserRepository;

/**
 * The adjuster queue. Who sees what is role-driven: supervisors get the whole team's open
 * claims; adjusters get only their own assignments (the 404-for-a-non-assignee rule
 * applies to per-claim detail endpoints, which arrive in slice 3). Claimants are blocked
 * by the URL-level role rules in SecurityConfig.
 */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);

    private final QueueService queueService;
    private final AppUserRepository appUsers;

    public QueueController(QueueService queueService, AppUserRepository appUsers) {
        this.queueService = queueService;
        this.appUsers = appUsers;
    }

    @GetMapping
    public List<QueueClaimView> myQueue(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        if (authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))) {
            return queueService.teamQueue();
        }
        return appUsers.findByKeycloakSub(jwt.getSubject())
                .map(me -> queueService.adjusterQueue(me.getId()))
                .orElseGet(() -> {
                    // An adjuster token whose subject has no staff-cache row gets an empty
                    // queue — but loudly, not silently: it usually means the Keycloak user
                    // and the app_user seeds have drifted (see the realm-sync test).
                    log.warn("Adjuster token subject {} has no app_user row; returning an empty "
                            + "queue (staff cache out of sync with Keycloak?)", jwt.getSubject());
                    return List.of();
                });
    }
}
