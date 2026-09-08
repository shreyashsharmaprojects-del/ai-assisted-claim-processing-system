package com.claims.claim;

import java.math.BigDecimal;
import java.util.List;

/** V2-5: per-cover assessed figures moving VERIFICATION→DECISION. */
public record AssessmentInput(List<AssessedCover> covers, String rationale) {
    public record AssessedCover(String coverCode, BigDecimal assessedAmount) {
    }
}
