package com.vedashree.game.admin;

import com.vedashree.game.AppContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * GET /admin/dashboard. The HTML lives in /WEB-INF, so it can only be delivered through this
 * servlet, after the server-side admin check in {@link AdminServlet}.
 */
@WebServlet(name = "AdminDashboardServlet", urlPatterns = "/admin/dashboard")
public class DashboardServlet extends AdminServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean touchOnAccess() {
        return true;
    }

    @Override
    protected void onUnauthenticated(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.sendRedirect(req.getContextPath() + "/admin/login");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        Templates.render(getServletContext(), resp, "/WEB-INF/admin/dashboard.html",
                Map.of("CSRF", app.adminAuth().csrfToken(req)));
    }
}
