package com.vedashree.game.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {
    private static AppConfig of(Map<String, String> values) {
        return AppConfig.from(values::get);
    }

    @Test
    void developmentDefaultsAreSafeAndAdminIsDisabled() {
        AppConfig config = AppConfig.defaults();
        assertFalse(config.production());
        assertFalse(config.cookieSecure());
        assertFalse(config.adminConfigured());
        assertEquals(30, config.sessionTimeoutMinutes());
        assertEquals(300, config.activeWindowSeconds());
        assertEquals(1, config.consentPolicyVersion());
    }

    @Test
    void productionTurnsOnSecureCookiesByDefault() {
        AppConfig config = of(Map.of("APP_ENV", "production"));
        assertTrue(config.production());
        assertTrue(config.cookieSecure());
        assertFalse(of(Map.of("APP_ENV", "production", "COOKIE_SECURE", "false")).cookieSecure());
    }

    @Test
    void adminNeedsBothUsernameAndHash() {
        assertFalse(of(Map.of("ADMIN_USERNAME", "root")).adminConfigured());
        assertTrue(of(Map.of("ADMIN_USERNAME", "root", "ADMIN_PASSWORD_HASH", "x")).adminConfigured());
    }

    @Test
    void invalidAndOutOfRangeNumbersFallBackToDefaults() {
        AppConfig config = of(Map.of("SESSION_TIMEOUT_MINUTES", "abc", "ANALYTICS_RETENTION_DAYS", "1",
                "DASHBOARD_REFRESH_SECONDS", "10", "ANALYTICS_ACTIVE_WINDOW_SECONDS", "120"));
        assertEquals(30, config.sessionTimeoutMinutes());
        assertEquals(365, config.retentionDays());
        assertEquals(10, config.dashboardRefreshSeconds());
        assertEquals(120, config.activeWindowSeconds());
    }
}
