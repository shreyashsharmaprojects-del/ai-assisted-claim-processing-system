package com.claims.claim;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An internal note on a claim (slice 3). Never claimant-visible — the visibility wall.
 * {@code authorId} is nullable because a supervisor token may not have an app_user
 * (staff-cache) row; the acting user is recorded on the row when they have one.
 */
@Entity
@Table(name = "internal_note")
public class InternalNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "author_id")
    private Long authorId;

    /**
     * V17: the author's Keycloak subject — the only identity on supervisor notes
     * (supervisors have no app_user row, so author_id is NULL for them). Null on
     * pre-V17 rows.
     */
    @Column(name = "author_sub")
    private String authorSub;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InternalNote() {
        // for JPA
    }

    public InternalNote(Long claimId, Long authorId, String body, Instant createdAt) {
        this(claimId, authorId, null, body, createdAt);
    }

    /** V17: note with the author's identity stamped (supervisor-safe). */
    public InternalNote(Long claimId, Long authorId, String authorSub, String body,
            Instant createdAt) {
        this.claimId = claimId;
        this.authorId = authorId;
        this.authorSub = authorSub;
        this.body = body;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getAuthorSub() {
        return authorSub;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
