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
 * V2-1 adds L3 (adjuster.six) plus adjuster.four (L1) and adjuster.five (L2); the level
 * check is enforced in the DB (V4/V12), not here.
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

    /**
     * V12 (V3 S7 mapped): the deactivation flag — FALSE removes the adjuster from
     * assignment eligibility without deleting history. New rows default TRUE to
     * match the DB default. No @Version (single-writer admin action; plain update).
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

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

    /** V12 (V3 S7 mapped): whether the adjuster is eligible for new assignments. */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
