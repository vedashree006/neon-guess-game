package com.vedashree.game;

import com.vedashree.game.Database.ScoreRecord;
import com.vedashree.game.Database.Stats;
import com.vedashree.game.Database.Sort;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only leaderboard API.
 *
 * GET /history?limit=10&amp;sort=recent|top
 */
@WebServlet(name = "HistoryServlet", urlPatterns = "/history")
public class HistoryServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(HistoryServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        try {
            int limit = Validation.parseLimit(req.getParameter("limit"));
            Sort sort = Validation.parseSort(req.getParameter("sort"));

            Object attribute = getServletContext().getAttribute(ServletSupport.DB_ATTRIBUTE);
            if (!(attribute instanceof Database database)) {
                ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Database unavailable.");
                return;
            }

            List<ScoreRecord> rows = database.findResults(limit, sort);
            Stats stats = database.stats();

            Map<String, Object> statsJson = new LinkedHashMap<>();
            statsJson.put("played", stats.played());
            statsJson.put("wins", stats.wins());
            statsJson.put("losses", stats.losses());

            List<Map<String, Object>> results = new ArrayList<>();
            for (ScoreRecord row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("playerName", row.playerName());
                item.put("outcome", row.outcome());
                item.put("attemptsUsed", row.attemptsUsed());
                item.put("score", row.score());
                item.put("playedAt", row.playedAt() == null ? null : row.playedAt().toString());
                results.add(item);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sort", sort.name().toLowerCase(Locale.ROOT));
            body.put("limit", limit);
            body.put("stats", statsJson);
            body.put("results", results);
            ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, body);
        } catch (InvalidInputException e) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Could not load history", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Could not load history.");
        }
    }
}
