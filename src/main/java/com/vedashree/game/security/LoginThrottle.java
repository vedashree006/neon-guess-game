package com.vedashree.game.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Blocks a client after too many failed logins within a window. In-memory and keyed by client
 * address (not by username, so an attacker cannot lock the real admin out by guessing).
 */
public final class LoginThrottle {
    private static final int MAX_KEYS = 20_000;

    private record State(int failures, long windowStartMs, long blockedUntilMs) { }

    private final int maxFailures;
    private final long windowMs;
    private final long lockoutMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, long windowMs, long lockoutMs, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowMs = windowMs;
        this.lockoutMs = lockoutMs;
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        State state = states.get(key);
        return state != null && state.blockedUntilMs() > clock.getAsLong();
    }

    public void recordFailure(String key) {
        long now = clock.getAsLong();
        if (states.size() >= MAX_KEYS && !states.containsKey(key)) {
            purgeExpired();
        }
        states.compute(key, (k, state) -> {
            if (state == null || now - state.windowStartMs() >= windowMs) {
                return new State(1, now, 0);
            }
            int failures = state.failures() + 1;
            long blockedUntil = failures >= maxFailures ? now + lockoutMs : state.blockedUntilMs();
            return new State(failures, state.windowStartMs(), blockedUntil);
        });
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }

    public void purgeExpired() {
        long now = clock.getAsLong();
        states.entrySet().removeIf(e -> now - e.getValue().windowStartMs() >= windowMs
                && e.getValue().blockedUntilMs() <= now);
    }
}
