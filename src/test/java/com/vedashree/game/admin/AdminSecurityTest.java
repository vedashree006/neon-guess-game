package com.vedashree.game.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.AppContext;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.analytics.SummaryServlet;
import com.vedashree.game.analytics.TimeseriesServlet;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.security.PasswordHasher;
import com.vedashree.game.testsupport.Fakes;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Server-side authorization, login hardening, CSRF and session expiry of the admin area. */
class AdminSecurityTest {
    private static final String USER = "owner";
    private static final String PASSWORD = "correct horse battery";
    private static final String HASH = PasswordHasher.hash(PASSWORD.toCharArray(), 1000);
    private static final long MINUTE = 60_000L;

    private Fakes.Context context;
    private final AtomicLong clock = new AtomicLong(1_730_000_000_000L);
    private SummaryServlet summary;
    private TimeseriesServlet timeseries;
    private DashboardServlet dashboard;
    private LoginServlet login;
    private LogoutServlet logout;

    /** Admin servlet with a state-changing method, to prove the base class enforces CSRF. */
    static class ProbeServlet extends AdminServlet {
        private static final long serialVersionUID = 1L;
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            ServletSupport.writeJson(resp, 200, Map.of("ok", true));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        install(Map.of("ADMIN_USERNAME", USER, "ADMIN_PASSWORD_HASH", HASH));
    }

    private void install(Map<String, String> env) throws Exception {
        context = Fakes.context();
        AppConfig config = AppConfig.from(env::get);
        context.attributes.put(AppContext.ATTRIBUTE, AppContext.create(config, null, clock::get));
        summary = new SummaryServlet();
        timeseries = new TimeseriesServlet();
        dashboard = new DashboardServlet();
        login = new LoginServlet();
        logout = new LogoutServlet();
        for (jakarta.servlet.http.HttpServlet s : new jakarta.servlet.http.HttpServlet[] {summary, timeseries, dashboard, login, logout}) {
            Fakes.init(s, context);
        }
    }

    /** Opens the login form and returns the request whose session holds the CSRF token. */
    private Fakes.Request openLoginForm() throws Exception {
        Fakes.Request get = Fakes.request(context, "GET");
        Fakes.Response page = Fakes.call(login, get);
        assertEquals(200, page.status);
        return get;
    }

    private String csrf(Fakes.Request withSession) {
        return (String) withSession.session.attributes.get(AdminAuth.ATTR_CSRF);
    }

    private Fakes.Request signIn() throws Exception {
        Fakes.Request form = openLoginForm();
        Fakes.Request post = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", USER).param("password", PASSWORD);
        post.session = form.session;
        Fakes.Response response = Fakes.call(login, post);
        assertEquals(302, response.status);
        assertEquals("/admin/dashboard", response.headers.get("Location"));
        return post;
    }

    private Fakes.Request withSession(Fakes.Request previous, String method) {
        Fakes.Request next = Fakes.request(context, method);
        next.session = previous.session;
        return next;
    }

    /* ---------------------------------------------------------- authorization */

    @Test
    void anonymousVisitorsCannotReachAnyAnalyticsApi() throws Exception {
        assertEquals(401, Fakes.call(summary, Fakes.request(context, "GET")).status);
        assertEquals(401, Fakes.call(timeseries, Fakes.request(context, "GET")).status);
        Fakes.Response body = Fakes.call(summary, Fakes.request(context, "GET"));
        assertFalse(body.body().contains("visitors"), "no data in the 401 body");
    }

    @Test
    void anonymousVisitorsAreRedirectedFromTheDashboardPage() throws Exception {
        Fakes.Response response = Fakes.call(dashboard, Fakes.request(context, "GET"));
        assertEquals(302, response.status);
        assertEquals("/admin/login", response.headers.get("Location"));
        assertFalse(response.body().contains("Analytics dashboard"));
    }

    @Test
    void aSessionAttributeThatIsNotAFullLoginIsNotEnough() throws Exception {
        Fakes.Request request = Fakes.request(context, "GET");
        request.session = Fakes.session(context, "forged");
        request.session.attributes.put(AdminAuth.ATTR_USER, USER);
        assertEquals(401, Fakes.call(summary, request).status);
    }

    @Test
    void forgedCookiesCannotAuthenticate() throws Exception {
        Fakes.Request request = Fakes.request(context, "GET").cookie("JSESSIONID", "admin").cookie("neon_admin", "true");
        assertEquals(401, Fakes.call(summary, request).status);
    }

