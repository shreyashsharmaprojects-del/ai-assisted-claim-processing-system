package com.claims.claim;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.api.ClaimNotFoundException;
import com.claims.staff.AppUserRepository;

import tools.jackson.databind.JsonNode;

/**
 * V17: the claim timeline — one sequential feed of everything said and attached
 * on a claim, in the order it happened. Notes, documents (claim-level and
 * verification-linked), verification opens/completions, and workflow milestones
 * (filed, assigned, advanced, need-info round-trips, referred, decided) ride one
 * list with the actor and timestamp on each row, Jira-activity style.
 *
 * <p>The rows are derived from the authoritative tables (they persist across
 * every decision because the tables do — referral, reassignment and closure
 * never rewrite them). Internal-only: this service is reached through
 * {@link ClaimAccess}, so only the holding adjuster or a supervisor ever reads
 * it. The claimant's own NEED_INFO uploads appear here as claimant rows
 * (their text responses stay inside the audit rationale, never on this feed).
 */
@Service
public class TimelineService {

    private final ClaimRepository claims;
    private final InternalNoteRepository notes;
    private final AttachmentRepository attachments;
    private final VerificationRepository verifications;
    private final AppUserRepository appUsers;
    private final ClaimAccess access;
    private final JdbcTemplate jdbcTemplate;

    public TimelineService(ClaimRepository claims, InternalNoteRepository notes,
            AttachmentRepository attachments, VerificationRepository verifications,
            AppUserRepository appUsers, ClaimAccess access, JdbcTemplate jdbcTemplate) {
        this.claims = claims;
        this.notes = notes;
        this.attachments = attachments;
        this.verifications = verifications;
        this.appUsers = appUsers;
        this.access = access;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One feed row: who did what and when, plus any linked document or text. */
    public record TimelineEntry(String kind, String actor, String detail, String attachmentName,
            Long attachmentId, Long verificationId, OffsetDateTime at) {
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(String claimNumber, String actorSub,
            boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, actorSub, supervisor)) {
            throw new ClaimNotFoundException();
        }
        List<TimelineEntry> feed = new ArrayList<>();

        // Workflow milestones: filed + assigned + the audit-logged transitions.
        // Filing: who claimed, and when.
        feed.add(new TimelineEntry("FILED", holderNameOf(claim),
                "Claim filed" + (claim.getClaimedTotal() == null ? ""
                        : " — claimed ₹" + claim.getClaimedTotal().toPlainString()),
                null, null, null, atOf(claim.getId(), "claim", "created_at")));
        for (AuditRow row : auditRows(claim.getId())) {
            String text = milestoneText(row);
            if (text != null) {
                feed.add(new TimelineEntry(row.action(), displayNameOfSub(row.actorSub()),
                        text, null, null, null, row.createdAt()));
            }
        }

        // Internal notes: who said what.
        for (InternalNote note : notes
                .findByClaimIdOrderByCreatedAtAscIdAsc(claim.getId())) {
            feed.add(new TimelineEntry("NOTE", authorOf(note), note.getBody(), null,
                    null, null, atOf(note.getCreatedAt())));
        }

        // Documents: who attached what (claim-level + per-check).
        for (Attachment attachment : attachments
                .findByClaimIdOrderById(claim.getId())) {
            String name = attachment.getLabel() == null
                    || attachment.getLabel().isBlank()
                            ? attachment.getOriginalName()
                            : attachment.getLabel();
            String scope = attachment.getVerificationId() == null ? "Attached"
                    : "Verification evidence";
            feed.add(new TimelineEntry("DOCUMENT",
                    displayNameOfSub(attachment.getUploadedBySub()),
                    scope + ": " + name, name, attachment.getId(),
                    attachment.getVerificationId(),
                    atOf(attachment.getId(), "attachment", "created_at")));
        }

        // Verifications: opened + completed, with outcome and linked documents.
        for (Verification row : verifications
                .findByClaimIdOrderByIdAsc(claim.getId())) {
            String type = row.getType() == null ? "Verification"
                    : titleOf(row.getType()) + " verification";
            feed.add(new TimelineEntry("VERIFICATION_OPENED",
                    displayNameOfSub(row.getPerformedBy()), "Opened " + type
                            + (row.getNotes() == null ? "" : " — " + row.getNotes()),
                    null, null, row.getId(), atOf(row.getStartedAt())));
            if ("COMPLETE".equals(row.getStatus()) && row.getCompletedAt() != null) {
                feed.add(new TimelineEntry("VERIFICATION_COMPLETED",
                        displayNameOfSub(row.getPerformedBy()),
                        "Completed " + type + " — "
                                + (row.getOutcome() == null ? "no outcome"
                                        : row.getOutcome())
                                + (row.getNotes() == null ? "" : " — " + row.getNotes()),
                        null, null, row.getId(), atOf(row.getCompletedAt())));
            }
        }

        // One sequential feed, oldest first. Same-instant rows keep insertion
        // order (stable sort), so a note and its document never swap.
        feed.sort(Comparator.comparing(
                (TimelineEntry e) -> e.at() == null ? OffsetDateTime.MIN : e.at()));
        return feed;
    }

