package com.vedashree.game;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Pure game rules with no servlet or database dependencies, so they are easy to unit test.
 * One instance is stored per HttpSession. The secret number never leaves this class
 * until the game is over.
 */
public final class GameLogic implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int MIN = 1;
    public static final int MAX = 50;
    public static final int MAX_ATTEMPTS = 10;
    /** A wrong guess within this distance of the secret is reported as "close". */
    public static final int CLOSE_RANGE = 3;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public enum Status { IN_PROGRESS, WON, LOST }

    public enum Hint { NONE, TOO_LOW, TOO_HIGH, CORRECT }

    public record Guess(int value, Hint hint, boolean close) implements Serializable { }

    private final String gameId = UUID.randomUUID().toString();
    private final String playerName;
    private final int secret;
    private final ArrayList<Guess> guesses = new ArrayList<>();
    private Status status = Status.IN_PROGRESS;
    private boolean resultSaved;

    public GameLogic(String playerName, int secret) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        if (secret < MIN || secret > MAX) {
            throw new IllegalArgumentException("Secret must be between " + MIN + " and " + MAX);
        }
        this.playerName = playerName;
        this.secret = secret;
    }

    public static GameLogic create(String playerName, Random random) {
        return new GameLogic(playerName, MIN + random.nextInt(MAX - MIN + 1));
    }

    public static GameLogic create(String playerName) {
        return create(playerName, SECURE_RANDOM);
    }

    /** Applies one guess. Does not consume an attempt if the value is out of range. */
    public synchronized Guess guess(int value) {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("The game is already over.");
        }
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException("Guess must be between " + MIN + " and " + MAX);
        }
        Hint hint;
        if (value == secret) {
            hint = Hint.CORRECT;
        } else if (value > secret) {
            hint = Hint.TOO_HIGH;
        } else {
            hint = Hint.TOO_LOW;
        }
        boolean close = hint != Hint.CORRECT && Math.abs(value - secret) <= CLOSE_RANGE;
        Guess guess = new Guess(value, hint, close);
        guesses.add(guess);

        if (hint == Hint.CORRECT) {
            status = Status.WON;
        } else if (guesses.size() >= MAX_ATTEMPTS) {
            status = Status.LOST;
        }
        return guess;
    }

    public String getGameId() {
        return gameId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized boolean isOver() {
        return status != Status.IN_PROGRESS;
    }

    public synchronized int getAttemptsUsed() {
        return guesses.size();
    }

    public synchronized int getAttemptsRemaining() {
        return MAX_ATTEMPTS - guesses.size();
    }

    public synchronized List<Guess> getGuesses() {
        return List.copyOf(guesses);
    }

    /** Last guess, or null if none was made yet. */
    public synchronized Guess getLastGuess() {
        return guesses.isEmpty() ? null : guesses.get(guesses.size() - 1);
    }

    /** 10 to 100 points for a win (faster is better), 0 for a loss or an unfinished game. */
    public synchronized int getScore() {
        return status == Status.WON ? (MAX_ATTEMPTS - guesses.size() + 1) * 10 : 0;
    }

    /** The secret number, or null while the game is still running. */
    public synchronized Integer secretIfOver() {
        return isOver() ? Integer.valueOf(secret) : null;
    }

    public synchronized boolean isResultSaved() {
        return resultSaved;
    }

    public synchronized void markResultSaved() {
        resultSaved = true;
    }

    /** Visible for testing only. Never expose this over HTTP. */
    int secretForTesting() {
        return secret;
    }

    public synchronized String message() {
        Guess last = getLastGuess();
        if (last == null) {
            return "I picked a number from " + MIN + " to " + MAX + ". You have "
                    + MAX_ATTEMPTS + " attempts. Good luck, " + playerName + "!";
        }
        switch (status) {
            case WON:
                int n = guesses.size();
                return "Correct! " + playerName + " found " + secret + " in " + n
                        + (n == 1 ? " attempt." : " attempts.");
            case LOST:
                return "Game over! The secret number was " + secret + ".";
            default:
                String direction = last.hint() == Hint.TOO_HIGH ? "Too high" : "Too low";
                return direction + (last.close() ? " - but you are really close!" : ".");
        }
    }
}
