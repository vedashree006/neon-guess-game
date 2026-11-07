package com.vedashree.game.analytics;

import com.vedashree.game.Database;
import com.vedashree.game.analytics.AnalyticsRepository.Overview;
import com.vedashree.game.analytics.AnalyticsRepository.PageViewResult;
import com.vedashree.game.config.AppConfig;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Business rules on top of {@link AnalyticsRepository}: windows and timeouts come from
 * {@link AppConfig}, time comes from an injectable clock, and results are shaped for JSON.
 * Callers must already have verified analytics consent; this class never reads cookies.
 */
public final class AnalyticsService {
    private static final long DAY_MS = 86_400_000L;

    private final AppConfig config;
    private final AnalyticsRepository repository;
    private final LongSupplier clock;

    public AnalyticsService(AppConfig config, AnalyticsRepository repository, LongSupplier clock) {
        this.config = config;
        this.repository = repository;
        this.clock = clock;
    }

    private long timeoutMs() {
        return config.visitTimeoutMinutes() * 60_000L;
    }

    public PageViewResult trackPageView(String visitorId, String eventId, String path) throws SQLException {
        return repository.recordPageView(visitorId, eventId, path, clock.getAsLong(), timeoutMs());
    }

    /** @return false when there is no live visit (caller answers 409 so the client reports a page view). */
    public boolean heartbeat(String visitorId) throws SQLException {
        return repository.touchVisit(visitorId, clock.getAsLong(), timeoutMs());
    }

    public void trackGameStarted(String visitorIdOrNull, String gameId) throws SQLException {
        repository.recordGameEvent(gameId, "STARTED", null, visitorIdOrNull, clock.getAsLong());
    }

    public void trackGameFinished(String visitorIdOrNull, String gameId, boolean won, int attempts)
            throws SQLException {
        repository.recordGameEvent(gameId, won ? "WON" : "LOST", attempts, visitorIdOrNull, clock.getAsLong());
    }

    /** Consent withdrawn: remove everything linked to this visitor id. */
    public void eraseVisitor(String visitorId) throws SQLException {
        repository.eraseVisitor(visitorId);
    }

    /** Periodic: ends visits that have been idle longer than the timeout. */
    public int closeIdleVisits() throws SQLException {
        return repository.closeIdleVisits(clock.getAsLong(), timeoutMs());
    }

    /** Periodic: deletes raw data older than the configured retention periods. */
    public int enforceRetention() throws SQLException {
        long now = clock.getAsLong();
        return repository.purge(now - config.retentionDays() * DAY_MS,
                now - config.consentLogRetentionDays() * DAY_MS,
                now - 180L * DAY_MS);
    }

    /** Aggregates only: no ids, no per-visitor rows. */
    public Map<String, Object> summary(LiveSessionRegistry.Snapshot live, Database.Stats leaderboard)
            throws SQLException {
        long now = clock.getAsLong();
        Overview o = repository.overview(now, config.activeWindowSeconds() * 1000L, timeoutMs());
        long completed = o.gamesWon() + o.gamesLost();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.ofEpochMilli(now).toString());

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("activeWindowSeconds", config.activeWindowSeconds());
        settings.put("visitTimeoutMinutes", config.visitTimeoutMinutes());
        settings.put("sessionTimeoutMinutes", config.sessionTimeoutMinutes());
        settings.put("retentionDays", config.retentionDays());
        settings.put("refreshSeconds", config.dashboardRefreshSeconds());
        root.put("settings", settings);

        Map<String, Object> liveNow = new LinkedHashMap<>();
        liveNow.put("activeVisitors", o.activeVisitors());
        liveNow.put("idleVisitors", o.idleVisitors());
        liveNow.put("ongoingGameSessions", live.ongoingGames());
        liveNow.put("activeServerSessions", live.activeSessions());
        liveNow.put("openServerSessions", live.visitorSessions());
        root.put("live", liveNow);

