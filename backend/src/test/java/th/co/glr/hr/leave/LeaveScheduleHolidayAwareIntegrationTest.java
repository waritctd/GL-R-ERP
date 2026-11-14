package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.HolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.attendance.schedule.WorkScheduleResolver;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Verifies leave counting uses real work schedules and holidays, including six-day weeks. */
class LeaveScheduleHolidayAwareIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every date below is fixed relative to this so the advance-notice
    // rules are a non-issue (every scenario here uses LEAVE_WITHOUT_PAY/MATERNITY, both seeded with
    // advance_notice_days = 0 -- see V116).
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;
    // Nit fix: hr used to hardcode employeeId 1L, which -- since every test here calls insertEmployee for the
    // leave-taking employee BEFORE ever calling hr, and each test starts from a fresh IDENTITY sequence --
    // collided with whichever employee happened to be inserted FIRST in that test.
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        hrEmployeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES ('HR-REVIEWER', 'HR-REVIEWER', 'ทดสอบ', 30000, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, Map.of(), Long.class);
        AppProperties appProperties = new AppProperties();
        WorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            new WorkScheduleAssignmentRepository(jdbc, appProperties),
            new CompanyWideWorkScheduleResolver(appProperties),
            appProperties);
        HolidayCalendar holidayCalendar = new DbHolidayCalendar(jdbc);
        leaveRepository = new LeaveRepository(jdbc, scheduleResolver, holidayCalendar);

        // fileStorage/leaveAttachments are mocked (attachment storage is not under test here), but MATERNITY requires
        // a real certificate attachment (requires_attachment = TRUE) -- same technique as
        // LeaveUnpaidDeductionIntegrationTest#sickLeaveBeyondQuotaStillAutoRejects...
        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.storeInDatabase(anyString(), anyLong(), any(), any()))
            .thenReturn(new FileStorageService.StoredContent(
                "cert.pdf", "leave/1/cert.pdf", "application/pdf", 3L, "cert content".getBytes()));
        long fileAttachmentId = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment (domain, owner_id, file_name, file_path, mime_type, file_size)
            VALUES ('leave', 1, 'cert.pdf', 'leave/1/cert.pdf', 'application/pdf', 3)
            RETURNING attachment_id
            """, Map.of(), Long.class);
        LeaveAttachmentRepository leaveAttachments = mock(LeaveAttachmentRepository.class);
        when(leaveAttachments.saveWithContent(anyLong(), anyString(), anyString(), anyString(), any(), anyLong(), any()))
            .thenReturn(new LeaveAttachmentDto(fileAttachmentId, "leave", 1L, "cert.pdf", "application/pdf", 3L, 1L, Instant.now()));

        leaveService = new LeaveService(
            leaveRepository,
            leaveAttachments,
            fileStorage,
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void aSixDaySaturdayCountsAsWorkingForAWarehouseEmployeeButNotForAnOfficeEmployee() {
        long warehouseDept = insertDepartmentOnSchedule("WH-TEST", "OPS_6D");
        long officeDept = insertDepartmentOnSchedule("OFC-TEST", "OFFICE_5D");
        long warehouseEmployee = insertEmployee("WH-EMP", warehouseDept);
        long officeEmployee = insertEmployee("OFC-EMP", officeDept);

        // Mon 2026-08-03 .. Sat 2026-08-08 (no Sunday in range). LEAVE_WITHOUT_PAY is always fully unpaid from day one
        // with no quota/notice/consecutive-day gates (V116) -- isolates the day-COUNTING defect from every other leave
        // rule.
        LeaveRequestDto warehouseResult = leaveService.submit(
            submitRequest(warehouseEmployee, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-08"),
            employee(warehouseEmployee));
        LeaveRequestDto officeResult = leaveService.submit(
            submitRequest(officeEmployee, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-08"),
            employee(officeEmployee));

        assertThat(warehouseResult.totalDays())
            .as("OPS_6D (warehouse): Mon-Sat all count = 6 working days")
            .isEqualByComparingTo("6.00");
        assertThat(officeResult.totalDays())
            .as("OFFICE_5D: same calendar range, Saturday excluded = 5 working days")
            .isEqualByComparingTo("5.00");
        assertThat(warehouseResult.unpaidDays()).isEqualByComparingTo("6.00");
        assertThat(officeResult.unpaidDays()).isEqualByComparingTo("5.00");

        // Leave requires approval (2026-08-05): the payroll deduction query below is filtered to APPROVED requests
        // (LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth) -- drive both requests there first so this test's
        // actual subject (the corrected counts reaching payroll) is genuinely exercised.
        leaveService.approve(warehouseResult.id(), new ReviewLeaveRequest("approved"), hr());
        leaveService.approve(officeResult.id(), new ReviewLeaveRequest("approved"), hr());

        // The payroll deduction query must reflect the same corrected counts -- this is the query that actually feeds
        // the base/30 unpaid-day deduction (PayrollService#suggestedInputs).
        Map<Long, BigDecimal> unpaidAugust =
            leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01"));
        assertThat(unpaidAugust)
            .containsEntry(warehouseEmployee, new BigDecimal("6.00"))
            .containsEntry(officeEmployee, new BigDecimal("5.00"));
    }

    @Test
    void aSeededHolidayInsideARangeIsNotCountedAsWorkingForEitherA5DayOrA6DayEmployee() {
        long warehouseDept = insertDepartmentOnSchedule("WH-HOL", "OPS_6D");
        long officeDept = insertDepartmentOnSchedule("OFC-HOL", "OFFICE_5D");
        long warehouseControl = insertEmployee("WH-HOL-CTRL", warehouseDept);
        long officeControl = insertEmployee("OFC-HOL-CTRL", officeDept);

        // Vacuous-fixture guard: submit a CONTROL pair over the identical Mon 8/3. hr.holiday is empty by default
        // (V115), so this also proves the test is not accidentally relying on a pre-seeded row.
        LeaveRequestDto warehouseControlResult = leaveService.submit(
            submitRequest(warehouseControl, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-04"),
            employee(warehouseControl));
        LeaveRequestDto officeControlResult = leaveService.submit(
            submitRequest(officeControl, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-04"),
            employee(officeControl));
        assertThat(warehouseControlResult.totalDays())
            .as("control, no holiday seeded yet: both Mon and Tue count")
            .isEqualByComparingTo("2.00");
        assertThat(officeControlResult.totalDays())
            .as("control, no holiday seeded yet: both Mon and Tue count")
            .isEqualByComparingTo("2.00");

        insertHoliday("2026-08-04", "ทดสอบวันหยุด");

        long warehouseEmployee = insertEmployee("WH-HOL-EMP", warehouseDept);
        long officeEmployee = insertEmployee("OFC-HOL-EMP", officeDept);
        LeaveRequestDto warehouseResult = leaveService.submit(
            submitRequest(warehouseEmployee, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-04"),
            employee(warehouseEmployee));
        LeaveRequestDto officeResult = leaveService.submit(
            submitRequest(officeEmployee, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-04"),
            employee(officeEmployee));

        assertThat(warehouseResult.totalDays())
            .as("Mon counts, the seeded Tue holiday does not -- even on a six-day (OPS_6D) schedule")
            .isEqualByComparingTo("1.00");
        assertThat(officeResult.totalDays())
            .as("same for a five-day (OFFICE_5D) schedule -- holiday beats schedule for everyone")
            .isEqualByComparingTo("1.00");

        // Leave requires approval (2026-08-05): findUnpaidLeaveDaysByEmployeeForMonth is filtered to APPROVED requests
        // -- drive all four requests (control pair included) there first.
        leaveService.approve(warehouseControlResult.id(), new ReviewLeaveRequest("approved"), hr());
        leaveService.approve(officeControlResult.id(), new ReviewLeaveRequest("approved"), hr());
        leaveService.approve(warehouseResult.id(), new ReviewLeaveRequest("approved"), hr());
        leaveService.approve(officeResult.id(), new ReviewLeaveRequest("approved"), hr());

        Map<Long, BigDecimal> unpaidAugust =
            leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01"));
        assertThat(unpaidAugust)
            .containsEntry(warehouseEmployee, new BigDecimal("1.00"))
            .containsEntry(officeEmployee, new BigDecimal("1.00"))
            // The CONTROL pair reads 1.00 here, not the 2.00 it was granted at, and that is correct rather than a bug:
            // findUnpaidLeaveDaysByEmployeeForMonth deliberately RECOMPUTES each request's unpaid days against the CURRENT
            // hr.holiday contents at read time.
            .containsEntry(warehouseControl, new BigDecimal("1.00"))
            .containsEntry(officeControl, new BigDecimal("1.00"));
    }

    @Test
    void maternityStillCountsCalendarDaysIncludingASeededHolidayAndAWeekendUnaffectedByEitherFix() {
        long officeDept = insertDepartmentOnSchedule("OFC-MAT", "OFFICE_5D");
        long maternityEmployee = insertEmployee("MAT-EMP", officeDept);
        long controlEmployee = insertEmployee("MAT-CTRL", officeDept);
        insertHoliday("2026-08-04", "ทดสอบวันหยุด");
        MockMultipartFile certificate =
            new MockMultipartFile("attachment", "cert.pdf", "application/pdf", "pdf".getBytes());

        // Maternity counts all seven calendar days, including holidays and weekends excluded by working-day leave.
        LeaveRequestDto maternityResult = leaveService.submit(
            submitRequest(maternityEmployee, "MATERNITY", "2026-08-03", "2026-08-09"),
            certificate, employee(maternityEmployee));
        LeaveRequestDto controlResult = leaveService.submit(
            submitRequest(controlEmployee, "LEAVE_WITHOUT_PAY", "2026-08-03", "2026-08-09"),
            employee(controlEmployee));

        assertThat(maternityResult.totalDays())
            .as("MATERNITY counts all 7 calendar days: 5 weekdays + the holiday + the weekend")
            .isEqualByComparingTo("7.00");
        assertThat(controlResult.totalDays())
            .as("the SAME range under WORKING_DAYS drops the holiday and the weekend: only 4 days")
            .isEqualByComparingTo("4.00");
    }

    @Test
    void aSubDayWorkingDaysRequestOnASeededHolidayIsRejectedButASubDayMaternityRequestOnTheSameHolidayIsNot() {
        long officeDept = insertDepartmentOnSchedule("OFC-SUB", "OFFICE_5D");
        long workingDaysEmployee = insertEmployee("SUB-HOL-EMP", officeDept);
        long maternityEmployee = insertEmployee("SUB-HOL-MAT", officeDept);
        insertHoliday("2026-08-04", "ทดสอบวันหยุด"); // Tuesday
        MockMultipartFile certificate =
            new MockMultipartFile("attachment", "cert.pdf", "application/pdf", "pdf".getBytes());

        SubmitLeaveRequest subDayOnHolidayWorkingDays = new SubmitLeaveRequest(
            workingDaysEmployee, "LEAVE_WITHOUT_PAY",
            LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-04"),
            "sub-day on a holiday", LocalTime.of(8, 30), LocalTime.of(12, 30),
            null, null, null, null, null, null, null);
        SubmitLeaveRequest subDayOnHolidayMaternity = new SubmitLeaveRequest(
            maternityEmployee, "MATERNITY",
            LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-04"),
            "sub-day maternity on the same holiday", LocalTime.of(8, 30), LocalTime.of(12, 30),
            null, null, null, null, null, null, null);

        // WORKING_DAYS: the new schedule/holiday-aware gate in validateSubDayTimes correctly rejects a sub-day request
        // whose only date is a holiday -- there is no working day to take partial leave from.
        assertThatThrownBy(() -> leaveService.submit(subDayOnHolidayWorkingDays, employee(workingDaysEmployee)))
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        // CALENDAR_DAYS (MATERNITY): the SAME holiday date, the SAME gate, must NOT reject -- this is the
        // double-handling guard. Before the basis gate in validateSubDayTimes, this would have been wrongly rejected
        // once holiday-awareness landed (it was accepted, holiday-blind, prior to this branch).
        LeaveRequestDto maternityResult =
            leaveService.submit(subDayOnHolidayMaternity, certificate, employee(maternityEmployee));
        assertThat(maternityResult.status()).isEqualTo("SUBMITTED");
    }

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(
            employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate),
            "Schedule/holiday-aware integration test leave");
    }

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
