package com.vedashree.game.config;

import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Immutable application settings. Values come from environment variables (production) or from
 * JVM system properties (local runs): the property name is "neon." plus the lower-case variable
 * name with '_' replaced by '.', e.g. ADMIN_USERNAME -> -Dneon.admin.username=...
 * Invalid or out-of-range numbers fall back to the default and are logged, never fatal.
 */
public final class AppConfig {
    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());

    public enum Environment { DEVELOPMENT, PRODUCTION }

    private final Environment environment;
    private final boolean cookieSecure;
    private final boolean trustProxy;
    private final String adminUsername;
    private final String adminPasswordHash;
    private final int adminIdleMinutes;
    private final int adminMaxSessionMinutes;
    private final int sessionTimeoutMinutes;
    private final int activeWindowSeconds;
    private final int visitTimeoutMinutes;
    private final int retentionDays;
    private final int visitorCookieDays;
    private final int consentPolicyVersion;
    private final int consentMaxAgeDays;
    private final int consentLogRetentionDays;
    private final int dashboardRefreshSeconds;
    private final int backupIntervalHours;
    private final int backupKeep;
    private final String backupDir;

    private AppConfig(Function<String, String> source) {
        String env = text(source, "APP_ENV");
        this.environment = "production".equalsIgnoreCase(env) ? Environment.PRODUCTION : Environment.DEVELOPMENT;
        this.cookieSecure = bool(source, "COOKIE_SECURE", environment == Environment.PRODUCTION);
        this.trustProxy = bool(source, "TRUST_PROXY", false);
        this.adminUsername = text(source, "ADMIN_USERNAME");
        this.adminPasswordHash = text(source, "ADMIN_PASSWORD_HASH");
        this.adminIdleMinutes = integer(source, "ADMIN_IDLE_MINUTES", 15, 1, 240);
        this.adminMaxSessionMinutes = integer(source, "ADMIN_MAX_SESSION_MINUTES", 120, 5, 1440);
        this.sessionTimeoutMinutes = integer(source, "SESSION_TIMEOUT_MINUTES", 30, 1, 1440);
        this.activeWindowSeconds = integer(source, "ANALYTICS_ACTIVE_WINDOW_SECONDS", 300, 30, 3600);
        this.visitTimeoutMinutes = integer(source, "ANALYTICS_VISIT_TIMEOUT_MINUTES", 30, 1, 240);
        this.retentionDays = integer(source, "ANALYTICS_RETENTION_DAYS", 365, 7, 800);
        this.visitorCookieDays = integer(source, "ANALYTICS_COOKIE_DAYS", 180, 1, 395);
        this.consentPolicyVersion = integer(source, "CONSENT_POLICY_VERSION", 1, 1, 9999);
        this.consentMaxAgeDays = integer(source, "CONSENT_MAX_AGE_DAYS", 180, 1, 395);
        this.consentLogRetentionDays = integer(source, "CONSENT_LOG_RETENTION_DAYS", 1095, 30, 3650);
        this.dashboardRefreshSeconds = integer(source, "DASHBOARD_REFRESH_SECONDS", 15, 10, 60);
        this.backupIntervalHours = integer(source, "BACKUP_INTERVAL_HOURS", 24, 0, 720);
        this.backupKeep = integer(source, "BACKUP_KEEP", 7, 1, 365);
        this.backupDir = text(source, "BACKUP_DIR");
    }

    /** Reads system properties first, then environment variables. */
    public static AppConfig fromEnvironment() {
        return from(AppConfig::lookup);
    }

    /** Builds a configuration from any key lookup (used by tests). Missing keys use defaults. */
    public static AppConfig from(Function<String, String> source) {
        return new AppConfig(source);
    }

    public static AppConfig defaults() {
        return new AppConfig(key -> null);
    }

    private static String lookup(String name) {
        String property = System.getProperty("neon." + name.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (property != null && !property.isBlank()) {
            return property;
        }
        return System.getenv(name);
    }

    private static String text(Function<String, String> source, String name) {
        String value = source.apply(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean bool(Function<String, String> source, String name, boolean fallback) {
        String value = text(source, name);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        LOG.warning(name + " must be true or false; using " + fallback);
        return fallback;
    }

    private static int integer(Function<String, String> source, String name, int fallback, int min, int max) {
        String value = text(source, name);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // handled below
        }
        LOG.warning(name + " must be a whole number from " + min + " to " + max + "; using " + fallback);
        return fallback;
    }

    public Environment environment() { return environment; }
    public boolean production() { return environment == Environment.PRODUCTION; }
    /** True when cookies get the Secure attribute (default: production only). */
    public boolean cookieSecure() { return cookieSecure; }
    /** True when X-Forwarded-For from a single trusted reverse proxy may be used for rate limiting. */
    public boolean trustProxy() { return trustProxy; }
    public String adminUsername() { return adminUsername; }
    public String adminPasswordHash() { return adminPasswordHash; }
    public boolean adminConfigured() { return adminUsername != null && adminPasswordHash != null; }
    public int adminIdleMinutes() { return adminIdleMinutes; }
    public int adminMaxSessionMinutes() { return adminMaxSessionMinutes; }
    public int sessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public int activeWindowSeconds() { return activeWindowSeconds; }
    public int visitTimeoutMinutes() { return visitTimeoutMinutes; }
    public int retentionDays() { return retentionDays; }
    public int visitorCookieDays() { return visitorCookieDays; }
    public int consentPolicyVersion() { return consentPolicyVersion; }
    public int consentMaxAgeDays() { return consentMaxAgeDays; }
    public int consentLogRetentionDays() { return consentLogRetentionDays; }
    public int dashboardRefreshSeconds() { return dashboardRefreshSeconds; }
    public int backupIntervalHours() { return backupIntervalHours; }
    public int backupKeep() { return backupKeep; }
    /** Folder for database backups, or null to use the default next to the database. */
    public String backupDir() { return backupDir; }
}
