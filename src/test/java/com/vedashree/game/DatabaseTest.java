package com.vedashree.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vedashree.game.Database.ScoreRecord;
import com.vedashree.game.Database.Sort;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Uses a private in-memory H2 database per test, so nothing touches the disk. */
class DatabaseTest {
    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = new Database("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        database.init();
    }

    private static ScoreRecord record(String name, String outcome, int attempts, int score) {
        return new ScoreRecord(UUID.randomUUID().toString(), name, outcome, attempts, 25, score, null);
    }

    @Test
    void initIsIdempotent() throws SQLException {
        database.init();
        assertEquals(0, database.stats().played());
    }

    @Test
    void savedResultCanBeReadBack() throws SQLException {
        assertTrue(database.saveResult(record("Veda", "WON", 3, 80)));
        List<ScoreRecord> rows = database.findResults(10, Sort.RECENT);
        assertEquals(1, rows.size());
        assertEquals("Veda", rows.get(0).playerName());
        assertEquals("WON", rows.get(0).outcome());
        assertEquals(80, rows.get(0).score());
        assertNotNull(rows.get(0).playedAt());
    }

    @Test
    void duplicateGameIdIsIgnored() throws SQLException {
        ScoreRecord first = record("Veda", "WON", 3, 80);
        assertTrue(database.saveResult(first));
        assertFalse(database.saveResult(first));
        assertEquals(1, database.stats().played());
    }

    @Test
    void recentOrderingIsNewestFirstAndLimitIsApplied() throws SQLException {
        database.saveResult(record("A", "WON", 1, 100));
        database.saveResult(record("B", "LOST", 10, 0));
        database.saveResult(record("C", "WON", 5, 60));
        List<ScoreRecord> rows = database.findResults(2, Sort.RECENT);
        assertEquals(2, rows.size());
        assertEquals("C", rows.get(0).playerName());
        assertEquals("B", rows.get(1).playerName());
    }

    @Test
    void topOrderingIsHighestScoreFirst() throws SQLException {
        database.saveResult(record("Low", "WON", 9, 20));
        database.saveResult(record("High", "WON", 1, 100));
        database.saveResult(record("Zero", "LOST", 10, 0));
        List<ScoreRecord> rows = database.findResults(10, Sort.TOP);
        assertEquals("High", rows.get(0).playerName());
        assertEquals("Low", rows.get(1).playerName());
        assertEquals("Zero", rows.get(2).playerName());
    }

    @Test
    void statsCountWinsAndLosses() throws SQLException {
        database.saveResult(record("A", "WON", 2, 90));
        database.saveResult(record("B", "WON", 4, 70));
        database.saveResult(record("C", "LOST", 10, 0));
        Database.Stats stats = database.stats();
        assertEquals(3, stats.played());
        assertEquals(2, stats.wins());
        assertEquals(1, stats.losses());
    }

    @Test
    void invalidRowsAreRejectedByCheckConstraints() {
        assertThrows(SQLException.class, () -> database.saveResult(record("A", "DRAW", 3, 10)));
        assertThrows(SQLException.class, () -> database.saveResult(record("A", "WON", 11, 10)));
    }

    @Test
    void sqlInjectionInNameIsStoredAsPlainText() throws SQLException {
        String nasty = "x'); DROP TABLE game_results;--";
        assertTrue(database.saveResult(record(nasty.substring(0, 20), "WON", 1, 100)));
        assertEquals(1, database.stats().played());
    }
}
