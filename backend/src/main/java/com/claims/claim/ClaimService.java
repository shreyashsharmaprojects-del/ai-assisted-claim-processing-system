package com.claims.claim;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.FnolValidationException;
import com.claims.api.PolicyMismatchException;
import com.claims.api.UnroutableException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;
import com.claims.routing.AuthorityConfigRepository;
import com.claims.routing.ClaimClassifier;

/**
 * FNOL: verify the policy, classify the claim from the product code, persist claim +
 * photos + audit entry in one transaction. Returns the claimant view with the claim
 * number immediately.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final AuthorityConfigRepository authorityConfigs;
    private final AttachmentRepository attachments;
    private final PhotoStorage photoStorage;
    private final AuditLogWriter auditLog;
    private final JdbcTemplate jdbcTemplate;

    public ClaimService(ClaimRepository claims, PolicyRepository policies,
            AuthorityConfigRepository authorityConfigs, AttachmentRepository attachments,
            PhotoStorage photoStorage, AuditLogWriter auditLog, JdbcTemplate jdbcTemplate) {
        this.claims = claims;
        this.policies = policies;
        this.authorityConfigs = authorityConfigs;
        this.attachments = attachments;
        this.photoStorage = photoStorage;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ClaimantClaimView fileFnol(FnolInput input) {
        LocalDate lossDate = validate(input);
        String policyNumber = normalizePolicyNumber(input.policyNumber());

        Policy policy = policies.findByPolicyNumber(policyNumber).orElse(null);
        if (policy == null
                || !policy.getHolderName().equalsIgnoreCase(input.holderName().trim())
                || !policy.getHolderEmail().equalsIgnoreCase(input.holderEmail().trim())) {
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

        auditLog.append(input.claimantSub(), "CLAIM_CREATED", "CLAIM", claimId, null,
                AuditJson.of(Map.of(
                        "claimNumber", claim.getClaimNumber(),
                        "level", claim.getLevel(),
                        "status", claim.getStatus())),
                null);

        return ClaimantClaimView.from(claim);
    }

    private LocalDate validate(FnolInput input) {
        List<String> errors = new ArrayList<>();
        if (isBlank(input.policyNumber())) {
            errors.add("Policy number is required.");
        }
        if (isBlank(input.holderName())) {
            errors.add("Policyholder name is required.");
        }
        if (isBlank(input.holderEmail())) {
            errors.add("Policyholder email is required.");
        }
        if (isBlank(input.lossLocation())) {
            errors.add("Loss location is required.");
        }
        if (isBlank(input.lossDescription())) {
            errors.add("A description of what happened is required.");
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
        }
        if (!errors.isEmpty()) {
            throw new FnolValidationException(String.join(" ", errors));
        }
        return date;
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
