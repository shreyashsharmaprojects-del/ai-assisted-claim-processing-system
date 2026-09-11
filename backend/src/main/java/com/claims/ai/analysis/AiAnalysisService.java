package com.claims.ai.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claims.ai.client.DeepSeekClient;
import com.claims.ai.client.DeepSeekProperties;
import com.claims.ai.client.ProviderException;
import com.claims.ai.prompt.AiOutputValidator;
import com.claims.ai.prompt.AiOutputValidator.ValidSuggestion;
import com.claims.ai.prompt.AiPromptBuilder;
import com.claims.ai.prompt.AiPromptBuilder.ClaimFacts;
import com.claims.ai.prompt.AiPromptBuilder.ClauseFacts;
import com.claims.ai.prompt.AiPromptBuilder.CoverFacts;
import com.claims.ai.prompt.InvalidAnalysisException;
import com.claims.ai.rules.CoverageRules;
import com.claims.ai.rules.CoverageRules.ClauseInput;
import com.claims.ai.rules.CoverageRules.CoverInput;
import com.claims.ai.rules.CoverageRules.RuleSuggestion;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V30 (V4 S2): DECISION-stage AI advisory snapshots.
 *
 * <p>Advisory only — the adjuster decides. One row per (claim, claim version):
 * requesting analysis twice for the same version returns the stored row, never
 * a second provider call. Claimant-invisible by design: assignee/supervisor
 * only (404-not-403 via {@link ClaimAccess}); role gates stay in
 * SecurityConfig.
 *
 * <p>Provider outcome is recorded, not hidden: COMPLETED (model output passed
 * strict validation), DEGRADED (deterministic rules-only fallback when the
 * provider is unreachable, unconfigured, or its output failed validation), or
 * FAILED (neither produced output).
 *
 * <p>Deliberately NOT transactional across the provider call: no DB lock is
 * held during the (up to ~30s) model round-trip. Each repository call is its
 * own transaction, which is also what makes the simultaneous-POST race safe —
 * the loser's unique-constraint violation rolls back only its own insert, and
 * the winner's committed row is then re-read and returned.
 */
@Service
public class AiAnalysisService {

    /** Model label recorded when the deterministic rules fallback serves. */
    static final String RULES_ONLY_MODEL = "rules-only";

    private static final String DECISION_STAGE = "DECISION";

    private final ClaimRepository claims;
    private final ClaimCoverRepository claimCovers;
    private final PolicyRepository policies;
    private final PolicyClauseService policyClauses;
    private final DeepSeekClient client;
    private final DeepSeekProperties properties;
    private final ClaimAiAnalysisRepository analyses;
    private final ClaimAccess access;
    private final AuditLogWriter auditLog;

    private static final ObjectMapper JSON = new ObjectMapper();

    public AiAnalysisService(ClaimRepository claims,
            ClaimCoverRepository claimCovers, PolicyRepository policies,
            PolicyClauseService policyClauses, DeepSeekClient client,
            DeepSeekProperties properties, ClaimAiAnalysisRepository analyses,
            ClaimAccess access, AuditLogWriter auditLog) {
        this.claims = claims;
        this.claimCovers = claimCovers;
        this.policies = policies;
        this.policyClauses = policyClauses;
        this.client = client;
        this.properties = properties;
        this.analyses = analyses;
        this.access = access;
        this.auditLog = auditLog;
    }

    /** One per-cover advisory line served to the panel. */
    public record SuggestionView(String coverCode, String recommendation,
            String rationale, List<Long> clauseIds,
            BigDecimal estimatedPayable) {
    }

    /** One frozen advisory snapshot. */
    public record AnalysisView(Long id, String status, String model,
            Long claimVersion, List<SuggestionView> suggestions,
            String errorMessage, java.time.Instant createdAt) {
    }

    /**
     * Computes (or replays) the advisory for the claim's current version.
     * Dedupe first: a stored row for (claim, version) is returned with no
     * provider call and no audit row.
     */
    public AnalysisView analyse(String claimNumber, String subject,
            boolean supervisor, String requestedBy) {
        Claim claim = visibleClaim(claimNumber, subject, supervisor);
        if (!DECISION_STAGE.equals(claim.getStage())) {
            throw new InvalidRequestException(
                    "AI advisory is available at the decision stage.");
        }
        Long version = claim.getVersion();
        AnalysisView replay = analyses
                .findByClaimIdAndClaimVersion(claim.getId(), version)
                .map(this::viewOf).orElse(null);
        if (replay != null) {
            return replay;
        }

        Policy policy = policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException("Claim "
                        + claim.getClaimNumber() + " references a missing policy "
                        + claim.getPolicyId()));
        List<ClaimCover> covers = claimCovers
                .findByClaimIdOrderByIdAsc(claim.getId());
        // forClaim enforces the same assignee/supervisor visibility internally.
        List<ClauseView> clauses = policyClauses.forClaim(claimNumber, subject,
                supervisor);

