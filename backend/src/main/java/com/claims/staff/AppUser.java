package com.claims.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The internal staff cache (slice 2). Identity and roles live in Keycloak; this row is a
 * cached projection the backend can query without an admin-API call — display identity for
 * emails/queues plus the adjuster's L1/L2 routing level (see docs/decisions.md).
 * Seeded by V4 with keycloak_sub values matching the users imported from
 * keycloak/realm-export.template.json (rendered to realm-export.json at dev/E2E boot).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "keycloak_sub", nullable = false, unique = true)
    private String keycloakSub;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "level", nullable = false)
    private String level;

    protected AppUser() {
        // for JPA
    }

    public AppUser(String keycloakSub, String displayName, String email, String level) {
        this.keycloakSub = keycloakSub;
        this.displayName = displayName;
        this.email = email;
        this.level = level;
    }

    public Long getId() {
        return id;
    }

    public String getKeycloakSub() {
        return keycloakSub;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getLevel() {
        return level;
    }
}
