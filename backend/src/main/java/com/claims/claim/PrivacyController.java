package com.claims.claim;

import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.audit.AuditJson;

/**
 * V24 (V3 S9: GDPR + retention story) — one controller for both halves of the
 * surface: the claimant's own export ({@code /api/privacy/me/export}, own sub
 * from auth) and the supervisor's anonymize + retention report ({@code
 * /api/admin/privacy/*}). URL roles live in {@code SecurityConfig}; own-sub is
 * enforced in the service (the export walks only the caller's claims).
 */
@RestController
@RequestMapping("/api")
public class PrivacyController {

    private final PrivacyService privacy;

    public PrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    /**
     * The caller's own data as a JSON download (attachment disposition). No
     * {@code privacy_request} row is written for exports — the slice records
     * ERASURE rows only, and the export leaves no trail by literal design.
     */
    @GetMapping("/privacy/me/export")
    public ResponseEntity<String> exportOwn(@AuthenticationPrincipal Jwt jwt) {
        String body = AuditJson.of(privacy.exportOwn(jwt.getSubject()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition
                        .attachment().filename("my-data.json").build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** Supervisor-only erasure of one subject's PII (400 on shared policies). */
    @PostMapping("/admin/privacy/anonymize")
    public Map<String, Object> anonymize(@AuthenticationPrincipal Jwt jwt,
            @RequestBody AnonymizeRequest request) {
        String claimantSub = request == null ? null : request.claimantSub();
        String rationale = request == null ? null : request.rationale();
        return privacy.anonymize(claimantSub, rationale, jwt.getSubject());
    }

    /** Supervisor-only retention counts (report-only, no deletes). */
    @GetMapping("/admin/privacy/retention-report")
    public Map<String, Object> retentionReport() {
        return privacy.retentionReport();
    }

    public record AnonymizeRequest(String claimantSub, String rationale) {
    }
}
