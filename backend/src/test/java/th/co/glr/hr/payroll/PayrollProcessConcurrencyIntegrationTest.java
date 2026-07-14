package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.support.PostgresTestSupport;

/**
 * Proves that two concurrent {@code POST /api/payroll/process} calls for the same month no longer
 * race each other now that {@link PayrollRepository#saveProcessedPeriod} takes a transaction-scoped
 * Postgres advisory lock. Calls {@link PayrollService#process} directly on two threads (not MockMvc,
 * which runs on the calling thread and never actually overlaps the DELETE+INSERT window, and not
 * real HTTP, which would add unrelated auth/session scaffolding) so both threads open genuinely
 * concurrent {@code @Transactional} transactions against a real Postgres, exercising the exact
 * DB-level race the lock is meant to prevent.
 *
 * <p>Before the advisory lock was added, this race was not silent data corruption — the unique
 * constraints on {@code payroll_period.payroll_month} and
 * {@code payroll_line (period_id, employee_id)} already guaranteed a consistent final row count —
 * but the losing concurrent call failed with a raw {@code DataIntegrityViolationException}
 * (surfaced as an HTTP 500) instead of the two calls serializing cleanly, even though reprocessing
 * the same month is otherwise a supported, idempotent operation.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@SpringBootTest
class PayrollProcessConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    @Autowired
    private PayrollService payrollService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void twoConcurrentProcessCallsForSameMonthBothSucceedWithoutDuplicatingOrLosingLines() throws Exception {
        // employee_code is VARCHAR(20); base-36 nanoTime keeps the unique suffix compact.
        long emp1 = seedEmployee("C1-" + Long.toString(System.nanoTime(), 36));
        long emp2 = seedEmployee("C2-" + Long.toString(System.nanoTime(), 36));
        LocalDate month = LocalDate.of(2026, 7, 1);
        UserPrincipal hr = new UserPrincipal(
            7L, "hr@glr.co.th", "HR", "hr", emp1, true, LocalDate.now(), false, null, false);
        ProcessPayrollRequest request = new ProcessPayrollRequest(month, List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error1 = new AtomicReference<>();
        AtomicReference<Throwable> error2 = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> runProcess(start, request, hr, error1));
        Future<?> f2 = pool.submit(() -> runProcess(start, request, hr, error2));
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(error1.get()).as("first concurrent process() call").isNull();
        assertThat(error2.get()).as("second concurrent process() call").isNull();

        // Scoped to (periodId, our two seeded employee ids): the shared Testcontainers Postgres is
        // not reset between test classes, so other suites' seeded active/salaried employees (e.g.
        // PayrollRepositoryIntegrationTest's EMP-001/EMP-002) also appear in this period's lines.
        // Asserting on the whole period would make this test flaky depending on run order/suite
        // composition; scoping to our own employee ids isolates the race being tested.
        Long periodId = jdbc.queryForObject(
            "SELECT period_id FROM hr.payroll_period WHERE payroll_month = :month",
            Map.of("month", month), Long.class);
        Integer periodRowsForMonth = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_period WHERE payroll_month = :month",
            Map.of("month", month), Integer.class);
        Integer lineCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.payroll_line WHERE period_id = :periodId AND employee_id IN (:emp1, :emp2)",
            Map.of("periodId", periodId, "emp1", emp1, "emp2", emp2), Integer.class);
        Integer distinctEmployees = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT employee_id) FROM hr.payroll_line WHERE period_id = :periodId AND employee_id IN (:emp1, :emp2)",
            Map.of("periodId", periodId, "emp1", emp1, "emp2", emp2), Integer.class);

        assertThat(periodRowsForMonth).as("no duplicate payroll_period row for the month").isEqualTo(1);
        assertThat(lineCount).as("exactly one correct run's worth of lines for our seeded employees").isEqualTo(2);
        assertThat(distinctEmployees).as("no employee counted twice").isEqualTo(2);
        assertThat(lineCount).as("no duplicate lines for any employee").isEqualTo(distinctEmployees);
    }

    private void runProcess(
        CountDownLatch start, ProcessPayrollRequest request, UserPrincipal actor, AtomicReference<Throwable> error
    ) {
        try {
            start.await();
            payrollService.process(request, actor);
        } catch (Throwable t) {
            error.set(t);
        }
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            """
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active)
            VALUES (:code, 'ทดสอบ', 'ทดสอบ', 30000, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code), Long.class);
    }
}
