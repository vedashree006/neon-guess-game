package com.vedashree.game.admin;

import com.vedashree.game.config.AppConfig;
import com.vedashree.game.security.LoginThrottle;
import com.vedashree.game.security.PasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Admin authentication on top of the container session.
 *
 * <ul>
 *   <li>Credentials come from the environment: ADMIN_USERNAME and a PBKDF2 ADMIN_PASSWORD_HASH.
 *       Without both, the admin area is disabled (secure by default).</li>
 *   <li>Login changes the session id (session-fixation defence) and rotates the CSRF token.</li>
 *   <li>Two limits apply: an idle timeout and an absolute lifetime. Dashboard polling does not
 *       extend the idle timeout, so an unattended dashboard signs itself out.</li>
 * </ul>
 */
public final class AdminAuth {
    public enum LoginResult { SUCCESS, INVALID, LOCKED, NOT_CONFIGURED }

    public static final String ATTR_USER = "neon.admin.user";
    static final String ATTR_LOGIN_MS = "neon.admin.loginMs";
    static final String ATTR_SEEN_MS = "neon.admin.seenMs";
    static final String ATTR_CSRF = "neon.admin.csrf";
    private static final int MAX_USERNAME = 64;
    private static final int MAX_PASSWORD = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Verified when the username is wrong, so both failure paths cost the same time. */
    private static final class Dummy {
        static final String HASH = PasswordHasher.hash("not-a-real-password".toCharArray());
    }

    private final AppConfig config;
    private final LongSupplier clock;
    private final LoginThrottle throttle;
    private final AuditLog audit;

    public AdminAuth(AppConfig config, LongSupplier clock, LoginThrottle throttle, AuditLog audit) {
        this.config = config;
        this.clock = clock;
        this.throttle = throttle;
        this.audit = audit;
    }

    public boolean isConfigured() {
        return config.adminConfigured();
    }

    public LoginResult login(HttpServletRequest request, String username, char[] password, String clientKey) {
        if (!config.adminConfigured()) {
            return LoginResult.NOT_CONFIGURED;
        }
        if (throttle.isBlocked(clientKey)) {
            return LoginResult.LOCKED;
        }
        boolean wellFormed = username != null && password != null
                && username.length() <= MAX_USERNAME && password.length <= MAX_PASSWORD;
        boolean userOk = wellFormed && constantTimeEquals(username, config.adminUsername());
        boolean passwordOk = wellFormed
                && PasswordHasher.verify(password, userOk ? config.adminPasswordHash() : Dummy.HASH);
        long now = clock.getAsLong();
        if (!(userOk && passwordOk)) {
            throttle.recordFailure(clientKey);
            audit.record("LOGIN_FAILED", null, now);
            return LoginResult.INVALID;
        }

        throttle.recordSuccess(clientKey);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.removeAttribute(ATTR_CSRF);
        session.setAttribute(ATTR_USER, config.adminUsername());
        session.setAttribute(ATTR_LOGIN_MS, now);
        session.setAttribute(ATTR_SEEN_MS, now);
        audit.record("LOGIN_OK", config.adminUsername(), now);
        return LoginResult.SUCCESS;
    }

    /**
     * @param touch true for real user navigation (extends the idle timeout); false for background polling
     * @return the admin username if the session is authenticated and not expired
     */
    public Optional<String> currentAdmin(HttpServletRequest request, boolean touch) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object user = session.getAttribute(ATTR_USER);
        if (!(user instanceof String name)) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (!isAuthenticated(session, now, config)) {
            clear(session);
            audit.record("SESSION_EXPIRED", name, now);
            return Optional.empty();
        }
        if (touch) {
            session.setAttribute(ATTR_SEEN_MS, now);
        }
        return Optional.of(name);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Object user = session.getAttribute(ATTR_USER);
        if (user instanceof String name) {
            audit.record("LOGOUT", name, clock.getAsLong());
        }
        session.invalidate();
    }

    /** Pure check used by the dashboard and by the live-session gauge. No side effects. */
    public static boolean isAuthenticated(HttpSession session, long nowMs, AppConfig config) {
        Object user = session.getAttribute(ATTR_USER);
        Object login = session.getAttribute(ATTR_LOGIN_MS);
        Object seen = session.getAttribute(ATTR_SEEN_MS);
        if (!(user instanceof String) || !(login instanceof Long loginMs) || !(seen instanceof Long seenMs)) {
            return false;
        }
        return nowMs - loginMs <= config.adminMaxSessionMinutes() * 60_000L
                && nowMs - seenMs <= config.adminIdleMinutes() * 60_000L;
    }

    private static void clear(HttpSession session) {
        session.removeAttribute(ATTR_USER);
        session.removeAttribute(ATTR_LOGIN_MS);
        session.removeAttribute(ATTR_SEEN_MS);
        session.removeAttribute(ATTR_CSRF);
    }

    /** Session-bound anti-CSRF token (created on first use, rotated on login). */
    public String csrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(ATTR_CSRF);
        if (existing instanceof String token) {
            return token;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        session.setAttribute(ATTR_CSRF, token);
        return token;
    }

    public boolean csrfMatches(HttpServletRequest request, String provided) {
        HttpSession session = request.getSession(false);
        if (session == null || provided == null) {
            return false;
        }
        Object expected = session.getAttribute(ATTR_CSRF);
        return expected instanceof String token && constantTimeEquals(token, provided);
    }

    private static boolean constantTimeEquals(String a, String b) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] left = digest.digest(a.getBytes(StandardCharsets.UTF_8));
            byte[] right = digest.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(left, right);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
