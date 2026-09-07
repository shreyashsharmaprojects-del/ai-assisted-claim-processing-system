package com.claims.claim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.claims.api.FnolValidationException;
import com.claims.mail.AssignmentEmailSender;
import com.claims.mail.FnolEmailSender;
import com.claims.outbox.EmailOutboxDispatcher;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Claimant FNOL submission (multipart: text fields + evidence photos).
 *
 * <p>R2: the service writes FNOL + assignment mails to the outbox in the filing
 * transaction; this controller keeps the best-effort immediate sends after commit
 * (existing Mailpit tests assert on them) and flushes the outbox rows through the
 * dispatcher, so normal operation delivers each mail effectively once per flush while an
 * SMTP outage degrades to PENDING rows the dispatcher retries.
 *
 * <p>V2-2: the multipart body gains an optional {@code covers} text part — a JSON
 * array of {@code {coverCode, claimedAmount}} (camelCase; {@code cover_code} /
 * {@code claimed_amount} accepted leniently). Absent = legacy single-figure path.
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClaimService claimService;
    private final FilingCoversService filingCoversService;
    private final FnolEmailSender fnolEmailSender;
    private final AssignmentEmailSender assignmentEmailSender;
    private final EmailOutboxDispatcher dispatcher;

    public ClaimController(ClaimService claimService, FilingCoversService filingCoversService,
            FnolEmailSender fnolEmailSender, AssignmentEmailSender assignmentEmailSender,
            EmailOutboxDispatcher dispatcher) {
        this.claimService = claimService;
        this.filingCoversService = filingCoversService;
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
            @RequestParam(value = "covers", required = false) String coversJson,
            @RequestPart(value = "photos", required = false) MultipartFile[] photos,
            HttpServletRequest request) {
        List<MultipartFile> photoList = photos == null ? List.of() : List.of(photos);
        FnolResult result = claimService.fileFnol(new FnolInput(policyNumber, holderName,
                holderEmail, lossDate, lossLocation, lossDescription, remarks,
                jwt.getSubject(), photoList, parseCovers(coversJson)),
                request.getRemoteAddr());
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

    /**
     * V2-2 cover picker source: the opted covers for a holder-matched ACTIVE policy,
     * keyed off the typed filing identity (policy number + holder name + email — not
     * the caller's account email, which may differ when filing for a holder).
     * Holder mismatch, unknown policy, or a non-ACTIVE policy is the same 404 shape
     * as FNOL (no existence signal). Returns only claimant-safe fields.
     */
    @GetMapping("/filing-covers")
    public List<FilingCoverView> filingCovers(
            @RequestParam("policyNumber") String policyNumber,
            @RequestParam("holderName") String holderName,
            @RequestParam("holderEmail") String holderEmail) {
        return filingCoversService.coversFor(policyNumber, holderName, holderEmail);
    }

    /**
     * Parses the optional {@code covers} JSON part. Null/blank = legacy path (null).
     * Malformed JSON or a non-array = 400; per-entry shape errors surface as 400 in
     * the service's cover validation.
     */
    static List<CoverSelection> parseCovers(String coversJson) {
        if (coversJson == null || coversJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(coversJson);
        } catch (tools.jackson.core.JacksonException ex) {
            throw new FnolValidationException(
                    "The selected covers could not be read — select the covers again.");
        }
        if (!root.isArray()) {
            throw new FnolValidationException(
                    "The selected covers could not be read — select the covers again.");
        }
        List<CoverSelection> selections = new ArrayList<>();
        for (JsonNode entry : root) {
            String code = textOf(entry, "coverCode", "cover_code");
            BigDecimal amount = decimalOf(entry, "claimedAmount", "claimed_amount");
            selections.add(new CoverSelection(code, amount));
        }
        return selections;
    }

    private static String textOf(JsonNode entry, String camel, String snake) {
        JsonNode node = entry.has(camel) ? entry.get(camel)
                : entry.has(snake) ? entry.get(snake) : null;
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        // asString() coerces (a numeric code becomes its text and fails the
        // opted-check with a 400 naming valid codes, never a 500).
        String text = node.asString();
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? "" : trimmed.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimalOf(JsonNode entry, String camel, String snake) {
        JsonNode node = entry.has(camel) ? entry.get(camel)
                : entry.has(snake) ? entry.get(snake) : null;
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        try {
            // asString() coerces numeric nodes to their text (stringValue() is
            // strict and throws on non-textual nodes in Jackson 3).
            return new BigDecimal(node.asString().trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }
}
