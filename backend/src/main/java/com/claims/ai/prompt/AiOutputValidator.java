package com.claims.ai.prompt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Subagent I: strict validation of DECISION-stage model JSON ({@code AiPromptBuilder}).
 *
 * <p>No Spring, no I/O: the only input is the raw model string plus the sets
 * the orchestrator served (claim cover codes, served clause ids). Expected
 * shape:
 * {@code {suggestions:[{coverCode, recommendation, rationale, clauseIds, estimatedPayable}]}}.
 * Any violation throws {@link InvalidAnalysisException} with the violation
 * named; the happy path returns the validated list (defensive copies,
 * rationales trimmed, cover codes upper-cased).
 */
public final class AiOutputValidator {

    /** Allowed recommendation values, exactly as the prompt contract states. */
    public static final Set<String> RECOMMENDATIONS = Set.of("APPROVE",
            "REJECT", "NEEDS_MORE_INFO");

    /** Rationale length budget, mirrored from the prompt contract. */
    public static final int MAX_RATIONALE_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One validated per-cover suggestion. {@code coverCode} is upper-cased;
     * {@code estimatedPayable} (INR) is {@code null} when the model could not
     * estimate it.
     */
    public record ValidSuggestion(String coverCode, String recommendation,
            String rationale, List<Long> clauseIds,
            BigDecimal estimatedPayable) {
    }

    private AiOutputValidator() {
    }

