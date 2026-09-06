package com.claims.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClaimNumberFormatterTest {

    @Test
    void formatsSequenceValuesAsPaddedClaimNumbers() {
        assertEquals("CLM-000001", ClaimNumberFormatter.format(1));
        assertEquals("CLM-000042", ClaimNumberFormatter.format(42));
        assertEquals("CLM-123456", ClaimNumberFormatter.format(123456));
    }
}
