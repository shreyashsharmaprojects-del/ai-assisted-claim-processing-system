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
import com.claims.outbox.EmailOutboxDispatcher;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Claimant FNOL submission (multipart: text fields + evidence photos).
 *
 * <p>R2: the service writes FNOL + assignment mails to the outbox in the filing
 * transaction; this controller keeps the best-effort immediate sends after commit
 * (existing Mailpit tests assert on them) and flushes the outbox rows through the
 * dispatcher, so normal operation delivers each mail effectively once per flush while an
 * SMTP outage degrades to PENDING rows the dispatcher retries.
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private final ClaimService claimService;
    private final FnolEmailSender fnolEmailSender;
    private final AssignmentEmailSender assignmentEmailSender;
    private final EmailOutboxDispatcher dispatcher;

    public ClaimController(ClaimService claimService, FnolEmailSender fnolEmailSender,
            AssignmentEmailSender assignmentEmailSender, EmailOutboxDispatcher dispatcher) {
        this.claimService = claimService;
        this.fnolEmailSender = fnolEmailSender;
        this.assignmentEmailSender = assignmentEmailSender;
        this.dispatcher = dispatcher;
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
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            HttpServletRequest request) {
        List<MultipartFile> photoList = photos == null ? List.of() : List.of(photos);
        FnolResult result = claimService.fileFnol(new FnolInput(policyNumber, holderName,
                holderEmail, lossDate, lossLocation, lossDescription, remarks,
                jwt.getSubject(), photoList), request.getRemoteAddr());
        // After the claim is committed: best-effort emails to the verified policy-holder
        // address; never blocks or rolls back (see docs/decisions.md).
        fnolEmailSender.sendFnolConfirmation(holderEmail, result.view().claimNumber(), holderName);
        if (result.adjusterName() != null) {
            assignmentEmailSender.sendAssignment(holderEmail, result.view().claimNumber(),
                    holderName, result.adjusterName(), result.adjusterEmail());
        }
        dispatcher.dispatch();
        return result.view();
    }
}
