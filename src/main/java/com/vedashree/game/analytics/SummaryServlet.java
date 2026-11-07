package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import com.vedashree.game.Database;
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

/** GET /api/analytics/summary: aggregated numbers only. Admin session required (401 otherwise). */
@WebServlet(name = "SummaryServlet", urlPatterns = "/api/analytics/summary")
public class SummaryServlet extends AdminServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(SummaryServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app.analytics() == null || app.database() == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Analytics unavailable.");
            return;
        }
        try {
            Database.Stats leaderboard = app.database().stats();
            ServletSupport.writeJson(resp, HttpServletResponse.SC_OK,
                    app.analytics().summary(app.sessions().snapshot(), leaderboard));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Could not build summary", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not load summary.");
        }
    }
}
