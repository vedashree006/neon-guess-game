package com.vedashree.game.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe fixed-window rate limiter held in memory only (nothing is persisted, so IP
 * addresses used as keys never reach the database). The key map is bounded; when it is full
 * and cannot be cleaned, new keys are refused (fail closed).
 */
public final class RateLimiter {
    private static final int MAX_KEYS = 50_000;

    private record Window(long startMs, int count) { }

    private final int limit;
    private final long windowMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMs, LongSupplier clock) {
        if (limit < 1 || windowMs < 1) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** @return true if the call is within the limit and was counted. */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            purgeExpired();
            if (windows.size() >= MAX_KEYS) {
                return false;
            }
        }
        boolean[] allowed = new boolean[1];
        windows.compute(key, (k, window) -> {
            if (window == null || now - window.startMs() >= windowMs) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (window.count() < limit) {
                allowed[0] = true;
                return new Window(window.startMs(), window.count() + 1);
            }
            allowed[0] = false;
            return window;
        });
        return allowed[0];
    }

    public void purgeExpired() {
        long now = clock.getAsLong();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startMs() >= windowMs);
    }

    public int trackedKeys() {
        return windows.size();
    }
}
