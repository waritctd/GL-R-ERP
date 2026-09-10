package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import th.co.glr.hr.common.ApiException;
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
 * Real-Postgres coverage of the partial-day-span feature (V166, owner-approved 2026-09-10): a
 * TIMED leave request (start_time/end_time set) may now cross more than one calendar day, e.g. an
 * afternoon-only first day continuing through a full second day. Wires the REAL {@link
 * TieredWorkScheduleResolver} and {@link DbHolidayCalendar} against real Postgres -- Mockito cannot
 * verify this: the day-fraction arithmetic depends on the actually-resolved {@code WorkSchedule}
 * (workStart/workEnd) reaching {@code LeaveDayMath#spanDayFractions} through the real repository,
 * and the relaxed {@code chk_leave_time_order}/dropped {@code chk_leave_time_single_day} constraints
 * are exercised through a REAL INSERT, not a mocked one. Mirrors {@link
 * LeaveScheduleHolidayAwareIntegrationTest}'s wiring exactly.
 */
class LeavePartialDaySpanIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every date below is well past VACATION's 3-day
    // advance-notice window and LEAVE_WITHOUT_PAY's (0-day) window alike.
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

    // --- The owner's exact motivating case -----------------------------------------------------

    @Test
    void halfDayAfternoonFirstDayPlusFullSecondDayTotalsOnePointFive() {
        // The owner's own literal motivating case (task brief): "ลาพักร้อน 11 ก.ย. 2569 บ่าย
        // (13:30-17:30) ต่อเนื่องถึง 12 ก.ย. 2569 เต็มวัน = 1.50 วัน in ONE request". 11 Sep 2026 is a
        // Friday and 12 Sep 2026 is a Saturday -- for that Saturday to count at all (rather than the
        // request silently dropping to 0.50), the employee must be on a six-day schedule, so this
        // test doubles as the required "six-day-division employee whose Saturday is a workday"
        // coverage using the EXACT dates from the brief, not stand-in ones (V160 already put ฝ่ายขาย
        // on a six-day week from 2026-09-01, so an OPS_6D employee on 12 Sep 2026 is realistic, not
        // synthetic). First day: 13:30-17:30 = 240 clock-minutes, ZERO overlap with the 12:30-13:30
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
        // Persisted, not just returned -- re-read confirms the row the DB constraints actually
        // accepted (chk_leave_time_order's new row-wise form, chk_leave_time_single_day dropped).
        LeaveRequestDto reread = leaveRepository.findById(result.id()).orElseThrow();
        assertThat(reread.totalDays()).isEqualByComparingTo("1.50");
        assertThat(reread.startDate()).isEqualTo(LocalDate.parse("2026-09-11"));
        assertThat(reread.endDate()).isEqualTo(LocalDate.parse("2026-09-12"));
    }

    /**
     * Regression guard for a defect introduced while building V166 and caught in review: the
     * CALENDAR_DAYS early-return was placed ABOVE the clock-bound check, so a MATERNITY request
     * outside working hours stopped being refused.
     *
     * <p>Pre-V166 the {@code basis == WORKING_DAYS} condition gated only the working-day COUNT --
     * the 08:30-17:30 bound below it was ungated and applied to every leave type. Returning early
     * for CALENDAR_DAYS silently dropped that bound. Only the WORKING-DAY checks are basis-specific.
     */
    @Test
    void aTimedMaternityRequestOutsideWorkingHoursIsStillRefusedEvenThoughItCountsCalendarDays() {
        long officeDept = insertDepartmentOnSchedule("OF-MAT1", "OFFICE_5D");
        long employeeId = insertEmployee("MAT-BOUND", officeDept);

        SubmitLeaveRequest outOfHours = new SubmitLeaveRequest(
            employeeId, "MATERNITY",
            LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"),
            "maternity, out of hours",
            LocalTime.of(6, 0), LocalTime.of(20, 0),
            null, null, null, null, null, null, null);

        assertThatThrownBy(() -> leaveService.submit(outOfHours, employee(employeeId)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ช่วงเวลาทำงาน");

        // Wrong-way-round: the SAME type inside working hours must still be accepted, so the guard
        // above is refusing the time bound rather than refusing MATERNITY outright.
        SubmitLeaveRequest inHours = new SubmitLeaveRequest(
            employeeId, "MATERNITY",
            LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-14"),
            "maternity, in hours",
            LocalTime.of(9, 0), LocalTime.of(12, 0),
            null, null, null, null, null, null, null);

        assertThat(leaveService.submit(inHours, employee(employeeId)).status()).isEqualTo("SUBMITTED");
    }

    // --- Required scenarios per the task brief --------------------------------------------------

    @Test
    void allDayMultiDaySpanIsUnchangedFromBeforeV166() {
        // No times at all -- must be byte-identical to the pre-V166 whole-day multi-day path
        // (LeaveDayMath#unpaidWorkingDaysByMonth/#totalDaysByYear, untouched by this migration).
        long officeDept = insertDepartmentOnSchedule("OFC-PDS2", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-ALLD", officeDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12"),
            "whole-day multi-day, unaffected by V166");

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.startTime()).isNull();
        assertThat(result.endTime()).isNull();
        assertThat(result.totalDays()).isEqualByComparingTo("3.00");
        assertThat(result.paidDays()).isEqualByComparingTo("3.00");
    }

    @Test
    void singleDayTimedRequestUsesTheFixedEightHourBreakAwareDivisorNotTheScheduleSpan() {
        // FINAL rule (owner 2026-09-10): 08:30-12:30 = 240 clock-minutes, ZERO overlap with the
        // 12:30-13:30 company-wide break (the segment ends exactly as the break starts) -> 240
        // worked minutes / 480 = 0.50 HALF_UP. An intermediate draft of this feature divided by the
        // day's own resolved WorkSchedule span instead (240min/540min = 0.4444... -> 0.44) -- WRONG,
        // superseded (see LeaveDayMath's "Partial-day span" section header) -- asserted
        // wrong-way-round below, mirroring PayrollLeaveUnpaidDeductionSeamIntegrationTest's own
        // pinned regression on the exact same arithmetic.
        long officeDept = insertDepartmentOnSchedule("OFC-PDS3", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-SNGL", officeDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-11"), LocalDate.parse("2026-08-11"),
            "single-day timed", LocalTime.of(8, 30), LocalTime.of(12, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.totalDays()).isEqualByComparingTo("0.50");
        assertThat(result.totalDays()).isNotEqualByComparingTo("0.44");
    }

    @Test
    void aSpanCrossingTheLunchBreakSubtractsOnlyTheOverlapNotAFlatHour() {
        // FINAL rule's own differentiator (owner ruling table, task brief): 11:00-14:00 = 180
        // clock-minutes, of which 60 overlap the 12:30-13:30 break -> 120 worked minutes / 480 =
        // 0.25 HALF_UP. This is the case that tells apart every earlier draft: the pre-V166
        // hours/8 divisor (no break subtraction at all) would give 180/480 = 0.375, and the
        // superseded schedule-span-divisor draft would give 180/540 = 0.33 -- both asserted
        // wrong-way-round below.
        long officeDept = insertDepartmentOnSchedule("OFC-PDS8", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-BRK", officeDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-11"), LocalDate.parse("2026-08-11"),
            "crosses the lunch break", LocalTime.of(11, 0), LocalTime.of(14, 0),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.totalDays()).isEqualByComparingTo("0.25");
        assertThat(result.totalDays()).isNotEqualByComparingTo("0.375");
        assertThat(result.totalDays()).isNotEqualByComparingTo("0.33");
    }

    @Test
    void aTimedSpanCrossingAWeekendSkipsSaturdayAndSundayForAFiveDayEmployee() {
        // Fri 2026-08-07 13:00 (afternoon, 0.50) -> Mon 2026-08-10 17:30 (full day, represented by
        // endTime == that day's workEnd, 1.00). Sat 8/8 + Sun 8/9 are non-working for OFFICE_5D and
        // contribute nothing -- proves the interior-day walk still skips weekends for a five-day
        // employee exactly as the whole-day path always has.
        long officeDept = insertDepartmentOnSchedule("OFC-PDS4", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-WKND", officeDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-07"), LocalDate.parse("2026-08-10"),
            "crosses a weekend", LocalTime.of(13, 0), LocalTime.of(17, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.totalDays()).isEqualByComparingTo("1.50");
    }

    @Test
    void aTimedSpanCrossingASeededHolidayExcludesItForEveryEmployee() {
        // Mon 2026-08-03 13:00 (0.50) -> Wed 2026-08-05 17:30 (1.00, last day), with Tue 8/4 seeded
        // as a company holiday -- the interior day contributes NOTHING, mirroring
        // LeaveScheduleHolidayAwareIntegrationTest's own holiday-beats-schedule rule, now proven for
        // the NEW fraction-ledger interior-day branch specifically (not just the whole-day path).
        long officeDept = insertDepartmentOnSchedule("OFC-PDS5", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-HOLI", officeDept);
        insertHoliday("2026-08-04", "ทดสอบวันหยุด");

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-05"),
            "crosses a seeded holiday", LocalTime.of(13, 0), LocalTime.of(17, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.totalDays())
            .as("Mon 0.50 + Tue holiday 0.00 + Wed 1.00 = 1.50")
            .isEqualByComparingTo("1.50");
    }

    @Test
    void aSixDayDivisionEmployeesSaturdayCountsAsAFullWorkingDayInATimedSpan() {
        // Fri 2026-08-07 13:00 (0.50) -> Sat 2026-08-08 17:30 (1.00) -- Saturday is a WORKING day
        // for an OPS_6D employee (V115/V117/V160), unlike the five-day-employee weekend case above
        // (same calendar range, opposite outcome), proving the new boundary-fraction branch is
        // genuinely schedule-aware, not hardcoded Mon-Fri.
        long warehouseDept = insertDepartmentOnSchedule("WH-PDS6", "OPS_6D");
        long employeeId = insertEmployee("PDS-SAT", warehouseDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse("2026-08-07"), LocalDate.parse("2026-08-08"),
            "six-day division, Saturday is a workday", LocalTime.of(13, 0), LocalTime.of(17, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));

        assertThat(result.totalDays())
            .as("Fri 0.50 + Sat (working for OPS_6D) 1.00 = 1.50")
            .isEqualByComparingTo("1.50");
    }

    @Test
    void aTimedSpanCrossingAPayrollMonthBoundaryAttributesUnpaidDaysToEachMonthSeparately() {
        // LEAVE_WITHOUT_PAY (0-day statutory quota, always fully unpaid, V116 -- no quota gate to
        // isolate away): Thu 2026-07-30 13:00 (0.50, July) -> Fri 2026-07-31 full day (1.00, July)
        // -> [Sat 8/1, Sun 8/2 excluded] -> Mon 2026-08-03 17:30 (1.00, August). Total 2.50, split
        // 1.50 July / 1.00 August -- proves LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth's
        // NEW fraction-aware branch (spanDayFractions filtered to the clipped year, then
        // unpaidByMonthFromFractions) attributes a timed span's fractional boundary days to the
        // correct payroll MONTH, not just the correct total.
        long officeDept = insertDepartmentOnSchedule("OFC-PDS7", "OFFICE_5D");
        long employeeId = insertEmployee("PDS-MNTH", officeDept);

        SubmitLeaveRequest request = new SubmitLeaveRequest(
            employeeId, "LEAVE_WITHOUT_PAY", LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-03"),
            "crosses a payroll month boundary", LocalTime.of(13, 0), LocalTime.of(17, 30),
            null, null, null, null, null, null, null);

        LeaveRequestDto result = leaveService.submit(request, employee(employeeId));
        assertThat(result.totalDays()).isEqualByComparingTo("2.50");
        assertThat(result.unpaidDays()).isEqualByComparingTo("2.50");

        // Leave requires approval (2026-08-05): findUnpaidLeaveDaysByEmployeeForMonth is filtered to
        // APPROVED requests.
        leaveService.approve(result.id(), new ReviewLeaveRequest("approved"), hr());

        Map<Long, BigDecimal> unpaidJuly =
            leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-07-01"));
        Map<Long, BigDecimal> unpaidAugust =
            leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01"));

        assertThat(unpaidJuly)
            .as("Thu 0.50 + Fri 1.00 = 1.50 attributed to July")
            .containsEntry(employeeId, new BigDecimal("1.50"));
        assertThat(unpaidAugust)
            .as("Mon 1.00 attributed to August")
            .containsEntry(employeeId, new BigDecimal("1.00"));
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

    private void insertHoliday(String date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :nameTh, 'COMPANY')
            """, new MapSqlParameterSource().addValue("date", LocalDate.parse(date)).addValue("nameTh", nameTh));
    }
}
