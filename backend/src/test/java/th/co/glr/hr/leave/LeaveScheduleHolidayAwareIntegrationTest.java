package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

}
