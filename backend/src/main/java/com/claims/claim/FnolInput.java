package com.claims.claim;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

/**
 * Raw FNOL submission from the controller; text fields are validated by ClaimService.
 *
 * <p>V2-2: {@code covers} carries the explicit per-cover selections (null = legacy
 * single-figure path: no cover rows, {@code claimed_total} stays NULL). A present
 * list is validated (1–N opted covers, amounts &gt; 0) and persisted as
 * {@code claim_cover} rows with a server-computed {@code claimed_total}.
 */
public record FnolInput(String policyNumber, String holderName, String holderEmail,
        String lossDate, String lossLocation, String lossDescription, String remarks,
        String claimantSub, List<MultipartFile> photos, List<CoverSelection> covers) {

    /** Backwards-compatible constructor for the legacy no-covers path (tests, old callers). */
    public FnolInput(String policyNumber, String holderName, String holderEmail,
            String lossDate, String lossLocation, String lossDescription, String remarks,
            String claimantSub, List<MultipartFile> photos) {
        this(policyNumber, holderName, holderEmail, lossDate, lossLocation, lossDescription,
                remarks, claimantSub, photos, null);
    }
}
