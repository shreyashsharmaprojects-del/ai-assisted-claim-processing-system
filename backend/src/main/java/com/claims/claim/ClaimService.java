package com.claims.claim;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.FnolValidationException;
import com.claims.api.PolicyMismatchException;
import com.claims.api.RateLimitedException;
import com.claims.api.UnroutableException;
import com.claims.assignment.ClaimAssigner;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.outbox.EmailOutboxWriter;
import com.claims.metrics.ClaimsMetrics;
import com.claims.policy.Policy;
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
    private final AuthorityConfigRepository authorityConfigs;
    private final AttachmentRepository attachments;
    private final PhotoStorage photoStorage;
    private final AuditLogWriter auditLog;
    private final ClaimAssigner assigner;
    private final JdbcTemplate jdbcTemplate;
    private final FnolSubmissionRepository submissions;
    private final ClaimsMetrics metrics;
    private final EmailOutboxWriter outboxWriter;
    private final int fnolPerDay;

    public ClaimService(ClaimRepository claims, PolicyRepository policies,
            AuthorityConfigRepository authorityConfigs, AttachmentRepository attachments,
            PhotoStorage photoStorage, AuditLogWriter auditLog, ClaimAssigner assigner,
            JdbcTemplate jdbcTemplate, FnolSubmissionRepository submissions,
            ClaimsMetrics metrics, EmailOutboxWriter outboxWriter,
            @Value("${claims.fnol.rate-limit-per-day:20}") int fnolPerDay) {
        this.claims = claims;
        this.policies = policies;
        this.authorityConfigs = authorityConfigs;
        this.attachments = attachments;
        this.photoStorage = photoStorage;
        this.auditLog = auditLog;
        this.assigner = assigner;
        this.jdbcTemplate = jdbcTemplate;
        this.submissions = submissions;
        this.metrics = metrics;
        this.outboxWriter = outboxWriter;
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
                || policy.isRetired()) {
            // RETIRED policies reuse the same shape: no new claimant-visible branch, and no
            // signal whether the number exists, the holder mismatched, or it was retired.
            throw new PolicyMismatchException(
                    "We could not match that policy number with the holder details provided.");
        }

        String level = ClaimClassifier.routeLevelFor(policy.getProductCode(), authorityConfigs.findAll());
        if (level == null) {
            throw new UnroutableException(
                    "No routing configuration exists for product " + policy.getProductCode());
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
            attachments.save(new Attachment(claimId, photo.storagePath(),
                    photo.contentType(), photo.originalName()));
        }

        submissions.save(new FnolSubmission(input.claimantSub(), claimId, clientIp, Instant.now()));

        auditLog.append(input.claimantSub(), "CLAIM_CREATED", "CLAIM", claimId, null,
                AuditJson.of(Map.of(
                        "claimNumber", claim.getClaimNumber(),
                        "level", claim.getLevel(),
                        "status", claim.getStatus())),
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
        outboxWriter.enqueueFnol(claimId, input.holderEmail().trim(), claim.getClaimNumber(),
                policy.getHolderName());
        if (adjuster != null) {
            outboxWriter.enqueueAssignment(claimId, input.holderEmail().trim(),
                    claim.getClaimNumber(), policy.getHolderName(), adjuster.getDisplayName(),
                    adjuster.getEmail());
        }
        return new FnolResult(ClaimantClaimView.from(claim),
                adjuster == null ? null : adjuster.getDisplayName(),
                adjuster == null ? null : adjuster.getEmail());
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
