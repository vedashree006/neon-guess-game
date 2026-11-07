package com.vedashree.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ValidationTest {

    @Test
    void validGuessesAreParsed() {
        assertEquals(1, Validation.parseGuess("1"));
        assertEquals(50, Validation.parseGuess("50"));
        assertEquals(7, Validation.parseGuess(" 7 "));
        assertEquals(7, Validation.parseGuess("007"));
    }

    @Test
    void invalidGuessesAreRejected() {
        String[] bad = {null, "", "   ", "abc", "1.5", "0", "51", "-3", "+5", "1e1",
            "99999999999", "5 5", "<script>", "\u0665"};
        for (String value : bad) {
            assertThrows(InvalidInputException.class, () -> Validation.parseGuess(value),
                    "should reject: " + value);
        }
    }

    @Test
    void blankNameFallsBackToDefault() {
        assertEquals("Player", Validation.parsePlayerName(null));
        assertEquals("Player", Validation.parsePlayerName("   "));
    }

    @Test
    void validNamesAreCleaned() {
        assertEquals("Veda Shree", Validation.parsePlayerName("  Veda    Shree "));
        assertEquals("Zo\u00eb_99", Validation.parsePlayerName("Zo\u00eb_99"));
        assertEquals("a.b-c", Validation.parsePlayerName("a.b-c"));
        // control characters such as newlines are collapsed to one space, never kept
        assertEquals("name new line", Validation.parsePlayerName("name\nnew\r\n\tline"));
    }

    @Test
    void unsafeOrTooLongNamesAreRejected() {
        String[] bad = {"<b>hi</b>", "a\"b", "x;DROP TABLE", "abcdefghijklmnopqrstu", "a/b", "a&b"};
        for (String value : bad) {
            assertThrows(InvalidInputException.class, () -> Validation.parsePlayerName(value),
                    "should reject: " + value);
        }
    }

    @Test
    void limitDefaultsAndBounds() {
        assertEquals(10, Validation.parseLimit(null));
        assertEquals(10, Validation.parseLimit(""));
        assertEquals(1, Validation.parseLimit("1"));
        assertEquals(50, Validation.parseLimit("50"));
        for (String value : new String[] {"0", "51", "-1", "abc", "1000"}) {
            assertThrows(InvalidInputException.class, () -> Validation.parseLimit(value));
        }
    }

    @Test
    void sortIsWhitelisted() {
        assertEquals(Database.Sort.RECENT, Validation.parseSort(null));
        assertEquals(Database.Sort.RECENT, Validation.parseSort("recent"));
        assertEquals(Database.Sort.TOP, Validation.parseSort("TOP"));
        assertThrows(InvalidInputException.class, () -> Validation.parseSort("score; DROP TABLE x"));
    }
}
