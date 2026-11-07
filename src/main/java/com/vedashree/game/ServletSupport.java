package com.vedashree.game;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/** Small helpers shared by all servlets. */
public final class ServletSupport {
    /** ServletContext attribute that holds the shared {@link Database}. */
    public static final String DB_ATTRIBUTE = "neon.database";
    /** The Servlet API has no constant for 429. */
    public static final int SC_TOO_MANY_REQUESTS = 429;

    private ServletSupport() { }

    public static void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(Json.write(body));
    }

    public static void writeError(HttpServletResponse resp, int status, String message)
            throws IOException {
        writeJson(resp, status, Map.of("error", message));
    }

    /** Status line only (for 204 responses and similar). */
    public static void writeEmpty(HttpServletResponse resp, int status) {
        resp.setStatus(status);
        resp.setHeader("Cache-Control", "no-store");
    }

    /**
     * CSRF defence for JSON endpoints: browsers cannot add this custom header on a cross-site
     * form post, and a cross-origin fetch with it triggers a CORS preflight that is never approved.
     *
     * @return true when the header is present; otherwise a 403 was written and the caller must stop
     */
    public static boolean requireFetchHeader(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        if ("fetch".equals(req.getHeader("X-Requested-With"))) {
            return true;
        }
        writeError(resp, HttpServletResponse.SC_FORBIDDEN, "Missing X-Requested-With header.");
        return false;
    }
}