        Map<String, Object> visitors = new LinkedHashMap<>();
        visitors.put("totalUnique", o.visitorsTotal());
        visitors.put("newToday", o.newVisitorsToday());
        visitors.put("uniqueToday", o.visitorsToday());
        visitors.put("unique7d", o.visitors7d());
        visitors.put("uniquePrevious7d", o.visitorsPrev7d());
        root.put("visitors", visitors);

        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("totalVisits", o.visitsTotal());
        traffic.put("visitsToday", o.visitsToday());
        traffic.put("totalPageViews", o.pageViewsTotal());
        traffic.put("pageViewsToday", o.pageViewsToday());
        root.put("traffic", traffic);

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("lastActivityAt", o.lastActivityMs() == null ? null : Instant.ofEpochMilli(o.lastActivityMs()).toString());
        activity.put("visitsActive", o.activeVisitors());
        activity.put("visitsIdle", o.idleVisitors());
        activity.put("visitsEndedLast24h", o.endedVisits24h());
        root.put("activity", activity);

        Map<String, Object> games = new LinkedHashMap<>();
        games.put("started", o.gamesStarted());
        games.put("completed", completed);
        games.put("won", o.gamesWon());
        games.put("lost", o.gamesLost());
        games.put("winRatePercent", completed == 0 ? null : round1(100.0 * o.gamesWon() / completed));
        games.put("avgAttemptsPerCompletedGame",
                completed == 0 ? null : round1((double) (o.attemptsWonSum() + o.attemptsLostSum()) / completed));
        games.put("avgAttemptsPerWonGame", o.gamesWon() == 0 ? null : round1((double) o.attemptsWonSum() / o.gamesWon()));
        root.put("games", games);

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("savedGames", leaderboard.played());
        board.put("wins", leaderboard.wins());
        board.put("losses", leaderboard.losses());
        root.put("leaderboard", board);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("startedAt", Instant.ofEpochMilli(live.startedAtMs()).toString());
        server.put("sessionsCreatedSinceStart", live.created());
        server.put("sessionsExpiredSinceStart", live.expired());
        server.put("sessionsInvalidatedSinceStart", live.invalidated());
        root.put("serverSessions", server);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("publicAccounts", false);
        auth.put("registeredUsers", null);
        auth.put("loggedInAdmins", live.adminSessions());
        root.put("authentication", auth);

        Map<String, Object> weekly = new LinkedHashMap<>();
        weekly.put("uniqueVisitorsLast7Days", o.visitors7d());
        weekly.put("uniqueVisitorsPrevious7Days", o.visitorsPrev7d());
        root.put("weekly", weekly);
        root.put("daily", timeseries(TimeRange.LAST_7_DAYS).get("points"));
        return root;
    }

    /** Zero-filled buckets, oldest first. Bucket boundaries are UTC. */
    public Map<String, Object> timeseries(TimeRange range) throws SQLException {
        long now = clock.getAsLong();
        long size = range.bucketMs();
        long current = Math.floorDiv(now, size);
        long first = current - (range.buckets() - 1);
        long from = first * size;
        long to = (current + 1) * size;

        Map<Long, long[]> views = repository.pageViewSeries(size, from, to);
        Map<Long, long[]> games = repository.gameSeries(size, from, to);

        List<Map<String, Object>> points = new ArrayList<>();
        for (long bucket = first; bucket <= current; bucket++) {
            long[] v = views.getOrDefault(bucket, new long[2]);
            long[] g = games.getOrDefault(bucket, new long[3]);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", Instant.ofEpochMilli(bucket * size).toString());
            point.put("visitors", v[1]);
            point.put("pageViews", v[0]);
            point.put("gamesStarted", g[0]);
            point.put("gamesCompleted", g[1] + g[2]);
            point.put("gamesWon", g[1]);
            points.add(point);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range.code());
        result.put("bucket", range.unit());
        result.put("timezone", "UTC");
        result.put("points", points);
        return result;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
