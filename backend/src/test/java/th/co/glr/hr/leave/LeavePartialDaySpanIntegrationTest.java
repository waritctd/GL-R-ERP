package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of the partial-day-span feature: a
 * TIMED leave request (start_time/end_time set) may cross more than one calendar day, e.g. an
 * afternoon-only first day continuing through a full second day. Wires the REAL {@link
 * TieredWorkScheduleResolver} and {@link DbHolidayCalendar} against real Postgres -- Mockito cannot
 * verify this: the day-fraction arithmetic depends on the actually-resolved {@code WorkSchedule}
 * (workStart/workEnd) reaching {@code LeaveDayMath#spanDayFractions} through the real repository,
 * and the relaxed {@code chk_leave_time_order} constraint (no single-day constraint)
 * are exercised through a REAL INSERT, not a mocked one. Mirrors {@link
 * LeaveScheduleHolidayAwareIntegrationTest}'s wiring exactly.
 */
class LeavePartialDaySpanIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every date below is well past VACATION's 3-day advance-notice
    // window and LEAVE_WITHOUT_PAY's (0-day) window alike.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        hrEmployeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES ('HR-REVIEWER-PDS', 'HR-REVIEWER', 'ทดสอบ', 30000, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, Map.of(), Long.class);
        AppProperties appProperties = new AppProperties();
        WorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            new WorkScheduleAssignmentRepository(jdbc, appProperties),
            new CompanyWideWorkScheduleResolver(appProperties),
            appProperties);
        HolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        leaveRepository = new LeaveRepository(jdbc, scheduleResolver, holidayCalendar);

        leaveService = new LeaveService(
            leaveRepository,
            Mockito.mock(LeaveAttachmentRepository.class),
            Mockito.mock(FileStorageService.class),
            Mockito.mock(AuditService.class),
            Mockito.mock(NotificationService.class),
            Mockito.mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    // --- The motivating case -----------------------------------------------------

    @Test
    void halfDayAfternoonFirstDayPlusFullSecondDayTotalsOnePointFive() {
        // The motivating case: "ลาพักร้อน 11 ก.ย. 2569 บ่าย
        // (13:30-17:30) ต่อเนื่องถึง 12 ก.ย. 2569 เต็มวัน = 1.50 วัน in ONE request". 11 Sep 2026 is a
        // Friday and 12 Sep 2026 is a Saturday -- for that Saturday to count at all (rather than the
        // request silently dropping to 0.50), the employee must be on a six-day schedule, so this
        // test doubles as the "six-day-division employee whose Saturday is a workday"
        // coverage (ฝ่ายขาย is on a six-day week from 2026-09-01, so an OPS_6D employee on
        // 12 Sep 2026 is realistic, not synthetic). First day: 13:30-17:30 = 240 clock-minutes, ZERO overlap with the 12:30-13:30
        // break (starts exactly as the break ends) -> 240/480 = 0.50. Second day: full 08:30-17:30 =
        // (540 clock - 60 break) / 480 = 480/480 = 1.00 EXACTLY. Total 1.50. VACATION, not
        // LEAVE_WITHOUT_PAY, to prove the quota-split machinery (computeQuotaSplit) also survives a
        // multi-day timed span end to end, not just the raw total -- this employee has never used
        // any VACATION quota, so all 1.50 is paid.
        long warehouseDept = insertDepartmentOnSchedule("WH-PDS1", "OPS_6D");
        long employeeId = insertEmployee("PDS-OWNR", warehouseDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION",
            LocalDate.parse("2026-09-11"), LocalDate.parse("2026-09-12"),
            "half day then full day",
            LocalTime.of(13, 30), LocalTime.of(17, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.totalDays()).isEqualByComparingTo("1.50");
        assertThat(result.paidDays()).isEqualByComparingTo("1.50");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");
        // Persisted, not just returned -- re-read confirms the row the DB constraints actually accepted
        // (chk_leave_time_order's new row-wise form, chk_leave_time_single_day dropped).
        LeaveRequestDto reread = leaveRepository.findById(result.id()).orElseThrow();
        assertThat(reread.totalDays()).isEqualByComparingTo("1.50");
        assertThat(reread.startDate()).isEqualTo(LocalDate.parse("2026-09-11"));
        assertThat(reread.endDate()).isEqualTo(LocalDate.parse("2026-09-12"));
    }

    // --- helpers ------------------------------------------------------------

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertDepartmentOnSchedule(String code, String scheduleCode) {
        long departmentId = jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE)
            RETURNING department_id
            """, new MapSqlParameterSource("code", code), Long.class);
        long workScheduleId = jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            new MapSqlParameterSource("code", scheduleCode), Long.class);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES ('DEPARTMENT', :departmentId, :workScheduleId, DATE '2024-10-01', NULL)
            """, new MapSqlParameterSource()
                .addValue("departmentId", departmentId)
                .addValue("workScheduleId", workScheduleId));
        return departmentId;
    }

    private long insertEmployee(String code, long departmentId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, department_id, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', :departmentId, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("departmentId", departmentId),
            Long.class);
    }

}
