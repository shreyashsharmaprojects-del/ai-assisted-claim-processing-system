package com.claims.claim;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.DuplicateFnolException;
import com.claims.api.FnolValidationException;
import com.claims.api.PolicyMismatchException;
import com.claims.api.RateLimitedException;
import com.claims.api.UnroutableException;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.outbox.EmailOutboxWriter;
import com.claims.notify.NotificationWriter;
import com.claims.metrics.ClaimsMetrics;
import com.claims.policy.Policy;
import com.claims.policy.PolicyCover;
import com.claims.policy.PolicyCoverRepository;
import com.claims.policy.PolicyRepository;
import com.claims.routing.AuthorityConfigRepository;
import com.claims.routing.ClaimClassifier;
import com.claims.staff.AppUser;

/**
 * FNOL: verify the policy, classify the claim from the product code, persist claim +
 * photos + audit entry, assign the claim to the least-loaded adjuster of its level (slice
 * 2), all in one transaction. Returns the claimant view with the claim number immediately.
 */
@Service
public class ClaimService {

    /**
     * Rolling FNOL window: at most this many filings per claimant per 24h. Generous on
     * purpose — real claimants file once in a blue moon; a burst is abuse or a stuck
     * retry loop. Configurable via {@code claims.fnol.rate-limit-per-day}.
     */
    static final int DEFAULT_FNOL_PER_DAY = 20;
    private static final java.time.Duration FNOL_WINDOW = java.time.Duration.ofHours(24);

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_TEXT = 200;
    private static final int MAX_DESCRIPTION = 5000;
    private static final int MAX_REMARKS = 2000;

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final PolicyCoverRepository policyCovers;
    private final ClaimCoverRepository claimCovers;
    private final AuthorityConfigRepository authorityConfigs;
    private final AttachmentRepository attachments;
    private final PhotoStorage photoStorage;
    private final AuditLogWriter auditLog;
    private final ClaimAssigner assigner;
    private final JdbcTemplate jdbcTemplate;
    private final FnolSubmissionRepository submissions;
    private final ClaimsMetrics metrics;
    private final EmailOutboxWriter outboxWriter;
    private final NotificationWriter notifications;
    private final RequiredDocumentService requiredDocuments;
    private final int fnolPerDay;

    public ClaimService(ClaimRepository claims, PolicyRepository policies,
            PolicyCoverRepository policyCovers, ClaimCoverRepository claimCovers,
            AuthorityConfigRepository authorityConfigs, AttachmentRepository attachments,
            PhotoStorage photoStorage, AuditLogWriter auditLog, ClaimAssigner assigner,
            JdbcTemplate jdbcTemplate, FnolSubmissionRepository submissions,
            ClaimsMetrics metrics, EmailOutboxWriter outboxWriter,
            NotificationWriter notifications, RequiredDocumentService requiredDocuments,
            @Value("${claims.fnol.rate-limit-per-day:20}") int fnolPerDay) {
        this.claims = claims;
        this.policies = policies;
        this.policyCovers = policyCovers;
        this.claimCovers = claimCovers;
        this.authorityConfigs = authorityConfigs;
        this.attachments = attachments;
        this.photoStorage = photoStorage;
        this.auditLog = auditLog;
        this.assigner = assigner;
        this.jdbcTemplate = jdbcTemplate;
        this.submissions = submissions;
        this.metrics = metrics;
        this.outboxWriter = outboxWriter;
        this.notifications = notifications;
        this.requiredDocuments = requiredDocuments;
        this.fnolPerDay = fnolPerDay;
    }

    @Transactional
    public FnolResult fileFnol(FnolInput input) {
        return fileFnol(input, null);
    }

