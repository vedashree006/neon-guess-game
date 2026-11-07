package com.vedashree.game.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.AppContext;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.testsupport.Fakes;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consent API behaviour. No database needed: the cookie is the source of truth. */
class ConsentServletTest {
    private Fakes.Context context;
    private ConsentServlet servlet;
    private final AtomicLong clock = new AtomicLong(1_730_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        install(AppConfig.defaults());
    }

    private void install(AppConfig config) throws Exception {
        context = Fakes.context();
        context.attributes.put(AppContext.ATTRIBUTE, AppContext.create(config, null, clock::get));
        servlet = new ConsentServlet();
        Fakes.init(servlet, context);
    }

    private Fakes.Response save(String analytics, String preferences, Fakes.Request base) throws Exception {
        base.method = "POST";
        base.fetch().param("analytics", analytics).param("preferences", preferences);
        return Fakes.call(servlet, base);
    }

    private Fakes.Response save(String analytics, String preferences) throws Exception {
        return save(analytics, preferences, Fakes.request(context, "POST"));
    }

    @Test
    void withoutADecisionNothingOptionalIsAllowed() throws Exception {
        Fakes.Response response = Fakes.call(servlet, Fakes.request(context, "GET"));
        assertEquals(200, response.status);
        String body = response.body();
        assertTrue(body.contains("\"decided\":false"));
        assertTrue(body.contains("\"analytics\":false"));
        assertTrue(body.contains("\"preferences\":false"));
        assertEquals("no-store", response.headers.get("Cache-Control"));
        assertTrue(response.cookies.isEmpty(), "reading the state must not set cookies");
    }

    @Test
    void postRequiresTheCsrfHeader() throws Exception {
        Fakes.Request request = Fakes.request(context, "POST").param("analytics", "true").param("preferences", "true");
        assertEquals(403, Fakes.call(servlet, request).status);
    }

    @Test
    void postRejectsAnythingButTrueOrFalse() throws Exception {
        for (String bad : new String[] {null, "", "1", "yes", "TRUE", "on"}) {
            assertEquals(400, save(bad, "false").status, "analytics=" + bad);
            assertEquals(400, save("false", bad).status, "preferences=" + bad);
        }
    }

    @Test
    void acceptAllSetsAnHttpOnlyStrictConsentCookie() throws Exception {
        Fakes.Response response = save("true", "true");
        assertEquals(200, response.status);
        Cookie cookie = response.cookie(CookieNames.CONSENT);
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertEquals("Strict", cookie.getAttribute("SameSite"));
        assertEquals("/", cookie.getPath());
        assertEquals(180 * 86_400, cookie.getMaxAge());
        assertFalse(cookie.getSecure(), "development: no Secure flag so http://localhost works");
        ConsentState state = ConsentState.parse(cookie.getValue()).orElseThrow();
        assertTrue(state.analytics());
        assertTrue(state.preferences());
        assertEquals(1_730_000_000L, state.decidedAtSeconds());
        assertTrue(response.body().contains("\"decided\":true"));
        assertFalse(response.body().contains(state.consentId()), "the consent id never leaves the cookie");
    }

    @Test
    void productionMarksCookiesSecure() throws Exception {
        install(AppConfig.from(Map.of("APP_ENV", "production")::get));
        assertTrue(save("true", "false").cookie(CookieNames.CONSENT).getSecure());
    }

    @Test
    void rejectOptionalStoresTheChoiceButNoOptionalCookie() throws Exception {
        Fakes.Response response = save("false", "false");
        ConsentState state = ConsentState.parse(response.cookie(CookieNames.CONSENT).getValue()).orElseThrow();
        assertFalse(state.analytics());
        assertFalse(state.preferences());
        Cookie visitor = response.cookie(CookieNames.VISITOR);
        assertTrue(visitor == null || visitor.getMaxAge() == 0, "no tracking cookie may be set");
    }

    @Test
    void withdrawingConsentDeletesTheOptionalCookies() throws Exception {
        Fakes.Request request = Fakes.request(context, "POST")
                .cookie(CookieNames.VISITOR, "AAAAAAAAAAAAAAAAAAAAAA")
                .cookie(CookieNames.PREFERENCES, "x");
        Fakes.Response response = save("false", "false", request);
        Cookie visitor = response.cookie(CookieNames.VISITOR);
        Cookie prefs = response.cookie(CookieNames.PREFERENCES);
        assertNotNull(visitor);
        assertEquals(0, visitor.getMaxAge());
        assertEquals("/", visitor.getPath());
        assertNotNull(prefs);
        assertEquals(0, prefs.getMaxAge());
    }

    @Test
    void keepingPreferencesDoesNotDeleteThem() throws Exception {
        Fakes.Response response = save("false", "true");
        assertNull(response.cookie(CookieNames.PREFERENCES));
    }

    @Test
    void changingTheChoiceKeepsTheSameConsentId() throws Exception {
        String first = save("true", "true").cookie(CookieNames.CONSENT).getValue();
        String id = ConsentState.parse(first).orElseThrow().consentId();
        clock.addAndGet(60_000);
        Fakes.Request second = Fakes.request(context, "POST").cookie(CookieNames.CONSENT, first);
        String updated = save("false", "true", second).cookie(CookieNames.CONSENT).getValue();
        assertEquals(id, ConsentState.parse(updated).orElseThrow().consentId());
    }

    @Test
    void aDecisionForAnOlderPolicyVersionAsksAgain() throws Exception {
        String old = ConsentState.decide(1, true, true, 1_730_000_000L, UUID.randomUUID().toString()).serialize();
        install(AppConfig.from(Map.of("CONSENT_POLICY_VERSION", "2")::get));
        Fakes.Response response = Fakes.call(servlet, Fakes.request(context, "GET").cookie(CookieNames.CONSENT, old));
        assertTrue(response.body().contains("\"decided\":false"));
        assertTrue(response.body().contains("\"analytics\":false"));
    }

    @Test
    void aForgedOrCorruptCookieIsTreatedAsNoDecision() throws Exception {
        Fakes.Response response = Fakes.call(servlet,
                Fakes.request(context, "GET").cookie(CookieNames.CONSENT, "1.1.1.evil"));
        assertTrue(response.body().contains("\"decided\":false"));
    }

    @Test
    void postsAreRateLimited() throws Exception {
        int last = 0;
        for (int i = 0; i < 31; i++) {
            last = save("false", "false").status;
        }
        assertEquals(429, last);
    }
}
