package th.co.glr.hr.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Complements {@link ActuatorHealthIntegrationTest} (which only asserts {@code UP}): verifies
 * {@code GET /actuator/health} reports HTTP 503 with status {@code DOWN} when the database becomes
 * unreachable, and that {@code management.endpoint.health.show-details: never} still hides
 * component/detail leakage in the DOWN response, matching the UP test's assertion style.
 *
 * <p>The app boots normally against a real, reachable Postgres (resolved by
 * {@link PostgresTestSupport}, same as the UP test) — Flyway migration and the Spring Session JDBC
 * store both need a working DB at startup, so the datasource cannot simply point at a bad URL from
 * boot. Instead, once the context is up, the underlying connection pool is closed in-process to
 * simulate the DB going away, then the health endpoint is hit again. Closing the pool is
 * irreversible (Hikari does not support reopening), which is fine here: this class holds no other
 * tests, and Spring's test context cache keys contexts by configuration, so other test classes
 * (with different {@code @DynamicPropertySource}/filter wiring) get their own fresh context rather
 * than reusing this closed one.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test") // excludes SchedulingConfig so no scheduled worker races this shared-DB context
@SpringBootTest
class ActuatorHealthDownIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private final MockMvc mvc;
    private final DataSource dataSource;

    @Autowired
    ActuatorHealthDownIntegrationTest(WebApplicationContext context,
                                       @Qualifier("springSecurityFilterChain") Filter securityFilterChain,
                                       DataSource dataSource) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(securityFilterChain)
            .build();
        this.dataSource = dataSource;
    }

    @Test
    void healthReportsDownWith503WhenDatabaseIsUnreachable() throws Exception {
        assertUnderlyingPoolIsHikari();
        HikariDataSource hikari = (HikariDataSource) dataSource;

        hikari.close(); // simulate the database becoming unreachable

        mvc.perform(get("/actuator/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            // show-details: never -> no component breakdown (e.g. no "db" detail/error message)
            // leaks to an anonymous caller even while reporting DOWN.
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    private void assertUnderlyingPoolIsHikari() {
        if (!(dataSource instanceof HikariDataSource)) {
            throw new IllegalStateException(
                "Expected a HikariDataSource (Spring Boot's default pool) but got: "
                    + dataSource.getClass());
        }
    }
}
