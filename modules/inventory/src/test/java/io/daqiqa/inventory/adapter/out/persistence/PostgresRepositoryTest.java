package io.daqiqa.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.inventory.support.InventoryTables;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repositories against real PostgreSQL, without Spring: real SQL, real locks, real constraints. A second transaction
 * runs on another thread when a test needs two at once.
 */
@Tag("integration")
abstract class PostgresRepositoryTest {

    static final PostgresTestDatabase DATABASE =
            PostgresTestDatabase.shared().migrate("classpath:db/migration/inventory");

    final JdbcClient jdbc = JdbcClient.create(DATABASE.dataSource());
    final TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(DATABASE.dataSource()));
    final InventoryTables tables = new InventoryTables(DATABASE.dataSource());

    private final ExecutorService otherThread = Executors.newSingleThreadExecutor();

    @BeforeEach
    void startFromEmptyTables() {
        DATABASE.truncateSchemas("inventory");
    }

    @AfterEach
    void stopOtherThread() {
        otherThread.shutdownNow();
    }

    /** Starts {@code work} in its own transaction on another thread, so it can overlap the caller's transaction. */
    <T> Future<T> startInAnotherTransaction(Supplier<T> work) {
        return otherThread.submit(() -> transaction.execute(status -> work.get()));
    }

    /** Runs {@code work} in another transaction on another thread, waits for it, and rethrows what it threw. */
    <T> T inAnotherTransaction(Supplier<T> work) {
        try {
            return startInAnotherTransaction(work).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new AssertionError(e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    /** In the current transaction, gives up waiting for a row lock after {@code timeout} instead of blocking. */
    void waitForLocksAtMost(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms")
                .query()
                .singleValue();
    }

    /**
     * Asserts that {@code work} gave up waiting for a row lock (SQLState 55P03, {@code lock_not_available}): proof that
     * another transaction holds it.
     */
    static void assertLockNotAvailable(ThrowingCallable work) {
        assertThatThrownBy(work)
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOfSatisfying(
                                SQLException.class,
                                cause -> assertThat(cause.getSQLState()).isEqualTo("55P03")));
    }

    /** Waits until some other session is blocked on a lock: proof that it is queueing behind the caller. */
    void awaitAnotherSessionWaitingForALock() throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            Integer waiting = jdbc.sql("""
                            SELECT count(*)::int
                              FROM pg_stat_activity
                             WHERE datname = current_database() AND wait_event_type = 'Lock'""").query(Integer.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No session started waiting for a lock");
    }
}
