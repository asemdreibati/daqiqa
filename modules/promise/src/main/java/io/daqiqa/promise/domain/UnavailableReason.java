package io.daqiqa.promise.domain;

/** Why no promise can be made. "We cannot deliver this" is a normal answer, not an error. */
public enum UnavailableReason {
    /** No store that accepts orders serves the location. */
    OUT_OF_COVERAGE,
    /** Stores serve the location, but none has the whole basket. */
    OUT_OF_STOCK,
    /** A store has the whole basket, but cannot deliver it within the slowest tier. */
    NO_CAPACITY
}
