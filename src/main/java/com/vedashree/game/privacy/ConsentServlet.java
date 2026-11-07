package com.vedashree.game.privacy;

import com.vedashree.game.AppContext;
import com.vedashree.game.InvalidInputException;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.analytics.TrackingCookies;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.security.ClientIp;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cookie consent API.
 *
 * <p>GET  /api/cookie-consent: the current decision (decided=false if none or if it was made for an
 * older policy version).<br>
 * POST /api/cookie-consent  analytics=true|false &amp; preferences=true|false: stores the decision in the
 * neon_consent cookie and the consent log. Withdrawing analytics deletes the visitor cookie and
 * erases the visitor's analytics data; withdrawing preferences deletes the preferences cookie.
 *
 * <p>Necessary cookies need no consent and are not part of the decision. Works without a database
 * (the cookie is still set; only the log entry is skipped).
 */
@WebServlet(name = "ConsentServlet", urlPatterns = "/api/cookie-consent")
public class ConsentServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ConsentServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (!app.consentLimiter().tryAcquire(ClientIp.resolve(req, app.config().trustProxy()))) {
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        ServletSupport.writeJson(resp, HttpServletResponse.SC_OK,
                describe(app.config(), Consents.readCurrent(req, app.config())));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        if (!ServletSupport.requireFetchHeader(req, resp)) {
            return;
        }
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (!app.consentLimiter().tryAcquire(ClientIp.resolve(req, app.config().trustProxy()))) {
            resp.setHeader("Retry-After", "60");
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        boolean analytics;
        boolean preferences;
        try {
            analytics = parseFlag(req.getParameter("analytics"), "analytics");
            preferences = parseFlag(req.getParameter("preferences"), "preferences");
        } catch (InvalidInputException e) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }

        AppConfig config = app.config();
        long nowMs = app.clock().getAsLong();
        String previousId = Consents.readAny(req).map(ConsentState::consentId).orElse(null);
        ConsentState state = ConsentState.decide(config.consentPolicyVersion(), analytics, preferences,
                nowMs / 1000, previousId);

        // The choice itself is stored in a necessary cookie (no personal data, no tracking id).
        CookieSupport.set(req, resp, CookieNames.CONSENT, state.serialize(),
                config.consentMaxAgeDays() * 86_400, true, config.cookieSecure());

        if (!analytics) {
            Optional<String> visitor = TrackingCookies.visitorId(req);
            if (visitor.isPresent() && app.analytics() != null) {
                try {
                    app.analytics().eraseVisitor(visitor.get());
                } catch (SQLException e) {
                    LOG.log(Level.SEVERE, "Could not erase analytics data after consent withdrawal", e);
                }
            }
            // Expire the cookie even if none was sent in a valid form: stop tracking, always.
            TrackingCookies.expire(req, resp, config);
        }
        if (!preferences) {
            CookieSupport.expire(req, resp, CookieNames.PREFERENCES, false, config.cookieSecure());
        }
        if (app.consentLog() != null) {
            try {
                app.consentLog().record(state, nowMs);
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "Could not write consent log entry", e);
            }
        }
        ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, describe(config, Optional.of(state)));
    }

    /** Only the literal strings true and false are accepted: no guessing about "1", "on", "yes". */
    static boolean parseFlag(String raw, String name) {
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw new InvalidInputException(name + " must be true or false.");
    }

    private static Map<String, Object> describe(AppConfig config, Optional<ConsentState> state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyVersion", config.consentPolicyVersion());
        body.put("decided", state.isPresent());
        body.put("necessary", true);
        body.put("analytics", state.map(ConsentState::analytics).orElse(false));
        body.put("preferences", state.map(ConsentState::preferences).orElse(false));
        body.put("decidedAt", state.map(s -> Instant.ofEpochSecond(s.decidedAtSeconds()).toString()).orElse(null));
        return body;
    }
}
