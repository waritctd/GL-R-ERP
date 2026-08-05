package th.co.glr.hr.attendance.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms attendance-correction authorization against the real service and the real SQL.
 *
 * <p>This feature adds a brand-new role gate (CEO-only approval, no manager stage at all — see
 * {@link AttendanceCorrectionService} class javadoc). Per CLAUDE.md, that requires a real-DB
 * integration test through the real service, not a claim inferred from {@code mockApi.js}. Every
 * case here (besides the CEO-approves positive case, kept deliberately minimal — see its own
 * javadoc) asks the question the wrong way round: can this caller reach or act on data they should
 * not, rather than confirming they can reach their own.
 *
 * <p>Modelled directly on {@code AttendanceScopeIntegrationTest}'s structure and idioms.
 */
class AttendanceCorrectionScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);
    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 15);

    private AttendanceCorrectionService service;
    private AttendanceCorrectionRepository repository;

    private long salesDivision;
    private long salesManager;
    private long salesStaff;
    private long anotherStaff;
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
        // Audit/notification are downstream of the guard, same convention
        // OvertimeRetroactiveScopeIntegrationTest uses — stubbing them keeps this test about who may
        // act on whose rows. The repository AND the attendance-write path (AttendanceDailyService)
        // are real, so the CEO-approve positive case genuinely exercises the punch insert +
        // is_manual_override write, not just a status flip.
        service = new AttendanceCorrectionService(
            repository,
            dailyService,
            mock(AuditService.class),
            mock(NotificationService.class));

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        salesManager = insertEmployee("M001", salesDivision);
        salesStaff = insertEmployee("S001", salesDivision);
        anotherStaff = insertEmployee("S002", salesDivision);
        ceoEmployee = insertEmployee("C001", salesDivision);
    }

    // --- submit is self-only --------------------------------------------------------------------

    @Test
    void anEmployeeSubmitsForThemselvesRegardlessOfSession() {
        AttendanceCorrectionRequestDto created = service.submit(
            checkInRequest(), employee(salesStaff));

        assertThat(created.employeeId()).isEqualTo(salesStaff);
        assertThat(created.status()).isEqualTo("SUBMITTED");
    }

    // --- approve: division manager is not the CEO -----------------------------------------------

    /**
     * The case that matters most: this feature has NO manager stage at all, so a division manager —
     * who WOULD be able to approve overtime for their own division — must still get 403 here. If
     * {@code requireCeo} ever regressed to also accept a manager, this is the test that must catch
     * it (see the mutation-check in this class's PR description).
     */
    @Test
    void aDivisionManagerCannotApproveAnotherEmployeesCorrection() {
        long requestId = submitAsAndReturnId(salesStaff);

        assertThatThrownBy(() -> service.approve(requestId, null, manager(salesManager, salesDivision)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // --- approve: self-approval is blocked, even for the requester -------------------------------

    /**
     * A plain employee attempting to approve their OWN request must also 403 — self-approval is not
     * a carve-out {@code requireCeo} makes for the requester.
     */
    @Test
    void anEmployeeCannotApproveTheirOwnCorrectionRequest() {
        long requestId = submitAsAndReturnId(salesStaff);

        assertThatThrownBy(() -> service.approve(requestId, null, employee(salesStaff)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // --- reject: same CEO-only gate ---------------------------------------------------------------

    @Test
    void aDivisionManagerCannotRejectAnotherEmployeesCorrection() {
        long requestId = submitAsAndReturnId(salesStaff);

        assertThatThrownBy(() -> service.reject(requestId, null, manager(salesManager, salesDivision)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(repository.findById(requestId).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    // --- list: an employee cannot list someone else's requests -------------------------------------

    @Test
    void anEmployeeCannotListAnotherEmployeesRequests() {
        submitAsAndReturnId(anotherStaff);

        // Same convention as AttendanceScopeIntegrationTest.anEmployeeAskingForSomeoneElseIsRejected:
        // asking for a specific out-of-scope employee's rows is refused outright (403), rather than
        // silently returning zero rows the caller could confuse with "no requests exist". There is
        // no GET /{id} on this controller -- list(employeeId=...) IS the by-employee read surface,
        // hence this exercises service.list, not a separate by-id lookup (review
        // #attendance-correction-request: the prior test name implied one existed).
        assertThatThrownBy(() -> service.list(employee(salesStaff), anotherStaff, null))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anEmployeesOwnRequestListNeverIncludesSomeoneElses() {
        submitAsAndReturnId(anotherStaff);
        submitAsAndReturnId(salesStaff, LocalDate.of(2026, 7, 16));

        var rows = service.list(employee(salesStaff), null, null);

        assertThat(rows).extracting(AttendanceCorrectionRequestDto::employeeId).containsOnly(salesStaff);
    }

    // --- approve: the CEO succeeding is the contrast case, kept minimal --------------------------

    /**
     * Positive case for contrast only — per CLAUDE.md's wrong-way-round rule, this is NOT the test
     * that matters most (the four cases above are). Included so the suite also proves the CEO path
     * is not accidentally broken by the same guard that blocks everyone else, and because the real
     * (non-mocked) {@link AttendanceDailyService} wiring lets this exercise the actual punch-insert +
     * {@code is_manual_override} write, not just a status flip.
     */
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

    private SubmitAttendanceCorrectionRequest checkInRequest() {
        return new SubmitAttendanceCorrectionRequest(
            WORK_DATE, AttendanceCorrectionType.CHECK_IN,
            WORK_DATE.atTime(8, 20).atOffset(BANGKOK_OFFSET), null, "ลืมสแกนนิ้วตอนเข้างาน");
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
