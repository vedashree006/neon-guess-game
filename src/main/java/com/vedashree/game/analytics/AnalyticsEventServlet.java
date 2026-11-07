package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import com.vedashree.game.InvalidInputException;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.analytics.AnalyticsRepository.PageViewResult;
import com.vedashree.game.privacy.Consents;
import com.vedashree.game.security.ClientIp;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /api/analytics/event  type=page_view&amp;eventId=&lt;uuid&gt;&amp;path=/some/path
 *
 * <p>201 recorded, 200 duplicate (same eventId replayed), 400 invalid input, 403 missing CSRF header
 * or no analytics consent, 429 rate limited, 503 analytics unavailable. Game events are not
 * accepted here: the server records them itself.
 */
@WebServlet(name = "AnalyticsEventServlet", urlPatterns = "/api/analytics/event")
public class AnalyticsEventServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(AnalyticsEventServlet.class.getName());

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
        String ip = ClientIp.resolve(req, app.config().trustProxy());
        if (!app.eventLimiter().tryAcquire(ip)) {
            resp.setHeader("Retry-After", "60");
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        if (!Consents.analyticsAllowed(req, app.config())) {
            TrackingCookies.visitorId(req).ifPresent(id -> TrackingCookies.expire(req, resp, app.config()));
            ServletSupport.writeError(resp, HttpServletResponse.SC_FORBIDDEN, "Analytics consent required.");
            return;
        }

        String eventId;
        String path;
        try {
            AnalyticsInput.parseEventType(req.getParameter("type"));
            eventId = AnalyticsInput.parseEventId(req.getParameter("eventId"));
            path = AnalyticsInput.parsePath(req.getParameter("path"));
        } catch (InvalidInputException e) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        if (app.analytics() == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Analytics unavailable.");
            return;
        }

        String visitorId = TrackingCookies.visitorId(req).orElse(null);
        boolean issue = false;
        if (visitorId == null) {
            if (!app.newVisitorLimiter().tryAcquire(ip)) {
                resp.setHeader("Retry-After", "60");
                ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
                return;
            }
            visitorId = AnalyticsInput.newVisitorId();
            issue = true;
        }

        PageViewResult result;
        try {
            result = app.analytics().trackPageView(visitorId, eventId, path);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Could not record page view", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not record event.");
            return;
        }
        if (issue) {
            TrackingCookies.issue(req, resp, app.config(), visitorId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recorded", result.recorded());
        ServletSupport.writeJson(resp, result.recorded()
                ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK, body);
    }
}
