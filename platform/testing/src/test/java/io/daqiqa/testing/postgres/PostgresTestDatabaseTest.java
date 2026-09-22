package io.daqiqa.testing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresTestDatabaseTest {

    @Test
    void replacesTheDatabaseNameAndKeepsQueryParameters() {
        assertThat(PostgresTestDatabase.withDatabase("jdbc:postgresql://localhost:5432/postgres", "t1"))
                .isEqualTo("jdbc:postgresql://localhost:5432/t1");
        assertThat(PostgresTestDatabase.withDatabase("jdbc:postgresql://db:5432/postgres?sslmode=disable", "t2"))
                .isEqualTo("jdbc:postgresql://db:5432/t2?sslmode=disable");
    }
}
