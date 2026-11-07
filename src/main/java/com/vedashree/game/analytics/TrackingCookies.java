package com.vedashree.game.analytics;

import com.vedashree.game.config.AppConfig;
import com.vedashree.game.privacy.CookieNames;
import com.vedashree.game.privacy.CookieSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/** The analytics visitor cookie (neon_vid). Only ever issued after analytics consent. */
public final class TrackingCookies {
    private TrackingCookies() { }

    /** The visitor id from the cookie, or empty if absent or not in the exact expected format. */
    public static Optional<String> visitorId(HttpServletRequest request) {
        return CookieSupport.read(request, CookieNames.VISITOR).filter(AnalyticsInput::isValidVisitorId);
    }

    public static void issue(HttpServletRequest request, HttpServletResponse response, AppConfig config,
                             String visitorId) {
        CookieSupport.set(request, response, CookieNames.VISITOR, visitorId,
                config.visitorCookieDays() * 86_400, true, config.cookieSecure());
    }

    public static void expire(HttpServletRequest request, HttpServletResponse response, AppConfig config) {
        CookieSupport.expire(request, response, CookieNames.VISITOR, true, config.cookieSecure());
    }
}
