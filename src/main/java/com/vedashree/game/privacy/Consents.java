package com.vedashree.game.privacy;

import com.vedashree.game.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Reads the consent cookie of a request. The server, not the browser, decides what is allowed. */
public final class Consents {
    private Consents() { }

    /** Any well-formed decision, even for an older policy version (used to reuse the consent id). */
    public static Optional<ConsentState> readAny(HttpServletRequest request) {
        return CookieSupport.read(request, CookieNames.CONSENT).flatMap(ConsentState::parse);
    }

    /** The decision only if it was made for the current policy version. */
    public static Optional<ConsentState> readCurrent(HttpServletRequest request, AppConfig config) {
        return readAny(request).filter(state -> state.isCurrent(config.consentPolicyVersion()));
    }

    public static boolean analyticsAllowed(HttpServletRequest request, AppConfig config) {
        return readCurrent(request, config).map(ConsentState::analytics).orElse(false);
    }
}
