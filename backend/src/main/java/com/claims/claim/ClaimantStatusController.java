package com.claims.claim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.policy.Policy;
import com.claims.policy.PolicyCover;
import com.claims.policy.PolicyCoverRepository;
import com.claims.policy.PolicyRepository;

/**
 * Claimant status surface (slice 3, E2E journey 2): a claimant opens their own claim by
 * number and gets only the claimant view. Someone else's claim number — or a number that
 * does not exist — is a 404, never a 403 (the response never reveals the number exists).
 *
 * <p>V2-2: filed covers ride the same view (claimant-supplied amounts + above-limit
 * flag only — the wall is unchanged). V2-5: per-cover outcomes + the payable figure
 * ride the same view on closure (never assessed/deductible/adjustment/proposals).
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimantStatusController {

    private final ClaimRepository claims;
    private final ClaimCoverRepository claimCovers;
    private final PolicyCoverRepository policyCovers;
    private final PolicyRepository policies;
    private final AttachmentRepository attachments;
    private final PhotoStorage photoStorage;
    private final RequiredDocumentService requiredDocuments;

    public ClaimantStatusController(ClaimRepository claims,
            ClaimCoverRepository claimCovers, PolicyCoverRepository policyCovers,
            PolicyRepository policies, AttachmentRepository attachments,
            PhotoStorage photoStorage, RequiredDocumentService requiredDocuments) {
        this.claims = claims;
        this.claimCovers = claimCovers;
        this.policyCovers = policyCovers;
        this.policies = policies;
        this.attachments = attachments;
        this.photoStorage = photoStorage;
        this.requiredDocuments = requiredDocuments;
    }

    @GetMapping("/{claimNumber}")
    public ClaimantClaimView status(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!claim.getClaimantSub().equals(jwt.getSubject())) {
            throw new ClaimNotFoundException();
        }
        List<ClaimCover> rows = claimCovers.findByClaimIdOrderByIdAsc(claim.getId());
        RequiredDocumentService.ClaimantDocs docs =
                requiredDocuments.claimantDocs(claim.getId());
        if (rows.isEmpty()) {
            return ClaimantClaimView.from(claim, null, null, null,
                    docs.received(), docs.total(), docs.items());
        }
        Map<String, PolicyCover> byCode = new HashMap<>();
        for (PolicyCover cover : policyCovers
                .findByPolicyIdOrderBySortOrderAscIdAsc(claim.getPolicyId())) {
            byCode.put(cover.getCoverCode(), cover);
        }
        List<ClaimantCoverView> covers = new ArrayList<>();
        for (ClaimCover row : rows) {
            PolicyCover cover = byCode.get(row.getCoverCode());
            covers.add(new ClaimantCoverView(row.getCoverCode(),
                    cover == null ? row.getCoverCode() : cover.getDisplayName(),
                    row.getClaimedAmount(),
                    cover == null ? null : cover.getSubLimit(),
                    cover != null && row.getClaimedAmount() != null
                            && cover.getSubLimit() != null
                            && row.getClaimedAmount().compareTo(cover.getSubLimit()) > 0,
                    row.getDecision(),
                    "APPROVED".equals(row.getDecision()) ? row.getApprovedAmount() : null,
                    row.getDecisionRemarks()));
        }
        return ClaimantClaimView.from(claim, covers, claim.getClaimedTotal(),
                "CLOSED".equals(claim.getStatus()) ? netPayableTotal(rows) : null,
                docs.received(), docs.total(), docs.items());
    }

    /**
     * V16: document upload answering a NEED_INFO request. Own claim only
     * (404 otherwise), NEED_INFO only (400 at any other state). The file lands
     * as a labelled attachment the adjuster sees on the documents panel; the
     * claimant stays on NEED_INFO until they send the text response — upload
     * and response are separate steps so either order works.
     */
    @PostMapping(value = "/{claimNumber}/documents",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentView uploadDocument(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String claimNumber,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "docKey", required = false) String docKey) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!claim.getClaimantSub().equals(jwt.getSubject())) {
            throw new ClaimNotFoundException();
        }
        if (!"NEED_INFO".equals(claim.getStatus())) {
            throw new InvalidRequestException(
                    "Documents can only be uploaded while a claim waits on information from you.");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A file is required.");
        }
        // S3: validate the docKey before storing bytes — unknown keys are a 400
        // naming the valid keys, and no file is persisted on rejection.
        Policy policy = policies.findById(claim.getPolicyId()).orElseThrow(
                () -> new IllegalStateException("Claim " + claimNumber
                        + " references a missing policy " + claim.getPolicyId()));
        if (docKey != null && !docKey.isBlank()) {
            requiredDocuments.validateDocKey(policy.getProductCode(), docKey);
        }
        List<StoredPhoto> stored = photoStorage.store(List.of(file), claim.getId());
        StoredPhoto photo = stored.get(0);
        String trimmed = label == null || label.isBlank() ? null : label.trim();
        if (trimmed != null && trimmed.length() > 120) {
            throw new InvalidRequestException(
                    "The document name may be at most 120 characters.");
        }
        Attachment row = attachments.save(new Attachment(claim.getId(),
                photo.storagePath(), photo.contentType(), photo.originalName(), trimmed,
                jwt.getSubject(), null, photo.sha256(), photo.sizeBytes()));
        // S3 auto-link runs in the request transaction via the service's
        // @Transactional method (self-call through the injected bean, not this).
        requiredDocuments.tryAutoLink(claim, docKey, row.getId(), jwt.getSubject());
        return new DocumentView(row.getId(), row.getLabel() == null
                ? row.getOriginalName() : row.getLabel());
    }

    /** The claimant-safe upload receipt: id + display name, nothing internal. */
    public record DocumentView(Long id, String name) {
    }

    /**
     * The payable figure on closure: Σ per-cover net on an APPROVED /
     * PARTIALLY_APPROVED closure (the V1 headline amount rides the same view for
     * legacy no-cover claims). Null while open — internal arithmetic never leaks.
     */
    private static BigDecimal netPayableTotal(List<ClaimCover> rows) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (ClaimCover row : rows) {
            if ("APPROVED".equals(row.getDecision()) && row.getNetPayable() != null) {
                total = total.add(row.getNetPayable());
                any = true;
            }
        }
        return any ? total : null;
    }
}
