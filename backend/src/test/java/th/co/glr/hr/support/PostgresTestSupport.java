package th.co.glr.hr.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Resolves the PostgreSQL datasource used by the integration + security tests, so a plain local
 * {@code ./mvnw verify} runs the full suite instead of skipping it. Resolution order:
 *
 * <ol>
 *   <li>{@code TEST_DB_URL} env set → use that url/user/pass (external-DB override; keeps the
 *       legacy CI path and any hosted DB working). Reset between tests is Flyway
 *       {@code clean()} + {@code migrate()} — see {@link #externalFlyway(DataSource)}.</li>
 *   <li>else Docker available → start/reuse a JVM-shared singleton {@code postgres:16-alpine}
 *       container. The 66-migration schema is applied <b>once</b> into a frozen
 *       {@code golden_it} template database; each test then gets a byte-identical copy by
 *       recreating {@code wrk_it} with {@code CREATE DATABASE ... TEMPLATE golden_it} (a fast
 *       server-side file copy) instead of replaying every migration. See {@link #resetToGolden()}.</li>
 *   <li>else → not available, and the DB-backed tests skip gracefully (no hard failure).</li>
 * </ol>
 *
 * <p><b>Why a template clone rather than clean+migrate per test:</b> the previous approach ran a
 * full Flyway {@code clean()} + {@code migrate()} (all 66 migrations) in {@code @BeforeEach}, i.e.
 * once per test <i>method</i> — the dominant cost of the backend integration suite in CI. Cloning
 * a pre-migrated template reproduces the exact same post-migration state (schema, migration-seeded
 * reference rows, sequences, identity columns) without re-running any DDL. The
 * {@code TEST_DB_URL} path is deliberately left on the old clean+migrate so we never
 * {@code DROP DATABASE} on an externally-provided (possibly shared) database.
 *
 * <p>{@link #isAvailable()} is the guard the tests use to decide whether to run.
 */
public final class PostgresTestSupport {

    private static final String ENV_URL = "TEST_DB_URL";
    private static final String ENV_USERNAME = "TEST_DB_USERNAME";
    private static final String ENV_PASSWORD = "TEST_DB_PASSWORD";

    // The schemas the app's Flyway migrations own. Kept in one place so the golden migrate and the
    // external-DB reset ({@link #externalFlyway}) stay in lockstep.
    private static final String[] SCHEMAS = {"hr", "hr_restricted", "sales", "customers", "price_catalog"};
    private static final String DEFAULT_SCHEMA = "hr";

    // Migrated once, mounted only as the source of CREATE DATABASE ... TEMPLATE (never connected to
    // by tests). Dropped + recreated fresh from the template before each test.
    private static final String GOLDEN_DB = "golden_it";
    private static final String WORKING_DB = "wrk_it";

    // Lazy-held singletons so the container starts at most once per JVM and only when actually needed.
    private static volatile Boolean available;
    private static volatile PostgreSQLContainer container;
    private static volatile boolean goldenReady;

    // Counts how many times the full migration set has actually been replayed on the container path.
    // The whole point of the golden-template design is that this stays 1 for the entire JVM no matter
    // how many integration tests reset — IntegrationResetInvariantTest asserts it.
    private static final AtomicInteger goldenMigrateCount = new AtomicInteger();

    private PostgresTestSupport() {
    }

    private static boolean envUrlSet() {
        String url = System.getenv(ENV_URL);
        return url != null && !url.isBlank();
    }

    /**
     * True when the fast Testcontainers template-clone path is in use (Docker available and no
     * external {@code TEST_DB_URL} override). The external path keeps the legacy clean+migrate reset.
     */
    public static boolean usesContainer() {
        return isAvailable() && !envUrlSet();
    }

    /** True when a datasource can be provided — either via TEST_DB_URL or a reachable Docker daemon. */
    public static synchronized boolean isAvailable() {
        if (available != null) {
            return available;
        }
        if (envUrlSet()) {
            available = Boolean.TRUE;
            return true;
        }
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        if (docker) {
            container(); // start/reuse the singleton up front so failures surface here
            available = Boolean.TRUE;
        } else {
            available = Boolean.FALSE;
        }
        return available;
    }

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer("postgres:16-alpine");
            started.start(); // Ryuk stops it (and every database inside) when the JVM/build ends
            container = started;
        }
        return container;
    }

    // ---- container path: golden template + per-test clone -------------------------------------

    private static String dbUrl(String db) {
        PostgreSQLContainer c = container();
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/" + db;
    }

    /** Applies the full migration set to the frozen {@link #GOLDEN_DB} template exactly once. */
    private static synchronized void ensureGolden() {
        if (goldenReady) {
            return;
        }
        // (Re)create an empty template DB, then migrate it once. FORCE evicts any straggler
        // connection so a re-run inside the same daemon can't wedge on the drop.
        execAdmin("DROP DATABASE IF EXISTS " + GOLDEN_DB + " WITH (FORCE)");
        execAdmin("CREATE DATABASE " + GOLDEN_DB);
        DriverManagerDataSource ds = new DriverManagerDataSource(
            dbUrl(GOLDEN_DB), container().getUsername(), container().getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        Flyway.configure()
            .dataSource(ds)
            .schemas(SCHEMAS)
            .defaultSchema(DEFAULT_SCHEMA)
            .load()
            .migrate();
        goldenMigrateCount.incrementAndGet();
        goldenReady = true;
    }

    /**
     * Container path only: drop the working database and re-clone it from the migrated golden
     * template, giving the next test a clean, fully-migrated schema without replaying migrations.
     * FORCE terminates any connection a test leaked, so the drop can't hang.
     */
    public static synchronized void resetToGolden() {
        ensureGolden();
        execAdmin("DROP DATABASE IF EXISTS " + WORKING_DB + " WITH (FORCE)");
        execAdmin("CREATE DATABASE " + WORKING_DB + " TEMPLATE " + GOLDEN_DB);
    }

    /** Runs a database-management statement on the maintenance {@code postgres} DB. */
    private static void execAdmin(String sql) {
        PostgreSQLContainer c = container();
        try (Connection conn = DriverManager.getConnection(dbUrl("postgres"), c.getUsername(), c.getPassword());
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Admin SQL failed: " + sql, e);
        }
    }

    // ---- datasource accessors -----------------------------------------------------------------

    /**
     * The shared "boot" datasource URL: the external {@code TEST_DB_URL} if set, else the
     * container's default database. Used by standalone {@code @SpringBootTest}s (via
     * {@code @DynamicPropertySource}) whose app-boot Flyway migrates it — a stable database that is
     * never dropped, unlike the per-test working DB. Not used for the repository reset path.
     */
    public static String jdbcUrl() {
        if (envUrlSet()) {
            return System.getenv(ENV_URL);
        }
        return container().getJdbcUrl();
    }

    /**
     * The datasource URL for {@link AbstractPostgresIntegrationTest}: the external
     * {@code TEST_DB_URL} if set (one shared DB, reset by clean+migrate), else the per-test
     * {@link #WORKING_DB} clone target that {@link #resetToGolden()} (re)creates before each test.
     */
    public static String workingJdbcUrl() {
        if (envUrlSet()) {
            return System.getenv(ENV_URL);
        }
        return dbUrl(WORKING_DB);
    }

    /** The per-test working database name on the container path, or {@code null} on the external path. */
    public static String workingDatabaseName() {
        return envUrlSet() ? null : WORKING_DB;
    }

    /**
     * Test-only regression signal: how many times the full migration set has been replayed on the
     * container path this JVM. Must stay {@code 1} — see {@code IntegrationResetInvariantTest}.
     */
    public static int goldenMigrateCount() {
        return goldenMigrateCount.get();
    }

    public static String username() {
        if (envUrlSet()) {
            return System.getenv(ENV_USERNAME);
        }
        return container().getUsername();
    }

    public static String password() {
        if (envUrlSet()) {
            return System.getenv(ENV_PASSWORD);
        }
        return container().getPassword();
    }

    /**
     * Flyway configured identically to the golden migrate, for the external {@code TEST_DB_URL}
     * reset path (clean + migrate per test). Kept here so both reset paths stay in lockstep.
     */
    public static Flyway externalFlyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMAS)
            .defaultSchema(DEFAULT_SCHEMA)
            .cleanDisabled(false)
            .load();
    }
}
