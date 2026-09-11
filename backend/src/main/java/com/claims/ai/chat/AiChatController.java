package com.claims.ai.chat;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claims.ai.chat.AiChatService.ChatMessageView;
import com.claims.ai.chat.AiChatService.ChatReplyView;
import com.claims.claim.Authorities;

/**
 * The internal AI chat surface — thin over {@link AiChatService}. Only the
 * assigned adjuster or a supervisor gets in (role gates in SecurityConfig,
 * object visibility in the service with the 404-not-403 rule); claimants
 * are 403 at the URL, never served these rows.
 *
 * <p>The request carries the transcript so far ({@code messages}) plus the
 * new {@code question}; the reply carries the full transcript including the
 * new exchange, which the client adopts wholesale (stateless server).
 */
@RestController
@RequestMapping("/api")
public class AiChatController {

    /**
     * Chat request shape, defined here (not in the service file): the
     * transcript so far plus the adjuster's new question.
     */
    public record ChatRequest(List<ChatMessageView> messages, String question) {
    }

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/claims/{claimNumber}/ai-chat")
    public ChatReplyView chat(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable String claimNumber,
            @RequestBody ChatRequest request) {
        List<ChatMessageView> messages = request == null ? null : request.messages();
        String question = request == null ? null : request.question();
        return aiChatService.chat(claimNumber, jwt.getSubject(),
                Authorities.isSupervisor(authentication), jwt.getSubject(),
                messages, question);
    }
}
