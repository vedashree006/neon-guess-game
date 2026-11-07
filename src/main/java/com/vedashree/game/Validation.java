package com.vedashree.game;

import java.util.Locale;
import java.util.regex.Pattern;

/** Central place for all request-parameter validation. Every method is pure and testable. */
public final class Validation {
    public static final int MAX_NAME_LENGTH = 20;
    public static final String DEFAULT_NAME = "Player";
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");
    private static final Pattern NAME =
            Pattern.compile("[\\p{L}\\p{N} _.\\-]{1," + MAX_NAME_LENGTH + "}");

    private Validation() { }

    /** Parses a guess such as "17". Accepts only whole numbers in the game range. */
    public static int parseGuess(String raw) {
        String message = "Enter a whole number from " + GameLogic.MIN + " to " + GameLogic.MAX + ".";
        if (raw == null || raw.isBlank()) {
            throw new InvalidInputException(message);
        }
        String trimmed = raw.trim();
        if (!DIGITS.matcher(trimmed).matches()) {
            throw new InvalidInputException(message);
        }
        int value = Integer.parseInt(trimmed);
        if (value < GameLogic.MIN || value > GameLogic.MAX) {
            throw new InvalidInputException(message);
        }
        return value;
    }

    /** Returns a cleaned player name, or "Player" when none was supplied. */
    public static String parsePlayerName(String raw) {
        if (raw == null) {
            return DEFAULT_NAME;
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return DEFAULT_NAME;
        }
        if (!NAME.matcher(cleaned).matches()) {
            throw new InvalidInputException("Name can use letters, digits, spaces, '_', '-' and '.' "
                    + "(max " + MAX_NAME_LENGTH + " characters).");
        }
        return cleaned;
    }

    /** Parses the leaderboard size. Defaults to 10, allowed range 1 to 50. */
    public static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        String trimmed = raw.trim();
        if (!DIGITS.matcher(trimmed).matches()) {
            throw new InvalidInputException("limit must be a whole number from 1 to " + MAX_LIMIT);
        }
        int value = Integer.parseInt(trimmed);
        if (value < 1 || value > MAX_LIMIT) {
            throw new InvalidInputException("limit must be a whole number from 1 to " + MAX_LIMIT);
        }
        return value;
    }

    /** Maps the public sort name to a fixed enum, so user input never reaches the SQL text. */
    public static Database.Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Database.Sort.RECENT;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "recent":
                return Database.Sort.RECENT;
            case "top":
                return Database.Sort.TOP;
            default:
                throw new InvalidInputException("sort must be 'recent' or 'top'");
        }
    }
}
