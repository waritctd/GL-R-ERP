package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * V164 (owner-approved change, 2026-09-09): real-Postgres proof of owner ruling #1 -- "the single
 * most important correctness property of this change" -- that a WARN_UNPAID_ALL/WARN_UNPAID_EXCESS
 * request consumes NO quota, through the REAL {@link LeaveRepository} and REAL SQL. Mockito cannot
 * prove this: {@code LeaveServiceTest}'s equivalent tests stub {@code sumUsedDays} directly, so they
 * can only prove {@link LeaveService#computeQuotaSplit}'s OWN arithmetic is right for the request
 * under test -- they cannot catch a bug in the SQL a LATER request's quota computation reads. This
 * class is written specifically to catch that class of bug: {@link
 * #aWarnedRequestsUnpaidByRuleDaysNeverReduceALaterRequestsRemainingQuota} submits a WARN-triggering
 * request FIRST, then a second, ordinary, full-quota request SECOND, and asserts the second one is
 * unaffected -- proving the exclusion survives into {@code hr.leave_request_quota_year} and back out
 * through {@link LeaveRepository#sumUsedDays}, not merely into the first request's own persisted
 * split. See that test's Javadoc for the concrete bug this construction is shaped to catch (found
 * and fixed while implementing this same change -- {@code sumUsedDays} originally summed the child
 * table's plain {@code total_days}, which does not know about {@code unpaid_by_rule_days} at all).
 *
 * <p>Uses REAL seeded {@code hr.leave_type} data (VACATION: advance_notice_days=3,
 * annual_quota_days=6.00, min_service_months=0, prorated_first_year=TRUE -- V116/V120), not a
 * Mockito fixture, so this is proof against the actual production rule data, not an approximation
 * of it. Every employee here has NO department ({@code insertEmployee}'s null third argument), so
 * §5.3.2 department coverage (still BLOCK, unrelated to this test) never fires and cannot confound
 * the result -- see {@code LeaveRelationalRulesIntegrationTest}'s identical "no work_schedule_assignment
 * rows for an ad-hoc department" reasoning for why every date below is a weekday.
 */
class LeaveWarnUnpaidQuotaIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok.
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T02:00:00Z");

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

    @Test
    void aWarnedRequestsUnpaidByRuleDaysNeverReduceALaterRequestsRemainingQuota() {
        // Hired long ago -- clears VACATION's real prorated_first_year scaling (full 6.00/year quota,
        // not a fraction of it) and its (now-zero) min_service_months, isolating this test to the
        // ADVANCE_NOTICE gate alone.
        long employeeId = insertEmployee("WARN-QUOTA-001", LocalDate.parse("2015-01-01"), null);
        // §5.3.5 VACATION carry-forward (V127): pin 2025's carry-OUT to ZERO so 2026's quota is
        // exactly the flat 6.00 annual figure, not 6.00 + an unused-2025 carry-in -- carry-forward
        // arithmetic is exercised elsewhere; this test is about unpaid-by-rule exclusion specifically.
        leaveRepository.insertCarryoverIfAbsent(
            employeeId, "VACATION", 2025, 2026, new BigDecimal("6.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        // ── Request 1: 1 working day, filed with ZERO days' notice (today == FIXED_NOW's date, well
        // inside VACATION's real 3-working-day requirement) -- fires ADVANCE_NOTICE, WARN_UNPAID_ALL.
        SubmitLeaveRequest lateRequest = submitRequest(employeeId, "2026-07-01", "2026-07-01");
        LeaveRequestDto warned = leaveService.submit(lateRequest, employee(employeeId));

        // Wrong-way-round assertion #1: this is SUBMITTED, NOT AUTO_REJECTED -- V164's entire point.
        assertThat(warned.status()).isEqualTo("SUBMITTED");
        assertThat(warned.systemNoteCode()).isNull();
        assertThat(warned.systemNote()).isNull();
        assertThat(warned.ruleWarnings()).hasSize(1);
        assertThat(warned.ruleWarnings().get(0).code()).isEqualTo("ADVANCE_NOTICE");
        assertThat(warned.ruleWarnings().get(0).messageTh()).contains("อย่างน้อย 3 วัน");
        assertThat(warned.totalDays()).isEqualByComparingTo("1.00");
        // Owner ruling #1, on THIS request's own split: wholly unpaid, nothing paid.
        assertThat(warned.unpaidByRuleDays()).isEqualByComparingTo("1.00");
        assertThat(warned.paidDays()).isEqualByComparingTo("0.00");
        assertThat(warned.unpaidDays()).isEqualByComparingTo("1.00");
        // Wrong-way-round assertion #2: quota_remaining_before == quota_remaining_after on the SAME
        // request -- nothing was consumed by submitting it, even though it went through.
        assertThat(warned.quotaRemainingBefore()).isEqualByComparingTo("6.00");
        assertThat(warned.quotaRemainingAfter()).isEqualByComparingTo("6.00");

        // ── Request 2: a SEPARATE, later, properly-noticed request for the FULL 6.00-day annual
        // quota (2026-08-03 Mon .. 2026-08-10 Mon, 6 working days -- comfortably >3 working days'
        // notice from FIXED_NOW). If request 1's unpaid-by-rule day had wrongly counted as "used"
        // (the bug this test is shaped to catch -- see this class's Javadoc), request 2 would show
        // paidDays=5.00/unpaidDays=1.00 (quota short by exactly the first request's 1 day) instead of
        // the correct paidDays=6.00/unpaidDays=0.00.
        SubmitLeaveRequest fullQuotaRequest = submitRequest(employeeId, "2026-08-03", "2026-08-10");
        LeaveRequestDto second = leaveService.submit(fullQuotaRequest, employee(employeeId));

        assertThat(second.status()).isEqualTo("SUBMITTED");
        assertThat(second.ruleWarnings()).isEmpty();
        assertThat(second.unpaidByRuleDays()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(second.totalDays()).isEqualByComparingTo("6.00");
        assertThat(second.quotaRemainingBefore()).isEqualByComparingTo("6.00");
        assertThat(second.paidDays()).isEqualByComparingTo("6.00");
        assertThat(second.unpaidDays()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(second.quotaRemainingAfter()).isEqualByComparingTo(BigDecimal.ZERO);

        // Direct SQL proof, independent of the DTO mapping: hr.leave_request_quota_year's own
        // unpaid_by_rule_days column (V164) for request 1's row, and LeaveRepository#sumUsedDays
        // (what request 2's own quota computation actually read) reflect the SAME exclusion.
        var splits = leaveRepository.findQuotaYearSplits(warned.id());
        assertThat(splits).hasSize(1);
        assertThat(splits.get(0).unpaidByRuleDays()).isEqualByComparingTo("1.00");
        assertThat(splits.get(0).totalDays()).isEqualByComparingTo("1.00");
    }

    @Test
    void aWeddingExcessWarningLeavesOnlyTheExcessDaysUnpaidAndUnconsumedFromQuota() {
        // WARN_UNPAID_EXCESS companion: real PERSONAL (min_service_months=0, prorated_first_year=TRUE,
        // annual_quota_days=7.00 -- V116/V120), WEDDING_LEAVE_MAX_DAYS=3 (a Java constant, not a
        // column -- see LeaveService#autoRejectNote's wedding-cap comment). 4 working days
        // (Mon-Thu), 1 over the cap.
        long employeeId = insertEmployee("WARN-QUOTA-002", LocalDate.parse("2015-01-01"), null);

        SubmitLeaveRequest weddingRequest = new SubmitLeaveRequest(
            employeeId, "PERSONAL", LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-16"),
            "Own wedding", null, null, null, null, null, null, null, "WEDDING", null);
        LeaveRequestDto warned = leaveService.submit(weddingRequest, employee(employeeId));

        assertThat(warned.status()).isEqualTo("SUBMITTED");
        assertThat(warned.ruleWarnings()).hasSize(1);
        assertThat(warned.ruleWarnings().get(0).code()).isEqualTo("WEDDING_MAX_DAYS");
        assertThat(warned.totalDays()).isEqualByComparingTo("4.00");
        // Only the 1 excess day is unpaid by rule -- the other 3 are ordinary paid quota consumption.
        assertThat(warned.unpaidByRuleDays()).isEqualByComparingTo("1.00");
        assertThat(warned.paidDays()).isEqualByComparingTo("3.00");
        assertThat(warned.unpaidDays()).isEqualByComparingTo("1.00");
        // Quota only moved by the 3 PAID days -- the 1 excess day never touched it (7.00 - 3.00 = 4.00).
        assertThat(warned.quotaRemainingBefore()).isEqualByComparingTo("7.00");
        assertThat(warned.quotaRemainingAfter()).isEqualByComparingTo("4.00");
    }

    private SubmitLeaveRequest submitRequest(long employeeId, String startDate, String endDate) {
        return new SubmitLeaveRequest(
            employeeId, "VACATION", LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertEmployee(String code, LocalDate hireDate, Long departmentId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, is_active, hire_date, department_id)
            VALUES (:code, :code, 'ทดสอบ', TRUE, :hireDate, :departmentId)
            RETURNING employee_id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("code", code)
            .addValue("hireDate", hireDate)
            .addValue("departmentId", departmentId),
            Long.class);
    }
}
