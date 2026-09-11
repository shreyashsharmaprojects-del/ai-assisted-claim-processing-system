package com.claims.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.claims.ai.prompt.AiOutputValidator;
import com.claims.ai.prompt.AiOutputValidator.ValidSuggestion;
import com.claims.ai.prompt.InvalidAnalysisException;
import com.claims.ai.rules.CoverageRules;
import com.claims.ai.rules.CoverageRules.ClauseInput;
import com.claims.ai.rules.CoverageRules.CoverInput;
import com.claims.ai.rules.CoverageRules.RuleSuggestion;

/**
 * Pure-unit coverage for the AI path (no Spring): the strict model-output
 * validator plus the deterministic rules fallback.
 *
 * <p>The sub-limit cap (assessed 90000 vs sub-limit 75000 → payable 75000) and
 * the co-pay chain (10% on the capped figure → 67500.00) live here with
 * hand-built clause inputs — per the orchestrator ruling they are unreachable
 * via HTTP (the assessment guard rejects assessed &gt; policy sub-limit, and
 * Rule 1 fires first whenever a waiting-period clause is served), so the
 * integration test asserts the real NEEDS_MORE_INFO shape instead.
 */
class AiOutputValidatorTest {

    // --- validator: happy path -----------------------------------------------------

    @Test
    void happyPathExactCoversPasses() {
        String json = """
                {"suggestions":[
                  {"coverCode":"maternity","recommendation":"approve",
                   "rationale":"Within the sub-limit.","clauseIds":[7],"estimatedPayable":50000}
                ]}""";
        List<ValidSuggestion> suggestions = AiOutputValidator.validate(
                json, Set.of("MATERNITY"), Set.of(7L));

        assertEquals(1, suggestions.size());
        ValidSuggestion suggestion = suggestions.getFirst();
        assertEquals("MATERNITY", suggestion.coverCode());
        assertEquals("APPROVE", suggestion.recommendation());
        assertEquals("Within the sub-limit.", suggestion.rationale());
        assertEquals(List.of(7L), suggestion.clauseIds());
        assertEquals(new BigDecimal("50000"), suggestion.estimatedPayable());
    }

    // --- validator: violations -----------------------------------------------------

    @Test
    void inventedCoverThrows() {
        String json = """
                {"suggestions":[
                  {"coverCode":"SPACESHIP","recommendation":"APPROVE",
                   "rationale":"Looks fine.","clauseIds":[],"estimatedPayable":100}
                ]}""";
        InvalidAnalysisException ex = assertThrows(InvalidAnalysisException.class,
                () -> AiOutputValidator.validate(json, Set.of("MATERNITY"), Set.of()));
        assertTrue(ex.getMessage().contains("SPACESHIP"), ex.getMessage());
    }

    @Test
    void unknownClauseIdThrows() {
        String json = """
                {"suggestions":[
                  {"coverCode":"MATERNITY","recommendation":"APPROVE",
                   "rationale":"Looks fine.","clauseIds":[999],"estimatedPayable":100}
                ]}""";
        InvalidAnalysisException ex = assertThrows(InvalidAnalysisException.class,
                () -> AiOutputValidator.validate(json, Set.of("MATERNITY"), Set.of(7L)));
        assertTrue(ex.getMessage().contains("999"), ex.getMessage());
    }

    @Test
    void badRecommendationThrows() {
        String json = """
                {"suggestions":[
                  {"coverCode":"MATERNITY","recommendation":"MAYBE",
                   "rationale":"Looks fine.","clauseIds":[],"estimatedPayable":100}
                ]}""";
        InvalidAnalysisException ex = assertThrows(InvalidAnalysisException.class,
                () -> AiOutputValidator.validate(json, Set.of("MATERNITY"), Set.of()));
        assertTrue(ex.getMessage().contains("MAYBE"), ex.getMessage());
    }

    @Test
    void rationaleOver500CharsThrows() {
        String json = """
                {"suggestions":[
                  {"coverCode":"MATERNITY","recommendation":"APPROVE",
                   "rationale":"%s","clauseIds":[],"estimatedPayable":100}
                ]}""".formatted("r".repeat(501));
        InvalidAnalysisException ex = assertThrows(InvalidAnalysisException.class,
                () -> AiOutputValidator.validate(json, Set.of("MATERNITY"), Set.of()));
        assertTrue(ex.getMessage().contains("500"), ex.getMessage());
    }

