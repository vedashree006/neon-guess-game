package com.vedashree.game.analytics;

import com.vedashree.game.InvalidInputException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validation for everything the browser sends to the analytics endpoints. Pure and testable. */
public final class AnalyticsInput {
    public static final String PAGE_VIEW = "page_view";

    private static final Pattern EVENT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Path only: no query string or fragment, so personal data in URLs can never be stored. */
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9/_.\\-]{0,99}");
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9_\\-]{22}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AnalyticsInput() { }

    public static String parseEventType(String raw) {
        if (PAGE_VIEW.equals(raw)) {
            return PAGE_VIEW;
        }
        throw new InvalidInputException("Unsupported event type.");
    }

    public static String parseEventId(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EVENT_ID.matcher(normalized).matches()) {
            throw new InvalidInputException("eventId must be a UUID.");
        }
        return normalized;
    }

    public static String parsePath(String raw) {
        if (raw == null || !PATH.matcher(raw).matches()) {
            throw new InvalidInputException("path must start with '/' and use only letters, digits and / _ . -");
        }
        return raw;
    }

    public static TimeRange parseRange(String raw) {
        if (raw == null || raw.isBlank()) {
            return TimeRange.LAST_7_DAYS;
        }
        for (TimeRange range : TimeRange.values()) {
            if (range.code().equals(raw.trim())) {
                return range;
            }
        }
        throw new InvalidInputException("range must be 24h, 7d or 30d.");
    }

    public static boolean isValidVisitorId(String value) {
        return value != null && VISITOR_ID.matcher(value).matches();
    }

    /** 128 random bits, base64url (22 characters). Contains no information about the visitor. */
    public static String newVisitorId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
