package com.claims.claim;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

/** Raw FNOL submission from the controller; text fields are validated by ClaimService. */
public record FnolInput(String policyNumber, String holderName, String holderEmail,
        String lossDate, String lossLocation, String lossDescription, String remarks,
        String claimantSub, List<MultipartFile> photos) {
}
