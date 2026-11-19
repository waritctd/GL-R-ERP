package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** V114: proves that a payroll month can be closed to overtime WITHOUT ever being marked PROCESSED in this
 * system -- because it was already paid outside the ERP and is reflected in hr.payroll_year_to_date_seed (PND1
 * filed). */
class SeedCoveredPayrollMonthIntegrationTest extends AbstractPostgresIntegrationTest {

    /** feat/ot-nonworkday-rate-suggestion: this class is about the seed-covered-payroll-month guard, not the
     * schedule-derived suggestion -- its fixture dates include a dynamic "today", which carries no guarantee of
     * landing on a weekday. */
    private static final WorkSchedule ALWAYS_WORKDAY_SCHEDULE = new WorkSchedule(
        ZoneId.of("Asia/Bangkok"), LocalTime.of(8, 30), LocalTime.of(17, 30), 15,
        EnumSet.allOf(DayOfWeek.class));

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
            new ManagerApproverRepository(jdbc),
            mock(AuditService.class),
            mock(NotificationService.class),
            appProperties,
            mock(AttendanceDailyService.class),
            new DbHolidayCalendar(jdbc),
            (employeeId, divisionId, departmentId, workDate) -> ALWAYS_WORKDAY_SCHEDULE, new CeoApproverRepository(jdbc));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", null, "ผู้จัดการฝ่ายขาย");
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
        // No coverage row yet -- submit succeeds normally, exactly as it did on prod before the owner's Mar-Jun close
        // attempt.
        long id = overtimeService.submit(backdated(workDate), employee(staff)).id();
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");

        insertCoverage(2026, "2026-06-01");

        assertThatThrownBy(() -> overtimeService.approve(id, new ApproveOvertimeRequest("ok", null), directManager()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
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

    // --- helpers -------------------------------------------------------

    private SubmitOvertimeRequest backdated(LocalDate workDate) {
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            null, workDate, startAt, startAt.plusHours(2), "WORKDAY",
            "Filed late after the shift ended, customer escalation kept everyone past close");
    }

    private void insertCoverage(int taxYear, String coversThrough) {
        jdbc.update("""
            INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
            VALUES (:taxYear, CAST(:coversThrough AS DATE), 'test coverage')
            """, Map.of("taxYear", taxYear, "coversThrough", coversThrough));
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
        return insertEmployee(code, reportsTo, null);
    }

    private long insertEmployee(String code, Long reportsTo, String positionNameTh) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", division);
        params.put("reportsTo", reportsTo);
        params.put("salary", new BigDecimal("30000.00"));
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, current_salary,
                                     position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :salary,
                    :positionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    /** A ผู้จัดการ position is what makes an employee a manager in the database, which is what
     * ManagerApproverRepository reads when deciding whether a manager stage exists at all. */
    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }

}
