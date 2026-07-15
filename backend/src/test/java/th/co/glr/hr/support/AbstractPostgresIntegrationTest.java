package th.co.glr.hr.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Base for repository integration tests that run the real dynamic SQL against a real PostgreSQL
 * database — the gap Mockito-based unit tests cannot cover (issue #28).
 *
 * <p>The datasource is resolved by {@link PostgresTestSupport}: an explicit {@code TEST_DB_URL}
 * overrides everything (external DB), otherwise a throwaway Testcontainers Postgres is started/reused.
 * When neither a {@code TEST_DB_URL} nor Docker is available the tests are skipped (not failed), so a
 * DB-less {@code mvnw verify} still runs green. Each test starts from a clean, fully-migrated schema
 * so tests are independent and order-free. Mirrors the app's Flyway settings.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
public abstract class AbstractPostgresIntegrationTest {
    private static DataSource dataSource;

    protected NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void resetSchema() {
        if (dataSource == null) {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
            ds.setDriverClassName("org.postgresql.Driver");
            dataSource = ds;
        }
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas("hr", "hr_restricted", "sales", "customers", "price_catalog")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }
}
