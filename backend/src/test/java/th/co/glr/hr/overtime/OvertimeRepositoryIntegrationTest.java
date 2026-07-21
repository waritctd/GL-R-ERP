package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

class OvertimeRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private OvertimeRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new OvertimeRepository(jdbc);
    }

    @Test
    void cancelSubmittedRequestWithOwnerNullReviewFieldsDoesNotFailJdbcUpdate() {
        Long employeeId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code) VALUES ('OT-001') RETURNING employee_id",
            Map.of(),
            Long.class);
        Long overtimeId = jdbc.queryForObject("""
            INSERT INTO hr.overtime_request (
                employee_id, work_date, planned_start_at, planned_end_at,
                planned_minutes, reason, payroll_month
            )
            VALUES (
                :employeeId, DATE '2026-07-15',
                TIMESTAMPTZ '2026-07-15 18:00:00+07',
                TIMESTAMPTZ '2026-07-15 20:00:00+07',
                120, 'Owner cancel integration test', DATE '2026-07-01'
            )
            RETURNING overtime_request_id
            """,
            Map.of("employeeId", employeeId),
            Long.class);

        int updated = repository.cancel(overtimeId, null, null);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT status
              FROM hr.overtime_request
             WHERE overtime_request_id = :id
            """, Map.of("id", overtimeId), String.class)).isEqualTo("CANCELLED");
    }

    @Test
    void payrollMonthProcessedIsFalseWhenNoPeriodExists() {
        assertThat(repository.payrollMonthProcessed(LocalDate.parse("2026-07-01"))).isFalse();
    }

    @Test
    void payrollMonthProcessedIsTrueOnlyForAProcessedPeriod() {
        insertPeriod("2026-05-01", "OPEN");
        insertPeriod("2026-06-01", "PROCESSED");

        assertThat(repository.payrollMonthProcessed(LocalDate.parse("2026-06-01"))).isTrue();
        // An open period must not lock the month — payroll has not run yet.
        assertThat(repository.payrollMonthProcessed(LocalDate.parse("2026-05-01"))).isFalse();
    }

    @Test
    void findSalaryBasisAsOfPicksTheLatestHistoryRowOnOrBeforeTheWorkDateAndIgnoresLaterOnes() {
        long employeeId = insertEmployeeWithSalary("OT-010", new BigDecimal("30000.00"));
        insertSalaryHistory(employeeId, "2026-01-01", new BigDecimal("25000.00"));
        insertSalaryHistory(employeeId, "2026-06-01", new BigDecimal("28000.00"));
        // Dated after the work date — must be ignored.
        insertSalaryHistory(employeeId, "2026-08-01", new BigDecimal("99000.00"));

        BigDecimal basis = repository.findSalaryBasisAsOf(employeeId, LocalDate.parse("2026-07-15"));

        assertThat(basis).isEqualByComparingTo(new BigDecimal("28000.00"));
    }

    /**
     * The case that current_salary cannot answer. The only recorded change is dated after the work
     * date, so the salary in force on the work date is that row's old_amount (เงินเก่า) — not the
     * employee's present salary, which is already the post-raise figure.
     */
    @Test
    void findSalaryBasisAsOfUsesOldAmountOfTheNextChangeWhenNoHistoryPrecedesTheWorkDate() {
        long employeeId = insertEmployeeWithSalary("OT-012", new BigDecimal("60000.00"));
        insertSalaryHistoryWithOldAmount(
            employeeId, "2026-08-01", new BigDecimal("30000.00"), new BigDecimal("60000.00"));

        BigDecimal basis = repository.findSalaryBasisAsOf(employeeId, LocalDate.parse("2026-07-15"));

        assertThat(basis).isEqualByComparingTo(new BigDecimal("30000.00"));
    }

    /** A row on or before the work date still wins over a later row's old_amount. */
    @Test
    void findSalaryBasisAsOfPrefersHistoryOnOrBeforeTheWorkDateOverALaterRowsOldAmount() {
        long employeeId = insertEmployeeWithSalary("OT-013", new BigDecimal("60000.00"));
        insertSalaryHistoryWithOldAmount(
            employeeId, "2026-06-01", new BigDecimal("20000.00"), new BigDecimal("28000.00"));
        insertSalaryHistoryWithOldAmount(
            employeeId, "2026-08-01", new BigDecimal("28000.00"), new BigDecimal("60000.00"));

        BigDecimal basis = repository.findSalaryBasisAsOf(employeeId, LocalDate.parse("2026-07-15"));

        assertThat(basis).isEqualByComparingTo(new BigDecimal("28000.00"));
    }

    @Test
    void findSalaryBasisAsOfFallsBackToCurrentSalaryWhenNoSalaryHistoryExists() {
        long employeeId = insertEmployeeWithSalary("OT-011", new BigDecimal("35000.00"));

        BigDecimal basis = repository.findSalaryBasisAsOf(employeeId, LocalDate.parse("2026-07-15"));

        assertThat(basis).isEqualByComparingTo(new BigDecimal("35000.00"));
    }

    private long insertEmployeeWithSalary(String code, BigDecimal currentSalary) {
        Long employeeId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, current_salary) VALUES (:code, :salary) RETURNING employee_id",
            Map.of("code", code, "salary", currentSalary),
            Long.class);
        return employeeId;
    }

    private void insertSalaryHistoryWithOldAmount(
            long employeeId, String effectiveDate, BigDecimal oldAmount, BigDecimal newAmount) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("effectiveDate", effectiveDate);
        params.put("oldAmount", oldAmount);
        params.put("newAmount", newAmount);
        jdbc.update("""
            INSERT INTO hr.salary_history (employee_id, effective_date, recorded_date, old_amount, new_amount)
            VALUES (:employeeId, CAST(:effectiveDate AS DATE), CAST(:effectiveDate AS DATE), :oldAmount, :newAmount)
            """, params);
    }

    private void insertSalaryHistory(long employeeId, String effectiveDate, BigDecimal newAmount) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("effectiveDate", effectiveDate);
        params.put("newAmount", newAmount);
        jdbc.update("""
            INSERT INTO hr.salary_history (employee_id, effective_date, recorded_date, new_amount)
            VALUES (:employeeId, CAST(:effectiveDate AS DATE), CAST(:effectiveDate AS DATE), :newAmount)
            """, params);
    }

    private void insertPeriod(String payrollMonth, String status) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                CAST(:payrollMonth AS DATE),
                CAST(:payrollMonth AS DATE),
                (CAST(:payrollMonth AS DATE) + INTERVAL '1 month - 1 day')::date,
                (CAST(:payrollMonth AS DATE) + INTERVAL '1 month - 1 day')::date,
                :status
            )
            """, Map.of("payrollMonth", payrollMonth, "status", status));
    }
}
