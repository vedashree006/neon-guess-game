package com.vedashree.game;

import com.vedashree.game.admin.AdminAuth;
import com.vedashree.game.admin.AuditLog;
import com.vedashree.game.analytics.AnalyticsRepository;
import com.vedashree.game.analytics.AnalyticsService;
import com.vedashree.game.analytics.LiveSessionRegistry;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.privacy.ConsentLog;
import com.vedashree.game.security.LoginThrottle;
import com.vedashree.game.security.RateLimiter;
import jakarta.servlet.ServletContext;
import java.util.function.LongSupplier;

/**
 * Wiring root: one instance per deployed application, stored as a ServletContext attribute.
 * Database-backed parts are null when the database could not be opened; every user of them
 * checks for that, so the game itself keeps working.
 */
public final class AppContext {
    public static final String ATTRIBUTE = "neon.app";
    private static final long MINUTE = 60_000L;

    private final AppConfig config;
    private final Database database;
    private final AnalyticsService analytics;
    private final ConsentLog consentLog;
    private final LiveSessionRegistry sessions;
    private final AdminAuth adminAuth;
    private final RateLimiter eventLimiter;
    private final RateLimiter newVisitorLimiter;
    private final RateLimiter consentLimiter;
    private final RateLimiter adminLimiter;
    private final LoginThrottle loginThrottle;
    private final LongSupplier clock;

    private AppContext(AppConfig config, Database database, LongSupplier clock) {
        this.config = config;
        this.database = database;
        this.clock = clock;
        this.analytics = database == null ? null
                : new AnalyticsService(config, new AnalyticsRepository(database), clock);
        this.consentLog = database == null ? null : new ConsentLog(database);
        this.sessions = new LiveSessionRegistry(config, clock);
        this.eventLimiter = new RateLimiter(120, MINUTE, clock);
        this.newVisitorLimiter = new RateLimiter(20, MINUTE, clock);
        this.consentLimiter = new RateLimiter(30, MINUTE, clock);
        this.adminLimiter = new RateLimiter(120, MINUTE, clock);
        this.loginThrottle = new LoginThrottle(5, 15 * MINUTE, 15 * MINUTE, clock);
        this.adminAuth = new AdminAuth(config, clock, loginThrottle, new AuditLog(database));
    }

    public static AppContext create(AppConfig config, Database database, LongSupplier clock) {
        return new AppContext(config, database, clock);
    }

    /** @return the context, or null if the application failed to start. */
    public static AppContext from(ServletContext servletContext) {
        Object value = servletContext.getAttribute(ATTRIBUTE);
        return value instanceof AppContext app ? app : null;
    }

    public AppConfig config() { return config; }
    /** May be null. */
    public Database database() { return database; }
    /** May be null (no database). */
    public AnalyticsService analytics() { return analytics; }
    /** May be null (no database). */
    public ConsentLog consentLog() { return consentLog; }
    public LiveSessionRegistry sessions() { return sessions; }
    public AdminAuth adminAuth() { return adminAuth; }
    public RateLimiter eventLimiter() { return eventLimiter; }
    public RateLimiter newVisitorLimiter() { return newVisitorLimiter; }
    public RateLimiter consentLimiter() { return consentLimiter; }
    public RateLimiter adminLimiter() { return adminLimiter; }
    public LoginThrottle loginThrottle() { return loginThrottle; }
    public LongSupplier clock() { return clock; }
}
