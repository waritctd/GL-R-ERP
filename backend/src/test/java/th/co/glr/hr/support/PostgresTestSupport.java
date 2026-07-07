package th.co.glr.hr.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Resolves the PostgreSQL datasource used by the integration + security tests, so a plain local
 * {@code ./mvnw verify} runs the full suite instead of skipping it. Resolution order:
 *
 * <ol>
 *   <li>{@code TEST_DB_URL} env set → use that url/user/pass (external-DB override; keeps the
 *       legacy CI path and any hosted DB working).</li>
 *   <li>else Docker available → start/reuse a JVM-shared singleton
 *       {@code postgres:16-alpine} container (started once; Testcontainers' Ryuk stops it).</li>
 *   <li>else → not available, and the DB-backed tests skip gracefully (no hard failure).</li>
 * </ol>
 *
 * <p>{@link #isAvailable()} is the guard the tests use to decide whether to run.
 */
public final class PostgresTestSupport {

    private static final String ENV_URL = "TEST_DB_URL";
    private static final String ENV_USERNAME = "TEST_DB_USERNAME";
    private static final String ENV_PASSWORD = "TEST_DB_PASSWORD";

    // Lazy-held singletons so the container starts at most once per JVM and only when actually needed.
    private static volatile Boolean available;
    private static volatile PostgreSQLContainer container;

    private PostgresTestSupport() {
    }

    private static boolean envUrlSet() {
        String url = System.getenv(ENV_URL);
        return url != null && !url.isBlank();
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
            started.start(); // Ryuk stops it when the JVM/build ends
            container = started;
        }
        return container;
    }

    public static String jdbcUrl() {
        if (envUrlSet()) {
            return System.getenv(ENV_URL);
        }
        return container().getJdbcUrl();
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
}
