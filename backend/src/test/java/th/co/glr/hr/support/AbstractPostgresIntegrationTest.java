package th.co.glr.hr.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Base for repository integration tests that run the real dynamic SQL against a real PostgreSQL
 * database — the gap Mockito-based unit tests cannot cover (issue #28).
 *
 * <p>Gated on {@code TEST_DB_URL} so the default {@code mvnw verify} (no database) still runs green;
 * CI provides a Postgres service and sets the variable. Each test starts from a clean, fully-migrated
 * schema so tests are independent and order-free. Mirrors the app's Flyway settings.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
public abstract class AbstractPostgresIntegrationTest {
    private static DataSource dataSource;

    protected NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void initDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            System.getenv("TEST_DB_URL"),
            System.getenv("TEST_DB_USERNAME"),
            System.getenv("TEST_DB_PASSWORD"));
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
    }

    @BeforeEach
    void resetSchema() {
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas("hr", "hr_restricted", "sales")
            .defaultSchema("hr")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }
}
