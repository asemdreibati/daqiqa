package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;

class LockContentionTest {

    @ParameterizedTest(name = "SQL state {0}")
    @ValueSource(strings = {"55P03", "40P01", "40001"})
    void findsContentionAnywhereInTheCauseChain(String state) {
        SQLException fromTheDriver = new SQLException("held elsewhere", state);

        assertThat(LockContention.isContention(fromTheDriver)).isTrue();
        assertThat(LockContention.isContention(new UncategorizedSQLException("expire", "UPDATE ...", fromTheDriver)))
                .as("Spring leaves a lock timeout uncategorised")
                .isTrue();
        assertThat(LockContention.isContention(new IllegalStateException(
                        "expiring failed", new CannotAcquireLockException("x", fromTheDriver))))
                .isTrue();
    }

    @Test
    void aHoldThatCannotExpireIsNotContention() {
        assertThat(LockContention.isContention(new IllegalStateException("reserved stock is inconsistent")))
                .isFalse();
        assertThat(LockContention.isContention(new UncategorizedSQLException(
                        "expire", "UPDATE ...", new SQLException("no such table", "42P01"))))
                .isFalse();
        assertThat(LockContention.isContention(new SQLException("no state at all")))
                .isFalse();
    }
}
