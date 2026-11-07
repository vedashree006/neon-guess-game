package com.vedashree.game.testsupport;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal servlet-API fakes built on java.lang.reflect.Proxy, so the tests need no mocking
 * library and exercise the real servlet classes (doGet/doPost, cookies, sessions, redirects).
 */
public final class Fakes {
    private Fakes() { }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        return null;
    }

    /* ------------------------------------------------------------ context */

    public static final class Context {
        public final Map<String, Object> attributes = new ConcurrentHashMap<>();
        public final Map<String, String> sessionCookieAttributes = new LinkedHashMap<>();
        public boolean sessionCookieSecure;
        public boolean sessionCookieHttpOnly;
        public int sessionTimeoutMinutes = -1;
        public final ServletContext proxy;

        Context() {
            SessionCookieConfig cookieConfig = (SessionCookieConfig) Proxy.newProxyInstance(
                    Fakes.class.getClassLoader(), new Class<?>[] {SessionCookieConfig.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setHttpOnly": sessionCookieHttpOnly = (Boolean) a[0]; return null;
                            case "setSecure": sessionCookieSecure = (Boolean) a[0]; return null;
                            case "setAttribute": sessionCookieAttributes.put((String) a[0], (String) a[1]); return null;
                            default: return defaultValue(m);
                        }
                    });
            this.proxy = (ServletContext) Proxy.newProxyInstance(
                    Fakes.class.getClassLoader(), new Class<?>[] {ServletContext.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "getAttribute": return attributes.get((String) a[0]);
                            case "setAttribute": attributes.put((String) a[0], a[1]); return null;
                            case "removeAttribute": attributes.remove((String) a[0]); return null;
                            case "getContextPath": return "";
                            case "getSessionCookieConfig": return cookieConfig;
                            case "setSessionTimeout": sessionTimeoutMinutes = (Integer) a[0]; return null;
                            case "getResourceAsStream": return openWebapp((String) a[0]);
                            default: return defaultValue(m);
                        }
                    });
        }

        private static InputStream openWebapp(String path) throws IOException {
            Path file = Path.of("src/main/webapp" + path);
            return Files.exists(file) ? Files.newInputStream(file) : null;
        }
    }

    public static Context context() {
        return new Context();
    }

    /** Initialises a servlet the way the container does. */
    public static void init(HttpServlet servlet, Context context) throws ServletException {
        ServletConfig config = (ServletConfig) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(), new Class<?>[] {ServletConfig.class}, (p, m, a) -> {
                    if ("getServletContext".equals(m.getName())) { return context.proxy; }
                    if ("getServletName".equals(m.getName())) { return "test"; }
                    return defaultValue(m);
                });
        servlet.init(config);
    }

    /* ------------------------------------------------------------ session */

    public static final class Session {
        public final Map<String, Object> attributes = new ConcurrentHashMap<>();
        public String id;
        public long lastAccessed;
        public long created;
        public int maxInactive = 1800;
        public boolean invalidated;
        public final HttpSession proxy;

        Session(String id, Context context, long now) {
            this.id = id;
            this.created = now;
            this.lastAccessed = now;
            this.proxy = (HttpSession) Proxy.newProxyInstance(
                    Fakes.class.getClassLoader(), new Class<?>[] {HttpSession.class}, (p, m, a) -> {
                        if (invalidated && !"isNew".equals(m.getName())) {
                            throw new IllegalStateException("session invalidated");
                        }
                        switch (m.getName()) {
                            case "getId": return this.id;
                            case "getAttribute": return attributes.get((String) a[0]);
                            case "setAttribute": attributes.put((String) a[0], a[1]); return null;
                            case "removeAttribute": attributes.remove((String) a[0]); return null;
                            case "invalidate": invalidated = true; return null;
                            case "getLastAccessedTime": return lastAccessed;
                            case "getCreationTime": return created;
                            case "getMaxInactiveInterval": return maxInactive;
                            case "setMaxInactiveInterval": maxInactive = (Integer) a[0]; return null;
                            case "getServletContext": return context.proxy;
                            default: return defaultValue(m);
                        }
                    });
        }
    }

    public static Session session(Context context, String id) {
        return new Session(id, context, System.currentTimeMillis());
    }

    /* ------------------------------------------------------------ request */

    public static final class Request {
        public String method = "GET";
        public String remoteAddr = "203.0.113.7";
        public final Map<String, String> params = new LinkedHashMap<>();
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final List<Cookie> cookies = new ArrayList<>();
        public Session session;
        public int sessionIdChanges;
        public final HttpServletRequest proxy;
        private int idCounter;

        Request(Context context) {
            this.proxy = (HttpServletRequest) Proxy.newProxyInstance(
                    Fakes.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "getMethod": return method == null ? "GET" : method;
                            case "getProtocol": return "HTTP/1.1";
                            case "getParameter": return params.get((String) a[0]);
                            case "getHeader":
                                for (Map.Entry<String, String> e : headers.entrySet()) {
                                    if (e.getKey().equalsIgnoreCase((String) a[0])) { return e.getValue(); }
                                }
                                return null;
                            case "getCookies": return cookies.isEmpty() ? null : cookies.toArray(new Cookie[0]);
                            case "getContextPath": return "";
                            case "getRequestURI": return "/";
                            case "getRemoteAddr": return remoteAddr;
                            case "getServletContext": return context.proxy;
                            case "isSecure": return false;
                            case "getSession":
                                boolean create = a == null || a.length == 0 || (Boolean) a[0];
                                if (session != null && session.invalidated) { session = null; }
                                if (session == null && create) {
                                    session = new Session("sid-" + (++idCounter), context, System.currentTimeMillis());
                                }
                                return session == null ? null : session.proxy;
                            case "changeSessionId":
                                sessionIdChanges++;
                                session.id = "sid-changed-" + sessionIdChanges;
                                return session.id;
                            default: return defaultValue(m);
                        }
                    });
        }

        public Request post(String name, String value) {
            method = "POST";
            params.put(name, value);
            return this;
        }

        public Request param(String name, String value) {
            params.put(name, value);
            return this;
        }

        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Request cookie(String name, String value) {
            cookies.add(new Cookie(name, value));
            return this;
        }

        public Request fetch() {
            return header("X-Requested-With", "fetch");
        }
    }

    public static Request request(Context context, String method) {
        Request request = new Request(context);
        request.method = method;
        return request;
    }

    /* ----------------------------------------------------------- response */

    public static final class Response {
        public int status = 200;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final List<Cookie> cookies = new ArrayList<>();
        public String contentType;
        private final StringWriter buffer = new StringWriter();
        private final PrintWriter writer = new PrintWriter(buffer);
        public final HttpServletResponse proxy;

        Response() {
            this.proxy = (HttpServletResponse) Proxy.newProxyInstance(
                    Fakes.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "setStatus": status = (Integer) a[0]; return null;
                            case "getStatus": return status;
                            case "setHeader":
                            case "addHeader": headers.put((String) a[0], (String) a[1]); return null;
                            case "getHeader": return headers.get((String) a[0]);
                            case "addCookie": cookies.add((Cookie) a[0]); return null;
                            case "setContentType": contentType = (String) a[0]; return null;
                            case "getWriter": return writer;
                            case "sendRedirect": status = 302; headers.put("Location", (String) a[0]); return null;
                            case "sendError": status = (Integer) a[0]; return null;
                            default: return defaultValue(m);
                        }
                    });
        }

        public String body() {
            writer.flush();
            return buffer.toString();
        }

        /** The last Set-Cookie for this name, or null. */
        public Cookie cookie(String name) {
            Cookie found = null;
            for (Cookie c : cookies) {
                if (c.getName().equals(name)) { found = c; }
            }
            return found;
        }
    }

    public static Request requestWithSession(Context context, String method, Session session) {
        Request request = new Request(context);
        request.method = method;
        request.session = session;
        return request;
    }

    public static Response response() {
        return new Response();
    }

    /** Runs the request through the servlet's public service() entry point. */
    public static Response call(HttpServlet servlet, Request request) throws Exception {
        Response response = new Response();
        servlet.service((jakarta.servlet.ServletRequest) request.proxy, (jakarta.servlet.ServletResponse) response.proxy);
        return response;
    }
}
