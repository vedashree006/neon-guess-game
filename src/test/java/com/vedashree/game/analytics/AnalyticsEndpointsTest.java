package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.AppContext;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.privacy.ConsentState;
import com.vedashree.game.privacy.CookieNames;
import com.vedashree.game.testsupport.Fakes;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consent gating, CSRF and validation of the public analytics endpoints (no database needed). */
class AnalyticsEndpointsTest {
    private static final String VISITOR = "AAAAAAAAAAAAAAAAAAAAAA";
    private Fakes.Context context;
    private AnalyticsEventServlet event;
    private HeartbeatServlet heartbeat;
    private final AtomicLong clock = new AtomicLong(1_730_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        context = Fakes.context();
        context.attributes.put(AppContext.ATTRIBUTE, AppContext.create(AppConfig.defaults(), null, clock::get));
        event = new AnalyticsEventServlet();
        heartbeat = new HeartbeatServlet();
        Fakes.init(event, context);
        Fakes.init(heartbeat, context);
    }

    private static String consent(boolean analytics) {
        return ConsentState.decide(1, analytics, false, 1_730_000_000L, UUID.randomUUID().toString()).serialize();
    }

    private Fakes.Request validEvent(boolean analyticsConsent) {
        return Fakes.request(context, "POST").fetch()
                .cookie(CookieNames.CONSENT, consent(analyticsConsent))
                .param("type", "page_view").param("eventId", UUID.randomUUID().toString()).param("path", "/");
    }

    @Test
    void noConsentCookieMeansNothingIsTracked() throws Exception {
        Fakes.Request request = Fakes.request(context, "POST").fetch()
                .param("type", "page_view").param("eventId", UUID.randomUUID().toString()).param("path", "/");
        Fakes.Response response = Fakes.call(event, request);
        assertEquals(403, response.status);
        assertTrue(response.body().contains("consent"));
        assertTrue(response.cookies.isEmpty(), "no visitor cookie may be issued without consent");
    }

    @Test
    void rejectedAnalyticsIsTreatedLikeNoConsentAndStaleVisitorCookieIsRemoved() throws Exception {
        Fakes.Request request = validEvent(false).cookie(CookieNames.VISITOR, VISITOR);
        Fakes.Response response = Fakes.call(event, request);
        assertEquals(403, response.status);
        assertNotNull(response.cookie(CookieNames.VISITOR));
        assertEquals(0, response.cookie(CookieNames.VISITOR).getMaxAge());
    }

    @Test
    void consentForAnOlderPolicyVersionDoesNotCount() throws Exception {
        String old = ConsentState.decide(7, true, true, 1_730_000_000L, UUID.randomUUID().toString()).serialize();
        Fakes.Request request = validEvent(true);
        request.cookies.removeIf(c -> c.getName().equals(CookieNames.CONSENT));
        request.cookie(CookieNames.CONSENT, old);
        assertEquals(403, Fakes.call(event, request).status);
    }

    @Test
    void eventsNeedTheCsrfHeader() throws Exception {
        Fakes.Request request = validEvent(true);
        request.headers.clear();
        assertEquals(403, Fakes.call(event, request).status);
    }

    @Test
    void invalidInputIsRejectedWith400() throws Exception {
        for (String[] bad : new String[][] {{"type", "game_won"}, {"eventId", "not-a-uuid"},
                {"path", "/x?email=a@b.c"}, {"path", null}}) {
            Fakes.Request request = validEvent(true);
            if (bad[1] == null) { request.params.remove(bad[0]); } else { request.param(bad[0], bad[1]); }
            assertEquals(400, Fakes.call(event, request).status, bad[0]);
        }
    }

    @Test
    void validRequestWithoutADatabaseIsReportedAsUnavailableNotCrashed() throws Exception {
        assertEquals(503, Fakes.call(event, validEvent(true)).status);
    }

    @Test
    void getIsNotAllowed() throws Exception {
        assertEquals(405, Fakes.call(event, Fakes.request(context, "GET")).status);
        assertEquals(405, Fakes.call(heartbeat, Fakes.request(context, "GET")).status);
    }

    @Test
    void heartbeatNeedsConsentAndTheCsrfHeader() throws Exception {
        assertEquals(403, Fakes.call(heartbeat, Fakes.request(context, "POST")).status);
        assertEquals(403, Fakes.call(heartbeat, Fakes.request(context, "POST").fetch()).status);
        assertEquals(403, Fakes.call(heartbeat, Fakes.request(context, "POST").fetch()
                .cookie(CookieNames.CONSENT, consent(false))).status);
    }

    @Test
    void floodingIsRateLimitedBeforeAnythingElseHappens() throws Exception {
        int last = 0;
        for (int i = 0; i < 125; i++) {
            last = Fakes.call(event, validEvent(false)).status;
        }
        assertEquals(429, last);
    }
}