    @Test
    void adminApiPassesTheGateAfterLoginAndThenNeedsTheDatabase() throws Exception {
        Fakes.Request signedIn = signIn();
        // Authorised, but this test context has no database: 503 proves the gate was passed.
        assertEquals(503, Fakes.call(summary, withSession(signedIn, "GET")).status);
        assertEquals(503, Fakes.call(timeseries, withSession(signedIn, "GET")).status);
    }

    @Test
    void dashboardPageIsServedToAnAdminWithTheirCsrfTokenAndNoStore() throws Exception {
        Fakes.Request signedIn = signIn();
        Fakes.Response page = Fakes.call(dashboard, withSession(signedIn, "GET"));
        assertEquals(200, page.status);
        assertEquals("no-store", page.headers.get("Cache-Control"));
        String html = page.body();
        assertTrue(html.contains("Analytics dashboard"));
        assertTrue(html.contains(csrf(signedIn)));
        assertFalse(html.contains("%%"), "all template tokens replaced");
    }

    /* ---------------------------------------------------------- login */

    @Test
    void loginFormCarriesACsrfTokenAndIsNotCacheable() throws Exception {
        Fakes.Request get = Fakes.request(context, "GET");
        Fakes.Response page = Fakes.call(login, get);
        assertTrue(page.body().contains("name=\"csrf\" value=\"" + csrf(get) + "\""));
        assertEquals("no-store", page.headers.get("Cache-Control"));
    }

    @Test
    void loginWithoutAValidCsrfTokenIsRefused() throws Exception {
        Fakes.Request form = openLoginForm();
        Fakes.Request post = Fakes.request(context, "POST").param("csrf", "wrong")
                .param("username", USER).param("password", PASSWORD);
        post.session = form.session;
        assertEquals("/admin/login?e=csrf", Fakes.call(login, post).headers.get("Location"));
        Fakes.Request noSession = Fakes.request(context, "POST").param("username", USER).param("password", PASSWORD);
        assertEquals("/admin/login?e=csrf", Fakes.call(login, noSession).headers.get("Location"));
    }

    @Test
    void wrongPasswordAndWrongUsernameLookIdentical() throws Exception {
        Fakes.Request form = openLoginForm();
        Fakes.Request wrongPassword = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", USER).param("password", "nope nope nope");
        wrongPassword.session = form.session;
        Fakes.Request wrongUser = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", "intruder").param("password", PASSWORD);
        wrongUser.session = form.session;
        String a = Fakes.call(login, wrongPassword).headers.get("Location");
        String b = Fakes.call(login, wrongUser).headers.get("Location");
        assertEquals("/admin/login?e=invalid", a);
        assertEquals(a, b);
        assertNull(form.session.attributes.get(AdminAuth.ATTR_USER));
    }

    @Test
    void successfulLoginChangesTheSessionIdAndRotatesTheCsrfToken() throws Exception {
        Fakes.Request form = openLoginForm();
        String before = csrf(form);
        Fakes.Request post = Fakes.request(context, "POST").param("csrf", before)
                .param("username", USER).param("password", PASSWORD);
        post.session = form.session;
        Fakes.call(login, post);
        assertEquals(1, post.sessionIdChanges, "session fixation defence");
        assertEquals(USER, post.session.attributes.get(AdminAuth.ATTR_USER));
        assertNull(csrf(post), "old token discarded; a new one is issued on next use");
    }

    @Test
    void tooManyFailuresLockTheClientOutEvenWithTheRightPassword() throws Exception {
        Fakes.Request form = openLoginForm();
        for (int i = 0; i < 5; i++) {
            Fakes.Request bad = Fakes.request(context, "POST").param("csrf", csrf(form))
                    .param("username", USER).param("password", "bad bad bad bad");
            bad.session = form.session;
            assertEquals("/admin/login?e=invalid", Fakes.call(login, bad).headers.get("Location"));
        }
        Fakes.Request good = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", USER).param("password", PASSWORD);
        good.session = form.session;
        assertEquals("/admin/login?e=locked", Fakes.call(login, good).headers.get("Location"));
        assertNull(form.session.attributes.get(AdminAuth.ATTR_USER));
        clock.addAndGet(16 * MINUTE);
        Fakes.Request later = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", USER).param("password", PASSWORD);
        later.session = form.session;
        assertEquals("/admin/dashboard", Fakes.call(login, later).headers.get("Location"));
    }

    @Test
    void adminAreaIsDisabledUntilCredentialsAreConfigured() throws Exception {
        install(Map.of());
        Fakes.Request form = Fakes.request(context, "GET");
        assertTrue(Fakes.call(login, form).body().contains("not configured"));
        Fakes.Request post = Fakes.request(context, "POST").param("csrf", csrf(form))
                .param("username", "admin").param("password", "admin");
        post.session = form.session;
        assertEquals("/admin/login?e=unconfigured", Fakes.call(login, post).headers.get("Location"));
    }

