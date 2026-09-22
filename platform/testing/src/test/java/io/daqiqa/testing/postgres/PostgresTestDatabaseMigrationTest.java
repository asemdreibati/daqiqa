package io.daqiqa.testing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Test classes of one module share a database, and each migrates the schemas it needs. Whatever order they run in, a
 * class asking for other schemas than an earlier one must find its schemas migrated, not a validation failure.
 */
@Tag("integration")
class PostgresTestDatabaseMigrationTest {

    // Test-only migrations (src/test/resources/db/harness): version 90.1, then the later version 91.1.
    private static final String FIRST = "classpath:db/harness/first";
    private static final String SECOND = "classpath:db/harness/second";

    @Test
    void aTestClassMayMigrateOtherLocationsThanOneThatRanBeforeIt() throws SQLException {
        PostgresTestDatabase db = PostgresTestDatabase.shared();

        db.migrate(FIRST);
        // Before the fix Flyway saw only SECOND here and refused: "Detected applied migration not resolved locally:
        // 90.1", as ordering's JdbcOrderRepositoryTest (ordering) followed by CheckoutServiceTest (messaging and
        // ordering) did.
        db.migrate(SECOND);
        db.migrate(FIRST); // a subset of what ran: nothing to do
        db.migrate("classpath:db/harness"); // overlapping locations: Flyway resolves each migration once

        assertThat(appliedVersions(db)).containsExactly("90.1", "91.1");
        assertThat(rows(db, "SELECT count(*) FROM harness_first.marker")).isZero();
        assertThat(rows(db, "SELECT count(*) FROM harness_second.marker")).isZero();
    }

    private static List<String> appliedVersions(PostgresTestDatabase db) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = db.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static long rows(PostgresTestDatabase db, String sql) throws SQLException {
        try (Connection connection = db.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
