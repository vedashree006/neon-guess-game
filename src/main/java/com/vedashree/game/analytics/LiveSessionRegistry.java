package com.vedashree.game.analytics;

import com.vedashree.game.GameLogic;
import com.vedashree.game.GameServlet;
import com.vedashree.game.admin.AdminAuth;
import com.vedashree.game.config.AppConfig;
import jakarta.servlet.http.HttpSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory view of the container's HttpSessions (the necessary game/admin session cookie).
 * It stores nothing about people: only session objects the server already holds, so it can show
 * live gauges for ALL sessions, independent of analytics consent. It resets when the app restarts
 * and does not see sessions restored from disk by the container.
 */
public final class LiveSessionRegistry {
    public record Snapshot(int visitorSessions, int activeSessions, int ongoingGames, int adminSessions,
                           long created, long expired, long invalidated, long startedAtMs) { }

    private final ConcurrentHashMap<String, HttpSession> live = new ConcurrentHashMap<>();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong invalidated = new AtomicLong();
    private final AppConfig config;
    private final LongSupplier clock;
    private final long startedAtMs;

    public LiveSessionRegistry(AppConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.startedAtMs = clock.getAsLong();
    }

    public void onCreated(HttpSession session) {
        created.incrementAndGet();
        live.put(session.getId(), session);
    }

    /** Container reports a new id after changeSessionId() (session-fixation defence on login). */
    public void onIdChanged(HttpSession session, String oldId) {
        live.remove(oldId);
        live.put(session.getId(), session);
    }

    public void onDestroyed(HttpSession session) {
        live.remove(session.getId());
        boolean timedOut;
        try {
            long idleMs = clock.getAsLong() - session.getLastAccessedTime();
            timedOut = idleMs >= session.getMaxInactiveInterval() * 1000L - 1000L
                    && session.getMaxInactiveInterval() > 0;
        } catch (IllegalStateException alreadyInvalid) {
            timedOut = false;
        }
        (timedOut ? expired : invalidated).incrementAndGet();
    }

    public Snapshot snapshot() {
        long now = clock.getAsLong();
        long activeMs = config.activeWindowSeconds() * 1000L;
        int visitorSessions = 0;
        int active = 0;
        int ongoing = 0;
        int admins = 0;
        for (HttpSession session : live.values()) {
            try {
                if (AdminAuth.isAuthenticated(session, now, config)) {
                    admins++;
                    continue;
                }
                visitorSessions++;
                if (now - session.getLastAccessedTime() <= activeMs) {
                    active++;
                }
                Object game = session.getAttribute(GameServlet.GAME_KEY);
                if (game instanceof GameLogic g && !g.isOver()) {
                    ongoing++;
                }
            } catch (IllegalStateException alreadyInvalid) {
                // session ended between listing and reading: ignore
            }
        }
        return new Snapshot(visitorSessions, active, ongoing, admins,
                created.get(), expired.get(), invalidated.get(), startedAtMs);
    }
}
