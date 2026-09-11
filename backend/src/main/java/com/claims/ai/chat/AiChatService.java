package com.claims.ai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.claims.ai.client.DeepSeekProperties;
import com.claims.ai.client.ProviderException;
import com.claims.api.ClaimNotFoundException;
import com.claims.api.InvalidRequestException;
import com.claims.audit.AuditJson;
import com.claims.audit.AuditLogWriter;
import com.claims.claim.Claim;
import com.claims.claim.ClaimAccess;
import com.claims.claim.ClaimCover;
import com.claims.claim.ClaimCoverRepository;
import com.claims.claim.ClaimRepository;
import com.claims.clause.PolicyClauseService;
import com.claims.clause.PolicyClauseService.ClauseView;
import com.claims.policy.Policy;
import com.claims.policy.PolicyRepository;

/**
 * Conversational AI chat over a claim: the adjuster's free-text questions
 * answered from the live claim snapshot plus the served policy clauses.
 *
 * <p>Read-only advisory — chat never writes money or stages; it only reads
 * claim/covers/clauses and talks to the provider. Deliberately
 * stage-agnostic (unlike the DECISION-only {@code AiAnalysisService}, which
 * is why that service is never called from here): the box is available at
 * REVIEW, VERIFICATION and DECISION alike.
 *
 * <p>Stateless server: nothing is persisted except one audit row per call.
 * The full transcript lives in the SPA; the client sends it on every call
 * and gets the transcript including the new exchange back.
 *
 * <p>Object auth is 404-not-403 on the claim view (via {@link ClaimAccess});
 * role gates stay in SecurityConfig. Provider failures never escape as 500:
 * they become a short assistant message with status DEGRADED.
 */
@Service
public class AiChatService {

    /** Model label recorded when the provider cannot serve. */
    static final String RULES_ONLY_MODEL = "rules-only";

    static final int MAX_QUESTION_CHARS = 2000;
    static final int MAX_HISTORY_ENTRIES = 20;
    static final int MAX_HISTORY_CONTENT_CHARS = 4000;
    static final int MAX_CLAUSES = 40;
    static final int MAX_CLAUSE_TEXT_CHARS = 500;
    static final int MAX_HISTORY_PAIRS_SENT = 10;
    static final int MAX_ANSWER_CHARS = 2000;

    static final String SYSTEM = """
            You are a cautious insurance-coverage assistant helping an adjuster \
            with one specific claim. Answer ONLY from the claim facts and policy \
            clauses given in the user prompt. Never invent covers, clauses, \
            amounts, or dates. Keep rationales short. All money figures are in \
            INR. This is advisory only — the adjuster decides, not you. If the \
            adjuster asks about anything other than this claim, refuse briefly: \
            "I can only help with this claim."
            """;

    static final String UNAVAILABLE_MESSAGE = "AI chat is unavailable — the provider is not configured. "
            + "The per-cover advisory panel still shows the rules-only fallback at decision.";

    private final ClaimRepository claims;
    private final ClaimCoverRepository claimCovers;
    private final PolicyRepository policies;
    private final PolicyClauseService policyClauses;
    private final ChatClient client;
    private final DeepSeekProperties properties;
    private final ClaimAccess access;
    private final AuditLogWriter auditLog;

    public AiChatService(ClaimRepository claims,
            ClaimCoverRepository claimCovers, PolicyRepository policies,
            PolicyClauseService policyClauses, ChatClient client,
            DeepSeekProperties properties, ClaimAccess access,
            AuditLogWriter auditLog) {
        this.claims = claims;
        this.claimCovers = claimCovers;
        this.policies = policies;
        this.policyClauses = policyClauses;
        this.client = client;
        this.properties = properties;
        this.access = access;
        this.auditLog = auditLog;
    }

    /** One transcript line, either side. */
    public record ChatMessageView(String role, String content) {
    }

    /**
     * The reply: the full transcript INCLUDING the new exchange (the client
     * replaces its transcript with this), plus the outcome.
     */
    public record ChatReplyView(List<ChatMessageView> messages, String status,
            String model, String errorMessage) {
    }

    /**
     * Answers one adjuster question about the claim. Always returns a
     * transcript — provider trouble becomes an assistant message with status
     * DEGRADED, never a 500. Validation failures throw
     * {@link InvalidRequestException} (400); invisible claims throw
     * {@link ClaimNotFoundException} (404, never 403).
     */
    public ChatReplyView chat(String claimNumber, String subject,
            boolean supervisor, String requestedBy,
            List<ChatMessageView> history, String question) {
        Claim claim = visibleClaim(claimNumber, subject, supervisor);
        if (question == null || question.isBlank()) {
            throw new InvalidRequestException("A question is required.");
        }
        String trimmedQuestion = question.strip();
        if (trimmedQuestion.length() > MAX_QUESTION_CHARS) {
            throw new InvalidRequestException(
                    "The question must be at most 2000 characters.");
        }
        List<ChatMessageView> prior = validatedHistory(history);

        Policy policy = policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException("Claim "
                        + claim.getClaimNumber() + " references a missing policy "
                        + claim.getPolicyId()));
        List<ClaimCover> covers = claimCovers
                .findByClaimIdOrderByIdAsc(claim.getId());
        // forClaim enforces the same assignee/supervisor visibility internally.
        List<ClauseView> clauses = policyClauses.forClaim(claimNumber, subject,
                supervisor);

        String userPrompt = userPromptOf(claim, policy.getProductCode(),
                covers, clauses, prior, trimmedQuestion);

        List<ChatMessageView> transcript = new ArrayList<>(prior);
        transcript.add(new ChatMessageView("user", trimmedQuestion));

