package com.vedashree.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All JDBC access lives here. Every query uses a PreparedStatement and the only text that is
 * ever concatenated into SQL comes from the fixed {@link Sort} enum, never from user input.
 */
public final class Database {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";

    /** Allowed leaderboard orderings. The clause text is a compile-time constant. */
    public enum Sort {
        RECENT("ORDER BY played_at DESC, id DESC"),
        TOP("ORDER BY score DESC, attempts_used ASC, played_at DESC, id DESC");

        private final String clause;

        Sort(String clause) {
            this.clause = clause;
        }
    }

    /** One finished game. playedAt is ignored on insert (the database sets it). */
    public record ScoreRecord(String gameId, String playerName, String outcome,
                              int attemptsUsed, int secretNumber, int score, Instant playedAt) { }

    public record Stats(int played, int wins, int losses) { }

    private static final String INSERT_SQL = "INSERT INTO game_results "
            + "(game_id, player_name, outcome, attempts_used, secret_number, score) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SELECT_SQL = "SELECT game_id, player_name, outcome, attempts_used, "
            + "secret_number, score, played_at FROM game_results ";

    private static final String STATS_SQL = "SELECT COUNT(*), "
            + "COALESCE(SUM(CASE WHEN outcome = 'WON' THEN 1 ELSE 0 END), 0) FROM game_results";

    private final String url;

    public Database(String url) {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("H2 driver not found on the classpath", e);
        }
        this.url = url;
    }

    /** Folder that holds the database files: -Dneon.db.dir, then env NEON_DB_DIR, then ~/.neon-guess-game. */
    public static Path defaultDirectory() {
        String dir = System.getProperty("neon.db.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("NEON_DB_DIR");
        }
        if (dir == null || dir.isBlank()) {
            dir = Paths.get(System.getProperty("user.home"), ".neon-guess-game").toString();
        }
        return Paths.get(dir).toAbsolutePath();
    }

    /**
     * File database location. Keeping it outside the WAR means scores and analytics survive redeploys.
     */
    public static String defaultUrl() {
        Path folder = defaultDirectory();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not create database folder " + folder, e);
        }
        return "jdbc:h2:file:" + folder.resolve("scores").toString().replace('\\', '/');
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, DB_USER, DB_PASSWORD);
    }

    /** Opens a new connection. Callers must close it (try-with-resources). */
    public Connection openConnection() throws SQLException {
        return connect();
    }

    /** Applies all pending schema migrations. Safe to call repeatedly. */
    public void init() throws SQLException {
        try (Connection connection = connect()) {
            Migrator.migrate(connection);
        }
        LOG.info("Database ready: " + url);
    }

    /**
     * Writes a consistent online backup (zip) with H2's BACKUP command. The target path comes from
     * trusted server configuration, never from a request.
     */
    public void backupTo(Path target) throws SQLException {
        String literal = target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("BACKUP TO '" + literal + "'");
        }
    }

    /**
     * Stores a finished game.
     *
     * @return true if a new row was written, false if this game_id was already stored
     */
    public boolean saveResult(ScoreRecord record) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, record.gameId());
            ps.setString(2, record.playerName());
            ps.setString(3, record.outcome());
            ps.setInt(4, record.attemptsUsed());
            ps.setInt(5, record.secretNumber());
            ps.setInt(6, record.score());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                LOG.fine("Duplicate result ignored for game " + record.gameId());
                return false;
            }
            throw e;
        }
    }

    public List<ScoreRecord> findResults(int limit, Sort sort) throws SQLException {
        String sql = SELECT_SQL + sort.clause + " LIMIT ?";
        List<ScoreRecord> rows = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp playedAt = rs.getTimestamp("played_at");
                    rows.add(new ScoreRecord(
                            rs.getString("game_id"),
                            rs.getString("player_name"),
                            rs.getString("outcome"),
                            rs.getInt("attempts_used"),
                            rs.getInt("secret_number"),
                            rs.getInt("score"),
                            playedAt == null ? null : playedAt.toInstant()));
                }
            }
        }
        return rows;
    }

    public Stats stats() throws SQLException {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(STATS_SQL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            int played = rs.getInt(1);
            int wins = rs.getInt(2);
            return new Stats(played, wins, played - wins);
        }
    }
}