    /**
     * Human text for the audit actions that belong on the timeline. Returns null
     * for bookkeeping rows (assignment mechanics, config) that would read as
     * noise next to the notes and documents.
     */
    private static String milestoneText(AuditRow row) {
        JsonNode after = row.after();
        return switch (row.action()) {
            case "CLAIM_ASSIGNED" ->
                "Assigned to " + textOf(after, "assignedTo");
            case "CLAIM_REASSIGNED" ->
                "Reassigned to " + textOf(after, "assignedTo")
                        + " (" + textOf(after, "level") + ")";
            case "REVIEW_ADVANCED" -> "Review passed — moved to verification"
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "STAGE_SENT_BACK" -> "Sent back to "
                    + textOf(after, "stage")
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "NEED_INFO_SENT" -> "Sent back to claimant — "
                    + (row.rationale() == null ? "more information requested"
                            : row.rationale());
            case "NEED_INFO_RESPONDED" -> "Claimant responded — back to "
                    + textOf(after, "stage")
                    + (row.rationale() == null || row.rationale().isBlank() ? ""
                            : " — " + row.rationale());
            case "ASSESSMENT_RECORDED" -> "Assessment recorded"
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "PROPOSALS_SAVED" -> "Proposals saved — above authority, awaiting a senior"
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "CLAIM_REFERRED" -> "Referred to "
                    + (textOf(after, "escalatedTo") == null ? "a senior"
                            : textOf(after, "escalatedTo"))
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "CLAIM_ESCALATED" -> "Escalated — awaiting a supervisor decision"
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            case "RESERVE_SET" -> "Reserve set to ₹" + textOf(after, "reserveAmount");
            case "DECISION" -> "Decision: " + textOf(after, "decision")
                    + (row.rationale() == null ? "" : " — " + row.rationale());
            default -> null;
        };
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "—";
        }
        return node.get(field).asString();
    }

    private static String titleOf(String type) {
        return switch (type) {
            case "PHYSICAL" -> "Physical";
            case "DOCUMENT" -> "Document";
            case "CLAUSE" -> "Clause";
            case "DIGITAL" -> "Digital";
            default -> "Verification";
        };
    }

    private String holderNameOf(Claim claim) {
        // The claimant filed it; on the internal feed their subject resolves via
        // the policy holder name when the staff cache has no such subject.
        String staff = displayNameOfSub(claim.getClaimantSub());
        return staff == null ? "Claimant" : staff;
    }

    private String authorOf(InternalNote note) {
        if (note.getAuthorId() != null) {
            String name = appUsers.findById(note.getAuthorId())
                    .map(u -> u.getDisplayName()).orElse(null);
            if (name != null) {
                return name;
            }
        }
        String staff = displayNameOfSub(note.getAuthorSub());
        return staff == null ? "Internal" : staff;
    }

    /**
     * Resolves a Keycloak subject to a display name: the staff cache first
     * (adjusters + supervisors' display rows where present), else the policy
     * holder when the subject filed the claim, else the raw "Claimant" /
     * supervisor fallback. Null only when nothing resolves (pre-V17 rows with
     * no stored subject).
     */
    private String displayNameOfSub(String sub) {
        if (sub == null) {
            return null;
        }
        String staff = appUsers.findByKeycloakSub(sub)
                .map(u -> u.getDisplayName()).orElse(null);
        if (staff != null) {
            return staff;
        }
        String holder = jdbcTemplate.query(
                "SELECT p.holder_name FROM claim c JOIN policy p ON p.id = c.policy_id "
                        + "WHERE c.claimant_sub = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, sub);
        if (holder != null) {
            return holder + " (claimant)";
        }
        return null;
    }

    private record AuditRow(String action, String actorSub, String rationale,
            OffsetDateTime createdAt, JsonNode after) {
    }

    private List<AuditRow> auditRows(Long claimId) {
        return jdbcTemplate.query(
                "SELECT action, actor_sub, rationale, created_at, after::text AS after_text "
                        + "FROM audit_log WHERE entity_type = 'CLAIM' AND entity_id = ? "
                        + "ORDER BY id",
                (rs, n) -> new AuditRow(rs.getString("action"),
                        rs.getString("actor_sub"), rs.getString("rationale"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        jsonOf(rs.getString("after_text"))),
                claimId);
    }

    private static JsonNode jsonOf(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new tools.jackson.databind.ObjectMapper().readTree(text);
        } catch (tools.jackson.core.JacksonException ex) {
            throw new IllegalStateException("audit_log holds invalid JSONB payload", ex);
        }
    }

    private OffsetDateTime atOf(Long claimId, String table, String column) {
        OffsetDateTime at = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?",
                OffsetDateTime.class, claimId);
        return at;
    }

    private static OffsetDateTime atOf(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