        ClaimFacts facts = factsOf(claim, policy.getProductCode(), covers,
                clauses);
        String snapshotJson = snapshotJsonOf(claim, policy.getProductCode(),
                covers);
        String clauseIdsJson = toJson(clauseIdsOf(clauses));

        Advisory advisory = advise(facts, claim, policy.getProductCode(),
                covers, clauses);

        ClaimAiAnalysis row = new ClaimAiAnalysis(claim.getId(), version,
                advisory.status(), advisory.model(), clauseIdsJson,
                snapshotJson, advisory.outputJson(), advisory.errorMessage(),
                requestedBy);
        ClaimAiAnalysis saved;
        try {
            saved = analyses.saveAndFlush(row);
        } catch (DataIntegrityViolationException dup) {
            // Simultaneous POSTs: the winner's row is committed — serve it.
            return analyses
                    .findByClaimIdAndClaimVersion(claim.getId(), version)
                    .map(this::viewOf).orElseThrow(() -> dup);
        }
        auditLog.append(requestedBy, "AI_ANALYSIS_REQUESTED", "CLAIM",
                claim.getId(), null,
                AuditJson.of(Map.of("claimNumber", claim.getClaimNumber(),
                        "status", advisory.status(), "model",
                        advisory.model())),
                null);
        return viewOf(saved);
    }

    /**
     * The newest advisory for the claim (max claim version). 404 when the
     * claim is invisible AND when no analysis exists yet — never an empty
     * body that callers could mistake for "no advice".
     */
    @Transactional(readOnly = true)
    public AnalysisView latest(String claimNumber, String subject,
            boolean supervisor) {
        Claim claim = visibleClaim(claimNumber, subject, supervisor);
        return analyses.findFirstByClaimIdOrderByClaimVersionDesc(claim.getId())
                .map(this::viewOf)
                .orElseThrow(ClaimNotFoundException::new);
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

    /** Provider attempt with deterministic rules fallback; never throws. */
    private Advisory advise(ClaimFacts facts, Claim claim, String productCode,
            List<ClaimCover> covers, List<ClauseView> clauses) {
        String providerReason = null;
        if (client.available()) {
            try {
                String modelJson = client.completeJson(
                        AiPromptBuilder.systemPrompt(),
                        AiPromptBuilder.userPrompt(facts));
                List<ValidSuggestion> valid = AiOutputValidator.validate(
                        modelJson, coverCodesOf(covers), clauseIdsOf(clauses));
                List<Map<String, Object>> rows = new ArrayList<>(valid.size());
                for (ValidSuggestion suggestion : valid) {
                    rows.add(suggestionRow(suggestion.coverCode(),
                            suggestion.recommendation(), suggestion.rationale(),
                            suggestion.clauseIds(),
                            suggestion.estimatedPayable()));
                }
                return new Advisory("COMPLETED", properties.getModel(),
                        toJson(Map.of("suggestions", rows)), null);
            } catch (ProviderException | InvalidAnalysisException ex) {
                providerReason = shortReason(ex.getMessage());
            }
        } else {
            providerReason = "provider not configured";
        }
        return rulesFallback(providerReason, productCode, claim, covers,
                clauses);
    }

    private Advisory rulesFallback(String providerReason, String productCode,
            Claim claim, List<ClaimCover> covers, List<ClauseView> clauses) {
        try {
            List<CoverInput> coverInputs = covers.stream()
                    .map(cover -> new CoverInput(cover.getCoverCode(),
                            cover.getClaimedAmount(),
                            cover.getAssessedAmount()))
                    .toList();
            List<ClauseInput> clauseInputs = clauses.stream()
                    .map(clause -> new ClauseInput(clause.id(),
                            clause.coverCode(), clause.clauseRef(),
                            clause.clauseType(), clause.waitingPeriodDays(),
                            clause.subLimitAmount(), clause.coPayPercent()))
                    .toList();
            List<RuleSuggestion> rules = CoverageRules.suggest(productCode,
                    claim.getLossDate(), coverInputs, clauseInputs);
            List<Map<String, Object>> rows = new ArrayList<>(rules.size());
            for (RuleSuggestion suggestion : rules) {
                rows.add(suggestionRow(suggestion.coverCode(),
                        suggestion.recommendation(), suggestion.rationale(),
                        suggestion.clauseIds(),
                        suggestion.estimatedPayable()));
            }
            return new Advisory("DEGRADED", RULES_ONLY_MODEL,
                    toJson(Map.of("suggestions", rows)), providerReason);
        } catch (RuntimeException rulesEx) {
            String detail = shortReason(rulesEx.getMessage());
            String error = providerReason == null ? detail
                    : providerReason + "; rules fallback failed: " + detail;
            return new Advisory("FAILED", RULES_ONLY_MODEL,
                    toJson(Map.of("suggestions", List.of())), error);
        }
    }

    private record Advisory(String status, String model, String outputJson,
            String errorMessage) {
    }

    private static ClaimFacts factsOf(Claim claim, String productCode,
            List<ClaimCover> covers, List<ClauseView> clauses) {
        List<CoverFacts> coverFacts = covers.stream()
                .map(cover -> new CoverFacts(cover.getCoverCode(),
                        cover.getClaimedAmount(), cover.getAssessedAmount()))
                .toList();
        List<ClauseFacts> clauseFacts = clauses.stream()
                .map(clause -> new ClauseFacts(clause.id(), clause.coverCode(),
                        clause.clauseRef(), clause.clauseType(),
                        clause.title(), clause.waitingPeriodDays(),
                        clause.subLimitAmount(), clause.coPayPercent(),
                        clause.clauseText()))
                .toList();
        String lossDate = claim.getLossDate() == null ? null
                : claim.getLossDate().toString();
        return new ClaimFacts(claim.getClaimNumber(), productCode, lossDate,
                claim.getLossDescription(), claim.getClaimedTotal(),
                coverFacts, clauseFacts);
    }

    private String snapshotJsonOf(Claim claim, String productCode,
            List<ClaimCover> covers) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("claimNumber", claim.getClaimNumber());
        snapshot.put("productCode", productCode);
        snapshot.put("lossDate",
                claim.getLossDate() == null ? null
                        : claim.getLossDate().toString());
        snapshot.put("claimedTotal", claim.getClaimedTotal());
        snapshot.put("stage", claim.getStage());
        snapshot.put("claimVersion", claim.getVersion());
        List<Map<String, Object>> coverRows = new ArrayList<>(covers.size());
        for (ClaimCover cover : covers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("coverCode", cover.getCoverCode());
            row.put("claimedAmount", cover.getClaimedAmount());
            row.put("assessedAmount", cover.getAssessedAmount());
            coverRows.add(row);
        }
        snapshot.put("covers", coverRows);
        return toJson(snapshot);
    }

    private static Map<String, Object> suggestionRow(String coverCode,
            String recommendation, String rationale, List<Long> clauseIds,
            BigDecimal estimatedPayable) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("coverCode", coverCode);
        row.put("recommendation", recommendation);
        row.put("rationale", rationale);
        row.put("clauseIds", clauseIds == null ? List.of() : clauseIds);
        row.put("estimatedPayable", estimatedPayable);
        return row;
    }

    private static Set<String> coverCodesOf(List<ClaimCover> covers) {
        return covers.stream().map(ClaimCover::getCoverCode)
                .collect(Collectors.toSet());
    }

    private static Set<Long> clauseIdsOf(List<ClauseView> clauses) {
        return clauses.stream().map(ClauseView::id)
                .collect(Collectors.toSet());
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException(
                    "AI snapshot is not JSON-serializable", ex);
        }
    }

    private AnalysisView viewOf(ClaimAiAnalysis row) {
        return new AnalysisView(row.getId(), row.getStatus(), row.getModel(),
                row.getClaimVersion(), suggestionsOf(row.getOutputJson()),
                row.getErrorMessage(), row.getCreatedAt());
    }

    private List<SuggestionView> suggestionsOf(String outputJson) {
        JsonNode root;
        try {
            root = JSON.readTree(
                    outputJson == null ? "{}" : outputJson);
        } catch (JacksonException ex) {
            return List.of();
        }
        JsonNode suggestions = root.get("suggestions");
        if (suggestions == null || suggestions.isNull()
                || !suggestions.isArray()) {
            return List.of();
        }
        List<SuggestionView> out = new ArrayList<>();
        for (JsonNode node : suggestions) {
            if (node == null || !node.isObject()) {
                continue;
            }
            List<Long> clauseIds = new ArrayList<>();
            JsonNode ids = node.get("clauseIds");
            if (ids != null && ids.isArray()) {
                for (JsonNode id : ids) {
                    if (id != null && id.isIntegralNumber()) {
                        clauseIds.add(id.longValue());
                    }
                }
            }
            JsonNode payable = node.get("estimatedPayable");
            BigDecimal estimated = payable != null && payable.isNumber()
                    ? payable.decimalValue()
                    : null;
            out.add(new SuggestionView(textOf(node, "coverCode"),
                    textOf(node, "recommendation"), textOf(node, "rationale"),
                    List.copyOf(clauseIds), estimated));
        }
        return List.copyOf(out);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        return value.asString();
    }

    private static String shortReason(String message) {
        if (message == null || message.isBlank()) {
            return "provider failure";
        }
        String flat = message.strip().replaceAll("\\s+", " ");
        return flat.length() <= 500 ? flat : flat.substring(0, 500);
    }
}