        String status;
        String model;
        String errorMessage = null;
        String assistantText;
        if (!client.available()) {
            status = "DEGRADED";
            model = RULES_ONLY_MODEL;
            errorMessage = "provider not configured";
            assistantText = UNAVAILABLE_MESSAGE;
        } else {
            try {
                String answer = client.ask(SYSTEM, userPrompt,
                        historyPairs(prior));
                status = "COMPLETED";
                model = properties.getModel();
                assistantText = cap(answer.strip(), MAX_ANSWER_CHARS);
            } catch (ProviderException ex) {
                status = "DEGRADED";
                model = RULES_ONLY_MODEL;
                errorMessage = shortReason(ex.getMessage());
                assistantText = "I could not reach the provider ("
                        + errorMessage + "). Try again.";
            }
        }
        transcript.add(new ChatMessageView("assistant", assistantText));

        auditLog.append(requestedBy, "AI_CHAT_MESSAGE", "CLAIM",
                claim.getId(), null,
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "stage", claim.getStage(), "status", status)),
                null);
        return new ChatReplyView(List.copyOf(transcript), status, model,
                errorMessage);
    }

    private Claim visibleClaim(String claimNumber, String subject,
            boolean supervisor) {
        Claim claim = claims.findByClaimNumber(claimNumber)
                .orElseThrow(ClaimNotFoundException::new);
        if (!access.internalReaderMaySee(claim, subject, supervisor)) {
            throw new ClaimNotFoundException();
        }
        return claim;
    }

    private static List<ChatMessageView> validatedHistory(
            List<ChatMessageView> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        if (history.size() > MAX_HISTORY_ENTRIES) {
            throw new InvalidRequestException(
                    "The transcript must hold at most 20 messages.");
        }
        List<ChatMessageView> out = new ArrayList<>(history.size());
        for (ChatMessageView entry : history) {
            if (entry == null || (!"user".equals(entry.role())
                    && !"assistant".equals(entry.role()))) {
                throw new InvalidRequestException(
                        "Every transcript message needs role user or assistant.");
            }
            if (entry.content() == null || entry.content().isBlank()) {
                throw new InvalidRequestException(
                        "Every transcript message needs content.");
            }
            if (entry.content().length() > MAX_HISTORY_CONTENT_CHARS) {
                throw new InvalidRequestException(
                        "Every transcript message must be at most 4000 characters.");
            }
            out.add(entry);
        }
        return List.copyOf(out);
    }

    /** Last 10 pairs of the validated transcript as provider history. */
    private static List<Map<String, String>> historyPairs(
            List<ChatMessageView> prior) {
        int from = Math.max(0, prior.size() - MAX_HISTORY_PAIRS_SENT);
        List<Map<String, String>> out = new ArrayList<>();
        for (ChatMessageView entry : prior.subList(from, prior.size())) {
            out.add(Map.of("role", entry.role(), "content", entry.content()));
        }
        return out;
    }

    private static String userPromptOf(Claim claim, String productCode,
            List<ClaimCover> covers, List<ClauseView> clauses,
            List<ChatMessageView> prior, String question) {
        StringBuilder facts = new StringBuilder();
        facts.append("Claim ").append(claim.getClaimNumber())
                .append(" (product ").append(productCode)
                .append(", stage ").append(claim.getStage())
                .append(", status ").append(claim.getStatus()).append(")\n");
        facts.append("Loss date: ").append(claim.getLossDate())
                .append("; description: ").append(claim.getLossDescription())
                .append("\n");
        facts.append("Claimed total (INR): ").append(claim.getClaimedTotal())
                .append("\n");
        facts.append("Covers (claimed / assessed, INR):\n");
        for (ClaimCover cover : covers) {
            facts.append("- ").append(cover.getCoverCode())
                    .append(": claimed ").append(cover.getClaimedAmount())
                    .append(", assessed ").append(cover.getAssessedAmount())
                    .append("\n");
        }
        facts.append("Policy clauses (at most ").append(MAX_CLAUSES)
                .append(", text truncated):\n");
        int shown = 0;
        for (ClauseView clause : clauses) {
            if (shown >= MAX_CLAUSES) {
                break;
            }
            facts.append("- [").append(clause.id()).append("] ")
                    .append(clause.coverCode() == null ? "*" : clause.coverCode())
                    .append(" ").append(clause.clauseRef())
                    .append(" (").append(clause.clauseType()).append("): ")
                    .append(cap(clause.clauseText() == null ? ""
                            : clause.clauseText().strip().replaceAll("\\s+", " "),
                            MAX_CLAUSE_TEXT_CHARS));
            if (clause.waitingPeriodDays() != null) {
                facts.append(" [waiting ").append(clause.waitingPeriodDays())
                        .append("d]");
            }
            if (clause.subLimitAmount() != null) {
                facts.append(" [sub-limit INR ").append(clause.subLimitAmount())
                        .append("]");
            }
            if (clause.coPayPercent() != null) {
                facts.append(" [co-pay ").append(clause.coPayPercent())
                        .append("%]");
            }
            facts.append("\n");
            shown++;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Claim facts (answer only from these):\n")
                .append(facts)
                .append("\nConversation so far:\n");
        int from = Math.max(0, prior.size() - MAX_HISTORY_PAIRS_SENT);
        for (ChatMessageView entry : prior.subList(from, prior.size())) {
            prompt.append("user".equals(entry.role()) ? "Adjuster: "
                    : "Assistant: ").append(entry.content()).append("\n");
        }
        prompt.append("Adjuster: ").append(question);
        return prompt.toString();
    }

    private static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String shortReason(String message) {
        if (message == null || message.isBlank()) {
            return "provider failure";
        }
        String flat = message.strip().replaceAll("\\s+", " ");
        return flat.length() <= 500 ? flat : flat.substring(0, 500);
    }

}
