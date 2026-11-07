package com.vedashree.game.admin;

import com.vedashree.game.AppContext;
import com.vedashree.game.ServletSupport;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** POST /admin/logout (csrf token required): invalidates the session. GET is not allowed (405). */
@WebServlet(name = "AdminLogoutServlet", urlPatterns = "/admin/logout")
public class LogoutServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        AppContext app = AppContext.from(getServletContext());
        if (app == null) {
            ServletSupport.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service unavailable.");
            return;
        }
        if (app.adminAuth().csrfMatches(req, req.getParameter("csrf"))) {
            app.adminAuth().logout(req);
            resp.sendRedirect(req.getContextPath() + "/admin/login?e=out");
        } else {
            resp.sendRedirect(req.getContextPath() + "/admin/login?e=csrf");
        }
    }
}
