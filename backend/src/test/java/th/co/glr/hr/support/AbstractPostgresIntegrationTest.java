package th.co.glr.hr.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentSsoInclusionUpsertRequest;
import th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest;
import th.co.glr.hr.payroll.PayrollComponent;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollTaxTreatment;

/**
 * Base for repository integration tests that run the real dynamic SQL against a real PostgreSQL
 * database — the gap Mockito-based unit tests cannot cover (issue #28).
 *
 * <p>The datasource is resolved by {@link PostgresTestSupport}: an explicit {@code TEST_DB_URL}
 * overrides everything (external DB), otherwise a throwaway Testcontainers Postgres is started/reused.
 * Each test starts from a clean, fully-migrated schema so tests are independent and order-free.
 *
 * <p><b>The {@code @EnabledIf} below does NOT disable subclasses, and this Javadoc used to claim
 * otherwise.</b> It said a DB-less {@code mvnw verify} "still runs green" because the tests skip.
 * They do not skip. JUnit's {@code @EnabledIf} is not {@code @Inherited} (junit-jupiter-api 5.12.2:
 * {@code @Target}, {@code @Retention}, {@code @Documented}, {@code @ExtendWith}, {@code @API} — and
 * JUnit only walks to a superclass for annotations that are), so declared here it governs this
 * class alone. With neither {@code TEST_DB_URL} nor Docker every subclass runs and fails in
 * {@link #resetSchema} — {@code Could not find a valid Docker environment}, or
 * {@code FATAL: database "wrk_it" does not exist} once {@link PostgresTestSupport#usesContainer()}
 * routes the reset down the external-DB path. Verified empirically, not inferred.
 *
 * <p>The annotation is kept rather than deleted: it is accurate for this class, and removing it
 * would change nothing. What matters is that <b>no one should "repair" the inheritance</b> — making
 * these ~130 classes genuinely skip is how a green build comes to mean nothing at all, which is the
 * outcome {@link IntegrationDatabaseRequiredInCiTest} exists to prevent. Read that class before
 * touching this annotation.
 *
 * <p>On the Testcontainers path the reset is a {@code CREATE DATABASE ... TEMPLATE} clone of a
 * schema migrated <b>once</b> per JVM — not a per-test Flyway {@code clean()} + {@code migrate()}.
 * The resulting state is identical (schema, migration-seeded rows, sequences), but replaying all 66
 * migrations before every test method — the old behaviour, and the dominant cost of the backend
 * integration suite in CI — is gone. The external {@code TEST_DB_URL} path keeps clean+migrate so we
 * never drop a database we don't own. See {@link PostgresTestSupport} for the full rationale.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
public abstract class AbstractPostgresIntegrationTest {
    /**
     * A real connection <b>pool</b>, not a {@code DriverManagerDataSource}.
     *
     * <p>This was the single dominant cost of the backend suite in CI, and it is worth stating
     * plainly because the old choice looked harmless: {@code DriverManagerDataSource} is not a pool
     * — it opens a <b>brand-new physical connection for every JDBC call</b> and closes it again.
     * Each of those connections paid a full PostgreSQL SCRAM-SHA-256 handshake, whose client side is
     * PBKDF2 with 4096 HMAC-SHA-256 iterations. A JFR profile of one test class
     * ({@code CustomerQuotationIntegrationTest}) showed PBKDF2 + SHA dominating every CPU sample and
     * 9,607 socket reads blocking for 19.2s of a 75s run — all of it spent re-authenticating, none
     * of it testing anything. Pooling took that class from 83.8s to 11.7s (-86%).
     *
     * <p><b>Why this does not change what the tests mean.</b> Reusing a connection could in principle
     * leak session state between statements that previously each got a virgin connection. It cannot
     * here, and that was checked rather than assumed: the codebase uses no session-scoped
     * {@code pg_advisory_lock} (only {@code pg_advisory_xact_lock}, which is transaction-scoped and
     * released at the end of its own auto-committed statement either way), no {@code SET}, no temp
     * tables, and no {@code search_path} juggling — every query is schema-qualified. Autocommit stays
     * on, matching the old behaviour. If anything this is <i>more</i> production-faithful, because
     * production runs HikariCP too (Spring Boot's default pool).
     *
     * <p>{@code maximumPoolSize} is 8 against a measured maximum demand of 2 (the optimistic-locking
     * and advisory-lock tests use {@code newFixedThreadPool(2)}); the headroom means a test that
     * leaks a connection still runs. {@code connectionTimeout} is deliberately short so exhaustion
     * fails loudly in seconds instead of wedging the build — this repo has lost builds to hangs
     * before, and a fast red is worth more than a slow mystery.
     */
    private static HikariDataSource dataSource;

    protected NamedParameterJdbcTemplate jdbc;

    /**
     * A real {@link PlatformTransactionManager}/{@link TransactionTemplate} pair on the same
     * {@link DataSource} {@link #jdbc} uses. Every integration test in this suite hand-wires its
     * services with {@code new} (no Spring context, no AOP proxy), so a bare {@code @Transactional}
     * on the production method does nothing by itself — see {@link #transactional} below for why
     * that matters, and this class's own Javadoc for the consequences (no rollback ever exercised,
     * {@code pg_advisory_xact_lock} released at the end of its own statement instead of held for
     * the transaction).
     */
    protected PlatformTransactionManager transactionManager;
    protected TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetSchema() {
        if (PostgresTestSupport.usesContainer()) {
            // Fast path: clone the pre-migrated golden template. Must run before dataSource() first
            // connects, since it (re)creates the working database this test connects to.
            //
            // The pool has to go FIRST, and only on this path: resetToGolden() issues
            // `DROP DATABASE wrk_it WITH (FORCE)`, which terminates every connection to it
            // server-side. A pool that survived the drop would keep handing out sockets to a
            // database that no longer exists. The external TEST_DB_URL path never drops the
            // database (Flyway clean+migrate on objects, not the DB), so its pool is kept — it
            // holds no locks while idle in autocommit, and clean() proceeds normally.
            closePool();
            PostgresTestSupport.resetToGolden();
        } else {
            // External TEST_DB_URL: replay clean + migrate, so we never drop a DB we don't own.
            Flyway flyway = PostgresTestSupport.externalFlyway(dataSource());
            flyway.clean();
            flyway.migrate();
        }
        jdbc = new NamedParameterJdbcTemplate(dataSource());
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource());
        transactionManager = txManager;
        transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Wraps a hand-wired service in a REAL transactional AOP proxy, so its own {@code @Transactional}
     * annotations are honoured exactly as they are in production.
     *
     * <p><strong>Why this matters, and why {@link #transactionTemplate} alone is not enough:</strong>
     * a test that wraps its call in {@code transactionTemplate.execute(...)} creates the transaction
     * *itself* — so deleting {@code @Transactional} from the method under test would NOT turn that
     * test red; the test's own template would silently supply the transaction the annotation was
     * supposed to. Only a call through a proxy built from the annotation proves the annotation is
     * doing the work: {@code transactionTemplate} tests rollback *SQL*, {@link #transactional} tests
     * the *annotation*. Note also that, exactly as in production, self-invocation inside the
     * wrapped service (one method calling another {@code @Transactional} method on {@code this})
     * bypasses the proxy — CGLIB proxies the entry point, not internal calls.
     */
    @SuppressWarnings("unchecked")
    protected <T> T transactional(T service) {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /**
     * A REAL {@link th.co.glr.hr.factoryquote.FactoryQuoteCarryForward}, for the many tests that
     * hand-wire a {@code PricingRequestService} and do not otherwise care about factory quotes.
     *
     * <p>Deliberately real rather than a mock, and deliberately a helper rather than an optional
     * constructor argument. A mock (or a null-tolerating overload) would make every one of the
     * ~20 hand-wired services silently skip the carry-forward branch, so the suite would be green
     * about a code path production runs and the tests never enter — the exact failure shape
     * {@code CLAUDE.md} catalogues. Wired against the same {@link #jdbc} everything else uses, so
     * a test that DOES set up a revision with identical items sees the real behaviour without
     * having to opt in.
     *
     * <p>Fresh repository instances are fine: they are stateless {@code NamedParameterJdbcTemplate}
     * wrappers, so sharing an instance with the caller's own would change nothing.
     */
    protected th.co.glr.hr.factoryquote.FactoryQuoteCarryForward factoryQuoteCarryForward() {
        th.co.glr.hr.pricingrequest.PricingRequestRepository pricingRequests =
            new th.co.glr.hr.pricingrequest.PricingRequestRepository(jdbc);
        th.co.glr.hr.factoryquote.FactoryQuoteRepository factoryQuotes =
            new th.co.glr.hr.factoryquote.FactoryQuoteRepository(jdbc);
        return new th.co.glr.hr.factoryquote.FactoryQuoteCarryForward(factoryQuotes, pricingRequests,
            new th.co.glr.hr.pricingcosting.LandedCostCalculator(factoryQuotes, pricingRequests,
                new th.co.glr.hr.pricing.FxRateRepository(jdbc),
                new th.co.glr.hr.factory.FactoryConfigRepository(jdbc),
                new th.co.glr.hr.catalog.CatalogRepository(jdbc),
                new th.co.glr.hr.pricingcosting.PricingFormulaEngine(
                    new th.co.glr.hr.pricing.PricingFormulaConfigRepository(jdbc))));
    }

    private static DataSource dataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(PostgresTestSupport.workingJdbcUrl());
            config.setUsername(PostgresTestSupport.username());
            config.setPassword(PostgresTestSupport.password());
            config.setDriverClassName("org.postgresql.Driver");
            config.setPoolName("integration-test-pool");
            // See the field Javadoc: 8 against a measured demand of 2, and a short timeout so a
            // leaked connection surfaces as a fast failure rather than a 30s stall per acquisition.
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10_000);
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /**
     * Tears the pool down so the working database can be dropped out from under it. Idempotent, and
     * safe to call before the pool has ever been built.
     */
    private static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
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
        // V152 (V109 engine wiring): every pricing-costing test needs a resolvable thickness_mm —
        // LandedCostCalculator#resolveThicknessMm refuses to cost an item without one (owner
        // ruling: refuse to price rather than guess). 10mm sits inside V109's seeded [8,12) band
        // for EVERY seeded origin country (Italy/Spain/China), and that band is the one seeded
        // with NO gap all the way to its open-ended top (qty [801,NULL)) — so a test using this
        // default is costable at ANY quantity, not just a specific range. A test that cares about
        // thickness itself uses the explicit overload below instead.
        return insertCatalogProduct(factoryName, countryCode2, productCode, price, currency, priceUnit,
            versionStatus, new BigDecimal("10"));
    }

    /**
     * Full control over {@code thickness_mm} — for tests exercising V109's thickness-banded
     * freight lookup itself (band edges, the "missing thickness" refusal), or a scenario that
     * must NOT default to the safe 10mm above.
     */
    protected long insertCatalogProduct(String factoryName, String countryCode2, String productCode,
                                        BigDecimal price, String currency, String priceUnit, String versionStatus,
                                        BigDecimal thicknessMm) {
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
            INSERT INTO price_catalog.product_prices
                (factory_id, version_id, product_code, price, currency, price_unit, thickness_mm)
            VALUES (:factoryId, :versionId, :productCode, :price, :currency, :priceUnit, :thicknessMm)
            RETURNING price_id
            """,
            new MapSqlParameterSource()
                .addValue("factoryId", factoryId)
                .addValue("versionId", versionId)
                .addValue("productCode", productCode)
                .addValue("price", price)
                .addValue("currency", currency)
                .addValue("priceUnit", priceUnit)
                .addValue("thicknessMm", thicknessMm),
            Long.class);
        return priceId;
    }

    /**
     * Task 2 (2026-07-29): {@link th.co.glr.hr.payroll.PayrollCalculator#calculateClassified} rejects
     * any non-zero pay component that has no stored withholding-tax classification (handoff section
     * 1, docs/agent-handoffs/119_feat-payroll-classification-and-hr-declarations.md). Every payroll
     * integration test that drives {@code PayrollService#preview}/{@code #process} with a non-zero
     * component now needs a real classification row for that employee/component, or the run 409s --
     * this is the seeding helper for the common case: classify every listed component
     * {@code REGULAR_REPROJECT}, which reproduces the pre-task-2 engine's blended single-limb
     * annualisation exactly for a test employee with no EXTRA_KNOWN_FREQUENCY/EXTRA_CUMULATIVE_ACTUAL
     * components in play. SALARY needs no call (it is locked to REGULAR_REPROJECT regardless of
     * whether a row exists).
     */
    /**
     * V98 (2026-07-29): {@code findCarryForwardSuggestions} pre-fills a slot only where THAT employee
     * has a carry-forward flag set. An employee with no flag row carries nothing, which is the safe
     * default and what the five unresolved workbook names get — so any test asserting a carried
     * figure has to seed the flags first, exactly as HR's real configuration does.
     *
     * <p>Do NOT reach for this to make an assertion pass. Carrying is per employee per component by
     * design; a test that seeds every component is asserting against a configuration no real employee
     * has.
     */
    protected void seedCarryForward(long employeeId, int taxYear, PayrollComponent... components) {
        for (PayrollComponent component : components) {
            jdbc.update("""
                INSERT INTO hr.payroll_component_carry_forward
                    (employee_id, tax_year, component, carry_forward, updated_at)
                VALUES (:employeeId, :taxYear, :component, TRUE, now())
                ON CONFLICT (employee_id, tax_year, component)
                DO UPDATE SET carry_forward = TRUE
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("employeeId", employeeId)
                    .addValue("taxYear", taxYear)
                    .addValue("component", component.name()));
        }
    }

    protected void seedRegularTaxTreatment(long employeeId, int taxYear, PayrollComponent... components) {
        List<ComponentTaxTreatmentUpsertRequest> items = Arrays.stream(components)
            .map(component -> new ComponentTaxTreatmentUpsertRequest(employeeId, component, PayrollTaxTreatment.REGULAR_REPROJECT))
            .toList();
        new PayrollRepository(jdbc).upsertComponentTaxTreatment(taxYear, items, employeeId);
    }

    /**
     * Companion to {@link #seedRegularTaxTreatment}: SSO inclusion has no application-level default
     * at the calculator layer (the TRUE-except-director/non-taxable default lives entirely in {@link
     * PayrollRepository#seedSsoInclusionDefaults}, upstream of the calculator) -- an employee with no
     * stored inclusion row is excluded from the SSO wage base entirely. Tests that assert a specific
     * {@code socialSecurity} figure need this too, not just the tax-treatment seed above.
     */
    protected void seedSsoIncluded(long employeeId, int taxYear, PayrollComponent... components) {
        List<ComponentSsoInclusionUpsertRequest> items = Arrays.stream(components)
            .map(component -> new ComponentSsoInclusionUpsertRequest(employeeId, component, true))
            .toList();
        new PayrollRepository(jdbc).upsertComponentSsoInclusion(taxYear, items, employeeId);
    }
}
