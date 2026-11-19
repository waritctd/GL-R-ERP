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

    // --- helpers ------------------------------------------------------------

    private String persistedStatus(long requestId) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", requestId), String.class);
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
