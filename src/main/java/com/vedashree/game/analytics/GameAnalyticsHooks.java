package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Called by GameServlet. Game events are written by the server (never trusted from the browser)
 * and only when the request carries valid analytics consent. Failures are logged and swallowed:
 * analytics must never break the game.
 */
public final class GameAnalyticsHooks {
    private static final Logger LOG = Logger.getLogger(GameAnalyticsHooks.class.getName());

    private interface Action {
        void run(AnalyticsService analytics, String visitorIdOrNull) throws SQLException;
    }

    private GameAnalyticsHooks() { }

    public static void gameStarted(ServletContext context, HttpServletRequest request, String gameId) {
        run(context, request, (analytics, visitor) -> analytics.trackGameStarted(visitor, gameId));
    }

    public static void gameFinished(ServletContext context, HttpServletRequest request, String gameId,
                                    boolean won, int attempts) {
        run(context, request, (analytics, visitor) -> analytics.trackGameFinished(visitor, gameId, won, attempts));
    }

    private static void run(ServletContext context, HttpServletRequest request, Action action) {
        try {
            AppContext app = AppContext.from(context);
            if (app == null || app.analytics() == null) {
                return;
            }
            if (!com.vedashree.game.privacy.Consents.analyticsAllowed(request, app.config())) {
                return;
            }
            action.run(app.analytics(), TrackingCookies.visitorId(request).orElse(null));
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not record game analytics event", e);
        }
    }
}
