package io.daqiqa.testing.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway PostgreSQL database for integration tests: real SQL, real locks, real constraints.
 *
 * <p>Two modes behind one API:
 *
 * <ul>
 *   <li><b>Testcontainers</b> (default, used in CI): a {@code postgres:17-alpine} container is started once per JVM.
 *   <li><b>External server</b>: when {@code DAQIQA_TEST_PG_URL} is set (for example
 *       {@code jdbc:postgresql://localhost:5432/postgres}), a uniquely named database is created on that server;
 *       databases older than an hour are dropped by later runs. Credentials come from {@code DAQIQA_TEST_PG_USER} / {@code DAQIQA_TEST_PG_PASSWORD}
 *       (default {@code postgres}/{@code postgres}). Useful on machines without Docker.
 * </ul>
 *
 * <p>Either way each test JVM (one per Gradle module) owns an empty database, so parallel builds never interfere.
 * Tests inside one module share it and are expected to clean up with {@link #truncateSchemas(String...)}.
 */
public final class PostgresTestDatabase {

    private static final String IMAGE = "postgres:17-alpine";
    private static final String DATABASE_PREFIX = "daqiqa_test_";
    private static final long STALE_AFTER_SECONDS = 3_600;
    private static volatile PostgresTestDatabase shared;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final HikariDataSource dataSource;

    /** Every location migrated so far in this JVM; guarded by {@code this}. */
    private final Set<String> migratedLocations = new LinkedHashSet<>();

    private PostgresTestDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(64);
        config.setPoolName("test-db");
        this.dataSource = new HikariDataSource(config);
    }

    /** The database for this JVM, created on first use. */
    public static PostgresTestDatabase shared() {
        PostgresTestDatabase db = shared;
        if (db == null) {
            synchronized (PostgresTestDatabase.class) {
                db = shared;
                if (db == null) {
                    db = create();
                    shared = db;
                }
            }
        }
        return db;
    }

    private static PostgresTestDatabase create() {
        String externalUrl = System.getenv("DAQIQA_TEST_PG_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            return createOnExternalServer(externalUrl.trim());
        }
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
        container.start();
        return new PostgresTestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static PostgresTestDatabase createOnExternalServer(String adminUrl) {
        String user = envOrDefault("DAQIQA_TEST_PG_USER", "postgres");
        String pass = envOrDefault("DAQIQA_TEST_PG_PASSWORD", "postgres");
        long now = Instant.now().getEpochSecond();
        dropStaleTestDatabases(adminUrl, user, pass, now);
        String database = DATABASE_PREFIX + now + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        execute(adminUrl, user, pass, "CREATE DATABASE " + database);
        return new PostgresTestDatabase(withDatabase(adminUrl, database), user, pass);
    }

    /**
     * Test databases are not dropped when their JVM exits: shutdown hooks run concurrently, so a drop could pull the
     * database from under a Spring context that is still stopping, and its scheduled jobs would fail noisily. Instead,
     * each new test JVM drops test databases created more than an hour ago, which no running build still uses.
     */
    private static void dropStaleTestDatabases(String adminUrl, String user, String pass, long now) {
        List<String> stale = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(adminUrl, user, pass);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT datname FROM pg_database WHERE datname LIKE 'daqiqa\\_test\\_%'")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (createdBefore(name, now - STALE_AFTER_SECONDS)) {
                    stale.add(name);
                }
            }
        } catch (SQLException e) {
            return; // Housekeeping only: never fail a build because old databases could not be listed.
        }
        for (String name : stale) {
            try {
                execute(adminUrl, user, pass, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            } catch (RuntimeException ignored) {
                // Another JVM may be dropping it at the same moment; either way it is gone or will be next time.
            }
        }
    }

    /** Names look like {@code daqiqa_test_<epochSecond>_<random>}; anything else is a leftover from an older scheme. */
    static boolean createdBefore(String databaseName, long epochSecond) {
        String[] parts = databaseName.substring(DATABASE_PREFIX.length()).split("_");
        try {
            return Long.parseLong(parts[0]) < epochSecond;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** A pooled data source (up to 64 connections, enough for concurrency tests). Do not close it. */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Runs Flyway migrations from the given locations, for example {@code "classpath:db/migration/inventory"}.
     * Calling again with locations already migrated is a no-op.
     *
     * <p>Flyway is always given <em>every</em> location migrated so far in this JVM, not only the new ones. Its
     * validation fails on an applied migration it cannot resolve ("applied migration not resolved locally"), so
     * migrating only the new locations broke whenever a test class asked for other schemas than a class that ran
     * before it: the outcome depended on the order in which the classes ran.
     */
    public synchronized PostgresTestDatabase migrate(String... locations) {
        Set<String> union = new LinkedHashSet<>(migratedLocations);
        union.addAll(Arrays.asList(locations));
        if (union.size() > migratedLocations.size()) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations(union.toArray(String[]::new))
                    .outOfOrder(true) // modules own disjoint version ranges; see docs/engineering/conventions.md
                    .load()
                    .migrate();
            migratedLocations.addAll(union); // only once Flyway succeeded: a failed migration is retried next call
        }
        return this;
    }

    /** Empties every table in the given schemas (except Flyway's history). Call from {@code @BeforeEach}. */
    public void truncateSchemas(String... schemas) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            for (String schema : schemas) {
                try (ResultSet rs = connection
                        .getMetaData()
                        .getTables(null, schema, "%", new String[] {"TABLE", "PARTITIONED TABLE"})) {
                    while (rs.next()) {
                        String table = rs.getString("TABLE_NAME");
                        if (!table.startsWith("flyway_schema_history")) {
                            tables.add(schema + "." + table);
                        }
                    }
                }
            }
            if (!tables.isEmpty()) {
                statement.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not truncate schemas " + Arrays.toString(schemas), e);
        }
    }

    /** Points a Spring test context at this database. Use from a {@code @DynamicPropertySource} method. */
    public void registerWith(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", this::jdbcUrl);
        registry.add("spring.datasource.username", this::username);
        registry.add("spring.datasource.password", this::password);
    }

    private static void execute(String url, String user, String pass, String sql) {
        try (Connection connection = DriverManager.getConnection(url, user, pass);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to run '" + sql + "' on " + url, e);
        }
    }

    static String withDatabase(String jdbcUrl, String database) {
        int schemeEnd = jdbcUrl.indexOf("//");
        int pathStart = jdbcUrl.indexOf('/', schemeEnd + 2);
        if (schemeEnd < 0 || pathStart < 0) {
            throw new IllegalArgumentException("Expected jdbc:postgresql://host:port/db, got " + jdbcUrl);
        }
        int queryStart = jdbcUrl.indexOf('?', pathStart);
        String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, pathStart + 1) + database + query;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
