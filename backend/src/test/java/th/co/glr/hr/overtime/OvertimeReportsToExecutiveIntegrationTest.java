package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
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

/** CEO-approval-reach follow-on (2026-09-01), real-Postgres pin: an employee whose reports_to_employee_id
 * points at an active executive has NO manager stage for overtime, even when their own ฝ่าย has a perfectly
 * reachable ผู้จัดการ. */
class OvertimeReportsToExecutiveIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final WorkSchedule ALWAYS_WORKDAY_SCHEDULE = new WorkSchedule(
        ZoneId.of("Asia/Bangkok"), LocalTime.of(8, 30), LocalTime.of(17, 30), 15,
        EnumSet.allOf(DayOfWeek.class));

    private OvertimeService overtimeService;
    private ManagerApproverRepository managerApproverRepository;
    private long division;
    private long divisionManager;
    private long activeExecutive;

    @BeforeEach
    void wireRealCollaborators() {
        managerApproverRepository = new ManagerApproverRepository(jdbc);
        overtimeService = new OvertimeService(
            new OvertimeRepository(jdbc),
            managerApproverRepository,
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(),
            mock(AttendanceDailyService.class),
            new DbHolidayCalendar(jdbc),
            (employeeId, divisionId, departmentId, workDate) -> ALWAYS_WORKDAY_SCHEDULE,
            new CeoApproverRepository(jdbc));

        division = insertDivision("RTE", "ฝ่ายทดสอบ");
        divisionManager = insertEmployee("RTE-MGR", division, "ผู้จัดการฝ่ายทดสอบ", true, null);
        activeExecutive = insertEmployee("RTE-EXEC", null, "กรรมการ", true, null);
    }

    @Test
    void employeeReportingToAnActiveExecutiveHasNoManagerStage() {
        long staff = insertEmployee("RTE-STF1", division, null, true, activeExecutive);

        assertThat(managerApproverRepository.hasManagerApprover(staff)).isFalse();
    }

    /** Regression control: same division, but reports_to is null — the pre-existing behaviour. */
    @Test
    void employeeNotReportingToAnyoneStillHasTheOrdinaryManagerStage() {
        long staff = insertEmployee("RTE-STF2", division, null, true, null);

        assertThat(managerApproverRepository.hasManagerApprover(staff)).isTrue();
    }

    /** Negative control for boss.is_active = TRUE. */
    @Test
    void employeeReportingToAnInactiveExecutiveStillHasTheOrdinaryManagerStage() {
        long inactiveExecutive = insertEmployee("RTE-EXGON", null, "กรรมการ", false, null);
        long staff = insertEmployee("RTE-STF3", division, null, true, inactiveExecutive);

        assertThat(managerApproverRepository.hasManagerApprover(staff)).isTrue();
    }

    /** The headline positive case: submit -> approve() dispatches straight to ceoDirectApprove (hasManagerStage is
     * false) -> a ceo actor completes it in one step. Read back independently of the returned DTO, so this proves
     * the real UPDATE landed in Postgres. */
    @Test
    void ceoCompletesTheRequestInOneStepForAnEmployeeReportingToAnExecutive() {
        long staff = insertEmployee("RTE-STF4", division, null, true, activeExecutive);
        long ceoActor = insertEmployee("RTE-CEO1", null, "กรรมการ", true, null);
        long requestId = submitFutureOvertime(staff);

        OvertimeRequestDto approved = overtimeService.approve(
            requestId, new ApproveOvertimeRequest("ok", null), ceo(ceoActor));

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(persistedStatus(requestId)).isEqualTo("APPROVED");
    }

    /** Wrong-way-round #1: the division ผู้จัดการ — who would ordinarily manage this employee — is refused. Refused
     * by requireCeoForManagerlessRequest (approve()'s own hasManagerStage dispatch), not requireManager — see this
     * class's own Javadoc. */
    @Test
    void divisionManagerCannotApproveTheRequestThroughTheRealDispatch() {
        long staff = insertEmployee("RTE-STF5", division, null, true, activeExecutive);
        long requestId = submitFutureOvertime(staff);

        assertThatThrownBy(() -> overtimeService.approve(
                requestId, new ApproveOvertimeRequest("ok", null), manager(divisionManager, division)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(persistedStatus(requestId)).isEqualTo("SUBMITTED");
    }

    /** Wrong-way-round #2: {@code cancel} does not go through the {@code hasManagerStage} dispatch. */
    @Test
    void divisionManagerCannotCancelTheRequest() {
        long staff = insertEmployee("RTE-STF6", division, null, true, activeExecutive);
        long requestId = submitFutureOvertime(staff);

        assertThatThrownBy(() -> overtimeService.cancel(
                requestId, new ReviewOvertimeRequest(null), manager(divisionManager, division)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(persistedStatus(requestId)).isEqualTo("SUBMITTED");
    }

    /** Wrong-way-round #3: list visibility via {@code canAccessEmployee}. */
    @Test
    void divisionManagerCannotListTheEmployeesOvertimeByRequestedEmployeeId() {
        long staff = insertEmployee("RTE-STF7", division, null, true, activeExecutive);
        submitFutureOvertime(staff);

        assertThatThrownBy(() -> overtimeService.list(
                manager(divisionManager, division),
                LocalDate.now(), LocalDate.now().plusDays(10), staff, null))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Wrong-way-round #4: submit-on-behalf via {@code resolveTargetEmployee}. */
    @Test
    void divisionManagerCannotSubmitOnBehalfOfTheEmployee() {
        long staff = insertEmployee("RTE-STF8", division, null, true, activeExecutive);
        LocalDate workDate = LocalDate.now().plusDays(4);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        OffsetDateTime endAt = workDate.atTime(20, 0).atOffset(ZoneOffset.ofHours(7));

        assertThatThrownBy(() -> overtimeService.submit(
                new SubmitOvertimeRequest(staff, workDate, startAt, endAt, null, "ทดสอบ"),
                manager(divisionManager, division)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Regression control: the division manager keeps full reach over a division peer who does NOT report to an
     * executive — proves this change narrows only the bypassed employee, not the division manager's ordinary
     * reach. */
    @Test
    void divisionManagerStillManagesAPeerNotReportingToAnExecutive() {
        long staff = insertEmployee("RTE-STF9", division, null, true, null);
        long requestId = submitFutureOvertime(staff);

        OvertimeRequestDto managerApproved = overtimeService.approve(
            requestId, new ApproveOvertimeRequest("ok", null), manager(divisionManager, division));

        assertThat(managerApproved.status()).isEqualTo("MANAGER_APPROVED");
    }

    /** the exec exclusion reached OvertimeService.managesEmployee but not OvertimeRepository#findRequests' own SQL
     * scope -- so the bypassed employee's request still leaked into the division manager's UNFILTERED list, a row
     * with no buttons the manager could press. */
    @Test
    void divisionManagersUnfilteredListOmitsTheBypassedEmployeesRequest() {
        long bypassedStaff = insertEmployee("RTE-STF10", division, null, true, activeExecutive);
        long ordinaryPeer = insertEmployee("RTE-STF11", division, null, true, null);
        long bypassedRequestId = submitFutureOvertime(bypassedStaff);
        long ordinaryRequestId = submitFutureOvertime(ordinaryPeer);

        List<OvertimeRequestDto> requests = overtimeService.list(
            manager(divisionManager, division), LocalDate.now(), LocalDate.now().plusDays(10), null, null);

        assertThat(requests).extracting(OvertimeRequestDto::id)
            .contains(ordinaryRequestId)
            .doesNotContain(bypassedRequestId);
    }

    /** OvertimeRepository#findEmployeeOptions' own directReport computation did not exclude an exec-reporting
     * employee either, so the division manager's submit-on-behalf picker offered someone whose submit call would
     * then 403 -- verbatim the failure that method's own comment says it exists to prevent. */
    @Test
    void submitOnBehalfPickerDoesNotOfferTheBypassedEmployeeAsADirectReport() {
        long bypassedStaff = insertEmployee("RTE-STF12", division, null, true, activeExecutive);
        long ordinaryPeer = insertEmployee("RTE-STF13", division, null, true, null);

        List<OvertimeEmployeeOption> options =
            overtimeService.employeeOptions(manager(divisionManager, division));

        OvertimeEmployeeOption bypassedOption = options.stream()
            .filter(option -> option.employeeId() == bypassedStaff)
            .findFirst()
            .orElseThrow();
        OvertimeEmployeeOption ordinaryOption = options.stream()
            .filter(option -> option.employeeId() == ordinaryPeer)
            .findFirst()
            .orElseThrow();
        assertThat(bypassedOption.directReport()).isFalse();
        assertThat(ordinaryOption.directReport()).isTrue();
    }

    /** C (owner ruling, 2026-09-01): "whoever approves it can also cancel it". */
    @Test
    void ceoCancelsAnApprovedRequestForAnEmployeeReportingToAnExecutive() {
        long staff = insertEmployee("RTE-STF14", division, null, true, activeExecutive);
        long ceoActor = insertEmployee("RTE-CEO2", null, "กรรมการ", true, null);
        long requestId = submitFutureOvertime(staff);
        overtimeService.approve(requestId, new ApproveOvertimeRequest("ok", null), ceo(ceoActor));

        OvertimeRequestDto cancelled = overtimeService.cancel(
            requestId, new ReviewOvertimeRequest("no longer needed"), ceo(ceoActor));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(persistedStatus(requestId)).isEqualTo("CANCELLED");
        assertThat(persistedReviewedBy(requestId)).isEqualTo(ceoActor);
    }

    /** Wrong-way-round #1 for the ruling above: the division ผู้จัดการ still cannot cancel, even once APPROVED. */
    @Test
    void divisionManagerStillCannotCancelTheApprovedRequest() {
        long staff = insertEmployee("RTE-STF15", division, null, true, activeExecutive);
        long ceoActor = insertEmployee("RTE-CEO3", null, "กรรมการ", true, null);
        long requestId = submitFutureOvertime(staff);
        overtimeService.approve(requestId, new ApproveOvertimeRequest("ok", null), ceo(ceoActor));

        assertThatThrownBy(() -> overtimeService.cancel(
                requestId, new ReviewOvertimeRequest(null), manager(divisionManager, division)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(persistedStatus(requestId)).isEqualTo("APPROVED");
    }

    /** Wrong-way-round #2: a ceo actor may NOT use this ruling to reach an employee who DOES have a manager stage
     * -- the new reach is conditioned on hasManagerStage being false, not on role alone, so it must not become a
     * second, parallel CEO cancel path for ordinary requests. */
    @Test
    void ceoCannotCancelAnApprovedRequestForAnEmployeeWhoHasAManagerStage() {
        long staff = insertEmployee("RTE-STF16", division, null, true, null);
        long ceoActor = insertEmployee("RTE-CEO4", null, "กรรมการ", true, null);
        long requestId = submitFutureOvertime(staff);
        overtimeService.approve(
            requestId, new ApproveOvertimeRequest("ok", null), manager(divisionManager, division));
        overtimeService.approve(requestId, new ApproveOvertimeRequest("ok", null), ceo(ceoActor));

        assertThatThrownBy(() -> overtimeService.cancel(
                requestId, new ReviewOvertimeRequest(null), ceo(ceoActor)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(persistedStatus(requestId)).isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    private String persistedStatus(long requestId) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", requestId), String.class);
    }

    private Long persistedReviewedBy(long requestId) {
        return jdbc.queryForObject(
            "SELECT reviewed_by_id FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private long submitFutureOvertime(long employeeId) {
        LocalDate workDate = LocalDate.now().plusDays(4);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        OffsetDateTime endAt = workDate.atTime(20, 0).atOffset(ZoneOffset.ofHours(7));
        OvertimeRequestDto created = overtimeService.submit(
            new SubmitOvertimeRequest(null, workDate, startAt, endAt, null, "ทดสอบ"),
            employee(employeeId));
        return created.id();
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(
            String code, Long divisionId, String positionNameTh, boolean active, Long reportsTo) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsTo);
        params.put("active", active);
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, position_id, reports_to_employee_id, hire_date,
                                     is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :positionId, :reportsTo,
                    DATE '2020-01-01', :active)
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Manager", "employee",
            employeeId, true, LocalDate.now(), false, divisionId, true);
    }

    private UserPrincipal ceo(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "CEO", "ceo",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
