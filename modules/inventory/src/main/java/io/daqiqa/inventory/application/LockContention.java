package io.daqiqa.inventory.application;

import java.sql.SQLException;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Tells ordinary contention from work that cannot succeed however often it is retried.
 *
 * <p>Contention means another transaction held what this one needed: it is normal under load, and the next sweep
 * picks the same holds up again. A hold whose stock rows are inconsistent, by contrast, needs a person.
 */
final class LockContention {

    /** PostgreSQL: lock_not_available, deadlock_detected, serialization_failure. */
    private static final Set<String> CONTENTION_STATES = Set.of("55P03", "40P01", "40001");

    private LockContention() {}

    /**
     * Whether {@code failure} was caused by contention. The whole cause chain is searched, because Spring leaves a
     * lock timeout as an {@code UncategorizedSQLException} that only its {@link SQLException} cause identifies.
     */
    static boolean isContention(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && isContention(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContention(@Nullable String sqlState) {
        return sqlState != null && CONTENTION_STATES.contains(sqlState); // a driver may report no state at all
    }
}
