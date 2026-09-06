package com.claims.claim;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.claims.mail.AssignmentEmailSender;
import com.claims.mail.FnolEmailSender;

/** Claimant FNOL submission (multipart: text fields + evidence photos). */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final ClaimService claimService;
    private final FnolEmailSender fnolEmailSender;
    private final AssignmentEmailSender assignmentEmailSender;

    public ClaimController(ClaimService claimService, FnolEmailSender fnolEmailSender,
            AssignmentEmailSender assignmentEmailSender) {
        this.claimService = claimService;
        this.fnolEmailSender = fnolEmailSender;
        this.assignmentEmailSender = assignmentEmailSender;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimantClaimView fileClaim(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("policyNumber") String policyNumber,
            @RequestParam("holderName") String holderName,
            @RequestParam("holderEmail") String holderEmail,
            @RequestParam("lossDate") String lossDate,
            @RequestParam("lossLocation") String lossLocation,
            @RequestParam("lossDescription") String lossDescription,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos) {
        List<MultipartFile> photoList = photos == null ? List.of() : List.of(photos);
        FnolResult result = claimService.fileFnol(new FnolInput(policyNumber, holderName,
                holderEmail, lossDate, lossLocation, lossDescription, remarks,
                jwt.getSubject(), photoList));
        // After the claim is committed: best-effort emails to the verified policy-holder
        // address; never blocks or rolls back (see docs/decisions.md).
        fnolEmailSender.sendFnolConfirmation(holderEmail, result.view().claimNumber(), holderName);
        if (result.adjusterName() != null) {
            assignmentEmailSender.sendAssignment(holderEmail, result.view().claimNumber(),
                    holderName, result.adjusterName(), result.adjusterEmail());
        }
        return result.view();
    }
}
