package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Leave requires approval (2026-08-05), F5: real-Postgres coverage of
 * {@link LeaveRepository#countSubmittedLeaveOverlappingMonth} -- the advisory count
 * {@code PayrollService#suggestedInputs} surfaces as {@code pendingSubmittedLeaveCount}. No test
 * covered this SQL before this class; Mockito cannot stand in for it since the whole point under
 * test is the month-boundary overlap predicate itself ({@code start_date <= monthEndInclusive AND
 * end_date >= monthStart}, deliberately the SAME predicate
 * {@link LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth} uses).
 *
 * <p>Every date below is placed EXACTLY on or one day past the boundary it is meant to exercise --
 * e.g. the wholly-outside-before fixture ends the day BEFORE the month starts -- so a
 * fence-post error in either comparison operator would flip a boundary case's expected inclusion.
 */
class LeavePendingApprovalCountIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate AUGUST = LocalDate.parse("2026-08-01");

    private LeaveRepository leaveRepository;

    @BeforeEach
    void wireRealCollaborators() {
        leaveRepository = new LeaveRepository(jdbc);
    }

    @Test
    void countsOnlySubmittedRequestsThatGenuinelyOverlapTheMonth() {
        long employeeId = insertEmployee("PENDING-CNT-001");

        // Wholly inside August -- must count.
        insertLeaveRequest(employeeId, "2026-08-10", "2026-08-12", "SUBMITTED");
        // Straddles the START boundary: ends exactly ON 2026-08-01 -- must count
        // (end_date >= monthStart with end_date == monthStart is inclusive).
        insertLeaveRequest(employeeId, "2026-07-31", "2026-08-01", "SUBMITTED");
        // Straddles the END boundary: starts exactly ON 2026-08-31 (the month's last day) -- must
        // count (start_date <= monthEndInclusive with start_date == monthEndInclusive is inclusive).
        insertLeaveRequest(employeeId, "2026-08-31", "2026-09-01", "SUBMITTED");
        // Wholly OUTSIDE, ending the day BEFORE August starts -- must NOT count.
        insertLeaveRequest(employeeId, "2026-07-20", "2026-07-31", "SUBMITTED");
        // Wholly OUTSIDE, starting the day AFTER August ends -- must NOT count.
        insertLeaveRequest(employeeId, "2026-09-01", "2026-09-05", "SUBMITTED");
        // Genuinely overlaps August (wholly inside, even), but is NOT SUBMITTED -- must NOT count.
        // APPROVED is the status this advisory is explicitly NOT about (that leave already feeds
        // payroll via findUnpaidLeaveDaysByEmployeeForMonth) -- see this repository method's Javadoc.
        insertLeaveRequest(employeeId, "2026-08-15", "2026-08-16", "APPROVED");

        assertThat(leaveRepository.countSubmittedLeaveOverlappingMonth(AUGUST)).isEqualTo(3);
    }

    @Test
    void countsZeroWhenNoLeaveRequestOverlapsTheMonthAtAll() {
        assertThat(leaveRepository.countSubmittedLeaveOverlappingMonth(AUGUST)).isZero();
    }

    // --- helpers ------------------------------------------------------------

    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, DATE '2020-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource("code", code), Long.class);
    }

    private void insertLeaveRequest(long employeeId, String startDate, String endDate, String status) {
        jdbc.update("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 status, quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, 'VACATION', :startDate, :endDate, 1.00, 2026,
                    'F5 month-overlap boundary fixture', :status, 6.00, 5.00)
            """, new MapSqlParameterSource()
                .addValue("employeeId", employeeId)
                .addValue("startDate", LocalDate.parse(startDate))
                .addValue("endDate", LocalDate.parse(endDate))
                .addValue("status", status));
    }
}
