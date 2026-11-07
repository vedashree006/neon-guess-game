package com.vedashree.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameLogicTest {
    private GameLogic game;

    @BeforeEach
    void setUp() {
        game = new GameLogic("Veda", 25);
    }

    @Test
    void newGameStartsInProgressWithAllAttempts() {
        assertEquals(GameLogic.Status.IN_PROGRESS, game.getStatus());
        assertEquals(10, game.getAttemptsRemaining());
        assertEquals(0, game.getAttemptsUsed());
        assertNull(game.getLastGuess());
        assertTrue(game.getGuesses().isEmpty());
    }

    @Test
    void guessAboveSecretIsTooHigh() {
        GameLogic.Guess guess = game.guess(40);
        assertEquals(GameLogic.Hint.TOO_HIGH, guess.hint());
        assertFalse(guess.close());
    }

    @Test
    void guessBelowSecretIsTooLow() {
        GameLogic.Guess guess = game.guess(5);
        assertEquals(GameLogic.Hint.TOO_LOW, guess.hint());
        assertFalse(guess.close());
    }

    @Test
    void guessWithinThreeIsMarkedClose() {
        assertTrue(game.guess(28).close());
        assertTrue(game.guess(22).close());
        assertFalse(game.guess(29).close());
        assertFalse(game.guess(21).close());
    }

    @Test
    void correctGuessWinsAndScoresByAttempts() {
        game.guess(1);
        GameLogic.Guess guess = game.guess(25);
        assertEquals(GameLogic.Hint.CORRECT, guess.hint());
        assertFalse(guess.close());
        assertEquals(GameLogic.Status.WON, game.getStatus());
        assertEquals(2, game.getAttemptsUsed());
        assertEquals(90, game.getScore());
    }

    @Test
    void winOnFirstTryScoresOneHundred() {
        game.guess(25);
        assertEquals(100, game.getScore());
    }

    @Test
    void winOnLastAttemptScoresTen() {
        for (int i = 0; i < 9; i++) {
            game.guess(1);
        }
        game.guess(25);
        assertEquals(GameLogic.Status.WON, game.getStatus());
        assertEquals(10, game.getScore());
    }

    @Test
    void tenWrongGuessesLoseTheGame() {
        for (int i = 0; i < 10; i++) {
            assertFalse(game.isOver());
            game.guess(1);
        }
        assertEquals(GameLogic.Status.LOST, game.getStatus());
        assertEquals(0, game.getAttemptsRemaining());
        assertEquals(0, game.getScore());
        assertEquals(Integer.valueOf(25), game.secretIfOver());
    }

    @Test
    void secretIsHiddenWhileGameIsRunning() {
        game.guess(1);
        assertNull(game.secretIfOver());
        assertFalse(game.message().contains("25"));
    }

    @Test
    void guessAfterGameOverIsRejected() {
        game.guess(25);
        assertThrows(IllegalStateException.class, () -> game.guess(10));
        assertEquals(1, game.getAttemptsUsed());
    }

    @Test
    void outOfRangeGuessIsRejectedWithoutUsingAnAttempt() {
        assertThrows(IllegalArgumentException.class, () -> game.guess(0));
        assertThrows(IllegalArgumentException.class, () -> game.guess(51));
        assertThrows(IllegalArgumentException.class, () -> game.guess(-7));
        assertEquals(0, game.getAttemptsUsed());
        assertEquals(GameLogic.Status.IN_PROGRESS, game.getStatus());
    }

    @Test
    void constructorRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new GameLogic("Veda", 0));
        assertThrows(IllegalArgumentException.class, () -> new GameLogic("Veda", 51));
        assertThrows(IllegalArgumentException.class, () -> new GameLogic(" ", 10));
        assertThrows(IllegalArgumentException.class, () -> new GameLogic(null, 10));
    }

    @Test
    void generatedSecretsAlwaysStayInRange() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            int secret = GameLogic.create("Test", random).secretForTesting();
            assertTrue(secret >= GameLogic.MIN && secret <= GameLogic.MAX);
        }
    }

    @Test
    void eachGameGetsItsOwnId() {
        assertNotNull(game.getGameId());
        assertFalse(game.getGameId().equals(new GameLogic("Veda", 25).getGameId()));
    }

    @Test
    void messagesDescribeTheCurrentState() {
        assertTrue(game.message().contains("Veda"));
        game.guess(40);
        assertEquals("Too high.", game.message());
        game.guess(23);
        assertEquals("Too low - but you are really close!", game.message());
        game.guess(25);
        assertTrue(game.message().startsWith("Correct!"));
    }

    @Test
    void resultSavedFlagStartsFalseAndCanBeSet() {
        assertFalse(game.isResultSaved());
        game.markResultSaved();
        assertTrue(game.isResultSaved());
    }
}
