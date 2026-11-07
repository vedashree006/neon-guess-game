package com.vedashree.game.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vedashree.game.GameLogic;
import com.vedashree.game.GameServlet;
import com.vedashree.game.admin.AdminAuth;
import com.vedashree.game.config.AppConfig;
import com.vedashree.game.testsupport.Fakes;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveSessionRegistryTest {
    private final AtomicLong clock = new AtomicLong(1_730_000_000_000L);
    private Fakes.Context context;
    private LiveSessionRegistry registry;

    @BeforeEach
    void setUp() {
        context = Fakes.context();
        registry = new LiveSessionRegistry(AppConfig.defaults(), clock::get);
    }

    private Fakes.Session open(String id) {
        Fakes.Session session = Fakes.session(context, id);
        session.lastAccessed = clock.get();
        registry.onCreated(session.proxy);
        return session;
    }

    @Test
    void countsOpenActiveAndOngoingGameSessions() {
        Fakes.Session playing = open("a");
        playing.attributes.put(GameServlet.GAME_KEY, new GameLogic("Veda", 25));
        Fakes.Session finished = open("b");
        GameLogic done = new GameLogic("Veda", 25);
        done.guess(25);
        finished.attributes.put(GameServlet.GAME_KEY, done);
        Fakes.Session idle = open("c");
        idle.lastAccessed = clock.get() - 20 * 60_000L;

        LiveSessionRegistry.Snapshot snapshot = registry.snapshot();
        assertEquals(3, snapshot.visitorSessions());
        assertEquals(2, snapshot.activeSessions(), "idle session is outside the 5 minute window");
        assertEquals(1, snapshot.ongoingGames(), "finished games are not ongoing");
        assertEquals(3, snapshot.created());
    }

    @Test
    void adminSessionsAreCountedSeparatelyAndNeverAsVisitors() {
        Fakes.Session admin = open("admin");
        admin.attributes.put(AdminAuth.ATTR_USER, "owner");
        admin.attributes.put("neon.admin.loginMs", clock.get());
        admin.attributes.put("neon.admin.seenMs", clock.get());
        open("visitor");
        LiveSessionRegistry.Snapshot snapshot = registry.snapshot();
        assertEquals(1, snapshot.adminSessions());
        assertEquals(1, snapshot.visitorSessions());
    }

    @Test
    void anExpiredAdminLoginIsNotCountedAsLoggedIn() {
        Fakes.Session admin = open("admin");
        admin.attributes.put(AdminAuth.ATTR_USER, "owner");
        admin.attributes.put("neon.admin.loginMs", clock.get());
        admin.attributes.put("neon.admin.seenMs", clock.get());
        clock.addAndGet(16 * 60_000L);
        assertEquals(0, registry.snapshot().adminSessions());
    }

    @Test
    void destroyedSessionsLeaveTheGaugeAndAreClassifiedAsExpiredOrEnded() {
        Fakes.Session expiring = open("x");
        Fakes.Session leaving = open("y");
        clock.addAndGet(31 * 60_000L);
        leaving.lastAccessed = clock.get() - 1000;   // used a second ago, then logged out / invalidated
        registry.onDestroyed(expiring.proxy);
        registry.onDestroyed(leaving.proxy);
        LiveSessionRegistry.Snapshot snapshot = registry.snapshot();
        assertEquals(0, snapshot.visitorSessions());
        assertEquals(1, snapshot.expired());
        assertEquals(1, snapshot.invalidated());
    }

    @Test
    void sessionIdChangeDoesNotLeakOrDuplicateTheEntry() {
        Fakes.Session session = open("old");
        session.id = "new";
        registry.onIdChanged(session.proxy, "old");
        assertEquals(1, registry.snapshot().visitorSessions());
        registry.onDestroyed(session.proxy);
        assertEquals(0, registry.snapshot().visitorSessions());
    }

    @Test
    void sessionsInvalidatedBehindOurBackAreIgnoredNotFatal() {
        Fakes.Session session = open("z");
        session.invalidated = true;
        assertEquals(0, registry.snapshot().visitorSessions());
    }
}