    @Test
    void errorMessagesAreFixedTextNeverReflectedInput() throws Exception {
        Fakes.Request request = Fakes.request(context, "GET").param("e", "<script>alert(1)</script>");
        Fakes.Response page = Fakes.call(login, request);
        assertFalse(page.body().contains("<script>alert"));
        assertFalse(page.body().contains("class=\"alert\""));
    }

    /* ---------------------------------------------------------- expiry */

    @Test
    void anIdleAdminSessionExpires() throws Exception {
        Fakes.Request signedIn = signIn();
        clock.addAndGet(16 * MINUTE);
        assertEquals(401, Fakes.call(summary, withSession(signedIn, "GET")).status);
        assertNull(signedIn.session.attributes.get(AdminAuth.ATTR_USER), "attributes cleared on expiry");
    }

    @Test
    void backgroundPollingDoesNotKeepAnUnattendedDashboardSignedIn() throws Exception {
        Fakes.Request signedIn = signIn();
        clock.addAndGet(10 * MINUTE);
        assertEquals(503, Fakes.call(summary, withSession(signedIn, "GET")).status, "still valid at 10 min");
        clock.addAndGet(10 * MINUTE);
        assertEquals(401, Fakes.call(summary, withSession(signedIn, "GET")).status, "polling did not extend it");
    }

    @Test
    void openingTheDashboardPageExtendsTheIdleTimeout() throws Exception {
        Fakes.Request signedIn = signIn();
        clock.addAndGet(10 * MINUTE);
        assertEquals(200, Fakes.call(dashboard, withSession(signedIn, "GET")).status);
        clock.addAndGet(10 * MINUTE);
        assertEquals(503, Fakes.call(summary, withSession(signedIn, "GET")).status);
    }

    @Test
    void thereIsAnAbsoluteSessionLifetimeEvenForAnActiveAdmin() throws Exception {
        install(Map.of("ADMIN_USERNAME", USER, "ADMIN_PASSWORD_HASH", HASH, "ADMIN_MAX_SESSION_MINUTES", "30"));
        Fakes.Request signedIn = signIn();
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(10 * MINUTE);
            Fakes.call(dashboard, withSession(signedIn, "GET"));
        }
        clock.addAndGet(1 * MINUTE);
        assertEquals(302, Fakes.call(dashboard, withSession(signedIn, "GET")).status);
    }

    /* ---------------------------------------------------------- logout and CSRF */

    @Test
    void logoutIsPostOnlyAndNeedsTheCsrfToken() throws Exception {
        Fakes.Request signedIn = signIn();
        assertEquals(405, Fakes.call(logout, withSession(signedIn, "GET")).status);

        Fakes.Request forged = withSession(signedIn, "POST");
        assertEquals("/admin/login?e=csrf", Fakes.call(logout, forged).headers.get("Location"));
        assertFalse(signedIn.session.invalidated, "a cross-site logout must not work");

        Fakes.Request genuine = withSession(signedIn, "POST");
        Fakes.call(dashboard, withSession(signedIn, "GET"));
        genuine.param("csrf", csrf(signedIn));
        assertEquals("/admin/login?e=out", Fakes.call(logout, genuine).headers.get("Location"));
        assertTrue(signedIn.session.invalidated);
    }

    @Test
    void stateChangingAdminRequestsNeedACsrfToken() throws Exception {
        ProbeServlet probe = new ProbeServlet();
        Fakes.init(probe, context);
        Fakes.Request signedIn = signIn();
        Fakes.call(dashboard, withSession(signedIn, "GET"));

        assertEquals(403, Fakes.call(probe, withSession(signedIn, "POST")).status);
        assertEquals(403, Fakes.call(probe, withSession(signedIn, "POST").header("X-CSRF-Token", "guess")).status);
        assertEquals(200, Fakes.call(probe, withSession(signedIn, "POST").header("X-CSRF-Token", csrf(signedIn))).status);
        assertEquals(401, Fakes.call(probe, Fakes.request(context, "POST")).status, "and anonymous is 401 first");
    }

    @Test
    void adminEndpointsAreRateLimited() throws Exception {
        int last = 0;
        for (int i = 0; i < 125; i++) {
            last = Fakes.call(summary, Fakes.request(context, "GET")).status;
        }
        assertEquals(429, last);
    }

    @Test
    void auditLogDoesNotRequireADatabase() {
        assertNotNull(new AuditLog(null));
        assertNotEquals(HASH, PASSWORD);
    }
}
