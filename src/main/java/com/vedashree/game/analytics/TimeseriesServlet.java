package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import com.vedashree.game.InvalidInputException;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.admin.AdminServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** GET /api/analytics/timeseries?range=24h|7d|30d. Admin session required (401 otherwise). */
@WebServlet(name = "TimeseriesServlet", urlPatterns = "/api/analytics/timeseries")
public class TimeseriesServlet extends AdminServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(TimeseriesServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app.analytics() == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Analytics unavailable.");
            return;
        }
        try {
            TimeRange range = AnalyticsInput.parseRange(req.getParameter("range"));
            ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, app.analytics().timeseries(range));
        } catch (InvalidInputException e) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Could not build time series", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not load chart data.");
        }
    }
}
