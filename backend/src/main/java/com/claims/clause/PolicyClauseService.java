package com.claims.clause;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.claim.Claim;
import com.claims.claim.ClaimAccess;
import com.claims.claim.ClaimCover;
import com.claims.claim.ClaimCoverRepository;
import com.claims.claim.ClaimRepository;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;

/**
 * V28 (V4 S1): the policy-clause reference read path. Clauses are reference
 * data, not claim state — read-only (no audit rows, no state changes).
 *
 * <p>Object auth is 404-not-403 on the claim view (via {@link ClaimAccess});
 * role gates stay in SecurityConfig. The catalogue is browsable by product
 * code: an unknown product yields an empty list, never a 404.
 */
@Service
public class PolicyClauseService {

    /** Read shape: every clause column, in table order. */
    public record ClauseView(Long id, String productCode, String coverCode,
            String clauseRef, String clauseType, String title, String clauseText,
            Integer waitingPeriodDays, BigDecimal subLimitAmount,
            BigDecimal coPayPercent, LocalDate effectiveFrom,
            LocalDate effectiveTo, int sortOrder) {
    }

    private final PolicyClauseRepository clauses;
    private final ClaimRepository claims;
    private final PolicyRepository policies;
    private final ClaimCoverRepository claimCovers;
    private final ClaimAccess access;

    public PolicyClauseService(PolicyClauseRepository clauses,
            ClaimRepository claims, PolicyRepository policies,
            ClaimCoverRepository claimCovers, ClaimAccess access) {
        this.clauses = clauses;
        this.claims = claims;
        this.policies = policies;
        this.claimCovers = claimCovers;
        this.access = access;
    }

    /**
     * Catalogue: every clause of a product, optionally narrowed to one cover
     * (that cover's rows plus the product-level rows). Blank product codes
     * are a 400; unknown products yield an empty list.
     */
    public List<ClauseView> catalogue(String productCode, String coverCode) {
        if (productCode == null || productCode.isBlank()) {
            throw new InvalidRequestException("A product code is required.");
        }
        String cover = coverCode == null || coverCode.isBlank() ? null
                : coverCode.trim();
        return clauses.findCatalogue(productCode.trim(), cover).stream()
                .map(PolicyClauseService::viewOf).toList();
    }

    /**
     * Claim view: clauses of the claim's product in force on the claim's loss
     * date, restricted to product-level rows plus the claim's own covers.
     * Assignee/supervisor only (404 otherwise, never revealing the number).
     */
    public List<ClauseView> forClaim(String claimNumber, String subject,
            boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, subject, supervisor)) {
            throw new ClaimNotFoundException();
        }
        Policy policy = policies.findById(claim.getPolicyId()).orElseThrow(
                () -> new IllegalStateException("Claim " + claim.getClaimNumber()
                        + " references a missing policy " + claim.getPolicyId()));
        List<String> coverCodes = claimCovers
                .findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .map(ClaimCover::getCoverCode).toList();
        return clauses
                .findForClaim(policy.getProductCode(), claim.getLossDate(),
                        coverCodes.isEmpty() ? List.of("") : coverCodes)
                .stream().map(PolicyClauseService::viewOf).toList();
    }

    private static ClauseView viewOf(PolicyClause clause) {
        return new ClauseView(clause.getId(), clause.getProductCode(),
                clause.getCoverCode(), clause.getClauseRef(),
                clause.getClauseType(), clause.getTitle(),
                clause.getClauseText(), clause.getWaitingPeriodDays(),
                clause.getSubLimitAmount(), clause.getCoPayPercent(),
                clause.getEffectiveFrom(), clause.getEffectiveTo(),
                clause.getSortOrder());
    }
}
