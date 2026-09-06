package com.claims.claim;

import java.math.BigDecimal;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal claim surface (slice 3): full view with policy/coverage/reserve/notes/
 * photos, reserve updates, internal notes, and photo downloads. Only the assigned adjuster
 * or a supervisor gets in; anyone else — including an adjuster on someone else's claim —
 * is a 404 (see ClaimWorkService).
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimWorkController {

    private final ClaimWorkService claimWorkService;

    public ClaimWorkController(ClaimWorkService claimWorkService) {
        this.claimWorkService = claimWorkService;
    }

    @GetMapping("/{claimNumber}/full")
    public InternalClaimView full(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber) {
        return claimWorkService.fullView(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }

    @PutMapping("/{claimNumber}/reserve")
    public InternalClaimView reserve(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber, @RequestBody ReserveRequest request) {
        return claimWorkService.updateReserve(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), request.amount());
    }

    @PostMapping("/{claimNumber}/notes")
    public InternalClaimView.NoteView note(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @RequestBody NoteRequest request) {
        return claimWorkService.addNote(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), request.body());
    }

    @GetMapping("/{claimNumber}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber,
            @PathVariable Long attachmentId) {
        AttachmentDownload download = claimWorkService.download(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), attachmentId);
        // Always served as an attachment, never inline: photos are untrusted bytes and
        // must not render as HTML in the adjuster's browser (slice-1 review deferral (a)).
        // The header name is scrubbed of control characters/quotes so a hostile stored
        // filename cannot smuggle header content.
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(safeHeaderName(download.originalName()))
                        .build().toString())
                .contentType(mediaType(download.contentType()))
                .body(download.bytes());
    }

    private static String safeHeaderName(String name) {
        if (name == null) {
            return "photo";
        }
        String scrubbed = name.replaceAll("[\\r\\n\"]", "_").replaceAll("\\p{Cntrl}", "_");
        return scrubbed.isBlank() ? "photo" : scrubbed;
    }

    private static MediaType mediaType(String contentType) {
        if (contentType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    public record ReserveRequest(BigDecimal amount) {
    }

    public record NoteRequest(String body) {
    }
}
