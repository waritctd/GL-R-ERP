package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.UndoImportStepRequest;
import th.co.glr.hr.support.PostgresTestSupport;
import th.co.glr.hr.ticket.DealStage;

/**
 * Review H1 (slice 5): undo must take its locks in EXACTLY advance's order — the ใบขอซื้อ row first (the guarded
 * UPDATE), then the deal (TicketRepository#lockTicketForUpdate) — or an advance and an undo racing on the same row can
 * deadlock. Runs through the real Spring context, so ImportRequestService is the @Transactional PROXY and each call is
 * its own transaction, exactly as in production.
 *
 * <p>Why the competitor is hand-written rather than a second real advance: two real calls race in microseconds and
 * almost never interleave, so a two-real-thread test passes whichever order the code uses (a green test that cannot
 * fail). Instead thread 1 does what advanceStep does at the database — UPDATE the row, then lock the deal — but holds
 * a pause between the two, so the proxied undo in thread 2 is guaranteed to run inside that gap:
 * <ul>
 *   <li>row-then-deal (the fix): the undo blocks on the row, holding nothing; thread 1 locks the deal and commits;
 *       the undo then proceeds and succeeds.</li>
 *   <li>deal-then-row (the bug): the undo locks the deal, then blocks on the row; thread 1 then blocks on the deal →
 *       Postgres detects a deadlock and aborts one of the two → this test fails.</li>
 * </ul>
 * Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@SpringBootTest
@ActiveProfiles("test")
class ImportStepLockOrderIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    @Autowired ImportRequestService service;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private UserPrincipal importUser;
    private long ticketId;
    private long irId;

    @BeforeEach
    void seed() {
        String tag = Long.toString(System.nanoTime(), 36);
        long ownerId = employee("LOCK-OWN-" + tag);
        UserPrincipal owner = principal(ownerId, "sales");
        importUser = principal(employee("LOCK-IMP-" + tag), "import");
        String factory = "Lock Factory " + tag;
        jdbc.update("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES (:n, 'IT', 'EUR')",
            Map.of("n", factory));
        ticketId = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES (:code, 'ทดสอบลำดับล็อก', :by, 'ลูกค้า', 'quotation_issued', 'DEPOSIT_PAID', :stage)
            RETURNING ticket_id""", new MapSqlParameterSource("by", ownerId).addValue("code", "IRLOCK-" + tag)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :f, 'Raku', '20x20', :q, 'pcs', 0, :f)""",
            new MapSqlParameterSource("t", ticketId).addValue("q", new BigDecimal("40")).addValue("f", factory));
        irId = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        service.issue(irId, null, owner);
        service.advanceStep(irId, new AdvanceImportStepRequest(ImportRequestStep.ORDERED, null, null), importUser);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void anUndoInsideAnAdvancesRowThenDealWindow_waitsOnTheRow_andNeitherSideDeadlocks() throws Exception {
        CountDownLatch rowLocked = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);

        Future<?> advanceShape = pool.submit(() -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE sales.import_request SET updated_at = now() WHERE import_request_id = :id",
                Map.of("id", irId));
            rowLocked.countDown();
            sleep(1500); // the undo runs inside this gap
            jdbc.queryForObject("SELECT ticket_id FROM sales.ticket WHERE ticket_id = :t FOR UPDATE",
                Map.of("t", ticketId), Long.class);
        }));
        Future<ImportRequestDto> undo = pool.submit(() -> {
            rowLocked.await(10, TimeUnit.SECONDS);
            return service.undoStep(irId, new UndoImportStepRequest(ImportRequestStep.ORDERED), importUser);
        });

        advanceShape.get(30, TimeUnit.SECONDS); // throws (ExecutionException) if it was the deadlock victim
        assertThat(undo.get(30, TimeUnit.SECONDS).importStep()).isEqualTo(ImportRequestStep.CONTACTED);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long employee(String code) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, first_name_th, last_name_th)"
            + " VALUES (:c, 'ทดสอบ', 'ล็อก') RETURNING employee_id", Map.of("c", code), Long.class);
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-lock@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
