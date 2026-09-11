package com.claims.ai.prompt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Subagent I: pure prompt assembly for DECISION-stage AI advisory.
 *
 * <p>No Spring, no I/O: deterministic {@link StringBuilder} rendering only.
 * The orchestrator assembles a {@link ClaimFacts} from the claim, its
 * per-cover rows and the loss-date-selected clauses (mirroring
 * {@code PolicyClauseService.ClauseView}; a {@code null} clause
 * {@code coverCode} means a product-level clause), then serves
 * {@link #systemPrompt()} + {@link #userPrompt(ClaimFacts)} to the model.
 * The model's raw JSON reply is checked by {@link AiOutputValidator}.
 */
public final class AiPromptBuilder {

    /** Max clause prose characters served to the model, per clause. */
    public static final int MAX_CLAUSE_TEXT_CHARS = 800;

    /** Marker appended when clause prose is cut to fit the budget. */
    public static final String TRUNCATION_MARKER = "…[truncated]";

    /** One covered claim-cover row: amounts in INR. */
    public record CoverFacts(String coverCode, BigDecimal claimedAmount,
            BigDecimal assessedAmount) {
    }

    /**
     * One loss-date-selected clause. {@code coverCode} is {@code null} for a
     * product-level clause. The text is expected pre-truncated to
     * {@value #MAX_CLAUSE_TEXT_CHARS} chars; the builder truncates again
     * defensively, so passing full text is equally safe.
     */
    public record ClauseFacts(Long id, String coverCode, String clauseRef,
            String clauseType, String title, Integer waitingPeriodDays,
            BigDecimal subLimitAmount, BigDecimal coPayPercent,
            String clauseTextTruncatedTo800Chars) {
    }

    /**
     * Everything the model may see about one claim. {@code lossDate} is an
     * ISO-8601 date string; amounts are in INR.
     */
    public record ClaimFacts(String claimNumber, String productCode,
            String lossDate, String lossDescription, BigDecimal claimedTotal,
            List<CoverFacts> covers, List<ClauseFacts> clauses) {
    }

    private AiPromptBuilder() {
    }

    /**
     * Role + output contract. JSON-only; cite only served ids; never invent
     * covers or clauses; rationales short, factual, in INR; advisory only —
     * the adjuster decides.
     */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    private static final String SYSTEM_PROMPT = """
            You are a cautious insurance-coverage assistant advising a claim adjuster \
            at the DECISION stage. Your output is ADVISORY ONLY; the adjuster decides.
            Respond with STRICT JSON and nothing else: no prose, no markdown, no code fences.
            Exact shape: {"suggestions":[{"coverCode":"...","recommendation":"APPROVE|REJECT|NEEDS_MORE_INFO",\
            "rationale":"...","clauseIds":[<served clause ids>],"estimatedPayable":<number>=0|null>]}.
            Rules: emit exactly one suggestion per cover listed in the user message — never invent, \
            merge, drop or rename covers (reuse each coverCode verbatim). clauseIds may contain ONLY \
            clause ids listed in the user message (empty array when none applies); never cite an \
            unserved id. Ground every waiting-period / sub-limit / co-pay number in the TYPED VALUES \
            served with each clause, never in numbers guessed from prose. Keep each rationale factual, \
            under 500 characters, with amounts in INR. estimatedPayable is the INR amount you estimate \
            payable under that cover (>= 0), or null when it cannot be estimated from the facts served.\
            """;

    /**
     * Renders the claim, its covers and its served clauses compactly. Clause
     * prose is flattened to one line and cut to {@value #MAX_CLAUSE_TEXT_CHARS}
     * chars (marked with {@value #TRUNCATION_MARKER}).
     */
    public static String userPrompt(ClaimFacts facts) {
        Objects.requireNonNull(facts, "facts");
        List<CoverFacts> covers = facts.covers() == null ? List.of()
                : facts.covers();
        List<ClauseFacts> clauses = facts.clauses() == null ? List.of()
                : facts.clauses();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("DECISION-stage coverage advisory request. ")
                .append("Reply with STRICT JSON only (no prose, no markdown): ")
                .append("{\"suggestions\":[{\"coverCode\":\"...\",\"recommendation\":")
                .append("\"APPROVE|REJECT|NEEDS_MORE_INFO\",\"rationale\":\"...<=500 chars...\",")
                .append("\"clauseIds\":[<served ids only>],")
                .append("\"estimatedPayable\":<number>=0|null>}]}. ")
                .append("Exactly one suggestion per COVER below (no more, no fewer; ")
                .append("coverCode verbatim). rationale factual, <= 500 chars, amounts in INR.\n");
        sb.append("Claim ").append(orDash(facts.claimNumber()))
                .append(" | product ").append(orDash(facts.productCode()))
                .append(" | loss ").append(orDash(facts.lossDate()))
                .append(" | claimed total ").append(inr(facts.claimedTotal()))
                .append('\n');
        String loss = oneLine(facts.lossDescription());
        sb.append("Loss: ").append(loss.isEmpty() ? "(none provided)" : loss)
                .append('\n');

        long coverCount = covers.stream().filter(Objects::nonNull).count();
        sb.append("Covers (").append(coverCount).append("):\n");
        if (coverCount == 0) {
            sb.append("none\n");
        }
        for (CoverFacts cover : covers) {
            if (cover == null) {
                continue;
            }
            sb.append("- ").append(orDash(cover.coverCode()))
                    .append(": claimed ").append(inr(cover.claimedAmount()))
                    .append(", assessed ").append(inr(cover.assessedAmount()))
                    .append('\n');
        }

        long clauseCount = clauses.stream().filter(Objects::nonNull).count();
        sb.append("Clauses in force on loss date (").append(clauseCount)
                .append("):\n");
        if (clauseCount == 0) {
            sb.append("none\n");
        }
        for (ClauseFacts clause : clauses) {
            if (clause == null) {
                continue;
            }
            String scope = clause.coverCode() == null
                    || clause.coverCode().isBlank() ? "product-level"
                            : clause.coverCode().strip();
            sb.append("- [id ").append(String.valueOf(clause.id()))
                    .append("] (").append(scope).append(") ")
                    .append(orDash(clause.clauseRef()))
                    .append(" [").append(orDash(clause.clauseType()))
                    .append("] \"").append(oneLine(clause.title()))
                    .append("\" | waiting ")
                    .append(clause.waitingPeriodDays() == null ? "none"
                            : clause.waitingPeriodDays() + "d")
                    .append(" | sub-limit ")
                    .append(inr(clause.subLimitAmount())).append(" | co-pay ")
                    .append(clause.coPayPercent() == null ? "none"
                            : clause.coPayPercent().toPlainString() + "%")
                    .append(" | ")
                    .append(truncateClauseText(clause.clauseTextTruncatedTo800Chars()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Flattens clause prose and enforces the per-clause character budget. */
    static String truncateClauseText(String text) {
        String flat = oneLine(text);
        if (flat.length() <= MAX_CLAUSE_TEXT_CHARS) {
            return flat;
        }
        return flat.substring(0, MAX_CLAUSE_TEXT_CHARS) + TRUNCATION_MARKER;
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ");
    }

    private static String orDash(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        return text.strip();
    }

    private static String inr(BigDecimal amount) {
        if (amount == null) {
            return "n/a";
        }
        return "INR " + amount.toPlainString();
    }
}
