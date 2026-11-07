package com.vedashree.game;

import com.vedashree.game.config.AppConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Adds defensive HTTP headers (CSP, nosniff, framing, referrer, HSTS in production) to every response. */
@WebFilter(filterName = "SecurityHeadersFilter", urlPatterns = "/*")
public class SecurityHeadersFilter implements Filter {
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    private boolean hsts;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        AppContext app = AppContext.from(filterConfig.getServletContext());
        AppConfig config = app == null ? AppConfig.defaults() : app.config();
        // HSTS only makes sense (and is only honoured) over HTTPS, so tie it to secure cookies.
        this.hsts = config.cookieSecure();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader("Content-Security-Policy", CSP);
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
            http.setHeader("Cross-Origin-Opener-Policy", "same-origin");
            http.setHeader("Cross-Origin-Resource-Policy", "same-origin");
            if (hsts) {
                http.setHeader("Strict-Transport-Security", "max-age=31536000");
            }
        }
        chain.doFilter(request, response);
    }
}
