package com.vedashree.game.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    @Test
    void blocksAfterTooManyFailuresAndRecoversAfterTheLockout() {
        AtomicLong now = new AtomicLong(0);
        LoginThrottle throttle = new LoginThrottle(3, 60_000, 120_000, now::get);
        throttle.recordFailure("ip");
        throttle.recordFailure("ip");
        assertFalse(throttle.isBlocked("ip"));
        throttle.recordFailure("ip");
        assertTrue(throttle.isBlocked("ip"));
        assertFalse(throttle.isBlocked("other"));
        now.addAndGet(119_000);
        assertTrue(throttle.isBlocked("ip"));
        now.addAndGet(2_000);
        assertFalse(throttle.isBlocked("ip"));
    }

    @Test
    void successResetsTheCounter() {
        LoginThrottle throttle = new LoginThrottle(2, 60_000, 60_000, () -> 0L);
        throttle.recordFailure("ip");
        throttle.recordSuccess("ip");
        throttle.recordFailure("ip");
        assertFalse(throttle.isBlocked("ip"));
    }
}