    // --- rules: sub-limit cap -------------------------------------------------------

    @Test
    void assessedAboveSubLimitCapsAtSubLimit() {
        List<RuleSuggestion> suggestions = CoverageRules.suggest("HLTH-PLUS",
                LocalDate.of(2024, 6, 1),
                List.of(new CoverInput("MATERNITY", new BigDecimal("90000"),
                        new BigDecimal("90000"))),
                List.of(new ClauseInput(11L, "MATERNITY", "4.5", "SUB_LIMIT", null,
                        new BigDecimal("75000.00"), null)));

        assertEquals(1, suggestions.size());
        RuleSuggestion suggestion = suggestions.getFirst();
        assertEquals("MATERNITY", suggestion.coverCode());
        assertEquals("APPROVE", suggestion.recommendation());
        assertEquals(new BigDecimal("75000.00"), suggestion.estimatedPayable());
        assertTrue(suggestion.rationale().contains("75000.00"), suggestion.rationale());
        assertEquals(List.of(11L), suggestion.clauseIds());
    }

    // --- rules: co-pay chains after the cap ------------------------------------------

    @Test
    void coPayAppliesAfterSubLimitCap() {
        // 90000 assessed → 75000 cap → 10% co-pay → 67500.00. The co-pay row is
        // product-level (null cover code) so the firstOfType chain finds it when
        // includeProductLevel is true.
        List<RuleSuggestion> suggestions = CoverageRules.suggest("HLTH-PLUS",
                LocalDate.of(2024, 6, 1),
                List.of(new CoverInput("MATERNITY", new BigDecimal("90000"),
                        new BigDecimal("90000"))),
                List.of(
                        new ClauseInput(11L, "MATERNITY", "4.5", "SUB_LIMIT", null,
                                new BigDecimal("75000.00"), null),
                        new ClauseInput(9L, null, "5.1", "CO_PAY", null, null,
                                new BigDecimal("10.00"))));

        assertEquals(1, suggestions.size());
        RuleSuggestion suggestion = suggestions.getFirst();
        assertEquals(new BigDecimal("67500.00"), suggestion.estimatedPayable());
        assertTrue(suggestion.rationale().contains("co-pay"), suggestion.rationale());
        assertEquals(List.of(11L, 9L), suggestion.clauseIds());
    }

    // --- rules: waiting guard + no-figure guard ---------------------------------------

    @Test
    void waitingPeriodClauseYieldsNeedsMoreInfo() {
        List<RuleSuggestion> suggestions = CoverageRules.suggest("HLTH-PLUS",
                LocalDate.of(2024, 6, 1),
                List.of(new CoverInput("MATERNITY", new BigDecimal("50000"),
                        new BigDecimal("50000"))),
                List.of(new ClauseInput(4L, "MATERNITY", "7.1", "WAITING_PERIOD",
                        180, null, null)));

        assertEquals(1, suggestions.size());
        assertEquals("NEEDS_MORE_INFO", suggestions.getFirst().recommendation());
        assertNull(suggestions.getFirst().estimatedPayable());
    }

    @Test
    void nullAssessedAmountYieldsNeedsMoreInfo() {
        List<RuleSuggestion> suggestions = CoverageRules.suggest("HLTH-PLUS",
                LocalDate.of(2024, 6, 1),
                List.of(new CoverInput("MATERNITY", new BigDecimal("50000"), null)),
                List.of());

        assertEquals(1, suggestions.size());
        assertEquals("NEEDS_MORE_INFO", suggestions.getFirst().recommendation());
        assertNull(suggestions.getFirst().estimatedPayable());
    }

    // --- rules: passthrough when no signal ---------------------------------------------

    @Test
    void assessedFigureStandsWithNoClauses() {
        List<RuleSuggestion> suggestions = CoverageRules.suggest("HLTH-PLUS",
                LocalDate.of(2024, 6, 1),
                List.of(new CoverInput("MATERNITY", new BigDecimal("50000"),
                        new BigDecimal("50000"))),
                List.of());

        assertEquals(1, suggestions.size());
        assertEquals("APPROVE", suggestions.getFirst().recommendation());
        assertEquals(new BigDecimal("50000.00"), suggestions.getFirst().estimatedPayable());
        assertNotNull(suggestions.getFirst().rationale());
    }
}
