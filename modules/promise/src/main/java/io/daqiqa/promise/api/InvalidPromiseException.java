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
        CART_MISMATCH
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
