package com.claims.ai.analysis;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.ai.analysis.AiAnalysisService.AnalysisView;
import com.claims.claim.Authorities;

/**
 * V30 (V4 S2): the internal AI advisory surface — thin over
 * {@link AiAnalysisService}. Only the assigned adjuster or a supervisor gets
 * in (role gates in SecurityConfig, object visibility in the service with the
 * 404-not-403 rule); claimants are 403 at the URL, never served these rows.
 */
@RestController
@RequestMapping("/api")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/claims/{claimNumber}/ai-analysis")
    public AnalysisView analyse(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String claimNumber) {
        return aiAnalysisService.analyse(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), jwt.getSubject());
    }

    @GetMapping("/claims/{claimNumber}/ai-analysis")
    public AnalysisView latest(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String claimNumber) {
        return aiAnalysisService.latest(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication));
    }
}
