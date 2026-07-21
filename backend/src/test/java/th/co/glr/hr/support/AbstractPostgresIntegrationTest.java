package th.co.glr.hr.support;

import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

    /**
     * Financial-integrity review Finding A (commit 3): submit() now requires every pricing
     * request item to have a fully-resolved catalog snapshot (an ACTIVE {@code
     * price_catalog.price_list_versions} row backing a {@code price_catalog.product_prices}
     * row). Every integration test that submits a pricing request with a catalog-backed item
     * needs a real row to point {@code productId} at — this creates one (an idempotent-by-name
     * factory, a fresh ACTIVE price list version, and one product price row) and returns the
     * product's {@code price_id}, i.e. exactly what {@code PricingRequestItemRequest.productId}
     * and the frontend's catalog picker both expect.
     */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit) {
        return insertCatalogProduct(factoryName, countryCode2, productCode, price, currency, priceUnit, "ACTIVE");
    }

    /**
     * Same as the 6-arg overload, but lets the caller choose the price list version's status —
     * used by the catalog-gate test that a {@code product_id} pointing at a non-ACTIVE (e.g.
     * ARCHIVED) version must still fail submit()'s catalog-completeness gate, exactly as an
     * unresolved/free-text item does.
     */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit, String versionStatus) {
        Long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, :currency)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING factory_id
            """,
            new MapSqlParameterSource()
                .addValue("name", factoryName)
                .addValue("country", countryCode2)
                .addValue("currency", currency),
            Long.class);
        Long versionId = jdbc.queryForObject("""
            INSERT INTO price_catalog.price_list_versions (factory_id, label, status, effective_from)
            VALUES (:factoryId, 'Test catalog', :status, CURRENT_DATE)
            RETURNING version_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("status", versionStatus),
            Long.class);
        Long priceId = jdbc.queryForObject("""
            INSERT INTO price_catalog.product_prices (factory_id, version_id, product_code, price, currency, price_unit)
            VALUES (:factoryId, :versionId, :productCode, :price, :currency, :priceUnit)
            RETURNING price_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("versionId", versionId)
                .addValue("productCode", productCode)
                .addValue("price", price)
                .addValue("currency", currency)
                .addValue("priceUnit", priceUnit),
            Long.class);
        return priceId;
    }
}
