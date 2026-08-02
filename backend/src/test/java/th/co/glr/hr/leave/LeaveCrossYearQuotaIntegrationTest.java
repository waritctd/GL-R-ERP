package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

import static org.mockito.Mockito.mock;

/**
 * V118 cross-year quota fix (2026-08-02): real-Postgres coverage of {@link
 * hr.leave_request_quota_year}, {@link LeaveService#submit}'s per-year attribution, and the two
 * downstream paths that read a request's paid/unpaid split by calendar month -- {@link
 * LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth} (feeds the payroll base/30 deduction) and
 * {@link LeaveService#cancel}'s cancel-after-close reversal. Mockito cannot reach any of this: the
 * V118 schema, real calendar math (LeaveDayMath.countWorkingDays), and the SQL itself are exactly
 * what is under test.
 *
 * <p><b>Money note:</b> quota attribution drives the paid/unpaid split, which drives the base/30
 * unpaid-day payroll deduction (see {@code PayrollCalculator#unpaidLeaveDeduction}). Every test in
 * this class that asserts a paid/unpaid split or a payroll-month attribution is, indirectly, a claim
 * about what an employee is or is not paid for a given month.
 */
class LeaveCrossYearQuotaIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- every leave date used below is comfortably past
    // every seeded advance-notice value (VACATION=3, MATERNITY=0).
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
            Clock.fixed(FIXED_NOW, BUSINESS_ZONE));
    }

    @Test
    void aRequestSpanningThirtyFirstDecemberToFirstJanuaryConsumesBothYearsQuotasInTheRightProportion() {
        // Thu 2026-12-31 (working day) .. Fri 2027-01-01 (working day, no holiday calendar in v1): 1
        // day in each year, both years fresh (6-day VACATION quota) -- entirely paid on both sides.
        long employeeId = insertEmployee("BOUNDARY-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-12-31", "2027-01-01"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("2.00");
        assertThat(result.paidDays()).isEqualByComparingTo("2.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository.findQuotaYearSplits(result.id());
        assertThat(splits).hasSize(2);
        assertThat(splits.get(0).quotaYear()).isEqualTo(2026);
        assertThat(splits.get(0).totalDays()).isEqualByComparingTo("1.00");
        assertThat(splits.get(0).paidDays()).isEqualByComparingTo("1.00");
        assertThat(splits.get(0).quotaRemainingBefore()).isEqualByComparingTo("6.00");
        assertThat(splits.get(0).quotaRemainingAfter()).isEqualByComparingTo("5.00");
        assertThat(splits.get(1).quotaYear()).isEqualTo(2027);
        assertThat(splits.get(1).totalDays()).isEqualByComparingTo("1.00");
        assertThat(splits.get(1).paidDays()).isEqualByComparingTo("1.00");
        // Year 2027 is a completely separate, fresh quota -- unaffected by 2026's own consumption.
        assertThat(splits.get(1).quotaRemainingBefore()).isEqualByComparingTo("6.00");
        assertThat(splits.get(1).quotaRemainingAfter()).isEqualByComparingTo("5.00");
    }

    @Test
    void aCrossYearRequestSplitsPaidAndUnpaidCorrectlyWhenTheLaterYearsQuotaIsAlreadyExhausted() {
        // Wrong-way-round-flavoured complement to the boundary test above: year 2026 has quota left,
        // year 2027 has NONE (already fully consumed by an earlier, unrelated single-year request) --
        // proves the per-year split reads each year's OWN remaining quota independently, not a single
        // shared figure.
        long employeeId = insertEmployee("EXHAUSTED-001");

        LeaveRequestDto priorRequest = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2027-01-05", "2027-01-12"), // 6 working days, exactly the 2027 quota
            employee(employeeId));
        assertThat(priorRequest.status()).isEqualTo("APPROVED");
        assertThat(priorRequest.paidDays()).isEqualByComparingTo("6.00");

        // Mon 2026-12-28 .. Fri 2027-01-01: working days 12/28, 12/29, 12/30, 12/31 (4, all 2026),
        // then 1/1 (1, 2027) -- 5 total. 2026 has its full 6-day quota available -> all 4 of its days
        // paid. 2027 has 0 remaining (consumed above) -> its 1 day is entirely unpaid.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-12-28", "2027-01-01"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("5.00");
        assertThat(result.paidDays()).isEqualByComparingTo("4.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("1.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository.findQuotaYearSplits(result.id());
        assertThat(splits.get(0).quotaYear()).isEqualTo(2026);
        assertThat(splits.get(0).paidDays()).isEqualByComparingTo("4.00");
        assertThat(splits.get(0).unpaidDays()).isEqualByComparingTo("0.00");
        assertThat(splits.get(1).quotaYear()).isEqualTo(2027);
        assertThat(splits.get(1).paidDays()).isEqualByComparingTo("0.00");
        assertThat(splits.get(1).unpaidDays()).isEqualByComparingTo("1.00");
    }

    @Test
    void aNinetyEightDayMaternityRequestStartingOnFirstNovemberIsFileableAndSplitsAcrossTheYearBoundary() {
        // THE case the owner asked for by name: pre-V118, LeaveService#validateDateRange rejected
        // this outright (400 "คำขอลาต้องไม่คร่อมปีโควตา") because a 98-working-day MATERNITY request
        // starting 1 Nov is ~136 calendar days, unavoidably crossing into the next year. It must now
        // be APPROVED.
        //
        // Mon 2026-11-01 (Sunday, but that only affects which days count) .. Wed 2027-03-17: verified
        // independently of LeaveDayMath (see the branch's PR description) to be exactly 98 working
        // weekdays, split 44 in 2026 (Nov 1 - Dec 31) / 54 in 2027 (Jan 1 - Mar 17).
        //
        // MATERNITY's paid_days_cap (45) applies PER YEAR under this migration's design (see
        // LeaveQuotaYearSplit's Javadoc) -- both years start with a fresh 45-day allowance:
        //   2026: 44 requested days, cap 45 -> all 44 paid, 0 unpaid.
        //   2027: 54 requested days, cap 45 -> 45 paid, 9 unpaid.
        // Total: 98 requested, 89 paid, 9 unpaid. This is MORE than the single-year 45-day cap would
        // ever allow a same-year request -- a deliberate, documented consequence of attributing the
        // cap per calendar year, not a miscalculation. See LeaveQuotaYearSplit's Javadoc.
        long employeeId = insertEmployee("MAT-CROSSYEAR-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MATERNITY", "2026-11-01", "2027-03-17"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("98.00");
        assertThat(result.paidDays()).isEqualByComparingTo("89.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("9.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository.findQuotaYearSplits(result.id());
        assertThat(splits).hasSize(2);
        assertThat(splits.get(0).quotaYear()).isEqualTo(2026);
        assertThat(splits.get(0).totalDays()).isEqualByComparingTo("44.00");
        assertThat(splits.get(0).paidDays()).isEqualByComparingTo("44.00");
        assertThat(splits.get(0).unpaidDays()).isEqualByComparingTo("0.00");
        assertThat(splits.get(1).quotaYear()).isEqualTo(2027);
        assertThat(splits.get(1).totalDays()).isEqualByComparingTo("54.00");
        assertThat(splits.get(1).paidDays()).isEqualByComparingTo("45.00");
        assertThat(splits.get(1).unpaidDays()).isEqualByComparingTo("9.00");

        // Parent quota_remaining_before/after reflect ONLY the start year (2026) -- see
        // hr.leave_request's V118 column comments. 2026's own 98-day quota had all 44 requested days
        // consumed from it (paid_days_cap bounds PAYMENT, not quota consumption) -> 98 - 44 = 54
        // remaining, NOT a number derived from the 2027 figures at all.
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("54.00");
    }

    @Test
    void crossYearUnpaidDaysAttributeToTheCorrectCalendarMonthNotTheNaiveWholeRequestRank() {
        // The scenario that would break under the OLD (pre-fix) aggregate-paidDays/totalDays call
        // into LeaveDayMath#unpaidWorkingDaysByMonth: year 2026 exhausts its 6-day VACATION quota
        // mid-range (an unpaid TAIL near year-end), year 2027 is fully paid from its own fresh start.
        //
        // Mon 2026-12-21 .. Tue 2027-01-05: working days 12/21,22,23,24,25,28,29,30,31 (9, all 2026),
        // then 1/1,1/4,1/5 (3, all 2027) -- 12 total. 2026's 6-day quota covers only the FIRST 6
        // chronologically (12/21-12/28) -> unpaid = the LAST 3 of 2026 (12/29, 12/30, 12/31), all
        // December. 2027's fresh 6-day quota covers all 3 of its days -> nothing unpaid there.
        //
        // A naive single-global-rank reading of the AGGREGATE (paid=6+3=9, total=9+3=12) would
        // instead mark the LAST 3 of the whole 12-day span as unpaid -- ranks 10-12, i.e. 1/1, 1/4,
        // 1/5, ALL JANUARY -- the wrong month and the wrong year. This test is the real-DB proof that
        // LeaveRepository#findUnpaidLeaveDaysByEmployeeForMonth does NOT do that.
        long employeeId = insertEmployee("MONTH-ATTR-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-12-21", "2027-01-05"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("12.00");
        assertThat(result.paidDays()).isEqualByComparingTo("9.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("3.00");

        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2026-12-01")))
            .containsEntry(employeeId, new BigDecimal("3.00"));
        // The critical negative assertion.
        assertThat(leaveRepository.findUnpaidLeaveDaysByEmployeeForMonth(LocalDate.parse("2027-01-01")))
            .doesNotContainKey(employeeId);
    }

    @Test
    void cancellingACrossYearLeaveAfterOnlyDecemberPayrollIsProcessedRefundsOnlyDecember() {
        // Same fixture and reasoning as the test above, now through LeaveService#cancel's
        // cancel-after-close reversal. Only December 2026 payroll has been PROCESSED -- the
        // correction must land there (3.00 days), and NOT in January (nothing was ever deducted there
        // under the correct per-year model, so there is nothing to refund).
        long employeeId = insertEmployee("CANCEL-CROSSYEAR-001");
        LeaveRequestDto approved = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-12-21", "2027-01-05"),
            employee(employeeId));
        assertThat(approved.unpaidDays()).isEqualByComparingTo("3.00");

        insertProcessedPayrollPeriod(LocalDate.parse("2026-12-01"));

        leaveService.cancel(approved.id(), new ReviewLeaveRequest("no longer needed"), hr());

        List<Map<String, Object>> corrections = jdbc.queryForList("""
            SELECT payroll_month, unpaid_days_to_refund
              FROM hr.leave_payroll_correction
             WHERE leave_request_id = :leaveRequestId
            """, new MapSqlParameterSource("leaveRequestId", approved.id()));
        assertThat(corrections).hasSize(1);
        // queryForList's untyped Map returns java.sql.Date for a DATE column, not java.time.LocalDate.
        LocalDate correctionMonth = ((java.sql.Date) corrections.get(0).get("payroll_month")).toLocalDate();
        assertThat(correctionMonth).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat((BigDecimal) corrections.get(0).get("unpaid_days_to_refund")).isEqualByComparingTo("3.00");

        Map<Long, BigDecimal> pending = leaveRepository.findPendingPayrollCorrectionsByEmployee();
        assertThat(pending).containsEntry(employeeId, new BigDecimal("3.00"));
    }

    @Test
    void backfillSqlReproducesTheParentRowsFiguresExactlyForALegacySingleYearRow() {
        // V118 backfill correctness: this is V118__leave_request_quota_year_split.sql's own
        // backfill INSERT-SELECT, copied here verbatim, run against a "legacy" row -- one inserted
        // directly (mirroring every hr.leave_request row that predates this migration, none of which
        // were ever written through LeaveService#submit's new insertQuotaYearSplits path). No
        // historical request's meaning should change: the resulting child row must carry EXACTLY the
        // parent's own total_days/paid_days/unpaid_days/quota_year/quota_remaining_before/after.
        long employeeId = insertEmployee("BACKFILL-001");
        long leaveRequestId = jdbc.queryForObject("""
            INSERT INTO hr.leave_request (
                employee_id, leave_type_code, start_date, end_date, total_days, paid_days, unpaid_days,
                quota_year, reason, status, quota_remaining_before, quota_remaining_after, requested_by_id
            )
            VALUES (
                :employeeId, 'VACATION', '2026-07-13', '2026-07-14', 2.00, 2.00, 0.00,
                2026, 'Legacy fixture', 'APPROVED', 4.00, 2.00, :employeeId
            )
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);

        // Confirm the starting state really is "no child row yet" -- this row was inserted directly,
        // bypassing LeaveService, so nothing else in this test class could have created one.
        assertThat(leaveRepository.findQuotaYearSplits(leaveRequestId)).isEmpty();

        jdbc.update("""
            INSERT INTO hr.leave_request_quota_year (
                leave_request_id, quota_year, total_days, paid_days, unpaid_days,
                quota_remaining_before, quota_remaining_after
            )
            SELECT leave_request_id, quota_year, total_days, paid_days, unpaid_days,
                   quota_remaining_before, quota_remaining_after
              FROM hr.leave_request
             WHERE leave_request_id = :leaveRequestId
            """, new MapSqlParameterSource("leaveRequestId", leaveRequestId));

        List<LeaveQuotaYearSplit> splits = leaveRepository.findQuotaYearSplits(leaveRequestId);
        assertThat(splits).hasSize(1);
        LeaveQuotaYearSplit split = splits.get(0);
        assertThat(split.quotaYear()).isEqualTo(2026);
        assertThat(split.totalDays()).isEqualByComparingTo("2.00");
        assertThat(split.paidDays()).isEqualByComparingTo("2.00");
        assertThat(split.unpaidDays()).isEqualByComparingTo("0.00");
        assertThat(split.quotaRemainingBefore()).isEqualByComparingTo("4.00");
        assertThat(split.quotaRemainingAfter()).isEqualByComparingTo("2.00");
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

    // Hired 2015-01-01: comfortably clears every seeded min_service_months floor (VACATION=12,
    // ORDINATION=12) for every test in this class -- none of them are ABOUT eligibility, they are
    // about quota attribution once a request is already eligible.
    private long insertEmployee(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, DATE '2015-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource("code", code), Long.class);
    }

    private void insertProcessedPayrollPeriod(LocalDate payrollMonth) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                :payrollMonth, :payrollMonth,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                'PROCESSED'
            )
            """, new MapSqlParameterSource().addValue("payrollMonth", payrollMonth));
    }
}
