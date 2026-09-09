package com.claims.claim;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S3: the required-documents checklist surface. GET serves full rows to the
 * assignee/supervisor and the walled {displayName, status} shape to the owning
 * claimant (404 for anyone else); link/waive are assignee-or-supervisor
 * (404 otherwise). Claimant vs adjuster URL roles stay in SecurityConfig.
 */
@RestController
@RequestMapping("/api/claims")
public class RequiredDocumentsController {

    private final RequiredDocumentService documents;

    public RequiredDocumentsController(RequiredDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/{claimNumber}/required-documents")
    public Object requiredDocuments(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable String claimNumber) {
        return documents.listFor(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }

    @PostMapping("/{claimNumber}/required-documents/{checkId}/link")
    public RequiredDocumentService.RequiredDocumentView link(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber, @PathVariable Long checkId,
            @RequestBody RequiredDocumentService.LinkRequest request) {
        return documents.link(claimNumber, checkId,
                request == null ? null : request.attachmentId(), jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }

    @PostMapping("/{claimNumber}/required-documents/{checkId}/waive")
    public RequiredDocumentService.RequiredDocumentView waive(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String claimNumber, @PathVariable Long checkId,
            @RequestBody RequiredDocumentService.WaiveRequest request) {
        return documents.waive(claimNumber, checkId,
                request == null ? null : request.rationale(), jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }
}
