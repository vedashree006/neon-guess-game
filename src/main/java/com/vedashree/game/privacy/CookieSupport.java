package com.vedashree.game.privacy;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/** Reads and writes first-party cookies with consistent, restrictive attributes. */
public final class CookieSupport {
    private CookieSupport() { }

    /** Cookie path: the application root, e.g. "/neon-guess-game/" or "/". */
    public static String path(HttpServletRequest request) {
        String context = request.getContextPath();
        return (context == null ? "" : context) + "/";
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** SameSite=Strict on every cookie; HttpOnly unless client-side code must read it. */
    public static void set(HttpServletRequest request, HttpServletResponse response, String name,
                           String value, int maxAgeSeconds, boolean httpOnly, boolean secure) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath(path(request));
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secure);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    /** Deletes a cookie by overwriting it with Max-Age=0 (same name and path). */
    public static void expire(HttpServletRequest request, HttpServletResponse response, String name,
                              boolean httpOnly, boolean secure) {
        set(request, response, name, "", 0, httpOnly, secure);
    }
}
