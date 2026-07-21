package th.co.glr.hr.overtime;

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
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Authorization evidence for the removal of the retroactive-submission gate.
 *
 * <p>Advance notice was removed on CEO instruction, and with it the rule that only a manager could
 * file overtime for a past date. That deletion <em>relaxes</em> authorization, so per CLAUDE.md it
 * needs proof through the real service and the real SQL rather than a claim — a mocked repository
 * would happily agree with whatever the service believes.
 *
 * <p>What must still hold: filing on behalf of someone else is governed by
 * {@code resolveTargetEmployee}, which was deliberately left untouched. Backdating must not have
 * become a side door into another employee's overtime.
 *
 * <p>Cases are written the wrong way round — can this caller reach what they should not.
 */
class OvertimeRetroactiveScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private OvertimeService service;

    private long salesDivision;
    private long factoryDivision;
    private long salesManager;
    private long salesStaff;
    private long factoryStaff;

    @BeforeEach
    void wireRealCollaborators() {
        // Audit, notification and attendance re-derivation are downstream of the guard; stubbing
        // them keeps this test about who may write whose rows. The repository is real.
        service = new OvertimeService(
            new OvertimeRepository(jdbc),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(),
            mock(AttendanceDailyService.class));

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        salesManager = insertEmployee("M001", salesDivision, null);
        salesStaff = insertEmployee("S001", salesDivision, salesManager);
        factoryStaff = insertEmployee("F001", factoryDivision, null);
    }

    // --- what the change deliberately allows --------------------------------

    @Test
    void anEmployeeCanNowSelfFileRetroactiveOvertime() {
        OvertimeRequestDto created = service.submit(backdated(null, 3, LATE_REASON), employee(salesStaff));

        assertThat(created.employeeId()).isEqualTo(salesStaff);
        assertThat(storedCountFor(salesStaff)).isEqualTo(1);
    }

    // --- what must still be refused -----------------------------------------

    /**
     * The guard this branch had to leave standing. Backdating is now open to everyone, so if
     * {@code resolveTargetEmployee} ever stopped checking, an employee could file overtime onto a
     * colleague's record and it would be paid to them.
     */
    @Test
    void anEmployeeCannotSelfFileRetroactiveOvertimeForSomeoneTheyDoNotManage() {
        assertThatThrownBy(() -> service.submit(backdated(factoryStaff, 3, LATE_REASON), employee(salesStaff)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(storedCountFor(factoryStaff)).isZero();
    }

    @Test
    void aManagerCannotFileRetroactiveOvertimeForAnotherDivision() {
        assertThatThrownBy(() ->
            service.submit(backdated(factoryStaff, 3, LATE_REASON), divisionManager(salesManager, salesDivision)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(storedCountFor(factoryStaff)).isZero();
    }

    /**
     * HR has {@code VIEW_ALL_ROLES} read access but no write path, and never gained one here.
     * Issue #199 was exactly this shape for approval — HR reading as more powerful than it is.
     */
    @Test
    void hrCannotFileRetroactiveOvertimeOnBehalfOfAnArbitraryEmployee() {
        assertThatThrownBy(() -> service.submit(backdated(factoryStaff, 3, LATE_REASON), hr()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(storedCountFor(factoryStaff)).isZero();
    }

    /**
     * The payroll lock, proved against a real {@code hr.payroll_period} row rather than a stub:
     * overtime that could never be paid must not be accepted in the first place.
     */
    @Test
    void retroactiveOvertimeIsRefusedOnceItsPayrollMonthHasBeenProcessed() {
        LocalDate workDate = bangkokToday().minusDays(3);
        insertProcessedPeriod(workDate.withDayOfMonth(1));

        assertThatThrownBy(() -> service.submit(backdated(null, 3, LATE_REASON), employee(salesStaff)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.CONFLICT);

        assertThat(storedCountFor(salesStaff)).isZero();
    }

    // --- fixtures -----------------------------------------------------------

    private static final String LATE_REASON = "Urgent customer escalation, filed after the shift ended";

    private SubmitOvertimeRequest backdated(Long employeeId, int daysAgo, String reason) {
        LocalDate workDate = bangkokToday().minusDays(daysAgo);
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(employeeId, workDate, startAt, startAt.plusHours(2), "WORKDAY", reason);
    }

    /** Match OvertimeService.BUSINESS_ZONE; the JVM default zone flakes in UTC CI (see the unit test). */
    private LocalDate bangkokToday() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
    }

    private int storedCountFor(long employeeId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.overtime_request WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, "emp@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal divisionManager(long employeeId, long divisionId) {
        return new UserPrincipal(employeeId, "mgr@glr.co.th", "Manager", "employee",
            employeeId, true, LocalDate.now(), false, divisionId, true);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(salesManager, "hr@glr.co.th", "HR", "hr",
            salesManager, true, LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, Long reportsTo) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsTo);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertProcessedPeriod(LocalDate payrollMonth) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                :payrollMonth, :payrollMonth,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                'PROCESSED'
            )
            """, Map.of("payrollMonth", payrollMonth));
    }
}
