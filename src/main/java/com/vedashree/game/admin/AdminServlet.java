package com.vedashree.game.admin;

import com.vedashree.game.AppContext;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.security.ClientIp;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * Base class for every admin-only servlet. Authorization happens here, on the server, before
 * any doGet/doPost runs, and {@code service} is final, so a subclass cannot forget the check
 * (deny by default). State-changing methods additionally need a valid CSRF token.
 */
public abstract class AdminServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected final void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (!app.adminLimiter().tryAcquire(ClientIp.resolve(req, app.config().trustProxy()))) {
            resp.setHeader("Retry-After", "60");
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        Optional<String> admin = app.adminAuth().currentAdmin(req, touchOnAccess());
        if (admin.isEmpty()) {
            onUnauthenticated(req, resp);
            return;
        }
        if (isStateChanging(req.getMethod())) {
            String token = req.getHeader("X-CSRF-Token");
            if (token == null) {
                token = req.getParameter("csrf");
            }
            if (!app.adminAuth().csrfMatches(req, token)) {
                ServletSupport.writeError(resp, HttpServletResponse.SC_FORBIDDEN, "Invalid CSRF token.");
                return;
            }
        }
        resp.setHeader("Cache-Control", "no-store");
        super.service(req, resp);
    }

    /** True for pages a person opens (extends the idle timeout); false for background polling. */
    protected boolean touchOnAccess() {
        return false;
    }

    /** Default for APIs: 401 JSON. Pages override this to redirect to the login form. */
    protected void onUnauthenticated(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletSupport.writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required.");
    }

    private static boolean isStateChanging(String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }
}
