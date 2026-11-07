package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.Database;
import com.vedashree.game.config.AppConfig;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dashboard numbers: zero-filled chart buckets, rates and the "no personal data" shape of the summary. */
class AnalyticsServiceTest {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2024-11-01 12:00:00 UTC, an exact hour inside a day. */
    private static final long NOON = 1_730_462_400_000L;

    private final AtomicLong clock = new AtomicLong(NOON);
    private Database database;
    private AnalyticsService service;

    @BeforeEach
    void setUp() throws SQLException {
        database = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        database.init();
        service = new AnalyticsService(AppConfig.defaults(), new AnalyticsRepository(database), clock::get);
    }

    private static String visitor(char c) {
        return String.valueOf(c).repeat(22);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(Map<String, Object> series) {
        return (List<Map<String, Object>>) series.get("points");
    }

    @Test
    void hourlySeriesHas24ZeroFilledBucketsEndingNow() throws SQLException {
        service.trackPageView(visitor('A'), UUID.randomUUID().toString(), "/");
        clock.addAndGet(-3 * HOUR);
        service.trackPageView(visitor('B'), UUID.randomUUID().toString(), "/");
        service.trackPageView(visitor('B'), UUID.randomUUID().toString(), "/");
        clock.set(NOON);

        List<Map<String, Object>> points = points(service.timeseries(TimeRange.LAST_24_HOURS));
        assertEquals(24, points.size());
        Map<String, Object> last = points.get(23);
        assertEquals(1L, last.get("pageViews"));
        assertEquals(1L, last.get("visitors"));
        Map<String, Object> threeHoursAgo = points.get(20);
        assertEquals(2L, threeHoursAgo.get("pageViews"));
        assertEquals(1L, threeHoursAgo.get("visitors"), "unique visitors, not page views");
        assertEquals(0L, points.get(0).get("pageViews"));
    }

    @Test
    void dailySeriesCountsGamesPerDay() throws SQLException {
        String won = UUID.randomUUID().toString();
        String lost = UUID.randomUUID().toString();
        clock.addAndGet(-DAY);
        service.trackGameStarted(null, won);
        service.trackGameFinished(null, won, true, 4);
        service.trackGameStarted(null, lost);
        service.trackGameFinished(null, lost, false, 10);
        clock.set(NOON);

        List<Map<String, Object>> points = points(service.timeseries(TimeRange.LAST_7_DAYS));
        assertEquals(7, points.size());
        Map<String, Object> yesterday = points.get(5);
        assertEquals(2L, yesterday.get("gamesStarted"));
        assertEquals(2L, yesterday.get("gamesCompleted"));
        assertEquals(1L, yesterday.get("gamesWon"));
        assertEquals(0L, points.get(6).get("gamesStarted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryComputesRatesAndKeepsGroupsSeparate() throws SQLException {
        service.trackPageView(visitor('A'), UUID.randomUUID().toString(), "/");
        for (int i = 0; i < 4; i++) {
            String game = UUID.randomUUID().toString();
            service.trackGameStarted(visitor('A'), game);
            if (i < 3) {
                service.trackGameFinished(visitor('A'), game, i != 2, i == 2 ? 10 : 4);
            }
        }
        LiveSessionRegistry.Snapshot live = new LiveSessionRegistry.Snapshot(5, 3, 2, 1, 9, 4, 2, NOON - HOUR);
        Map<String, Object> summary = service.summary(live, new Database.Stats(12, 7, 5));

        Map<String, Object> games = (Map<String, Object>) summary.get("games");
        assertEquals(4L, games.get("started"));
        assertEquals(3L, games.get("completed"));
        assertEquals(2L, games.get("won"));
        assertEquals(1L, games.get("lost"));
        assertEquals(66.7, (Double) games.get("winRatePercent"), 0.001);
        assertEquals(6.0, (Double) games.get("avgAttemptsPerCompletedGame"), 0.001);
        assertEquals(4.0, (Double) games.get("avgAttemptsPerWonGame"), 0.001);

        Map<String, Object> liveNow = (Map<String, Object>) summary.get("live");
        assertEquals(1L, liveNow.get("activeVisitors"));
        assertEquals(2, liveNow.get("ongoingGameSessions"));

        Map<String, Object> auth = (Map<String, Object>) summary.get("authentication");
        assertEquals(1, auth.get("loggedInAdmins"));
        assertEquals(false, auth.get("publicAccounts"));
        assertNull(auth.get("registeredUsers"));

        Map<String, Object> board = (Map<String, Object>) summary.get("leaderboard");
        assertEquals(12, board.get("savedGames"));
        assertEquals(1L, ((Map<String, Object>) summary.get("visitors")).get("totalUnique"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryNeverContainsVisitorIdsOrSessionIds() throws SQLException {
        service.trackPageView(visitor('Z'), UUID.randomUUID().toString(), "/secret-path");
        Map<String, Object> summary = service.summary(
                new LiveSessionRegistry.Snapshot(0, 0, 0, 0, 0, 0, 0, NOON), new Database.Stats(0, 0, 0));
        String json = com.vedashree.game.Json.write(summary);
        assertTrue(!json.contains(visitor('Z')));
        assertTrue(!json.contains("secret-path"));
        assertTrue(!json.toLowerCase().contains("sessionid"));
    }

    @Test
    void ratesAreNullWhenNoGameWasCompleted() throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> games = (Map<String, Object>) service.summary(
                new LiveSessionRegistry.Snapshot(0, 0, 0, 0, 0, 0, 0, NOON), new Database.Stats(0, 0, 0)).get("games");
        assertNull(games.get("winRatePercent"));
        assertNull(games.get("avgAttemptsPerCompletedGame"));
    }

    @Test
    void retentionJobRunsWithoutError() throws SQLException {
        service.trackPageView(visitor('A'), UUID.randomUUID().toString(), "/");
        clock.addAndGet(400 * DAY);
        service.closeIdleVisits();
        assertTrue(service.enforceRetention() >= 1);
    }
}
