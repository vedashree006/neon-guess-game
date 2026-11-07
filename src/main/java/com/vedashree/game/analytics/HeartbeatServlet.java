package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.privacy.Consents;
import com.vedashree.game.security.ClientIp;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/analytics/heartbeat
 *
 * <p>Keeps an existing visit alive. It never creates a page view or a visit. 204 extended,
 * 409 there is no live visit (the client then reports a page view), 403 no consent / no CSRF
 * header, 429 rate limited, 503 unavailable.
 */
@WebServlet(name = "HeartbeatServlet", urlPatterns = "/api/analytics/heartbeat")
public class HeartbeatServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(HeartbeatServlet.class.getName());

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!ServletSupport.requireFetchHeader(req, resp)) {
            return;
        }
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (!app.eventLimiter().tryAcquire(ClientIp.resolve(req, app.config().trustProxy()))) {
            resp.setHeader("Retry-After", "60");
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        if (!Consents.analyticsAllowed(req, app.config())) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_FORBIDDEN, "Analytics consent required.");
            return;
        }
        if (app.analytics() == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Analytics unavailable.");
            return;
        }
        Optional<String> visitorId = TrackingCookies.visitorId(req);
        if (visitorId.isEmpty()) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_CONFLICT, "No active visit.");
            return;
        }
        try {
            if (app.analytics().heartbeat(visitorId.get())) {
                ServletSupport.writeEmpty(resp, HttpServletResponse.SC_NO_CONTENT);
            } else {
                ServletSupport.writeError(resp, HttpServletResponse.SC_CONFLICT, "No active visit.");
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Heartbeat failed", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not record heartbeat.");
        }
    }
}
