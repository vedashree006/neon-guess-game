package com.vedashree.game;

import com.vedashree.game.Database.ScoreRecord;
import com.vedashree.game.analytics.GameAnalyticsHooks;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Game API. The secret number and all rules live on the server in the HttpSession.
 *
 * GET  /game                      current game state (or {"active":false})
 * POST /game  action=start&name=  begin or restart a game
 * POST /game  action=guess&guess= make a guess
 */
@WebServlet(name = "GameServlet", urlPatterns = "/game")
public class GameServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(GameServlet.class.getName());

    public static final String GAME_KEY = "neon.game";
    static final String NAME_KEY = "neon.lastName";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        GameLogic game = currentGame(req);
        if (game == null) {
            Map<String, Object> idle = new LinkedHashMap<>();
            idle.put("active", false);
            idle.put("min", GameLogic.MIN);
            idle.put("max", GameLogic.MAX);
            idle.put("maxAttempts", GameLogic.MAX_ATTEMPTS);
            ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, idle);
            return;
        }
        boolean saved = persistIfNeeded(game);
        ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, state(game, saved));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");

        // CSRF defence: browsers cannot add this header on a cross-site form post.
        if (!"fetch".equals(req.getHeader("X-Requested-With"))) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_FORBIDDEN,
                    "Missing X-Requested-With header.");
            return;
        }

        String action = req.getParameter("action");
        try {
            if ("start".equals(action)) {
                handleStart(req, resp);
            } else if ("guess".equals(action)) {
                handleGuess(req, resp);
            } else {
                ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Unknown action. Use 'start' or 'guess'.");
            }
        } catch (InvalidInputException e) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error while handling /game", e);
            ServletSupport.writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal server error.");
        }
    }

    private void handleStart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(true);
        String rawName = req.getParameter("name");
        if (rawName == null || rawName.isBlank()) {
            Object previous = session.getAttribute(NAME_KEY);
            rawName = previous instanceof String s ? s : null;
        }
        String name = Validation.parsePlayerName(rawName);

        GameLogic game = GameLogic.create(name);
        session.setAttribute(GAME_KEY, game);
        session.setAttribute(NAME_KEY, name);
        LOG.info("Game " + game.getGameId() + " started for player '" + name + "'");
        GameAnalyticsHooks.gameStarted(getServletContext(), req, game.getGameId());

        ServletSupport.writeJson(resp, HttpServletResponse.SC_CREATED, state(game, false));
    }

    private void handleGuess(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        GameLogic game = currentGame(req);
        if (game == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_CONFLICT,
                    "No active game. Start a new game first.");
            return;
        }
        int value = Validation.parseGuess(req.getParameter("guess"));

        Map<String, Object> body;
        synchronized (game) {
            if (game.isOver()) {
                ServletSupport.writeError(resp, HttpServletResponse.SC_CONFLICT,
                        "The game is already over. Start a new game.");
                return;
            }
            game.guess(value);
            if (game.isOver()) {
                // Runs exactly once per game: guess() rejects further input after the game ends.
                GameAnalyticsHooks.gameFinished(getServletContext(), req, game.getGameId(),
                        game.getStatus() == GameLogic.Status.WON, game.getAttemptsUsed());
            }
            boolean saved = persistIfNeeded(game);
            body = state(game, saved);
        }
        ServletSupport.writeJson(resp, HttpServletResponse.SC_OK, body);
    }

    private static GameLogic currentGame(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(GAME_KEY);
        return value instanceof GameLogic game ? game : null;
    }

    /**
     * Writes a finished game to the database exactly once. The in-session flag stops repeats,
     * and the UNIQUE(game_id) constraint stops them even if the flag were lost.
     *
     * @return true when the result is stored (or was stored before)
     */
    private boolean persistIfNeeded(GameLogic game) {
        synchronized (game) {
            if (!game.isOver()) {
                return false;
            }
            if (game.isResultSaved()) {
                return true;
            }
            Object attribute = getServletContext().getAttribute(ServletSupport.DB_ATTRIBUTE);
            if (!(attribute instanceof Database database)) {
                LOG.severe("Database is not available; result for game " + game.getGameId()
                        + " was not saved");
                return false;
            }
            try {
                database.saveResult(new ScoreRecord(
                        game.getGameId(),
                        game.getPlayerName(),
                        game.getStatus().name(),
                        game.getAttemptsUsed(),
                        game.secretIfOver(),
                        game.getScore(),
                        null));
                game.markResultSaved();
                return true;
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Could not save result for game " + game.getGameId(), e);
                return false;
            }
        }
    }

    /** Builds the JSON view of a game. The secret is included only after the game is over. */
    private static Map<String, Object> state(GameLogic game, boolean saved) {
        synchronized (game) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("active", true);
            map.put("playerName", game.getPlayerName());
            map.put("status", game.getStatus());
            map.put("over", game.isOver());
            map.put("message", game.message());
            map.put("min", GameLogic.MIN);
            map.put("max", GameLogic.MAX);
            map.put("maxAttempts", GameLogic.MAX_ATTEMPTS);
            map.put("attemptsUsed", game.getAttemptsUsed());
            map.put("attemptsRemaining", game.getAttemptsRemaining());
            map.put("score", game.getScore());
            map.put("saved", saved);

            List<Map<String, Object>> guesses = new ArrayList<>();
            for (GameLogic.Guess guess : game.getGuesses()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", guess.value());
                item.put("hint", guess.hint());
                item.put("close", guess.close());
                guesses.add(item);
            }
            map.put("guesses", guesses);

            if (game.isOver()) {
                map.put("secret", game.secretIfOver());
            }
            return map;
        }
    }
}
