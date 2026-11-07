package com.vedashree.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Minimal forward-only schema migrations (no extra dependency). Scripts live in
 * src/main/resources/db/migration, are applied once in order and recorded in schema_version.
 * Every script is written with IF NOT EXISTS so an interrupted run can simply be repeated.
 * Scripts must not contain ';' inside string literals (statements are split on ';').
 */
final class Migrator {
    private static final Logger LOG = Logger.getLogger(Migrator.class.getName());
    /** Order matters. Add new scripts at the end; never edit an applied one. */
    private static final String[] SCRIPTS = {"V1__game_results.sql", "V2__analytics.sql"};

    private Migrator() { }

    static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INT NOT NULL PRIMARY KEY, "
                    + "script VARCHAR(100) NOT NULL, "
                    + "applied_ms BIGINT NOT NULL)");
        }
        for (String script : SCRIPTS) {
            int version = Integer.parseInt(script.substring(1, script.indexOf("__")));
            if (isApplied(connection, version)) {
                continue;
            }
            for (String sql : statements(read(script))) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_version (version, script, applied_ms) VALUES (?, ?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, script);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            LOG.info("Applied database migration " + script);
        }
    }

    static List<String> appliedScripts(Connection connection) throws SQLException {
        List<String> applied = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT script FROM schema_version ORDER BY version")) {
            while (rs.next()) {
                applied.add(rs.getString(1));
            }
        }
        return applied;
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM schema_version WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String read(String script) throws SQLException {
        String path = "/db/migration/" + script;
        try (InputStream in = Migrator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new SQLException("Migration script not found on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Could not read migration script " + path, e);
        }
    }

    /** Drops '--' comment lines and splits on ';'. Visible for testing. */
    static List<String> statements(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                cleaned.append(line).append('\n');
            }
        }
        List<String> result = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                result.add(sql);
            }
        }
        return result;
    }
}
