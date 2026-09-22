package io.daqiqa.promise.api;

/** A promise token that cannot be honoured. */
public final class InvalidPromiseException extends RuntimeException {

    public enum Reason {
        /** Not a token we issued (bad format or unknown key). */
        MALFORMED,
        /** Signature does not match: the token was altered. */
        TAMPERED,
        /** Valid, but its short validity window has passed; ask for a new promise. */
        EXPIRED,
        /** Valid, but issued for a different basket. */
        CART_MISMATCH,
        /** Valid, but issued for a different delivery location (the address changed after the quote). */
        LOCATION_MISMATCH,
        /** Valid, but the promised store stopped accepting orders since (paused, closed); ask for a new promise. */
        STORE_UNAVAILABLE
    }

    private final Reason reason;

    public InvalidPromiseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
