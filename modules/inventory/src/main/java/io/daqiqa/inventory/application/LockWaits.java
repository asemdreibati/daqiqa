package io.daqiqa.inventory.application;

import java.time.Duration;

/** Outbound port: how long the current transaction waits for a row lock before giving up. */
public interface LockWaits {

    /**
     * Limits every lock wait of the current transaction, and of that transaction only, to {@code timeout}. Past it the
     * statement fails (PostgreSQL SQLState {@code 55P03}) instead of holding its thread and connection.
     */
    void limitTo(Duration timeout);
}
