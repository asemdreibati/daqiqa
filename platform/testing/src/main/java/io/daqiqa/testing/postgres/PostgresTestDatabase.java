package io.daqiqa.testing.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *       {@code jdbc:postgresql://localhost:5432/postgres}), a uniquely named database is created on that server and
 *       dropped when the JVM exits. Credentials come from {@code DAQIQA_TEST_PG_USER} / {@code DAQIQA_TEST_PG_PASSWORD}
 *       (default {@code postgres}/{@code postgres}). Useful on machines without Docker.
 * </ul>
 *
 * <p>Either way each test JVM (one per Gradle module) owns an empty database, so parallel builds never interfere.
 * Tests inside one module share it and are expected to clean up with {@link #truncateSchemas(String...)}.
 */
public final class PostgresTestDatabase {

    private static final String IMAGE = "postgres:17-alpine";
    private static volatile PostgresTestDatabase shared;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final HikariDataSource dataSource;
    private final Set<String> migratedLocations = ConcurrentHashMap.newKeySet();

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
        String database =
                "daqiqa_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        execute(adminUrl, user, pass, "CREATE DATABASE " + database);
        PostgresTestDatabase db = new PostgresTestDatabase(withDatabase(adminUrl, database), user, pass);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            db.dataSource.close();
            try {
                execute(adminUrl, user, pass, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            } catch (RuntimeException ignored) {
                // Best effort: a leftover test database is harmless.
            }
        }));
        return db;
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
     * Runs Flyway migrations from the given locations, for example {@code "classpath:db/migration/inventory"}. Each
     * location is migrated once per JVM; calling again is a no-op.
     */
    public synchronized PostgresTestDatabase migrate(String... locations) {
        List<String> pending = Arrays.stream(locations)
                .filter(location -> !migratedLocations.contains(location))
                .toList();
        if (!pending.isEmpty()) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations(pending.toArray(String[]::new))
                    .outOfOrder(true) // modules own disjoint version ranges; see docs/engineering/conventions.md
                    .load()
                    .migrate();
            migratedLocations.addAll(pending);
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
