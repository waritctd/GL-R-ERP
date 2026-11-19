package th.co.glr.hr.attendance.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyCalculator;
import th.co.glr.hr.attendance.daily.AttendanceDailyRepository;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms attendance-correction authorization against the real service and the real SQL. This feature adds a
 * brand-new role gate (CEO-only approval, no manager stage at all — see AttendanceCorrectionService class
 * javadoc). */
class AttendanceCorrectionScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 15);

    private AttendanceCorrectionService service;
    private AttendanceCorrectionRepository repository;

    private long salesDivision;
    private long salesManager;
    private long salesStaff;
    private long ceoEmployee;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new AttendanceCorrectionRepository(jdbc);
        AppProperties properties = new AppProperties();
        AttendanceDailyRepository dailyRepository = new AttendanceDailyRepository(jdbc);
        AttendanceDailyService dailyService = new AttendanceDailyService(
            dailyRepository,
            new AttendanceDailyCalculator(),
            new CompanyWideWorkScheduleResolver(properties),
            new DbHolidayCalendar(jdbc));
        // Audit/notification are downstream of the guard, same convention OvertimeRetroactiveScopeIntegrationTest uses
        // — stubbing them keeps this test about who may act on whose rows.
        service = new AttendanceCorrectionService(
            repository,
            dailyService,
            mock(AuditService.class),
            mock(NotificationService.class), new CeoApproverRepository(jdbc));

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        salesManager = insertEmployee("M001", salesDivision);
        salesStaff = insertEmployee("S001", salesDivision);
        insertEmployee("S002", salesDivision);
        ceoEmployee = insertEmployee("C001", salesDivision);
    }

    // --- approve: division manager is not the CEO -----------------------------------------------

    /** The case that matters most: this feature has NO manager stage at all, so a division manager — who WOULD be
     * able to approve overtime for their own division — must still get 403 here. */
    @Test
    void aDivisionManagerCannotApproveAnotherEmployeesCorrection() {
        long requestId = submitAsAndReturnId(salesStaff);

        assertThatThrownBy(() -> service.approve(requestId, null, manager(salesManager, salesDivision)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // --- approve: the CEO succeeding is the contrast case, kept minimal --------------------------

    /** Positive case for contrast only — per CLAUDE.md's wrong-way-round rule, this is NOT the test that matters
     * most (the four cases above are). */
    @Test
    void theCeoCanApproveAndTheCorrectionLandsOnAttendance() {
        long requestId = submitAsAndReturnId(salesStaff);

        AttendanceCorrectionRequestDto approved = service.approve(requestId, null, ceo(ceoEmployee));

        assertThat(approved.status()).isEqualTo("APPROVED");
        Boolean manualOverride = jdbc.queryForObject("""
            SELECT is_manual_override FROM hr.attendance_daily
             WHERE employee_id = :employeeId AND work_date = :workDate
            """, Map.of("employeeId", salesStaff, "workDate", WORK_DATE), Boolean.class);
        assertThat(manualOverride).isTrue();
        Integer punchCount = jdbc.queryForObject("""
            SELECT count(*)::int FROM hr.attendance_punch
             WHERE employee_id = :employeeId AND work_date = :workDate AND punch_source = 'MANUAL'
            """, Map.of("employeeId", salesStaff, "workDate", WORK_DATE), Integer.class);
        assertThat(punchCount).isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------

    private long submitAsAndReturnId(long employeeId) {
        return submitAsAndReturnId(employeeId, WORK_DATE);
    }

    private long submitAsAndReturnId(long employeeId, LocalDate workDate) {
        SubmitAttendanceCorrectionRequest request = new SubmitAttendanceCorrectionRequest(
            workDate, AttendanceCorrectionType.CHECK_IN,
            workDate.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้วตอนเข้างาน");
        return service.submit(request, employee(employeeId)).id();
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, salesDivision, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(2L, "mgr@glr.co.th", "mgr", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, true);
    }

    private UserPrincipal ceo(long employeeId) {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", employeeId, true,
            LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }
}
