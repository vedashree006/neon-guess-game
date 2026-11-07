package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.Database;
import com.vedashree.game.analytics.AnalyticsRepository.Overview;
import com.vedashree.game.analytics.AnalyticsRepository.PageViewResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real SQL against an in-memory H2 database. Times are passed explicitly, so every case is deterministic. */
class AnalyticsRepositoryTest {
    private static final long MIN = 60_000L;
    private static final long T0 = 1_730_000_000_000L;
    private static final long TIMEOUT = 30 * MIN;
    private static final long ACTIVE = 5 * MIN;

    private Database database;
    private AnalyticsRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        database = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        database.init();
        repo = new AnalyticsRepository(database);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String visitor(char c) {
        return String.valueOf(c).repeat(22);
    }

    private Overview overview(long now) throws SQLException {
        return repo.overview(now, ACTIVE, TIMEOUT);
    }

    /* ------------------------------------------------ visitors, visits, page views */

    @Test
    void firstPageViewCreatesVisitorVisitAndPageView() throws SQLException {
        PageViewResult result = repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        assertTrue(result.recorded());
        assertTrue(result.newVisit());
        assertEquals(1, repo.count("analytics_visitors"));
        assertEquals(1, repo.count("analytics_visits"));
        assertEquals(1, repo.count("analytics_page_views"));
    }

    @Test
    void secondTabOrReloadInsideTheTimeoutReusesTheVisit() throws SQLException {
        PageViewResult first = repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        PageViewResult second = repo.recordPageView(visitor('A'), id(), "/privacy.html", T0 + 5 * MIN, TIMEOUT);
        assertEquals(first.visitId(), second.visitId());
        assertFalse(second.newVisit());
        assertEquals(1, repo.count("analytics_visits"));
        assertEquals(2, repo.count("analytics_page_views"));
        assertEquals(1, repo.count("analytics_visitors"));
    }

    @Test
    void replayingTheSameEventIdIsIgnored() throws SQLException {
        String event = id();
        assertTrue(repo.recordPageView(visitor('A'), event, "/", T0, TIMEOUT).recorded());
        assertFalse(repo.recordPageView(visitor('A'), event, "/", T0 + 1000, TIMEOUT).recorded());
        assertFalse(repo.recordPageView(visitor('B'), event, "/", T0 + 2000, TIMEOUT).recorded(),
                "same id from another visitor is also a duplicate");
        assertEquals(1, repo.count("analytics_page_views"));
        assertEquals(1, repo.count("analytics_visitors"), "a duplicate must not create a visitor");
    }

    @Test
    void comingBackAfterTheInactivityTimeoutStartsANewVisit() throws SQLException {
        PageViewResult first = repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        PageViewResult later = repo.recordPageView(visitor('A'), id(), "/", T0 + TIMEOUT + 1, TIMEOUT);
        assertNotEquals(first.visitId(), later.visitId());
        assertTrue(later.newVisit());
        assertEquals(2, repo.count("analytics_visits"));
        assertEquals(1, repo.count("analytics_visitors"), "still one unique visitor");
        Overview o = overview(T0 + TIMEOUT + 1);
        assertEquals(1, o.visitorsTotal());
        assertEquals(2, o.visitsTotal());
    }

