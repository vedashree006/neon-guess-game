package com.vedashree.game.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @Test
    void correctPasswordVerifies() {
        assertTrue(PasswordHasher.verify(PASSWORD, PasswordHasher.hash(PASSWORD, 1000)));
    }

    @Test
    void wrongPasswordIsRejected() {
        String stored = PasswordHasher.hash(PASSWORD, 1000);
        assertFalse(PasswordHasher.verify("wrong password!!".toCharArray(), stored));
    }

    @Test
    void saltMakesEveryHashDifferent() {
        assertNotEquals(PasswordHasher.hash(PASSWORD, 1000), PasswordHasher.hash(PASSWORD, 1000));
    }

    @Test
    void storedFormatIsEnvFileSafe() {
        String stored = PasswordHasher.hash(PASSWORD, 1000);
        assertTrue(stored.startsWith("pbkdf2_sha256:1000:"));
        assertFalse(stored.contains("$"));
        assertFalse(stored.contains("="));
        assertEquals(4, stored.split(":").length);
    }

    @Test
    void malformedHashesNeverThrowAndNeverVerify() {
        for (String bad : new String[] {"", "plain", "a:b:c:d", "pbkdf2_sha256:x:AA:AA",
                "pbkdf2_sha256:0:AA:AA", "pbkdf2_sha256:1000::", "pbkdf2_sha256:99999999999:AA:AA",
                "pbkdf2_sha256:1000:!!!:???"}) {
            assertFalse(PasswordHasher.verify(PASSWORD, bad), bad);
        }
        assertFalse(PasswordHasher.verify(null, "x"));
        assertFalse(PasswordHasher.verify(PASSWORD, null));
    }

    @Test
    void emptyPasswordCannotBeHashed() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(new char[0], 1000));
    }
}
