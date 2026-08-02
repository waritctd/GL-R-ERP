package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V114: proves that a payroll month can be closed to overtime WITHOUT ever being marked {@code
 * PROCESSED} in this system -- because it was already paid outside the ERP and is reflected in
 * {@code hr.payroll_year_to_date_seed} (PND1 filed). Drives the real {@link OvertimeService} and
 * {@link OvertimeRepository} against real Postgres, plus the {@code hr.payroll_period} guard
 * trigger directly -- Mockito cannot reach either the repository SQL or the trigger.
 *
 * <p>Written wrong-way-round throughout: every guarded case asserts the write was REFUSED, not
 * that a permitted write succeeded.
 *
 * <p>{@code appProperties.getOvertime().setRetroactiveWindowDays} is widened here on purpose. The
 * 60-day default (see {@link AppProperties.Overtime}) bounds how far back a request may reach and
 * is unrelated to what this class tests; left at 60, dates fixed to the real 2026 seed-coverage
 * boundary (2026-03, 2026-06, 2026-07) would fail with an unrelated BAD_REQUEST ("filed too late")
 * once the suite runs more than 60 days after 2026-06-03, which would make this class rot the
 * moment CI's clock moved past that -- exactly the kind of test that passes for the wrong reason.
 * Widening the window keeps every case here isolated to the one guard it exists to prove.
 */
class SeedCoveredPayrollMonthIntegrationTest extends AbstractPostgresIntegrationTest {

    private OvertimeService overtimeService;
    private OvertimeRepository overtimeRepository;

    private long division;
    private long manager;
    private long staff;

    @BeforeEach
    void wireRealCollaborators() {
        overtimeRepository = new OvertimeRepository(jdbc);
        AppProperties appProperties = new AppProperties();
        appProperties.getOvertime().setRetroactiveWindowDays(20_000);
        overtimeService = new OvertimeService(
            overtimeRepository,
            mock(AuditService.class),
            mock(NotificationService.class),
            appProperties,
            mock(AttendanceDailyService.class));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", null);
        staff = insertEmployee("S001", manager);
    }

    // --- case 1: submit -------------------------------------------------

    @Test
    void submitIntoASeedCoveredMonthIsRefusedAndWritesNothing() {
        insertCoverage(2026, "2026-06-01");
        LocalDate workDate = LocalDate.parse("2026-03-16");

        assertThatThrownBy(() -> overtimeService.submit(backdated(workDate), employee(staff)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("จ่ายนอกระบบ") // distinguishes this from the "already processed"
            .satisfies(exception -> assertThat(((ApiException) exception).getStatus())
                .isEqualTo(HttpStatus.CONFLICT));

        assertThat(countOvertimeRequestsFor(staff)).isZero();
    }

    // --- case 2: each approval stage, coverage recorded AFTER filing ----

    @Test
    void managerApprovalIsRefusedWhenCoverageIsRecordedAfterSubmit() {
        LocalDate workDate = LocalDate.parse("2026-03-16");
        // No coverage row yet -- submit succeeds normally, exactly as it did on prod before the
        // owner's Mar-Jun close attempt.
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");

        insertCoverage(2026, "2026-06-01");

        assertThatThrownBy(() -> overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    }

    @Test
    void ceoApprovalIsRefusedWhenCoverageIsRecordedAfterManagerApproval() {
        LocalDate workDate = LocalDate.parse("2026-03-16");
        insertPunchesCovering(workDate);
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        assertThat(statusOf(id)).isEqualTo("MANAGER_APPROVED");

        // Coverage is recorded only now -- after manager approval, before CEO approval. Proves the
        // guard must run again at the CEO stage, not just at submit/manager-approve.
        insertCoverage(2026, "2026-06-01");

        assertThatThrownBy(() -> overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(id)).isEqualTo("MANAGER_APPROVED");
    }

    // --- case 3: boundary -- June covered, July is not -------------------

    @Test
    void submitForJulyWithTheSameCoverageRowPresentSucceeds() {
        insertCoverage(2026, "2026-06-01");
        LocalDate workDate = LocalDate.parse("2026-07-02");

        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();

        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
        assertThat(payrollMonthOf(id)).isEqualTo(LocalDate.parse("2026-07-01"));
    }

    // --- case 4: no coverage row at all (the UAT shape) -------------------

    @Test
    void submitForMarchSucceedsOnADatabaseWithNoCoverageRow() {
        // Deliberately no insertCoverage() call: a freshly migrated schema has zero rows in
        // hr.payroll_seed_coverage (V114's insert is guarded on a 2026 hr.payroll_year_to_date_seed
        // row, which this schema does not have) -- proving the guard is data-driven, not a
        // hardcoded date, and that an environment with no seed is not silently closed anyway.
        LocalDate workDate = LocalDate.parse("2026-03-16");

        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();

        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
    }

    // --- case 5: trigger-level ---------------------------------------------

    @Test
    void triggerRefusesProcessingACoveredMonthButAllowsAnUncoveredOne() {
        insertCoverage(2026, "2026-06-01");
        insertPeriod("2026-03-01", "OPEN");
        insertPeriod("2026-08-01", "OPEN");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE hr.payroll_period SET status = 'PROCESSED' WHERE payroll_month = DATE '2026-03-01'",
                Map.of()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("จ่ายนอกระบบ");
        assertThat(statusOfPeriod("2026-03-01")).isEqualTo("OPEN");

        // An uncovered month must still be processable -- the trigger is scoped, not a blanket lock.
        jdbc.update(
            "UPDATE hr.payroll_period SET status = 'PROCESSED' WHERE payroll_month = DATE '2026-08-01'",
            Map.of());
        assertThat(statusOfPeriod("2026-08-01")).isEqualTo("PROCESSED");
    }

    // --- helpers -------------------------------------------------------

    private SubmitOvertimeRequest backdated(LocalDate workDate) {
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            null, workDate, startAt, startAt.plusHours(2), "WORKDAY",
            "Filed late after the shift ended, customer escalation kept everyone past close");
    }

    private void insertPunchesCovering(LocalDate workDate) {
        insertPunch(workDate.atTime(8, 0).atOffset(ZoneOffset.ofHours(7)), workDate);
        insertPunch(workDate.atTime(20, 30).atOffset(ZoneOffset.ofHours(7)), workDate);
    }

    private void insertPunch(OffsetDateTime at, LocalDate workDate) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """, Map.of("badge", "S001", "at", at, "workDate", workDate, "employeeId", staff));
    }

    private void insertCoverage(int taxYear, String coversThrough) {
        jdbc.update("""
            INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
            VALUES (:taxYear, CAST(:coversThrough AS DATE), 'test coverage')
            """, Map.of("taxYear", taxYear, "coversThrough", coversThrough));
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

    private String statusOfPeriod(String payrollMonth) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.payroll_period WHERE payroll_month = CAST(:payrollMonth AS DATE)",
            Map.of("payrollMonth", payrollMonth), String.class);
    }

    private int countOvertimeRequestsFor(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.overtime_request WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private String statusOf(long id) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), String.class);
    }

    private LocalDate payrollMonthOf(long id) {
        return jdbc.queryForObject(
            "SELECT payroll_month FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), LocalDate.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, "emp@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal directManager() {
        return new UserPrincipal(manager, "mgr@glr.co.th", "Manager", "employee",
            manager, true, LocalDate.now(), false, division, true);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(manager, "ceo@glr.co.th", "CEO", "ceo",
            manager, true, LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long reportsTo) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", division);
        params.put("reportsTo", reportsTo);
        params.put("salary", new BigDecimal("30000.00"));
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, current_salary,
                                     hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :salary, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
