package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * CEO leave-approval reach (2026-09-01), owner ruling: "make it so that ceo can approve everyone
 * leaves". Real-Postgres pin for {@code LeaveService#REVIEW_ALL_ROLES} gaining {@code "ceo"}
 * alongside {@code "hr"}.
 *
 * <p>Mockito cannot reach this -- see {@code LeaveServiceTest#ceoCanApproveLeave} for the
 * decision-level twin (CLAUDE.md's "Permission changes must ship evidence" requires both: a unit
 * test proves the right branch was chosen, this class proves the decision survives into a real
 * {@code UPDATE ... WHERE status = 'SUBMITTED'} against real Postgres, including the
 * {@code reviewed_by_id} foreign key to {@code hr.employee} that a mocked repository would never
 * exercise). Runs the real {@link LeaveService} against a real {@link LeaveRepository}.
 */
class LeaveCeoApprovalIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class));
    }

    /** The headline case: a ceo actor, with no reports_to relationship to the filer at all. */
    @Test
    void ceoApprovesAnotherEmployeesSubmittedLeaveRequest() {
        long ceoEmployeeId = insertEmployee("CEOAPR-CEO1");
        long staff = insertEmployee("CEOAPR-STF1");
        long requestId = insertSubmittedLeaveRequest(staff);

        LeaveRequestDto approved = leaveService.approve(
            requestId, new ReviewLeaveRequest("ok"), ceo(ceoEmployeeId));

        assertThat(approved.status()).isEqualTo("APPROVED");
        // Read back independently of the returned DTO -- proves the real UPDATE landed in
        // Postgres (and satisfied the reviewed_by_id FK to hr.employee), not just that the
        // in-memory return value looks right.
        assertThat(persistedStatus(requestId)).isEqualTo("APPROVED");
    }

    /**
     * Wrong-way-round, the case that actually distinguishes "reach widened to ceo" from "reach
     * widened to everyone": a sales actor with no reports_to relationship to the filer must still
     * 403, and the row must stay SUBMITTED.
     */
    @Test
    void nonReviewerRoleStillCannotApproveSomeoneElsesLeave() {
        long salesActor = insertEmployee("CEOAPR-SLS1");
        long staff = insertEmployee("CEOAPR-STF2");
        long requestId = insertSubmittedLeaveRequest(staff);

        assertThatThrownBy(() -> leaveService.approve(
                requestId, new ReviewLeaveRequest(null), sales(salesActor)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(persistedStatus(requestId)).isEqualTo("SUBMITTED");
    }

    /** Regression control: hr's pre-existing reach must be unaffected by this change. */
    @Test
    void hrStillApprovesAfterTheChange() {
        long hrActor = insertEmployee("CEOAPR-HR1");
        long staff = insertEmployee("CEOAPR-STF3");
        long requestId = insertSubmittedLeaveRequest(staff);

        LeaveRequestDto approved = leaveService.approve(
            requestId, new ReviewLeaveRequest("ok"), hr(hrActor));

        assertThat(approved.status()).isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    private String persistedStatus(long requestId) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.leave_request WHERE leave_request_id = :id",
            Map.of("id", requestId), String.class);
    }

    private long insertEmployee(String code) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, TRUE, DATE '2020-01-01')
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertSubmittedLeaveRequest(long employeeId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, 'VACATION', DATE '2026-08-10', DATE '2026-08-10', 1.00, 2026,
                    'Integration test leave', 6.00, 5.00)
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
    }

    private UserPrincipal ceo(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "CEO", "ceo",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "HR", "hr",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal sales(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Sales", "sales",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
