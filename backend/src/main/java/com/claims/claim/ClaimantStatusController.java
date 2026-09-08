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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.api.ClaimNotFoundException;
import com.claims.policy.PolicyCover;
import com.claims.policy.PolicyCoverRepository;

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

    public ClaimantStatusController(ClaimRepository claims,
            ClaimCoverRepository claimCovers, PolicyCoverRepository policyCovers) {
        this.claims = claims;
        this.claimCovers = claimCovers;
        this.policyCovers = policyCovers;
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
        if (rows.isEmpty()) {
            return ClaimantClaimView.from(claim);
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
                "CLOSED".equals(claim.getStatus()) ? netPayableTotal(rows) : null);
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
