package com.vedashree.game.privacy;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A visitor's cookie decision, stored in the neon_consent cookie as
 * {@code <policyVersion>.<analytics 0|1>.<preferences 0|1>.<decidedAtEpochSeconds>.<consentId>}.
 * The consentId is a random UUID used only to tie later changes (e.g. withdrawal) to the earlier
 * log entry; it is unrelated to the analytics visitor id. Necessary cookies need no flag.
 */
public record ConsentState(int policyVersion, boolean analytics, boolean preferences,
                           long decidedAtSeconds, String consentId) {

    private static final Pattern FORMAT = Pattern.compile(
            "(\\d{1,4})\\.([01])\\.([01])\\.(\\d{9,12})\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    public static ConsentState decide(int policyVersion, boolean analytics, boolean preferences,
                                      long nowSeconds, String existingConsentId) {
        String id = existingConsentId != null ? existingConsentId : UUID.randomUUID().toString();
        return new ConsentState(policyVersion, analytics, preferences, nowSeconds, id);
    }

    /** Strict parser: anything that does not match the exact format is treated as "no decision". */
    public static Optional<ConsentState> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ConsentState(
                Integer.parseInt(m.group(1)),
                "1".equals(m.group(2)),
                "1".equals(m.group(3)),
                Long.parseLong(m.group(4)),
                m.group(5)));
    }

    public String serialize() {
        return policyVersion + "." + (analytics ? "1" : "0") + "." + (preferences ? "1" : "0")
                + "." + decidedAtSeconds + "." + consentId;
    }

    /** A decision only counts if it was made for the current policy text. */
    public boolean isCurrent(int currentPolicyVersion) {
        return policyVersion == currentPolicyVersion;
    }
}
