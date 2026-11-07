package com.vedashree.game.admin;

import com.vedashree.game.AppContext;
import com.vedashree.game.ServletSupport;
import com.vedashree.game.security.ClientIp;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * GET  /admin/login   sign-in form (with a session-bound CSRF token)
 * POST /admin/login   username, password, csrf  ->  302 to the dashboard, or back to the form
 *                     with a fixed error code (no request text is ever reflected).
 */
@WebServlet(name = "AdminLoginServlet", urlPatterns = "/admin/login")
public class LoginServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String TEMPLATE = "/WEB-INF/admin/login.html";

    private static final Map<String, String> MESSAGES = Map.of(
            "invalid", "Invalid username or password.",
            "locked", "Too many failed attempts. Please try again later.",
            "csrf", "The form expired. Please try again.",
            "expired", "Your admin session expired. Please sign in again.",
            "unconfigured", "Admin access is not configured on this server.",
            "out", "You have been signed out.");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (!app.adminLimiter().tryAcquire(ClientIp.resolve(req, app.config().trustProxy()))) {
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        if (app.adminAuth().currentAdmin(req, false).isPresent()) {
            resp.sendRedirect(req.getContextPath() + "/admin/dashboard");
            return;
        }
        String code = req.getParameter("e");
        String message = code == null ? null : MESSAGES.get(code);
        if (message == null && !app.adminAuth().isConfigured()) {
            message = MESSAGES.get("unconfigured");
        }
        String banner = message == null ? ""
                : "<p class=\"alert\" role=\"alert\">" + message + "</p>";
        Templates.render(getServletContext(), resp, TEMPLATE,
                Map.of("CSRF", app.adminAuth().csrfToken(req), "MESSAGE", banner));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        String ip = ClientIp.resolve(req, app.config().trustProxy());
        if (!app.adminLimiter().tryAcquire(ip)) {
            ServletSupport.writeError(resp, ServletSupport.SC_TOO_MANY_REQUESTS, "Too many requests.");
            return;
        }
        if (!app.adminAuth().csrfMatches(req, req.getParameter("csrf"))) {
            redirect(req, resp, "csrf");
            return;
        }
        String password = req.getParameter("password");
        AdminAuth.LoginResult result = app.adminAuth().login(req, req.getParameter("username"),
                password == null ? null : password.toCharArray(), ip);
        switch (result) {
            case SUCCESS:
                resp.sendRedirect(req.getContextPath() + "/admin/dashboard");
                break;
            case LOCKED:
                redirect(req, resp, "locked");
                break;
            case NOT_CONFIGURED:
                redirect(req, resp, "unconfigured");
                break;
            default:
                redirect(req, resp, "invalid");
        }
    }

    private static void redirect(HttpServletRequest req, HttpServletResponse resp, String code) throws IOException {
        resp.sendRedirect(req.getContextPath() + "/admin/login?e=" + code);
    }
}
