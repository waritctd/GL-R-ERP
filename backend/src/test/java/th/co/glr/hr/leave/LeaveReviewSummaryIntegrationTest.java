package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * GET /api/leave/review-summary (Phase A0b, refined after the frontend's phase A1 started consuming
 * it): real-Postgres coverage of {@link LeaveService#reviewSummary}'s two-field shape.
 *
 * <p>The case that motivated splitting a single count into {@code isReviewer}/{@code pendingCount}:
 * {@link #managerWithZeroPendingRequestsIsStillAReviewer} below. A test that only checks a manager
 * WITH pending work would pass whether {@code isReviewer} was derived correctly or was merely {@code
 * pendingCount > 0} in disguise -- proving nothing about the distinction the two fields exist to
 * make. This is the one that actually tells them apart.
 */
class LeaveReviewSummaryIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class));
    }

    @Test
    void managerWithZeroPendingRequestsIsStillAReviewer() {
        long manager = insertEmployee("SUM-MGR-EMPTY", null, null, true);
        // An active direct report exists, but files nothing -- the queue is genuinely empty.
        insertEmployee("SUM-RPT-EMPTY", null, manager, true);

        LeaveReviewSummaryDto summary = leaveService.reviewSummary(employee(manager));

        assertThat(summary.isReviewer())
            .as("a manager with an active direct report is a reviewer even with nothing pending")
            .isTrue();
        assertThat(summary.pendingCount()).isZero();
    }

    @Test
    void managerWithAPendingRequestSeesTheCorrectCount() {
        long manager = insertEmployee("SUM-MGR-PENDING", null, null, true);
        long report = insertEmployee("SUM-RPT-PENDING", null, manager, true);
        insertSubmittedLeaveRequest(report);

        LeaveReviewSummaryDto summary = leaveService.reviewSummary(employee(manager));

        assertThat(summary.isReviewer()).isTrue();
        assertThat(summary.pendingCount()).isEqualTo(1);
    }

    @Test
    void aPlainEmployeeWithNoDirectReportsIsNotAReviewer() {
        long employeeId = insertEmployee("SUM-PLAIN-001", null, null, true);

        LeaveReviewSummaryDto summary = leaveService.reviewSummary(employee(employeeId));

        assertThat(summary.isReviewer()).isFalse();
        assertThat(summary.pendingCount()).isZero();
    }

    @Test
    void aManagerOfAnInactiveReportOnlyIsNotAReviewer() {
        // reports_to_employee_id alone is not enough -- the report must be ACTIVE, mirroring
        // LeaveService#isDirectManager's own access.active() check.
        long manager = insertEmployee("SUM-MGR-INACTIVE", null, null, true);
        insertEmployee("SUM-RPT-INACTIVE", null, manager, false);

        LeaveReviewSummaryDto summary = leaveService.reviewSummary(employee(manager));

        assertThat(summary.isReviewer()).isFalse();
        assertThat(summary.pendingCount()).isZero();
    }

    @Test
    void hrIsAlwaysAReviewerRegardlessOfPending() {
        LeaveReviewSummaryDto summary = leaveService.reviewSummary(hr());

        assertThat(summary.isReviewer()).isTrue();
    }

    // --- helpers ------------------------------------------------------------

    private long insertEmployee(String code, Long departmentId, Long reportsToId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("departmentId", departmentId);
        params.put("reportsTo", reportsToId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th, department_id,
                 reports_to_employee_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :departmentId, :reportsTo, :active, DATE '2020-01-01')
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

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }
}
