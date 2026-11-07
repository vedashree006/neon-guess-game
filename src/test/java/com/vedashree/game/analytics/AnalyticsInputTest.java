package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.InvalidInputException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsInputTest {
    @Test
    void onlyPageViewEventsAreAccepted() {
        assertEquals("page_view", AnalyticsInput.parseEventType("page_view"));
        for (String bad : new String[] {null, "", "game_won", "PAGE_VIEW", "page_view "}) {
            assertThrows(InvalidInputException.class, () -> AnalyticsInput.parseEventType(bad));
        }
    }

    @Test
    void eventIdMustBeAUuid() {
        String id = UUID.randomUUID().toString();
        assertEquals(id, AnalyticsInput.parseEventId(id.toUpperCase()));
        for (String bad : new String[] {null, "", "1", "' OR 1=1 --", id + "x", id.replace('-', '_')}) {
            assertThrows(InvalidInputException.class, () -> AnalyticsInput.parseEventId(bad));
        }
    }

    @Test
    void pathsHaveNoQueryStringFragmentOrOddCharacters() {
        assertEquals("/", AnalyticsInput.parsePath("/"));
        assertEquals("/neon-guess-game/privacy.html", AnalyticsInput.parsePath("/neon-guess-game/privacy.html"));
        for (String bad : new String[] {null, "", "x", "/a?token=1", "/a#b", "/a b", "/<script>", "/%41", "/" + "a".repeat(100)}) {
            assertThrows(InvalidInputException.class, () -> AnalyticsInput.parsePath(bad), String.valueOf(bad));
        }
    }

    @Test
    void rangeDefaultsToSevenDaysAndRejectsUnknownValues() {
        assertEquals(TimeRange.LAST_7_DAYS, AnalyticsInput.parseRange(null));
        assertEquals(TimeRange.LAST_24_HOURS, AnalyticsInput.parseRange("24h"));
        assertEquals(TimeRange.LAST_30_DAYS, AnalyticsInput.parseRange("30d"));
        assertThrows(InvalidInputException.class, () -> AnalyticsInput.parseRange("1y"));
        assertThrows(InvalidInputException.class, () -> AnalyticsInput.parseRange("7d; DROP TABLE x"));
    }

    @Test
    void visitorIdsAreRandomAndWellFormed() {
        String a = AnalyticsInput.newVisitorId();
        assertEquals(22, a.length());
        assertTrue(AnalyticsInput.isValidVisitorId(a));
        assertNotEquals(a, AnalyticsInput.newVisitorId());
        assertFalse(AnalyticsInput.isValidVisitorId("short"));
        assertFalse(AnalyticsInput.isValidVisitorId("AAAAAAAAAAAAAAAAAAAAA'"));
        assertFalse(AnalyticsInput.isValidVisitorId(null));
    }
}
