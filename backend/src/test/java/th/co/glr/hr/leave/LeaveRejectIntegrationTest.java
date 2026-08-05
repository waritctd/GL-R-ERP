package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave requires approval (2026-08-05): real-Postgres coverage of {@link LeaveService#reject} /
 * {@link LeaveRepository#reject} -- the OTHER limb of the approve/reject workflow that went live
 * with this branch. Before this class, {@code grep -rn "leaveService.reject(" backend/src/test}
 * returned nothing: {@link LeaveService#approve} has full end-to-end coverage (e.g.
 * {@link LeaveUnpaidDeductionIntegrationTest}), but {@code reject} -- reachable in production from
 * {@code frontend/src/features/leave/ReviewQueueTab.jsx} -- had none. Mockito cannot stand in for
 * this: whether rejecting actually releases the quota and keeps the request out of payroll's view
 * depends on real SQL ({@link LeaveRepository#sumUsedDays}'s status filter,
 * {@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}'s {@code WHERE status = 'APPROVED'}),
 * not on a mocked repository that would happily "pass" regardless of what the query actually says.
 */
class LeaveRejectIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Instant FIXED_NOW = Instant.parse("2026-08-01T02:00:00Z");

    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    /**
     * The three things F3 asks to be pinned on one fixture: (1) status/reviewer note land correctly,
     * (2) quota is released -- verified by reading {@link LeaveRepository#sumUsedDays}, the actual
     * mechanism {@code LeaveService#balanceFor}/{@code #remainingDays} rely on to compute a live
     * "remaining" figure (REJECTED is deliberately absent from {@code ACTIVE_QUOTA_STATUSES}, so a
     * rejected request simply stops being summed -- there is no separate "release" column to assert
     * against), and (3) a rejected request contributes nothing to
     * {@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth}, which was never in doubt for
     * REJECTED (paidDays/unpaidDays are 0 for it, per {@link LeaveRequestDto}'s own Javadoc) but had
     * no real-Postgres proof until now.
     */
    @Test
    void rejectingASubmittedRequestSetsStatusAndReviewerNoteAndReleasesQuota() {
        long employeeId = insertEmployee("REJECT-001");

        // Mon 2026-08-10 .. Tue 2026-08-11: 2 working days, well within the 30-day SICK quota (no
        // certificate needed -- within the no-certificate monthly tolerance).
        LeaveRequestDto submitted = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-10", "2026-08-11"),
            employee(employeeId));
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.paidDays()).isEqualByComparingTo("2.00");

        // Quota is consumed the moment a rule-passing request lands SUBMITTED -- the same
        // ACTIVE_QUOTA_STATUSES (SUBMITTED, APPROVED) set LeaveService#balanceFor's `pending`/
        // `remainingDays` read.
        assertThat(leaveRepository.sumUsedDays(employeeId, "SICK", 2026, Set.of(LeaveStatus.SUBMITTED, LeaveStatus.APPROVED)))
            .as("a SUBMITTED request must already count against the quota")
            .isEqualByComparingTo("2.00");

        LeaveRequestDto rejected = leaveService.reject(
            submitted.id(), new ReviewLeaveRequest("ไม่อนุมัติ ติดงานสำคัญ"), hr());

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.reviewerNote()).isEqualTo("ไม่อนุมัติ ติดงานสำคัญ");
        assertThat(rejected.reviewedById()).isEqualTo(1L);
        assertThat(rejected.reviewedAt()).isNotNull();

        // "Quota is released": REJECTED is not in ACTIVE_QUOTA_STATUSES, so sumUsedDays stops
        // counting this request at all -- the actual mechanism, read from the repository rather than
        // assumed.
        assertThat(leaveRepository.sumUsedDays(employeeId, "SICK", 2026, Set.of(LeaveStatus.SUBMITTED, LeaveStatus.APPROVED)))
            .as("a REJECTED request must no longer count against the quota")
            .isEqualByComparingTo("0.00");

        // A rejected request was never APPROVED, so it contributes nothing to payroll's unpaid-day
        // view either (findUnpaidLeaveDaysByEmployeeForMonth is filtered to APPROVED only).
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-08-01")))
            .doesNotContainKey(employeeId);
    }

    /**
     * Mirrors {@link LeaveRepository#approve}'s own optimistic-concurrency shape (the {@code WHERE
     * status = 'SUBMITTED'} guard, surfaced by {@link LeaveService#reject} as a 409): a request that
     * has already been reviewed -- here, already APPROVED -- cannot also be rejected out from under
     * that decision.
     */
    @Test
    void rejectingAnAlreadyReviewedRequestFailsAndLeavesTheApprovalIntact() {
        long employeeId = insertEmployee("REJECT-002");
        LeaveRequestDto submitted = leaveService.submit(
            submitRequest(employeeId, "SICK", "2026-08-12", "2026-08-12"),
            employee(employeeId));
        leaveService.approve(submitted.id(), new ReviewLeaveRequest("approved"), hr());

        assertThatThrownBy(() -> leaveService.reject(submitted.id(), new ReviewLeaveRequest("too late"), hr()))
            .isInstanceOf(ApiException.class);

        var row = jdbc.queryForMap(
            "SELECT status FROM hr.leave_request WHERE leave_request_id = :id",
            new MapSqlParameterSource("id", submitted.id()));
        assertThat(row.get("status")).isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }

    // Hire date far enough in the past that neither the VACATION/PERSONAL min-service floor nor the
    // SICK quota is a factor here -- SICK carries no carry-forward complication the way VACATION does
    // (see LeaveUnpaidDeductionIntegrationTest's neutralizeVacationCarryForwardFrom2025 for why that
    // class needs the extra fixture step and this one deliberately does not).
    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, DATE '2020-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource("code", code), Long.class);
    }
}
