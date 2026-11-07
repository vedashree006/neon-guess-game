package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.AppContext;
import com.vedashree.game.Database;
import com.vedashree.game.admin.LoginServlet;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.privacy.ConsentServlet;
import com.vedashree.game.privacy.CookieNames;
import com.vedashree.game.security.PasswordHasher;
import com.vedashree.game.testsupport.Fakes;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Browser-like journeys through the real servlets with a real (in-memory H2) database. */
class AnalyticsFlowTest {
    private static final long MIN = 60_000L;
    private static final String PASSWORD = "correct horse battery";

    private final AtomicLong clock = new AtomicLong(1_730_462_400_000L);
    private Fakes.Context context;
    private Database database;
    private AnalyticsRepository repo;
    private ConsentServlet consent;
    private AnalyticsEventServlet event;
    private HeartbeatServlet heartbeat;
    private SummaryServlet summary;
    private TimeseriesServlet timeseries;
    private LoginServlet login;

    /** Cookies the "browser" keeps between requests. */
    private final Map<String, String> jar = new java.util.LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        database.init();
        repo = new AnalyticsRepository(database);
        context = Fakes.context();
        AppConfig config = AppConfig.from(Map.of("ADMIN_USERNAME", "owner",
                "ADMIN_PASSWORD_HASH", PasswordHasher.hash(PASSWORD.toCharArray(), 1000))::get);
        context.attributes.put(AppContext.ATTRIBUTE, AppContext.create(config, database, clock::get));
        consent = new ConsentServlet();
        event = new AnalyticsEventServlet();
        heartbeat = new HeartbeatServlet();
        summary = new SummaryServlet();
        timeseries = new TimeseriesServlet();
        login = new LoginServlet();
        for (jakarta.servlet.http.HttpServlet s : new jakarta.servlet.http.HttpServlet[] {
                consent, event, heartbeat, summary, timeseries, login}) {
            Fakes.init(s, context);
        }
    }

    /* ---------- tiny browser ---------- */

    private Fakes.Request browser(String method) {
        Fakes.Request request = Fakes.request(context, method);
        jar.forEach(request::cookie);
        return request;
    }

    private Fakes.Response send(jakarta.servlet.http.HttpServlet servlet, Fakes.Request request) throws Exception {
        Fakes.Response response = Fakes.call(servlet, request);
        for (Cookie c : response.cookies) {
            if (c.getMaxAge() == 0) { jar.remove(c.getName()); } else { jar.put(c.getName(), c.getValue()); }
        }
        return response;
    }

    private Fakes.Response decide(boolean analytics, boolean preferences) throws Exception {
        return send(consent, browser("POST").fetch()
                .param("analytics", String.valueOf(analytics)).param("preferences", String.valueOf(preferences)));
    }

    private Fakes.Response pageView(String eventId) throws Exception {
        return send(event, browser("POST").fetch().param("type", "page_view")
                .param("eventId", eventId).param("path", "/"));
    }

    private Fakes.Response beat() throws Exception {
        return send(heartbeat, browser("POST").fetch());
    }

    /* ---------- journeys ---------- */

    @Test
    void visitorWhoNeverDecidedLeavesNoTrace() throws Exception {
        assertEquals(403, pageView(UUID.randomUUID().toString()).status);
        assertEquals(403, beat().status);
        assertEquals(0, repo.count("analytics_visitors"));
        assertEquals(0, repo.count("analytics_page_views"));
        assertFalse(jar.containsKey(CookieNames.VISITOR));
    }

    @Test
    void visitorWhoRejectedOptionalCookiesLeavesNoTrace() throws Exception {
        decide(false, false);
        assertEquals(403, pageView(UUID.randomUUID().toString()).status);
        assertEquals(0, repo.count("analytics_visitors"));
        assertFalse(jar.containsKey(CookieNames.VISITOR));
    }

    @Test
    void afterConsentThePageViewIsRecordedAndAnAnonymousCookieIsIssued() throws Exception {
        decide(true, false);
        Fakes.Response response = pageView(UUID.randomUUID().toString());
        assertEquals(201, response.status);
        assertTrue(response.body().contains("\"recorded\":true"));
        Cookie vid = response.cookie(CookieNames.VISITOR);
        assertNotNull(vid);
        assertTrue(vid.isHttpOnly());
        assertEquals("Strict", vid.getAttribute("SameSite"));
        assertEquals(180 * 86_400, vid.getMaxAge());
        assertEquals(22, vid.getValue().length());
        assertEquals(1, repo.count("analytics_page_views"));
        assertFalse(response.body().contains(vid.getValue()), "the id is never echoed back");
    }

    @Test
    void replayedEventsAndMultipleTabsAreCountedCorrectly() throws Exception {
        decide(true, true);
        String first = UUID.randomUUID().toString();
        assertEquals(201, pageView(first).status);
        Fakes.Response replay = pageView(first);
        assertEquals(200, replay.status);
        assertTrue(replay.body().contains("\"recorded\":false"));
        clock.addAndGet(MIN);
        assertEquals(201, pageView(UUID.randomUUID().toString()).status);   // second tab / reload

        assertEquals(2, repo.count("analytics_page_views"));
        assertEquals(1, repo.count("analytics_visitors"));
        assertEquals(1, repo.count("analytics_visits"));
    }

    @Test
    void heartbeatsKeepTheVisitAliveButNeverAddPageViews() throws Exception {
        decide(true, false);
        pageView(UUID.randomUUID().toString());
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(10 * MIN);
            assertEquals(204, beat().status);
        }
        assertEquals(1, repo.count("analytics_page_views"));
        assertEquals(1, repo.count("analytics_visits"));
    }

    @Test
    void anExpiredVisitAnswers409AndTheNextPageViewStartsANewVisit() throws Exception {
        decide(true, false);
        pageView(UUID.randomUUID().toString());
        clock.addAndGet(31 * MIN);
        assertEquals(409, beat().status);
        assertEquals(201, pageView(UUID.randomUUID().toString()).status);
        assertEquals(2, repo.count("analytics_visits"));
        assertEquals(1, repo.count("analytics_visitors"));
    }

    @Test
    void withdrawingConsentStopsTrackingAndErasesTheVisitorsData() throws Exception {
        decide(true, false);
        pageView(UUID.randomUUID().toString());
        assertTrue(jar.containsKey(CookieNames.VISITOR));

        Fakes.Response withdrawal = decide(false, false);
        Cookie expired = withdrawal.cookie(CookieNames.VISITOR);
        assertNotNull(expired);
        assertEquals(0, expired.getMaxAge());
        assertFalse(jar.containsKey(CookieNames.VISITOR));
        assertEquals(0, repo.count("analytics_visitors"));
        assertEquals(0, repo.count("analytics_page_views"));
        assertEquals(403, pageView(UUID.randomUUID().toString()).status);
        assertEquals(403, beat().status);
        assertEquals(0, repo.count("analytics_visitors"));
    }

    @Test
    void everyConsentDecisionIsLoggedWithoutAnyIdentity() throws Exception {
        decide(true, true);
        decide(false, false);
        assertEquals(2, repo.count("consent_log"));
    }

    @Test
    void aTamperedVisitorCookieIsIgnoredAndReplaced() throws Exception {
        decide(true, false);
        jar.put(CookieNames.VISITOR, "'; DROP TABLE x; --");
        Fakes.Response response = pageView(UUID.randomUUID().toString());
        assertEquals(201, response.status);
        assertEquals(22, response.cookie(CookieNames.VISITOR).getValue().length());
        assertEquals(1, repo.count("analytics_visitors"));
    }

    /* ---------- admin APIs on real data ---------- */

    private Fakes.Request adminSession() throws Exception {
        Fakes.Request form = Fakes.request(context, "GET");
        Fakes.call(login, form);
        Fakes.Request post = Fakes.request(context, "POST")
                .param("csrf", (String) form.session.attributes.get("neon.admin.csrf"))
                .param("username", "owner").param("password", PASSWORD);
        post.session = form.session;
        assertEquals(302, Fakes.call(login, post).status);
        return post;
    }

    private Fakes.Request asAdmin(Fakes.Request signedIn, String method) {
        Fakes.Request next = Fakes.request(context, method);
        next.session = signedIn.session;
        return next;
    }

    @Test
    void adminSummaryShowsAggregatesAndNoIdentifiers() throws Exception {
        decide(true, false);
        pageView(UUID.randomUUID().toString());
        String visitorId = jar.get(CookieNames.VISITOR);

        Fakes.Response anonymous = Fakes.call(summary, Fakes.request(context, "GET"));
        assertEquals(401, anonymous.status);

        Fakes.Request admin = adminSession();
        Fakes.Response response = Fakes.call(summary, asAdmin(admin, "GET"));
        assertEquals(200, response.status);
        String json = response.body();
        assertTrue(json.contains("\"totalUnique\":1"));
        assertTrue(json.contains("\"totalPageViews\":1"));
        assertTrue(json.contains("\"activeVisitors\":1"));
        assertTrue(json.contains("\"registeredUsers\":null"));
        assertFalse(json.contains(visitorId));
        assertFalse(json.contains(admin.session.id));
        assertEquals("no-store", response.headers.get("Cache-Control"));
    }

    @Test
    void timeseriesValidatesItsParameter() throws Exception {
        Fakes.Request admin = adminSession();
        assertEquals(400, Fakes.call(timeseries, asAdmin(admin, "GET").param("range", "999d")).status);
        assertEquals(400, Fakes.call(timeseries, asAdmin(admin, "GET").param("range", "7d' OR '1'='1")).status);
        Fakes.Response ok = Fakes.call(timeseries, asAdmin(admin, "GET").param("range", "24h"));
        assertEquals(200, ok.status);
        assertTrue(ok.body().contains("\"range\":\"24h\""));
        assertEquals(200, Fakes.call(timeseries, asAdmin(admin, "GET")).status, "default range");
    }
}