    @Test
    void differentVisitorsAreCountedSeparately() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        repo.recordPageView(visitor('B'), id(), "/", T0 + 1, TIMEOUT);
        repo.recordPageView(visitor('A'), id(), "/", T0 + 2, TIMEOUT);
        Overview o = overview(T0 + 10);
        assertEquals(2, o.visitorsTotal());
        assertEquals(2, o.visitorsToday());
        assertEquals(2, o.visitsTotal());
        assertEquals(3, o.pageViewsTotal());
    }

    /* ------------------------------------------------ heartbeat */

    @Test
    void heartbeatKeepsTheVisitAliveWithoutCreatingPageViews() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        for (int i = 1; i <= 10; i++) {
            assertTrue(repo.touchVisit(visitor('A'), T0 + i * 20 * MIN / 2, TIMEOUT));
        }
        assertEquals(1, repo.count("analytics_page_views"), "polling is never a page view");
        assertEquals(1, repo.count("analytics_visits"));
    }

    @Test
    void heartbeatAfterTheTimeoutDoesNotResurrectOrCreateAVisit() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        assertFalse(repo.touchVisit(visitor('A'), T0 + TIMEOUT + 1, TIMEOUT));
        assertEquals(1, repo.count("analytics_visits"));
        assertEquals(1, overview(T0 + TIMEOUT + 2).endedVisits24h());
        assertFalse(repo.touchVisit(visitor('A'), T0 + TIMEOUT + 3, TIMEOUT), "stays closed");
    }

    @Test
    void heartbeatForAnUnknownVisitorDoesNothing() throws SQLException {
        assertFalse(repo.touchVisit(visitor('Z'), T0, TIMEOUT));
        assertEquals(0, repo.count("analytics_visitors"));
        assertEquals(0, repo.count("analytics_visits"));
    }

    @Test
    void sweepClosesOnlyIdleVisits() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        repo.recordPageView(visitor('B'), id(), "/", T0 + 25 * MIN, TIMEOUT);
        assertEquals(1, repo.closeIdleVisits(T0 + 35 * MIN, TIMEOUT));
        assertEquals(0, repo.closeIdleVisits(T0 + 35 * MIN, TIMEOUT), "idempotent");
        assertEquals(1, overview(T0 + 35 * MIN).activeVisitors() + overview(T0 + 35 * MIN).idleVisitors());
    }

    @Test
    void activeAndIdleVisitorsFollowTheActivityWindow() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        repo.recordPageView(visitor('B'), id(), "/", T0 + 8 * MIN, TIMEOUT);
        Overview o = overview(T0 + 10 * MIN);
        assertEquals(1, o.activeVisitors(), "only B was active in the last 5 minutes");
        assertEquals(1, o.idleVisitors(), "A is idle but its visit has not timed out");
        assertEquals(Long.valueOf(T0 + 8 * MIN), o.lastActivityMs());
    }

    /* ------------------------------------------------ game events */

    @Test
    void gameEventsAreStoredOncePerGameAndType() throws SQLException {
        String game = id();
        assertTrue(repo.recordGameEvent(game, "STARTED", null, null, T0));
        assertFalse(repo.recordGameEvent(game, "STARTED", null, null, T0 + 1));
        assertTrue(repo.recordGameEvent(game, "WON", 4, null, T0 + 2));
        assertFalse(repo.recordGameEvent(game, "WON", 4, null, T0 + 3));
        assertEquals(2, repo.count("game_events"));
    }

    @Test
    void gameStatisticsAreAggregatedCorrectly() throws SQLException {
        String a = id();
        String b = id();
        String c = id();
        for (String g : new String[] {a, b, c}) {
            repo.recordGameEvent(g, "STARTED", null, null, T0);
        }
        repo.recordGameEvent(a, "WON", 3, null, T0 + 1);
        repo.recordGameEvent(b, "WON", 7, null, T0 + 2);
        repo.recordGameEvent(c, "LOST", 10, null, T0 + 3);
        Overview o = overview(T0 + 10);
        assertEquals(3, o.gamesStarted());
        assertEquals(2, o.gamesWon());
        assertEquals(1, o.gamesLost());
        assertEquals(10, o.attemptsWonSum());
        assertEquals(10, o.attemptsLostSum());
    }

    @Test
    void anUnknownVisitorIdOnAGameEventIsStoredAnonymously() throws SQLException {
        assertTrue(repo.recordGameEvent(id(), "STARTED", null, visitor('Q'), T0));
        assertEquals(1, repo.count("game_events"));
    }

    /* ------------------------------------------------ consent withdrawal and retention */

    @Test
    void erasingAVisitorRemovesTheirDataButKeepsAnonymousGameCounts() throws SQLException {
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        repo.recordPageView(visitor('B'), id(), "/", T0, TIMEOUT);
        repo.recordGameEvent(id(), "STARTED", null, visitor('A'), T0);
        repo.eraseVisitor(visitor('A'));
        assertEquals(1, repo.count("analytics_visitors"));
        assertEquals(1, repo.count("analytics_visits"));
        assertEquals(1, repo.count("analytics_page_views"));
        assertEquals(1, repo.count("game_events"), "the game event survives, now without a visitor");
    }

    @Test
    void retentionDeletesOldRowsAndKeepsRecentOnes() throws SQLException {
        long day = 86_400_000L;
        repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT);
        repo.recordGameEvent(id(), "STARTED", null, null, T0);
        repo.recordPageView(visitor('B'), id(), "/", T0 + 400 * day, TIMEOUT);
        repo.closeIdleVisits(T0 + 400 * day, TIMEOUT);
        long now = T0 + 400 * day + MIN;
        int deleted = repo.purge(now - 365 * day, now - 1095 * day, now - 180 * day);
        assertTrue(deleted > 0);
        assertEquals(1, repo.count("analytics_visitors"));
        assertEquals(1, repo.count("analytics_page_views"));
        assertEquals(0, repo.count("game_events"));
    }

    /* ------------------------------------------------ concurrency */

    @Test
    void manyTabsRacingForOneVisitorProduceExactlyOneVisit() throws Exception {
        int threads = 8;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger recorded = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (repo.recordPageView(visitor('A'), id(), "/", T0, TIMEOUT).recorded()) {
                            recorded.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    synchronized (errors) { errors.add(e); }
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) { worker.join(); }
        assertTrue(errors.isEmpty(), String.valueOf(errors));
        assertEquals(threads * perThread, recorded.get());
        assertEquals(1, repo.count("analytics_visitors"));
        assertEquals(1, repo.count("analytics_visits"), "no duplicate visit under concurrency");
        assertEquals(threads * perThread, repo.count("analytics_page_views"));
    }

    @Test
    void theSameEventSentConcurrentlyIsStoredOnce() throws Exception {
        String event = id();
        AtomicInteger recorded = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread worker = new Thread(() -> {
                try {
                    if (repo.recordPageView(visitor('A'), event, "/", T0, TIMEOUT).recorded()) {
                        recorded.incrementAndGet();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) { worker.join(); }
        assertEquals(1, recorded.get());
        assertEquals(1, repo.count("analytics_page_views"));
    }

    @Test
    void emptyDatabaseGivesZerosAndNullLastActivity() throws SQLException {
        Overview o = overview(T0);
        assertEquals(0, o.visitorsTotal());
        assertEquals(0, o.activeVisitors());
        assertNull(o.lastActivityMs());
    }
}
