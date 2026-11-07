package com.vedashree.game.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void allowsUpToTheLimitThenBlocksUntilTheWindowPasses() {
        AtomicLong now = new AtomicLong(1000);
        RateLimiter limiter = new RateLimiter(3, 60_000, now::get);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"), "other keys are independent");
        now.addAndGet(60_000);
        assertTrue(limiter.tryAcquire("a"), "new window");
    }

    @Test
    void purgeRemovesExpiredKeys() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(1, 1000, now::get);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        assertEquals(2, limiter.trackedKeys());
        now.addAndGet(5000);
        limiter.purgeExpired();
        assertEquals(0, limiter.trackedKeys());
    }

    @Test
    void isExactUnderConcurrency() throws Exception {
        RateLimiter limiter = new RateLimiter(50, 60_000, () -> 1L);
        AtomicInteger allowed = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int n = 0; n < 100; n++) {
                    if (limiter.tryAcquire("same")) { allowed.incrementAndGet(); }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) { t.join(); }
        assertEquals(50, allowed.get());
    }
}
