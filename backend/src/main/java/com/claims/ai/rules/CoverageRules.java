package com.claims.ai.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic rules-only fallback for per-cover AI suggestions.
 *
 * <p>Used when the language-model provider is unreachable or unconfigured.
 * Every decision is conservative arithmetic over the claim snapshot and the
 * served clauses - no model behaviour, no text generation. The service layer
 * marks the surrounding response DEGRADED; this class only produces the
 * suggestion content.
 *
 * <p>Pure static utility with zero framework dependencies.
 */
public final class CoverageRules {

    private CoverageRules() {
    }

    /** One claimed cover of the snapshot. Amounts may be null. */
    public record CoverInput(String coverCode, BigDecimal claimedAmount,
            BigDecimal assessedAmount) {
    }

    /** One served clause of the snapshot. Numeric fields may be null. */
    public record ClauseInput(Long id, String coverCode, String clauseRef,
            String clauseType, Integer waitingPeriodDays,
            BigDecimal subLimitAmount, BigDecimal coPayPercent) {
    }

    /** Deterministic suggestion for one cover. */
    public record RuleSuggestion(String coverCode, String recommendation,
            String rationale, List<Long> clauseIds,
            BigDecimal estimatedPayable) {
    }

    /**
     * Suggests one outcome per cover, in input order. Never returns null
     * lists; every amount is scale 2 HALF_UP. {@code productCode} and
     * {@code lossDate} are accepted for signature stability (the waiting rule
     * deliberately refuses to decide without the policy inception date, which
     * the snapshot does not carry).
     */
    public static List<RuleSuggestion> suggest(String productCode,
            LocalDate lossDate, List<CoverInput> covers,
            List<ClauseInput> clauses) {
        if (covers == null || covers.isEmpty()) {
            return List.of();
        }
        List<ClauseInput> served = clauses == null ? List.of() : clauses;
        List<RuleSuggestion> out = new ArrayList<>(covers.size());
        for (CoverInput cover : covers) {
            if (cover == null) {
                continue;
            }
            out.add(suggestForCover(cover, served));
        }
        return Collections.unmodifiableList(out);
    }

    private static RuleSuggestion suggestForCover(CoverInput cover,
            List<ClauseInput> clauses) {
        List<Long> cited = new ArrayList<>(2);

        // Rule 1 - waiting period: without the policy inception date the
        // period can never be evaluated from the snapshot, so it always
        // yields NEEDS_MORE_INFO (never an approve/reject).
        ClauseInput waiting = firstOfType(clauses, cover.coverCode(),
                "WAITING_PERIOD", true);
        if (waiting != null && waiting.waitingPeriodDays() != null) {
            cite(cited, waiting);
            return new RuleSuggestion(cover.coverCode(), "NEEDS_MORE_INFO",
                    capped("Waiting period of " + waiting.waitingPeriodDays()
                            + " days (clause " + refOf(waiting)
                            + ") needs the policy inception date to evaluate"
                            + " - confirm continuous cover."),
                    List.copyOf(cited), null);
        }

        BigDecimal assessed = scale(cover.assessedAmount());

        // Rule 2 - cover-specific sub-limit (only when an assessed figure
        // exists to cap).
        ClauseInput subLimit = firstOfType(clauses, cover.coverCode(),
                "SUB_LIMIT", false);
        if (subLimit != null && subLimit.subLimitAmount() != null
                && assessed != null) {
            BigDecimal cap = scale(subLimit.subLimitAmount());
            cite(cited, subLimit);
            BigDecimal payable;
            String rationale;
            if (assessed.compareTo(cap) > 0) {
                payable = cap;
                rationale = "Assessed " + money(assessed) + " exceeds the "
                        + refOf(subLimit) + " sub-limit of " + money(cap)
                        + "; payable capped at the sub-limit.";
            } else {
                payable = assessed;
                rationale = "Within the " + refOf(subLimit)
                        + " sub-limit of " + money(cap) + ".";
            }
            // Rule 3 - co-pay chains after a sub-limit decision.
            ClauseInput coPay = firstOfType(clauses, cover.coverCode(),
                    "CO_PAY", true);
            if (coPay != null && coPay.coPayPercent() != null) {
                payable = applyCoPay(payable, coPay.coPayPercent());
                rationale += " Less " + pct(coPay.coPayPercent())
                        + "% co-pay (clause " + refOf(coPay) + ").";
                cite(cited, coPay);
            }
            return new RuleSuggestion(cover.coverCode(), "APPROVE",
                    capped(rationale + " Subject to adjuster review."),
                    List.copyOf(cited), payable);
        }

        // Rule 5 - no assessed figure: nothing arithmetic to decide.
        if (assessed == null) {
            return new RuleSuggestion(cover.coverCode(), "NEEDS_MORE_INFO",
                    capped("No assessed amount recorded for this cover yet"
                            + " - complete assessment before deciding."),
                    List.of(), null);
        }

        // Rule 4 - no signal: the assessed figure stands.
        return new RuleSuggestion(cover.coverCode(), "APPROVE",
                capped("No sub-limit, waiting-period, or co-pay clause served"
                        + " for this cover; assessed figure stands subject to"
                        + " adjuster review."),
                List.of(), assessed);
    }

    /**
     * First served clause of the given type visible to the cover. A null or
     * blank clause cover code means product-level (visible to every cover);
     * otherwise the clause must name this cover. Type comparison is
     * case-insensitive.
     */
    private static ClauseInput firstOfType(List<ClauseInput> clauses,
            String coverCode, String type, boolean includeProductLevel) {
        for (ClauseInput c : clauses) {
            if (c == null || !isType(c, type)) {
                continue;
            }
            if (isProductLevel(c)) {
                if (includeProductLevel) {
                    return c;
                }
                continue;
            }
            if (coverCode != null && c.coverCode().equals(coverCode)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isProductLevel(ClauseInput c) {
        return c.coverCode() == null || c.coverCode().isBlank();
    }

    private static boolean isType(ClauseInput c, String type) {
        return c.clauseType() != null
                && c.clauseType().trim().equalsIgnoreCase(type);
    }

    private static void cite(List<Long> cited, ClauseInput clause) {
        if (clause.id() != null) {
            cited.add(clause.id());
        }
    }

    private static String refOf(ClauseInput clause) {
        if (clause.clauseRef() != null && !clause.clauseRef().isBlank()) {
            return clause.clauseRef();
        }
        return "id " + clause.id();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal scaled) {
        return scaled.toPlainString();
    }

    private static String pct(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal applyCoPay(BigDecimal payable,
            BigDecimal percent) {
        BigDecimal factor = BigDecimal.ONE.subtract(percent
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        BigDecimal result =
                payable.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        return result.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO.setScale(2)
                : result;
    }

    private static String capped(String rationale) {
        return rationale.length() > 300 ? rationale.substring(0, 300)
                : rationale;
    }
}