    /**
     * Validates the raw model reply against the served claim covers and
     * served clause ids.
     *
     * @param modelJson raw model output, expected to be a JSON object with a
     *        {@code suggestions} array
     * @param claimCoverCodes cover codes of the claim (matched
     *        case-insensitively); the reply must cover EXACTLY these, each once
     * @param servedClauseIds clause ids served to the model; cited ids must
     *        be drawn ONLY from this set
     * @return validated suggestions in reply order
     * @throws InvalidAnalysisException naming the first violation found
     */
    public static List<ValidSuggestion> validate(String modelJson,
            Set<String> claimCoverCodes, Set<Long> servedClauseIds) {
        if (modelJson == null || modelJson.isBlank()) {
            throw new InvalidAnalysisException(
                    "AI analysis is not valid JSON: empty response.");
        }
        Set<String> expected = normalizeCovers(claimCoverCodes);
        Set<Long> served = servedClauseIds == null ? Set.of()
                : Set.copyOf(servedClauseIds);

        JsonNode root;
        try {
            root = JSON.readTree(modelJson.strip());
        } catch (JacksonException ex) {
            throw new InvalidAnalysisException(
                    "AI analysis is not valid JSON: " + oneLine(ex.getMessage()));
        }
        if (!root.isObject()) {
            throw new InvalidAnalysisException(
                    "AI analysis must be a JSON object with a \"suggestions\" array.");
        }
        JsonNode suggestions = root.get("suggestions");
        if (suggestions == null || suggestions.isNull()
                || !suggestions.isArray()) {
            throw new InvalidAnalysisException(
                    "AI analysis is missing a \"suggestions\" array.");
        }
        List<JsonNode> rows = new ArrayList<>();
        suggestions.forEach(rows::add);
        if (expected.isEmpty() && rows.isEmpty()) {
            return List.of();
        }
        if (rows.size() != expected.size() || expected.isEmpty()) {
            throw new InvalidAnalysisException(
                    "AI analysis must cover exactly the " + expected.size()
                            + " claim cover(s) but has " + rows.size()
                            + " suggestion(s).");
        }

        List<ValidSuggestion> out = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            String where = "suggestion[" + i + "]";
            if (row == null || !row.isObject()) {
                throw new InvalidAnalysisException(
                        "AI analysis " + where + " must be a JSON object.");
            }
            String cover = textOf(row, "coverCode");
            if (cover == null || cover.isBlank()) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " is missing a coverCode.");
            }
            String normalized = cover.strip().toUpperCase(Locale.ROOT);
            if (!expected.contains(normalized)) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " invents cover \"" + cover.strip()
                        + "\"; expected one of " + expected + ".");
            }
            if (!seen.add(normalized)) {
                throw new InvalidAnalysisException("AI analysis repeats cover \""
                        + normalized + "\"; each claim cover needs exactly one suggestion.");
            }
            String recommendation = textOf(row, "recommendation");
            if (recommendation == null || recommendation.isBlank()) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + normalized
                        + "\") is missing a recommendation.");
            }
            String rec = recommendation.strip().toUpperCase(Locale.ROOT);
            if (!RECOMMENDATIONS.contains(rec)) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + normalized
                        + "\") has invalid recommendation \""
                        + recommendation.strip()
                        + "\"; expected one of APPROVE, REJECT, NEEDS_MORE_INFO.");
            }
            String rationale = textOf(row, "rationale");
            if (rationale == null || rationale.isBlank()) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + normalized
                        + "\") has a blank rationale.");
            }
            String trimmed = rationale.strip();
            if (trimmed.length() > MAX_RATIONALE_CHARS) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + normalized + "\") rationale is "
                        + trimmed.length()
                        + " chars, exceeding the 500-char limit.");
            }
            List<Long> clauseIds = clauseIdsOf(row, where, normalized, served);
            BigDecimal payable = payableOf(row, where, normalized);
            out.add(new ValidSuggestion(normalized, rec, trimmed,
                    List.copyOf(clauseIds), payable));
        }

        if (!seen.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(seen);
            throw new InvalidAnalysisException(
                    "AI analysis is missing suggestions for cover(s) " + missing
                            + ".");
        }
        return List.copyOf(out);
    }

    private static List<Long> clauseIdsOf(JsonNode row, String where,
            String cover, Set<Long> served) {
        JsonNode node = row.get("clauseIds");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InvalidAnalysisException("AI analysis " + where
                    + " (cover \"" + cover
                    + "\") clauseIds must be an array.");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode entry : node) {
            Long id = longOf(entry);
            if (id == null) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + cover
                        + "\") clauseIds must be integer clause ids.");
            }
            if (!served.contains(id)) {
                throw new InvalidAnalysisException("AI analysis " + where
                        + " (cover \"" + cover + "\") cites unserved clause id "
                        + id + ".");
            }
            ids.add(id);
        }
        return ids;
    }

    private static BigDecimal payableOf(JsonNode row, String where,
            String cover) {
        JsonNode node = row.get("estimatedPayable");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isValueNode()) {
            throw new InvalidAnalysisException("AI analysis " + where
                    + " (cover \"" + cover
                    + "\") estimatedPayable must be a number >= 0 or null.");
        }
        BigDecimal amount;
        try {
            // asString() coerces numeric nodes to text; stringValue() is
            // strict and throws on non-textual nodes in Jackson 3.
            amount = new BigDecimal(node.asString().strip());
        } catch (NumberFormatException | NullPointerException | ArithmeticException ex) {
            throw new InvalidAnalysisException("AI analysis " + where
                    + " (cover \"" + cover
                    + "\") estimatedPayable must be a number >= 0 or null.");
        }
        if (amount.signum() < 0) {
            throw new InvalidAnalysisException("AI analysis " + where
                    + " (cover \"" + cover + "\") estimatedPayable " + amount
                    + " must be >= 0 or null.");
        }
        return amount;
    }

    private static String textOf(JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asString();
    }

    private static Long longOf(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        // Integral model numbers parse directly; anything else (decimals,
        // text, booleans) is rejected rather than coerced.
        if (!node.isIntegralNumber()) {
            return null;
        }
        try {
            return Long.valueOf(node.asString().strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Set<String> normalizeCovers(Set<String> covers) {
        if (covers == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String cover : covers) {
            if (cover != null && !cover.isBlank()) {
                out.add(cover.strip().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return "unparseable response.";
        }
        return message.strip().replaceAll("\\s+", " ");
    }
}
