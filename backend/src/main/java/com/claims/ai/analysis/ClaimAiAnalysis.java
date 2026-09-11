package com.claims.ai.analysis;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * V30 (V4 S2): one frozen AI advisory snapshot per (claim, claim version).
 *
 * <p>Advisory snapshots, not claim state: the claim snapshot, the served
 * clause ids and the model output are frozen so a later dispute can replay
 * exactly what the panel showed. JSONB columns are stored as pre-serialized
 * JSON text (no extra mapping dependency) — the prompt contract evolves
 * without migrations. No FK to claim: retention-deleted claims must not drag
 * snapshots.
 */
@Entity
@Table(name = "claim_ai_analysis",
        uniqueConstraints = @UniqueConstraint(columnNames = {"claim_id", "claim_version"}))
public class ClaimAiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    /** Mirrors {@code claim.version} at request time (dedupe key with claim_id). */
    @Column(name = "claim_version", nullable = false)
    private Long claimVersion;

    /** COMPLETED | DEGRADED | FAILED. */
    @Column(name = "status", nullable = false)
    private String status;

    /** Provider model id, or {@code "rules-only"} for the deterministic fallback. */
    @Column(name = "model", nullable = false)
    private String model;

    /** JSON array of the clause ids served to the model. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "clause_ids", columnDefinition = "jsonb", nullable = false)
    private String clauseIds = "[]";

    /** Frozen claim facts the advisory was computed from. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "claim_snapshot", columnDefinition = "jsonb", nullable = false)
    private String claimSnapshot = "{}";

    /** Frozen model/rules output ({@code {"suggestions":[...]}} shape). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", columnDefinition = "jsonb", nullable = false)
    private String outputJson = "{}";

    /** Provider failure for ops; never claimant-visible. Null on COMPLETED. */
    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ClaimAiAnalysis() {
        // for JPA
    }

    public ClaimAiAnalysis(Long claimId, Long claimVersion, String status,
            String model, String clauseIds, String claimSnapshot,
            String outputJson, String errorMessage, String requestedBy) {
        this.claimId = claimId;
        this.claimVersion = claimVersion;
        this.status = status;
        this.model = model;
        this.clauseIds = clauseIds;
        this.claimSnapshot = claimSnapshot;
        this.outputJson = outputJson;
        this.errorMessage = errorMessage;
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getClaimVersion() {
        return claimVersion;
    }

    public String getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public String getClauseIds() {
        return clauseIds;
    }

    public String getClaimSnapshot() {
        return claimSnapshot;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
