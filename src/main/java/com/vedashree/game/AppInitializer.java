package com.vedashree.game;

import com.vedashree.game.analytics.MaintenanceScheduler;
import com.vedashree.game.config.AppConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.annotation.WebListener;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Runs once at deployment: loads configuration, hardens the session cookie, prepares the database. */
@WebListener
public class AppInitializer implements ServletContextListener {
    private static final Logger LOG = Logger.getLogger(AppInitializer.class.getName());
    private static final String SCHEDULER_ATTRIBUTE = "neon.maintenance";

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        AppConfig config = AppConfig.fromEnvironment();

        // Session cookie (necessary): HttpOnly, SameSite=Strict, Secure in production.
        SessionCookieConfig cookie = context.getSessionCookieConfig();
        cookie.setHttpOnly(true);
        cookie.setSecure(config.cookieSecure());
        cookie.setAttribute("SameSite", "Strict");
        context.setSessionTimeout(config.sessionTimeoutMinutes());

        Database database = null;
        try {
            Database candidate = new Database(Database.defaultUrl());
            candidate.init();
            database = candidate;
            context.setAttribute(ServletSupport.DB_ATTRIBUTE, database);
        } catch (SQLException | RuntimeException e) {
            // The game still works without a database; /history reports 503 and analytics is off.
            LOG.log(Level.SEVERE, "Database initialisation failed", e);
        }

        AppContext app = AppContext.create(config, database, System::currentTimeMillis);
        context.setAttribute(AppContext.ATTRIBUTE, app);
        context.setAttribute(SCHEDULER_ATTRIBUTE, new MaintenanceScheduler(app));

        LOG.info("Environment=" + config.environment() + ", secure cookies=" + config.cookieSecure()
                + ", admin configured=" + config.adminConfigured());
        if (config.production() && !config.cookieSecure()) {
            LOG.warning("APP_ENV=production but COOKIE_SECURE=false: cookies will not be marked Secure");
        }
        if (!config.adminConfigured()) {
            LOG.warning("ADMIN_USERNAME / ADMIN_PASSWORD_HASH not set: the admin dashboard is disabled");
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        Object scheduler = context.getAttribute(SCHEDULER_ATTRIBUTE);
        if (scheduler instanceof MaintenanceScheduler s) {
            s.close();
        }
        context.removeAttribute(SCHEDULER_ATTRIBUTE);
        context.removeAttribute(AppContext.ATTRIBUTE);
        context.removeAttribute(ServletSupport.DB_ATTRIBUTE);
    }
}