    @Transactional
    public FnolResult fileFnol(FnolInput input, String clientIp) {
        LocalDate lossDate = validate(input);
        enforceRateLimit(input.claimantSub());
        String policyNumber = normalizePolicyNumber(input.policyNumber());

        Policy policy = policies.findByPolicyNumber(policyNumber).orElse(null);
        if (policy == null
                || !policy.getHolderName().equalsIgnoreCase(input.holderName().trim())
                || !policy.getHolderEmail().equalsIgnoreCase(input.holderEmail().trim())
                || !"ACTIVE".equals(policy.getStatus())) {
            // Non-ACTIVE (RETIRED/EXPIRED/future) policies reuse the same shape: no new
            // claimant-visible branch, and no signal whether the number exists, the
            // holder mismatched, or it was non-fileable (V2-2 E9). Closed-world: only
            // ACTIVE files.
            throw new PolicyMismatchException(
                    "We could not match that policy number with the holder details provided.");
        }

        String level = ClaimClassifier.routeLevelFor(policy.getProductCode(), authorityConfigs.findAll());
        if (level == null) {
            throw new UnroutableException(
                    "No routing configuration exists for product " + policy.getProductCode());
        }

        // V2-2: explicit cover selections are validated against the policy's opted
        // covers (unknown codes name the valid ones — R1 discipline). Above-limit
        // amounts are accepted, never blocked (locked rule 1): the flag is derived
        // at read time and enforced at assessment/approval (V2-5).
        List<PolicyCover> opted = policyCovers
                .findByPolicyIdOrderBySortOrderAscIdAsc(policy.getId());
        List<CoverSelection> selections = normalizeCovers(input.covers(), opted);

        // Duplicate guard BEFORE the claim-number burn, photo store, audit and
        // assignment: same policy + loss date + cover set within 24h returns the
        // existing number (409). Amounts are ignored (a refiled set is a correction
        // pointer, not a new claim). Best-effort under concurrency — no locking.
        if (selections != null) {
            String duplicate = findDuplicateClaimNumber(policy.getId(), lossDate,
                    coverSetOf(selections));
            if (duplicate != null) {
                throw new DuplicateFnolException(duplicate);
            }
        }

        Long sequence = jdbcTemplate.queryForObject("SELECT nextval('claim_number_seq')", Long.class);
        Claim claim = claims.save(new Claim(
                ClaimNumberFormatter.format(sequence),
                policy.getId(),
                input.claimantSub(),
                level,
                "UNASSIGNED",
                lossDate,
                input.lossLocation().trim(),
                input.lossDescription().trim(),
                blankToNull(input.remarks())));
        Long claimId = claim.getId();

        // S3: the policy product's required-doc checklist — PENDING rows in the
        // same transaction (products without seeds get an empty checklist).
        requiredDocuments.seedForClaim(claimId, policy.getProductCode());

        // If anything after this point rolls back, remove the photos we wrote to disk.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    photoStorage.deleteClaimDir(claimId);
                }
            }
        });

        List<StoredPhoto> storedPhotos = photoStorage.store(input.photos(), claimId);
        for (StoredPhoto photo : storedPhotos) {
            Attachment savedAttachment = attachments.save(new Attachment(claimId, photo.storagePath(),
                    photo.contentType(), photo.originalName(), null, null, null,
                    photo.sha256(), photo.sizeBytes()));
            // S3: FNOL uploads may carry a docKey (multipart part or aligned input
            // order); auto-link matching PENDING checks.
            requiredDocuments.tryAutoLink(claim, input.docKeyFor(storedPhotos.indexOf(photo)),
                    savedAttachment.getId(), input.claimantSub());
        }

        submissions.save(new FnolSubmission(input.claimantSub(), claimId, clientIp, Instant.now()));

        // V2-2: persist one claim_cover row per selected cover (codes stored UPPER,
        // trimmed) and the server-computed claimed total. Legacy no-covers filings
        // persist nothing and leave claimed_total NULL (V1 bridge).
        BigDecimal claimedTotal = null;
        List<ClaimantCoverView> coverViews = null;
        if (selections != null) {
            claimedTotal = BigDecimal.ZERO;
            coverViews = new ArrayList<>();
            Map<String, PolicyCover> byCode = new HashMap<>();
            for (PolicyCover cover : opted) {
                byCode.put(cover.getCoverCode(), cover);
            }
            for (CoverSelection selection : selections) {
                PolicyCover cover = byCode.get(selection.coverCode());
                claimCovers.save(new ClaimCover(claimId, selection.coverCode(),
                        selection.claimedAmount()));
                claimedTotal = claimedTotal.add(selection.claimedAmount());
                coverViews.add(new ClaimantCoverView(selection.coverCode(),
                        cover.getDisplayName(), selection.claimedAmount(),
                        cover.getSubLimit(),
                        selection.claimedAmount().compareTo(cover.getSubLimit()) > 0));
            }
            claim.setClaimedTotal(claimedTotal);
            claims.save(claim);
        }

        Map<String, Object> createdAfter = new HashMap<>();
        createdAfter.put("claimNumber", claim.getClaimNumber());
        createdAfter.put("level", claim.getLevel());
        createdAfter.put("status", claim.getStatus());
        if (selections != null) {
            createdAfter.put("covers", coverSetOf(selections).toString());
            createdAfter.put("claimedTotal", claimedTotal);
        }
        auditLog.append(input.claimantSub(), "CLAIM_CREATED", "CLAIM", claimId, null,
                AuditJson.of(createdAfter),
                null);

        // Assign inside the creating transaction: the claim is never observable as
        // UNASSIGNED. CLAIM_ASSIGNED is recorded as a system action (no actor).
        AppUser adjuster = assigner.assign(claim);
        if (adjuster != null) {
            auditLog.append(null, "CLAIM_ASSIGNED", "CLAIM", claimId, null,
                    AuditJson.of(Map.of(
                            "claimNumber", claim.getClaimNumber(),
                            "assignedAdjusterId", adjuster.getId(),
                            "assignedTo", adjuster.getDisplayName(),
                            "status", claim.getStatus())),
                    null);
        }

        metrics.fnolAccepted();
        // R2: FNOL + assignment mails are outbox rows in this same transaction — the write
        // commits or rolls back with the claim, so mail is never lost and never sent for a
        // filing that did not happen. Delivery is the dispatcher's job, after commit.
        // V25 (V3 S10): each mail is mirrored with an INAPP row in this same
        // transaction (honours the claimant's inapp_events preference).
        outboxWriter.enqueueFnol(claimId, input.holderEmail().trim(), claim.getClaimNumber(),
                policy.getHolderName());
        notifications.write(claimId, input.claimantSub(), "FNOL_RECEIVED",
                "Claim " + claim.getClaimNumber() + " received",
                "We have received your claim " + claim.getClaimNumber()
                        + ". It is being routed to an adjuster.");
        if (adjuster != null) {
            outboxWriter.enqueueAssignment(claimId, input.holderEmail().trim(),
                    claim.getClaimNumber(), policy.getHolderName(), adjuster.getDisplayName(),
                    adjuster.getEmail());
            notifications.write(claimId, input.claimantSub(), "ASSIGNED",
                    "Claim " + claim.getClaimNumber() + " is now with an adjuster",
                    "Your claim " + claim.getClaimNumber() + " has been assigned to "
                            + adjuster.getDisplayName() + ".");
        }
        return new FnolResult(ClaimantClaimView.from(claim, coverViews, claimedTotal,
                null, docsOf(claimId).received(), docsOf(claimId).total(),
                docsOf(claimId).items()),
                adjuster == null ? null : adjuster.getDisplayName(),
                adjuster == null ? null : adjuster.getEmail());
    }

    /** S3: the freshly seeded checklist for the FNOL response tracker. */
    private RequiredDocumentService.ClaimantDocs docsOf(Long claimId) {
        return requiredDocuments.claimantDocs(claimId);
    }

    /**
     * V2-2 cover validation. Null input = legacy path (no selections, no rows).
     * A present list must carry 1–N opted covers with amounts &gt; 0; codes are
     * normalized (trim + UPPER) and matched against the policy's opted set.
     * Above-limit amounts pass untouched (locked rule 1).
     */
    private List<CoverSelection> normalizeCovers(List<CoverSelection> covers,
            List<PolicyCover> opted) {
        if (covers == null) {
            return null;
        }
        Map<String, PolicyCover> byCode = new HashMap<>();
        List<String> validCodes = new ArrayList<>();
        for (PolicyCover cover : opted) {
            byCode.put(cover.getCoverCode(), cover);
            validCodes.add(cover.getCoverCode());
        }
        if (covers.isEmpty()) {
            throw new FnolValidationException(
                    "Select at least one cover to claim under.");
        }
        if (covers.size() > opted.size()) {
            throw new FnolValidationException(
                    "Too many covers selected: this policy carries " + opted.size() + ".");
        }
        Set<String> seen = new HashSet<>();
        List<CoverSelection> normalized = new ArrayList<>();
        for (CoverSelection selection : covers) {
            String code = selection == null || selection.coverCode() == null ? ""
                    : selection.coverCode().trim().toUpperCase(Locale.ROOT);
            BigDecimal amount = selection == null ? null : selection.claimedAmount();
            if (code.isEmpty()) {
                throw new FnolValidationException("Each selected cover needs a cover code.");
            }
            if (amount == null) {
                throw new FnolValidationException(
                        "Enter a claimed amount for cover " + code + ".");
            }
            if (amount.scale() > 2 || amount.compareTo(BigDecimal.ZERO) <= 0
                    || amount.compareTo(new BigDecimal("999999999999.99")) > 0) {
                throw new FnolValidationException(
                        "The claimed amount for cover " + code
                                + " must be greater than 0 with at most two decimals.");
            }
            if (!seen.add(code)) {
                throw new FnolValidationException(
                        code + " was selected twice — select each cover once.");
            }
            if (!byCode.containsKey(code)) {
                throw new FnolValidationException("Cover " + code
                        + " is not on this policy. Valid covers: "
                        + String.join(", ", validCodes) + ".");
            }
            normalized.add(new CoverSelection(code, amount));
        }
        return normalized;
    }

    /** Normalized cover set, order-insensitive (duplicate-key comparison). */
    private static Set<String> coverSetOf(List<CoverSelection> selections) {
        Set<String> set = new TreeSet<>();
        for (CoverSelection selection : selections) {
            set.add(selection.coverCode());
        }
        return set;
    }

    /**
     * Duplicate-FNOL lookup: the most recent claim on this policy with the same loss
     * date and same cover set (order-insensitive, amounts ignored) filed in the last
     * 24h, any status. Null when none. The sliding window + set equality cannot be a
     * DB constraint, so this is a service query — best-effort under concurrency.
     */
    private String findDuplicateClaimNumber(Long policyId, LocalDate lossDate,
            Set<String> coverSet) {
        List<Long> candidates = jdbcTemplate.query(
                "SELECT id FROM claim WHERE policy_id = ? AND loss_date = ? "
                        + "AND created_at >= now() - interval '24 hours' "
                        + "ORDER BY created_at DESC, id DESC LIMIT 20",
                (rs, rowNum) -> rs.getLong("id"), policyId, lossDate);
        for (Long candidateId : candidates) {
            Set<String> filed = new TreeSet<>(jdbcTemplate.query(
                    "SELECT cover_code FROM claim_cover WHERE claim_id = ?",
                    (rs, rowNum) -> rs.getString("cover_code"), candidateId));
            if (!filed.isEmpty() && filed.equals(coverSet)) {
                String number = jdbcTemplate.queryForObject(
                        "SELECT claim_number FROM claim WHERE id = ?", String.class,
                        candidateId);
                if (number != null) {
                    return number;
                }
            }
        }
        return null;
    }

    private LocalDate validate(FnolInput input) {
        List<String> errors = new ArrayList<>();
        if (isBlank(input.policyNumber())) {
            errors.add("Policy number is required.");
        } else if (input.policyNumber().trim().length() > MAX_TEXT) {
            errors.add("Policy number is too long.");
        }
        if (isBlank(input.holderName())) {
            errors.add("Policyholder name is required.");
        } else if (input.holderName().trim().length() > MAX_TEXT) {
            errors.add("Policyholder name is too long.");
        }
        if (isBlank(input.holderEmail())) {
            errors.add("Policyholder email is required.");
        } else if (input.holderEmail().trim().length() > MAX_TEXT
                || !EMAIL_PATTERN.matcher(input.holderEmail().trim()).matches()) {
            errors.add("Policyholder email must be a valid email address.");
        }
        if (isBlank(input.lossLocation())) {
            errors.add("Loss location is required.");
        } else if (input.lossLocation().trim().length() > MAX_TEXT) {
            errors.add("Loss location is too long.");
        }
        if (isBlank(input.lossDescription())) {
            errors.add("A description of what happened is required.");
        } else if (input.lossDescription().trim().length() > MAX_DESCRIPTION) {
            errors.add("The description is too long (maximum 5000 characters).");
        }
        if (input.remarks() != null && input.remarks().trim().length() > MAX_REMARKS) {
            errors.add("Remarks are too long (maximum 2000 characters).");
        }
        LocalDate date = null;
        if (isBlank(input.lossDate())) {
            errors.add("Loss date is required.");
        } else {
            try {
                date = LocalDate.parse(input.lossDate().trim());
            } catch (DateTimeParseException ex) {
                errors.add("Loss date must be in yyyy-MM-dd format.");
            }
            if (date != null) {
                if (date.isAfter(LocalDate.now())) {
                    errors.add("Loss date cannot be in the future.");
                } else if (date.isBefore(LocalDate.now().minusYears(10))) {
                    errors.add("Loss date looks too far in the past — check the year.");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new FnolValidationException(String.join(" ", errors));
        }
        return date;
    }

    /**
     * Rolling-window flood guard: counts this claimant's filings in the last 24h and
     * rejects the burst with a 429 (the ledger write happens later in this same
     * transaction, so the counted rows are committed filings only).
     */
    private void enforceRateLimit(String claimantSub) {
        if (claimantSub == null || claimantSub.isBlank()) {
            return;
        }
        long recent = submissions.countSince(claimantSub, Instant.now().minus(FNOL_WINDOW));
        if (recent >= Math.max(1, fnolPerDay)) {
            throw new RateLimitedException(
                    "Too many claims filed recently. Please wait a day before filing another claim.",
                    FNOL_WINDOW.toSeconds());
        }
    }

    /** Policy numbers are matched leniently on input: trimmed and case-insensitive. */
    private static String normalizePolicyNumber(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
