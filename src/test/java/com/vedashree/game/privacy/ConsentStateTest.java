package com.vedashree.game.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsentStateTest {
    @Test
    void roundTripsThroughTheCookieFormat() {
        ConsentState state = ConsentState.decide(3, true, false, 1_730_000_000L, null);
        ConsentState parsed = ConsentState.parse(state.serialize()).orElseThrow();
        assertEquals(state, parsed);
        assertTrue(parsed.analytics());
        assertFalse(parsed.preferences());
        assertEquals(3, parsed.policyVersion());
    }

    @Test
    void existingConsentIdIsReused() {
        String id = UUID.randomUUID().toString();
        assertEquals(id, ConsentState.decide(1, false, false, 1_730_000_000L, id).consentId());
    }

    @Test
    void anythingMalformedIsNoDecision() {
        String id = UUID.randomUUID().toString();
        for (String bad : new String[] {"", "1", "1.2.0.1730000000." + id, "1.1.0.12." + id,
                "x.1.0.1730000000." + id, "1.1.0.1730000000.not-a-uuid", "1.1.0.1730000000." + id + ".extra",
                "1.1.0.1730000000." + id.toUpperCase(), "1.1.0.1730000000." + id + "<script>"}) {
            assertFalse(ConsentState.parse(bad).isPresent(), bad);
        }
        assertFalse(ConsentState.parse(null).isPresent());
    }

    @Test
    void decisionOnlyCountsForTheCurrentPolicyVersion() {
        ConsentState state = ConsentState.decide(1, true, true, 1_730_000_000L, null);
        assertTrue(state.isCurrent(1));
        assertFalse(state.isCurrent(2));
    }
}
