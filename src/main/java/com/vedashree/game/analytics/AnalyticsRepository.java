package com.vedashree.game.analytics;

import com.vedashree.game.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * All analytics SQL. Every statement is a PreparedStatement; the only text ever concatenated
 * into SQL is a bucket size that must equal one of two compile-time constants.
 *
 * <p>Concurrency: writes for one visitor are serialised with a striped in-JVM lock and run in
 * one transaction, so two browser tabs cannot open two visits at once. The embedded H2 database
 * is single-process, so a JVM lock is sufficient; for several app instances use a server
 * database and {@code SELECT ... FOR UPDATE} on the visitor row instead.
 */
public final class AnalyticsRepository {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final int STRIPES = 64;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 86_400_000L;

    public record PageViewResult(boolean recorded, long visitId, boolean newVisit) { }

    public record Overview(long visitorsTotal, long newVisitorsToday, long visitorsToday,
                           long visitors7d, long visitorsPrev7d, long visitsTotal, long visitsToday,
                           long pageViewsTotal, long pageViewsToday, long activeVisitors,
                           long idleVisitors, long endedVisits24h, Long lastActivityMs,
                           long gamesStarted, long gamesWon, long gamesLost,
                           long attemptsWonSum, long attemptsLostSum) { }

    private final Database database;
    private final Object[] locks = new Object[STRIPES];

