package com.vedashree.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.analytics.AnalyticsRepository;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.privacy.ConsentState;
import com.vedashree.game.privacy.CookieNames;
import com.vedashree.game.testsupport.Fakes;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The game after analytics integration: it behaves exactly as before, with or without a database,
 * and records server-side game events only for visitors who consented.
 */
class GameAnalyticsFlowTest {
    private final AtomicLong clock = new AtomicLong(1_730_462_400_000L);
    private Fakes.Context context;
    private GameServlet game;
    private Database database;
    private AnalyticsRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        context = Fakes.context();
        game = new GameServlet();
        Fakes.init(game, context);
    }

    private void withDatabase() throws Exception {
        database = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        database.init();
        repo = new AnalyticsRepository(database);
        context.attributes.put(ServletSupport.DB_ATTRIBUTE, database);
        context.attributes.put(AppContext.ATTRIBUTE, AppContext.create(AppConfig.defaults(), database, clock::get));
    }

    private static String consentCookie(boolean analytics) {
        return ConsentState.decide(1, analytics, false, 1_730_462_400L, UUID.randomUUID().toString()).serialize();
    }

    private Fakes.Request post(Fakes.Session session, boolean analyticsConsent) {
        Fakes.Request request = Fakes.request(context, "POST").fetch().cookie(CookieNames.CONSENT, consentCookie(analyticsConsent));
        request.session = session;
        return request;
    }

    private static int secretOf(Fakes.Session session) {
        return ((GameLogic) session.attributes.get(GameServlet.GAME_KEY)).secretForTesting();
    }

    /** Plays a full winning game through the servlet. Returns the session. */
    private Fakes.Session playAndWin(boolean analyticsConsent) throws Exception {
        Fakes.Request start = post(null, analyticsConsent).param("action", "start").param("name", "Veda");
        assertEquals(201, Fakes.call(game, start).status);
        Fakes.Session session = start.session;
        Fakes.Response win = Fakes.call(game, post(session, analyticsConsent)
                .param("action", "guess").param("guess", String.valueOf(secretOf(session))));
        assertEquals(200, win.status);
        assertTrue(win.body().contains("\"status\":\"WON\""));
        return session;
    }

    /* ---------- the original game still works (no database, no consent) ---------- */

    @Test
    void gameStillWorksWithoutAnyAnalyticsInfrastructure() throws Exception {
        Fakes.Request start = Fakes.request(context, "POST").fetch().param("action", "start").param("name", "Veda");
        Fakes.Response response = Fakes.call(game, start);
        assertEquals(201, response.status);
        assertFalse(response.body().contains("\"secret\""), "secret hidden while playing");
        assertTrue(response.body().contains("\"attemptsRemaining\":10"));
    }

    @Test
    void invalidAndOutOfOrderRequestsKeepTheirStatusCodes() throws Exception {
        assertEquals(403, Fakes.call(game, Fakes.request(context, "POST").param("action", "start")).status);
        assertEquals(409, Fakes.call(game, Fakes.request(context, "POST").fetch()
                .param("action", "guess").param("guess", "5")).status);
        Fakes.Request start = Fakes.request(context, "POST").fetch().param("action", "start");
        Fakes.call(game, start);
        Fakes.Request bad = Fakes.request(context, "POST").fetch().param("action", "guess").param("guess", "99");
        bad.session = start.session;
        assertEquals(400, Fakes.call(game, bad).status);
        assertEquals(400, Fakes.call(game, Fakes.request(context, "POST").fetch().param("action", "nope")).status);
    }

    @Test
    void aDeterministicGameCanBeWonAndThenRejectsFurtherGuesses() throws Exception {
        Fakes.Session session = Fakes.session(context, "s1");
        session.attributes.put(GameServlet.GAME_KEY, new GameLogic("Veda", 25));
        Fakes.Request request = Fakes.request(context, "POST").fetch().param("action", "guess").param("guess", "25");
        request.session = session;
        Fakes.Response win = Fakes.call(game, request);
        assertTrue(win.body().contains("\"secret\":25"), "secret revealed only after the game");
        Fakes.Request again = Fakes.request(context, "POST").fetch().param("action", "guess").param("guess", "25");
        again.session = session;
        assertEquals(409, Fakes.call(game, again).status);
    }

    @Test
    void statusEndpointReportsNoGameForNewVisitorsWithoutCreatingASession() throws Exception {
        Fakes.Request request = Fakes.request(context, "GET");
        Fakes.Response response = Fakes.call(game, request);
        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"active\":false"));
        assertNull(request.session, "browsing must not create a session cookie");
    }

    /* ---------- analytics hooks ---------- */

    @Test
    void consentedGamesRecordStartedAndWonEventsOnTheServer() throws Exception {
        withDatabase();
        playAndWin(true);
        assertEquals(2, repo.count("game_events"));
        assertEquals(1, database.stats().played(), "leaderboard saving is unchanged");
    }

    @Test
    void gamesWithoutAnalyticsConsentRecordNoAnalyticsEvents() throws Exception {
        withDatabase();
        playAndWin(false);
        assertEquals(0, repo.count("game_events"));
        assertEquals(1, database.stats().played(), "the leaderboard is a service feature, not analytics");
    }

    @Test
    void aLostGameIsRecordedAsLostWithTenAttempts() throws Exception {
        withDatabase();
        Fakes.Request start = post(null, true).param("action", "start");
        Fakes.call(game, start);
        int secret = secretOf(start.session);
        int wrong = secret == 1 ? 2 : 1;
        for (int i = 0; i < 10; i++) {
            Fakes.call(game, post(start.session, true).param("action", "guess").param("guess", String.valueOf(wrong)));
        }
        assertEquals(2, repo.count("game_events"));
        AnalyticsRepository.Overview o = repo.overview(clock.get(), 300_000, 1_800_000);
        assertEquals(1, o.gamesLost());
        assertEquals(10, o.attemptsLostSum());
    }

    @Test
    void duplicateFinishRequestsDoNotDoubleCountTheGame() throws Exception {
        withDatabase();
        Fakes.Session session = playAndWin(true);
        Fakes.call(game, post(session, true).param("action", "guess").param("guess", "1"));   // 409, game over
        Fakes.call(game, Fakes.requestWithSession(context, "GET", session));                  // GET re-persists, not re-counts
        assertEquals(2, repo.count("game_events"));
    }

    @Test
    void anAnalyticsFailureNeverBreaksTheGame() throws Exception {
        withDatabase();
        try (java.sql.Connection c = database.openConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("DROP TABLE game_events");
        }
        Fakes.Request start = post(null, true).param("action", "start");
        assertEquals(201, Fakes.call(game, start).status);
        Fakes.Response win = Fakes.call(game, post(start.session, true)
                .param("action", "guess").param("guess", String.valueOf(secretOf(start.session))));
        assertEquals(200, win.status);
        assertNotNull(win.body());
    }
}
