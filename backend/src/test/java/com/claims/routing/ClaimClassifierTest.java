package com.claims.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for FNOL classification (product code -> route level via the config table). */
class ClaimClassifierTest {

    private static final List<AuthorityConfig> CONFIGS = List.of(
            new AuthorityConfig("HOME", "L1"),
            new AuthorityConfig("AUTO", "L2"));

    @Test
    void routesHomeProductToL1() {
        assertEquals("L1", ClaimClassifier.routeLevelFor("HOME", CONFIGS));
    }

    @Test
    void routesAutoProductToL2() {
        assertEquals("L2", ClaimClassifier.routeLevelFor("AUTO", CONFIGS));
    }

    @Test
    void unknownProductCodeReturnsNull() {
        assertNull(ClaimClassifier.routeLevelFor("MARINE", CONFIGS));
    }
}
