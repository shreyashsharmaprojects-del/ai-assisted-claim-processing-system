package com.claims.claim;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.claims.api.PolicyMismatchException;
import com.claims.policy.Policy;
import com.claims.policy.PolicyCover;
import com.claims.policy.PolicyCoverRepository;
import com.claims.policy.PolicyRepository;

/**
 * V2-2 cover-picker source: the opted covers for a holder-matched ACTIVE policy.
 * Keys off the typed filing identity (policy number + holder name + email), not the
 * caller's account email — a claimant may file for a holder with a different email.
 * The gate mirrors {@code ClaimService.fileFnol} exactly: holder mismatch, unknown
 * policy, or a non-ACTIVE (RETIRED/EXPIRED) policy is the same 404 shape (E9, no
 * existence signal).
 */
@Service
public class FilingCoversService {

    private final PolicyRepository policies;
    private final PolicyCoverRepository policyCovers;

    public FilingCoversService(PolicyRepository policies,
            PolicyCoverRepository policyCovers) {
        this.policies = policies;
        this.policyCovers = policyCovers;
    }

    public List<FilingCoverView> coversFor(String policyNumber, String holderName,
            String holderEmail) {
        String number = policyNumber == null ? "" : policyNumber.trim()
                .toUpperCase(Locale.ROOT);
        Policy policy = policies.findByPolicyNumber(number).orElse(null);
        if (policy == null
                || holderName == null
                || !policy.getHolderName().equalsIgnoreCase(holderName.trim())
                || holderEmail == null
                || !policy.getHolderEmail().equalsIgnoreCase(holderEmail.trim())
                || !"ACTIVE".equals(policy.getStatus())) {
            throw new PolicyMismatchException(
                    "We could not match that policy number with the holder details provided.");
        }
        List<FilingCoverView> views = new java.util.ArrayList<>();
        for (PolicyCover cover : policyCovers
                .findByPolicyIdOrderBySortOrderAscIdAsc(policy.getId())) {
            views.add(new FilingCoverView(cover.getCoverCode(), cover.getDisplayName(),
                    cover.getSubLimit()));
        }
        return views;
    }
}
