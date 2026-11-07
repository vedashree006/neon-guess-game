package com.vedashree.game.security;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing using only the JDK.
 * Stored format: {@code pbkdf2_sha256:<iterations>:<salt, base64url>:<hash, base64url>}
 * (colon separated so the value is safe in .env files and docker-compose without escaping '$').
 *
 * <p>Create a hash for ADMIN_PASSWORD_HASH with:
 * {@code java -cp target/classes com.vedashree.game.security.PasswordHasher}
 */
public final class PasswordHasher {
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_PASSWORD_LENGTH = 12;
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() { }

    public static String hash(char[] password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    public static String hash(char[] password, int iterations) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("Unsupported iteration count");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + ":" + iterations + ":" + encoder.encodeToString(salt) + ":" + encoder.encodeToString(derived);
    }

    /** Constant-time check. Returns false (never throws) for wrong passwords or malformed hashes. */
    public static boolean verify(char[] password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length == 0 || expected.length == 0) {
                return false;
            }
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        return derive(password, salt, iterations, KEY_BITS);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available in this JVM", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Command line helper: reads a password without echo (or from stdin when piped) and prints the hash. */
    public static void main(String[] args) throws IOException {
        Console console = System.console();
        char[] password;
        if (console != null) {
            password = console.readPassword("New admin password (min %d characters): ", MIN_PASSWORD_LENGTH);
            char[] again = console.readPassword("Repeat password: ");
            if (password == null || again == null || !Arrays.equals(password, again)) {
                System.err.println("Passwords do not match.");
                System.exit(1);
                return;
            }
        } else {
            String line = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
            password = line.toCharArray();
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            System.err.println("Password must have at least " + MIN_PASSWORD_LENGTH + " characters.");
            System.exit(1);
            return;
        }
        System.out.println(hash(password));
        Arrays.fill(password, '\0');
    }
}
