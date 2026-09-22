package io.daqiqa.testing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

class PostgresTestDatabaseTest {

    @Test
    void replacesTheDatabaseNameAndKeepsQueryParameters() {
        assertThat(PostgresTestDatabase.withDatabase("jdbc:postgresql://localhost:5432/postgres", "t1"))
                .isEqualTo("jdbc:postgresql://localhost:5432/t1");
        assertThat(PostgresTestDatabase.withDatabase("jdbc:postgresql://db:5432/postgres?sslmode=disable", "t2"))
                .isEqualTo("jdbc:postgresql://db:5432/t2?sslmode=disable");
    }

    @Test
    void recognisesStaleTestDatabasesByTheirCreationSecond() {
        assertThat(PostgresTestDatabase.createdBefore("daqiqa_test_1000_ab12cd34", 2000))
                .isTrue();
        assertThat(PostgresTestDatabase.createdBefore("daqiqa_test_3000_ab12cd34", 2000))
                .isFalse();
        assertThat(PostgresTestDatabase.createdBefore("daqiqa_test_0123456789abcdef", 2000))
                .as("names from the previous scheme are always stale")
                .isTrue();
    }

    @Test
    void keepsTwoConnectionsOpenWhileIdleAndOpensUpToSixtyFourUnderLoad() {
        HikariConfig config = PostgresTestDatabase.poolConfig("jdbc:postgresql://localhost:5432/t", "user", "secret");

        assertThat(config.getMaximumPoolSize()).isEqualTo(64);
        assertThat(config.getMinimumIdle()).isEqualTo(2);
        assertThat(config.getIdleTimeout()).isEqualTo(30_000);
    }

    @Test
    void startsTheContainerWithRoomForThreeHundredConnectionsAndKeepsFsyncOff() {
        // Only configured, never started: this needs no Docker.
        assertThat(PostgresTestDatabase.container().getCommandParts())
                .containsExactly("postgres", "-c", "fsync=off", "-c", "max_connections=300");
    }
}