    public AnalyticsRepository(Database database) {
        this.database = database;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(String visitorId) {
        return locks[(visitorId.hashCode() & 0x7fffffff) % STRIPES];
    }

    /* ---------------------------------------------------------------- writes */

    /**
     * Records one page view and keeps the visitor's visit alive (or opens a new one when the
     * previous visit has been idle longer than {@code timeoutMs}). Replays of the same
     * {@code eventId} change nothing and report {@code recorded=false}.
     */
    public PageViewResult recordPageView(String visitorId, String eventId, String path,
                                         long nowMs, long timeoutMs) throws SQLException {
        synchronized (lockFor(visitorId)) {
            try (Connection c = database.openConnection()) {
                c.setAutoCommit(false);
                try {
                    if (exists(c, "SELECT 1 FROM analytics_page_views WHERE event_id = ?", eventId)) {
                        c.rollback();
                        return new PageViewResult(false, 0, false);
                    }
                    upsertVisitor(c, visitorId, nowMs);
                    long[] visit = openOrStartVisit(c, visitorId, nowMs, timeoutMs);
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO analytics_page_views (event_id, visit_id, visitor_id, path, viewed_ms) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        ps.setString(1, eventId);
                        ps.setLong(2, visit[0]);
                        ps.setString(3, visitorId);
                        ps.setString(4, path);
                        ps.setLong(5, nowMs);
                        ps.executeUpdate();
                    }
                    c.commit();
                    return new PageViewResult(true, visit[0], visit[1] == 1);
                } catch (SQLException e) {
                    c.rollback();
                    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                        return new PageViewResult(false, 0, false);
                    }
                    throw e;
                } catch (RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }
    }

    /**
     * Heartbeat: extends an existing, non-expired visit and nothing else (never a page view).
     *
     * @return true if a live visit was extended; false if there is none (the caller should
     *         report a new page view, which starts a new visit)
     */
    public boolean touchVisit(String visitorId, long nowMs, long timeoutMs) throws SQLException {
        synchronized (lockFor(visitorId)) {
            try (Connection c = database.openConnection()) {
                c.setAutoCommit(false);
                try {
                    long[] open = findOpenVisit(c, visitorId);
                    if (open == null) {
                        c.rollback();
                        return false;
                    }
                    if (nowMs - open[1] > timeoutMs) {
                        closeVisit(c, open[0]);
                        c.commit();
                        return false;
                    }
                    boolean extended = extendVisit(c, open[0], nowMs);
                    if (extended) {
                        upsertVisitor(c, visitorId, nowMs);
                    }
                    c.commit();
                    return extended;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }
    }

    /**
     * Stores a game lifecycle event once per (gameId, type). visitorId may be null.
     *
     * @return false if this event was already stored
     */
    public boolean recordGameEvent(String gameId, String eventType, Integer attempts,
                                   String visitorId, long nowMs) throws SQLException {
        try (Connection c = database.openConnection()) {
            String visitor = visitorId;
            if (visitor != null && !exists(c, "SELECT 1 FROM analytics_visitors WHERE visitor_id = ?", visitor)) {
                visitor = null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO game_events (game_id, event_type, attempts, visitor_id, occurred_ms) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, gameId);
                ps.setString(2, eventType);
                if (attempts == null) {
                    ps.setNull(3, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(3, attempts);
                }
                if (visitor == null) {
                    ps.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(4, visitor);
                }
                ps.setLong(5, nowMs);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            }
        }
    }

    /** Deletes the visitor and, by cascade, their visits and page views. Game events are anonymised. */
    public void eraseVisitor(String visitorId) throws SQLException {
        synchronized (lockFor(visitorId)) {
            try (Connection c = database.openConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM analytics_visitors WHERE visitor_id = ?")) {
                ps.setString(1, visitorId);
                ps.executeUpdate();
            }
        }
    }

    /** Marks visits that were idle longer than the timeout as ended. */
    public int closeIdleVisits(long nowMs, long timeoutMs) throws SQLException {
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE analytics_visits SET ended_ms = last_activity_ms, end_reason = 'TIMEOUT' "
                             + "WHERE ended_ms IS NULL AND last_activity_ms < ?")) {
            ps.setLong(1, nowMs - timeoutMs);
            return ps.executeUpdate();
        }
    }

    /** Retention: removes raw analytics older than the cut-offs. Returns the number of rows deleted. */
    public int purge(long analyticsCutoffMs, long consentCutoffMs, long auditCutoffMs) throws SQLException {
        int total = 0;
        try (Connection c = database.openConnection()) {
            total += delete(c, "DELETE FROM analytics_page_views WHERE viewed_ms < ?", analyticsCutoffMs);
            total += delete(c, "DELETE FROM game_events WHERE occurred_ms < ?", analyticsCutoffMs);
            total += delete(c, "DELETE FROM analytics_visits WHERE ended_ms IS NOT NULL AND last_activity_ms < ?",
                    analyticsCutoffMs);
            total += delete(c, "DELETE FROM analytics_visitors WHERE last_seen_ms < ?", analyticsCutoffMs);
            total += delete(c, "DELETE FROM consent_log WHERE decided_ms < ?", consentCutoffMs);
            total += delete(c, "DELETE FROM admin_audit WHERE occurred_ms < ?", auditCutoffMs);
        }
        return total;
    }

    /* ---------------------------------------------------------------- reads */

    public Overview overview(long nowMs, long activeWindowMs, long timeoutMs) throws SQLException {
        long todayStart = Math.floorDiv(nowMs, DAY_MS) * DAY_MS;
        long upper = nowMs + 1;
        try (Connection c = database.openConnection()) {
            long visitorsTotal = scalar(c, "SELECT COUNT(*) FROM analytics_visitors");
            long newToday = scalar(c, "SELECT COUNT(*) FROM analytics_visitors WHERE first_seen_ms >= ?", todayStart);
            long visitorsToday = scalar(c, DISTINCT_VISITORS, todayStart, upper);
            long visitors7d = scalar(c, DISTINCT_VISITORS, nowMs - 7 * DAY_MS, upper);
            long visitorsPrev7d = scalar(c, DISTINCT_VISITORS, nowMs - 14 * DAY_MS, nowMs - 7 * DAY_MS);
            long visitsTotal = scalar(c, "SELECT COUNT(*) FROM analytics_visits");
            long visitsToday = scalar(c, "SELECT COUNT(*) FROM analytics_visits WHERE started_ms >= ?", todayStart);
            long pvTotal = scalar(c, "SELECT COUNT(*) FROM analytics_page_views");
            long pvToday = scalar(c, "SELECT COUNT(*) FROM analytics_page_views WHERE viewed_ms >= ?", todayStart);
            long active = scalar(c, "SELECT COUNT(DISTINCT visitor_id) FROM analytics_visits "
                    + "WHERE ended_ms IS NULL AND last_activity_ms >= ?", nowMs - activeWindowMs);
            long idle = scalar(c, "SELECT COUNT(DISTINCT visitor_id) FROM analytics_visits "
                    + "WHERE ended_ms IS NULL AND last_activity_ms < ? AND last_activity_ms >= ?",
                    nowMs - activeWindowMs, nowMs - timeoutMs);
            long ended24h = scalar(c, "SELECT COUNT(*) FROM analytics_visits WHERE last_activity_ms >= ? "
                    + "AND (ended_ms IS NOT NULL OR last_activity_ms < ?)", nowMs - DAY_MS, nowMs - timeoutMs);
            Long lastActivity = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT MAX(last_activity_ms) FROM analytics_visits");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                long value = rs.getLong(1);
                lastActivity = rs.wasNull() ? null : value;
            }
            long started = 0;
            long won = 0;
            long lost = 0;
            long wonAttempts = 0;
            long lostAttempts = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_type, COUNT(*), COALESCE(SUM(attempts), 0) FROM game_events GROUP BY event_type");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString(1);
                    long count = rs.getLong(2);
                    long attempts = rs.getLong(3);
                    if ("STARTED".equals(type)) {
                        started = count;
                    } else if ("WON".equals(type)) {
                        won = count;
                        wonAttempts = attempts;
                    } else if ("LOST".equals(type)) {
                        lost = count;
                        lostAttempts = attempts;
                    }
                }
            }
            return new Overview(visitorsTotal, newToday, visitorsToday, visitors7d, visitorsPrev7d,
                    visitsTotal, visitsToday, pvTotal, pvToday, active, idle, ended24h, lastActivity,
                    started, won, lost, wonAttempts, lostAttempts);
        }
    }

    private static final String DISTINCT_VISITORS = "SELECT COUNT(DISTINCT visitor_id) FROM analytics_page_views "
            + "WHERE viewed_ms >= ? AND viewed_ms < ?";

    /** bucket index (epoch ms / bucketMs) -> {pageViews, uniqueVisitors}. */
    public Map<Long, long[]> pageViewSeries(long bucketMs, long fromMs, long toMs) throws SQLException {
        String sql = "SELECT b, COUNT(*), COUNT(DISTINCT v) FROM (SELECT viewed_ms / " + checkedBucket(bucketMs)
                + " AS b, visitor_id AS v FROM analytics_page_views WHERE viewed_ms >= ? AND viewed_ms < ?) x GROUP BY b";
        Map<Long, long[]> result = new HashMap<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), new long[] {rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        return result;
    }

    /** bucket index -> {started, won, lost}. */
    public Map<Long, long[]> gameSeries(long bucketMs, long fromMs, long toMs) throws SQLException {
        String sql = "SELECT b, t, COUNT(*) FROM (SELECT occurred_ms / " + checkedBucket(bucketMs)
                + " AS b, event_type AS t FROM game_events WHERE occurred_ms >= ? AND occurred_ms < ?) x GROUP BY b, t";
        Map<Long, long[]> result = new HashMap<>();
        try (Connection c = database.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long[] row = result.computeIfAbsent(rs.getLong(1), k -> new long[3]);
                    String type = rs.getString(2);
                    int index = "STARTED".equals(type) ? 0 : "WON".equals(type) ? 1 : 2;
                    row[index] = rs.getLong(3);
                }
            }
        }
        return result;
    }

    /** Test/diagnostic helper: row count of a fixed set of analytics tables. */
    public long count(String table) throws SQLException {
        String sql;
        switch (table) {
            case "analytics_visitors":
            case "analytics_visits":
            case "analytics_page_views":
            case "game_events":
            case "consent_log":
            case "admin_audit":
                sql = "SELECT COUNT(*) FROM " + table;
                break;
            default:
                throw new IllegalArgumentException("Unknown table");
        }
        try (Connection c = database.openConnection()) {
            return scalar(c, sql);
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private static long checkedBucket(long bucketMs) {
        if (bucketMs != HOUR_MS && bucketMs != DAY_MS) {
            throw new IllegalArgumentException("Unsupported bucket size");
        }
        return bucketMs;
    }

    private static void upsertVisitor(Connection c, String visitorId, long nowMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE analytics_visitors SET last_seen_ms = ? WHERE visitor_id = ?")) {
            ps.setLong(1, nowMs);
            ps.setString(2, visitorId);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO analytics_visitors (visitor_id, first_seen_ms, last_seen_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, visitorId);
            ps.setLong(2, nowMs);
            ps.setLong(3, nowMs);
            ps.executeUpdate();
        }
    }

    /** @return {id, lastActivityMs} of the newest open visit, or null. */
    private static long[] findOpenVisit(Connection c, String visitorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, last_activity_ms FROM analytics_visits WHERE visitor_id = ? AND ended_ms IS NULL "
                        + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, visitorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : null;
            }
        }
    }

    private static void closeVisit(Connection c, long visitId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE analytics_visits SET ended_ms = last_activity_ms, end_reason = 'TIMEOUT' "
                        + "WHERE id = ? AND ended_ms IS NULL")) {
            ps.setLong(1, visitId);
            ps.executeUpdate();
        }
    }

    /**
     * Records activity on a visit that is still open. Writes for one visitor are serialised and
     * use the server clock, so a plain assignment is enough (no CASE with untyped parameters,
     * which H2 may reject).
     */
    private static boolean extendVisit(Connection c, long visitId, long nowMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE analytics_visits SET last_activity_ms = ? WHERE id = ? AND ended_ms IS NULL")) {
            ps.setLong(1, nowMs);
            ps.setLong(2, visitId);
            return ps.executeUpdate() == 1;
        }
    }

    /** @return {visitId, 1 if a new visit was started else 0} */
    private static long[] openOrStartVisit(Connection c, String visitorId, long nowMs, long timeoutMs)
            throws SQLException {
        long[] open = findOpenVisit(c, visitorId);
        if (open != null) {
            if (nowMs - open[1] <= timeoutMs && extendVisit(c, open[0], nowMs)) {
                return new long[] {open[0], 0};
            }
            closeVisit(c, open[0]);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO analytics_visits (visitor_id, started_ms, last_activity_ms) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, visitorId);
            ps.setLong(2, nowMs);
            ps.setLong(3, nowMs);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key returned for analytics_visits");
                }
                return new long[] {keys.getLong(1), 1};
            }
        }
    }

    private static boolean exists(Connection c, String sql, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int delete(Connection c, String sql, long cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    private static long scalar(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setLong(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
