package com.vedashree.game;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** GET /healthz for hosting health checks: 200 when the database answers, 503 otherwise. No details leak. */
@WebServlet(name = "HealthServlet", urlPatterns = "/healthz")
public class HealthServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        boolean ok = false;
        Object attribute = getServletContext().getAttribute(ServletSupport.DB_ATTRIBUTE);
        if (attribute instanceof Database database) {
            try (Connection c = database.openConnection()) {
                ok = c.isValid(2);
            } catch (Exception e) {
                ok = false;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "degraded");
        ServletSupport.writeJson(resp, ok ? HttpServletResponse.SC_OK
                : HttpServletResponse.SC_SERVICE_UNAVAILABLE, body);
    }
}
