package com.vedashree.game;

/** Thrown when a request parameter fails validation. The message is safe to show to users. */
public class InvalidInputException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }
}
