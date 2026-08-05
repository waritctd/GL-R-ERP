package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres regression coverage for {@link LeaveRepository#cancel}, found via a live
 * click-through against a real backend (not mocks -- mocks never touch real SQL, and every
 * PRE-EXISTING real-Postgres test that calls {@code cancel(...)} does so as {@link #hr()}, never
 * as the owning employee -- see this class's own {@link #employeeCancellingTheirOwnRequestSucceeds}
 * for exactly the gap that let this ship).
 *
 * <p><b>The bug:</b> {@code cancel}'s SQL binds {@code :reviewedById} and {@code :reviewerNote}
 * ONLY inside {@code COALESCE(...)}/{@code CASE WHEN ... IS NULL} -- never in a context with an
 * inferable column type on their own, unlike {@link LeaveRepository#approve}/{@link
 * LeaveRepository#reject}, where {@code :reviewedById} binds directly into a typed assignment.
 * {@link LeaveService#cancel} passes {@code reviewedById = null} for the ONE caller who can
 * legitimately do that -- an employee cancelling their own SUBMITTED request (not a reviewer) --
 * and Postgres' extended query protocol cannot infer a type from that shape when the bound value is
 * also null: {@code ERROR: could not determine data type of parameter $2}. Fixed by giving both
 * ambiguous binds explicit {@code java.sql.Types} hints.
 *
 * <p>Asserts BOTH sides on the same shape of fixture -- self-cancel (reviewedById == null) AND
 * reviewer-cancel (reviewedById set) -- so a regression in either path fails this suite, not just
 * the one that shipped broken.
 */
class LeaveSelfCancelIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(th.co.glr.hr.attachment.FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(java.time.Instant.parse("2026-08-01T02:00:00Z"), ZoneId.of("Asia/Bangkok")));
    }

    @Test
    void employeeCancellingTheirOwnRequestSucceeds() {
        long employeeId = insertEmployee("CANC-SELF");
        long requestId = insertSubmittedLeaveRequest(employeeId);

        // Before the fix: this threw (DataAccessException wrapping PSQLException "could not
        // determine data type of parameter $2") for exactly this caller shape -- an employee
        // cancelling their own request, so LeaveService#cancel passes reviewedById = null.
        assertThatCode(() -> leaveService.cancel(requestId, new ReviewLeaveRequest(null), employee(employeeId)))
            .doesNotThrowAnyException();

        var row = jdbc.queryForMap(
            "SELECT status, reviewed_by_id, reviewed_at, cancelled_at FROM hr.leave_request WHERE leave_request_id = :id",
            new MapSqlParameterSource("id", requestId));
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        // The employee is not a reviewer -- reviewed_by_id/reviewed_at must stay untouched (COALESCE
        // preserving the pre-existing NULL), not attributed to the cancelling employee.
        assertThat(row.get("reviewed_by_id")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(row.get("cancelled_at")).isNotNull();
    }

    @Test
    void reviewerCancellingSomeoneElsesRequestSucceedsAndRecordsTheReviewer() {
        long employeeId = insertEmployee("CANC-REVW");
        long requestId = insertSubmittedLeaveRequest(employeeId);

        assertThatCode(() -> leaveService.cancel(requestId, new ReviewLeaveRequest("no longer needed"), hr()))
            .doesNotThrowAnyException();

        var row = jdbc.queryForMap(
            "SELECT status, reviewed_by_id, reviewed_at, reviewer_note, cancelled_at FROM hr.leave_request WHERE leave_request_id = :id",
            new MapSqlParameterSource("id", requestId));
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) row.get("reviewed_by_id")).longValue()).isEqualTo(1L);
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(row.get("reviewer_note")).isEqualTo("no longer needed");
        assertThat(row.get("cancelled_at")).isNotNull();
    }

    private long insertSubmittedLeaveRequest(long employeeId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, 'SICK', DATE '2026-08-10', DATE '2026-08-10', 1.00, 2026,
                    'Self-cancel regression test', 30.00, 29.00)
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
    }

    private long insertEmployee(String code) {
        long departmentId = jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :code, TRUE)
            RETURNING department_id
            """, new MapSqlParameterSource("code", code), Long.class);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th, department_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :departmentId, TRUE, DATE '2020-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("departmentId", departmentId),
            Long.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }
}
