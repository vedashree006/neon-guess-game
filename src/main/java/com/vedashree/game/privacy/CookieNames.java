package com.vedashree.game.privacy;

/** Every cookie this application can set, grouped by consent category. */
public final class CookieNames {
    /** NECESSARY. Server-side game/admin session (container default name). */
    public static final String SESSION = "JSESSIONID";
    /** NECESSARY. Remembers the visitor's cookie choice (no personal data, no tracking id). */
    public static final String CONSENT = "neon_consent";
    /** ANALYTICS. Random anonymous visitor id; only set after analytics consent. */
    public static final String VISITOR = "neon_vid";
    /** PREFERENCES. Remembered player name and leaderboard sort; only set after consent. */
    public static final String PREFERENCES = "neon_prefs";

    private CookieNames() { }
}
