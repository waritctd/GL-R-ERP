package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave HR-submit gate (2026-08-03): real-Postgres coverage of the owner ruling "HR and บริหาร
 * oversee leave but do not request it for themselves." Verified role mapping (frontend/src/app/
 * roles.js): `ผู้บริหาร` -> role {@code hr}, `ผู้บริหารระดับสูง` -> role {@code ceo} -- there is no
 * separate "บริหาร" role, so the gate is on {@code hr}/{@code ceo}, matching the existing {@code
 * LeaveService.VIEW_ALL_ROLES} bucket and the "CEO/HR are queue-side, they never file their own
 * requests" precedent already established for OT/welfare approval routing in this codebase.
 *
 * <p>Mockito cannot reach this: the gate lives in {@link LeaveService#resolveTargetEmployee}, which
 * is exercised only through the real {@link LeaveService#submit} entry point end-to-end against a
 * real {@link LeaveRepository} -- a mocked repository would prove nothing about whether the actual
 * decision (role bucket + self-vs-other target resolution) is wired correctly into the request path.
 *
 * <p>Written WRONG-WAY-ROUND, per CLAUDE.md's rule for authorization changes: every 403 case here
 * asserts what the caller CANNOT reach (submitting for themselves), not merely that the intended
 * caller can reach what they should. The HR-for-a-subordinate / CEO-for-a-subordinate / plain-
 * employee-for-self cases are regression guards -- proof this gate did NOT become a role-alone
 * block, which the brief explicitly forbids ("Do NOT gate on role alone").
 *
 * <p>Every request here uses {@code LEAVE_WITHOUT_PAY} (V116: zero advance notice, zero min-service,
 * no consecutive-day cap, not once-per-employment, no attachment requirement) so the only gate any
 * case can possibly trip is the one under test -- a 403 that showed up instead because of an
 * unrelated leave-type rule would be a false positive for this class's own point.
 */
class LeaveSubmitSelfRoleGateIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-08-05 09:00 Asia/Bangkok -- every leave date below is a later-in-the-week
    // working day (Mon-Fri, the legacy no-schedule LeaveRepository(jdbc) default), comfortably
    // clear of LEAVE_WITHOUT_PAY's own zero-notice requirement (irrelevant here regardless).
    private static final Instant FIXED_NOW = Instant.parse("2026-08-05T02:00:00Z");
    private static final LocalDate LEAVE_DATE = LocalDate.of(2026, 8, 6); // Thursday

    private LeaveService leaveService;

    private long hrEmployeeId;
    private long ceoEmployeeId;
    private long plainEmployeeId;
    private long hrManagedSubordinateId;
    private long ceoDirectReportId;

    @BeforeEach
    void wireRealCollaboratorsAndSeed() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));

        hrEmployeeId = insertEmployee("GATE-HR", null, true);
        ceoEmployeeId = insertEmployee("GATE-CEO", null, true);
        plainEmployeeId = insertEmployee("GATE-EMP", null, true);
        // Not the HR actor's own report -- proves the HR case below is HR's blanket
        // REVIEW_ALL_ROLES reach ("paper form on someone else's behalf"), not a manager-FK match.
        hrManagedSubordinateId = insertEmployee("GATE-HR-SUB", null, true);
        // Reports directly to the CEO actor -- CEO is NOT in REVIEW_ALL_ROLES (that set is {hr}
        // only), so CEO-for-a-subordinate can only succeed via the isDirectManager relationship.
        ceoDirectReportId = insertEmployee("GATE-CEO-SUB", ceoEmployeeId, true);
    }

    // --- wrong-way-round: what HR/CEO cannot reach ---------------------------------------------

    @Test
    void hrSubmittingForThemselvesIsForbidden() {
        assertSelfSubmitForbidden(hr(hrEmployeeId));
    }

    @Test
    void ceoSubmittingForThemselvesIsForbidden() {
        assertSelfSubmitForbidden(ceo(ceoEmployeeId));
    }

    // --- regression guards: NOT the point of this class, but must not silently break -----------

    @Test
    void hrSubmittingForASubordinateStillSucceeds() {
        LeaveRequestDto result = leaveService.submit(
            submitRequest(hrManagedSubordinateId), hr(hrEmployeeId));

        assertThat(result.employeeId()).isEqualTo(hrManagedSubordinateId);
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void ceoSubmittingForADirectReportStillSucceeds() {
        // CEO can submit for someone else ONLY via the manager-of-record relationship (CEO is not
        // in REVIEW_ALL_ROLES) -- this is that path, seeded above via ceoDirectReportId.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(ceoDirectReportId), ceo(ceoEmployeeId));

        assertThat(result.employeeId()).isEqualTo(ceoDirectReportId);
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void aPlainEmployeeSubmittingForThemselvesStillSucceeds() {
        LeaveRequestDto result = leaveService.submit(
            submitRequest(null), employee(plainEmployeeId));

        assertThat(result.employeeId()).isEqualTo(plainEmployeeId);
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    private void assertSelfSubmitForbidden(UserPrincipal actor) {
        assertThatThrownBy(() -> leaveService.submit(submitRequest(null), actor))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private SubmitLeaveRequest submitRequest(Long employeeId) {
        return new SubmitLeaveRequest(employeeId, "LEAVE_WITHOUT_PAY", LEAVE_DATE, LEAVE_DATE,
            "Integration test leave gate");
    }

    private long insertEmployee(String code, Long reportsToId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("reportsTo", reportsToId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th, reports_to_employee_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :reportsTo, :active, DATE '2020-01-01')
            RETURNING employee_id
            """, params, Long.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "HR", "hr",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal ceo(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "CEO", "ceo",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
