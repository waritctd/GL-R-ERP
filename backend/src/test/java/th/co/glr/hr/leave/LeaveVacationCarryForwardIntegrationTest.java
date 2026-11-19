package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * §5.3.5 VACATION carry-forward: real-Postgres coverage of {@code
 * hr.leave_carryover} and {@link LeaveService#ensureCarryoverGrant}. The lazy memoization
 * (SELECT-then-INSERT-if-absent, keyed by a real calendar clock crossing a real year boundary) and
 * the non-accumulation CHECK constraint ({@code chk_lc_carried_days_bounded_by_own_quota}) are
 * exactly what Mockito cannot reach -- see {@code LeaveServiceTest} for the unrelated gates this
 * class does not re-prove.
 *
 * <p><b>Money note:</b> a carried-in day increases the paid quota available for a request, which
 * reduces unpaid days and therefore the base/30 unpaid-day payroll deduction (see
 * {@code PayrollCalculator#unpaidLeaveDeduction}). Every test below that asserts a paidDays/
 * remainingDays figure for a carrying employee is, indirectly, a claim about what that employee is
 * or is not paid for a period they would otherwise have gone unpaid.
 *
 * <p>Each test uses its own fresh employee and its own {@link #serviceAt} clock per submission --
 * carry-forward eligibility is clock-year-gated (see {@link LeaveService#ensureCarryoverGrant}), so
 * a request dated in 2025 must be submitted under a "today" that is actually still in 2025 (VACATION
 * requires 3 days' advance notice; a 2025-dated request submitted under a 2026 "today" would 400 on
 * notice, not on anything this class is testing).
 *
 * <p>Leave requires approval: every rule-passing submission below lands SUBMITTED,
 * not APPROVED (see {@link LeaveService#submit}'s comment) -- but this class's own
 * quota/carry-forward arithmetic (paidDays/unpaidDays split, {@link
 * LeaveService#ensureCarryoverGrant}'s "used" figure) is computed from {@code ACTIVE_QUOTA_STATUSES}
 * ({@code SUBMITTED} + {@code APPROVED}), not {@code APPROVED} alone, so none of it is affected: a
 * request sitting SUBMITTED still consumes quota and still determines what carries forward, exactly
 * as an APPROVED one always did. No test here calls {@link LeaveService#approve} -- doing so would
 * prove nothing additional about carry-forward math (unlike the payroll-visibility seam, which IS
 * gated on APPROVED and is covered separately by {@code LeaveUnpaidDeductionIntegrationTest} and
 * {@code PayrollLeaveUnpaidDeductionSeamIntegrationTest}).
 */
class LeaveVacationCarryForwardIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private LeaveRepository leaveRepository() {
        return new LeaveRepository(jdbc);
    }

    private LeaveService serviceAt(String today) {
        java.time.Instant instant = LocalDate.parse(today).atTime(9, 0).atZone(BUSINESS_ZONE).toInstant();
        return new LeaveService(
            leaveRepository(),
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class),
            Clock.fixed(instant, BUSINESS_ZONE));
    }

    @Test
    void unusedVacationDaysCarryIntoTheFollowingYearAndTheTotalConsumableIsExactlyEight() {
        // Employee uses 4 of a 6-day 2025 VACATION quota -> 2 unused -> carries into 2026.
        long employeeId = insertEmployee("CF-TOTAL-001");

        // Mon 2025-06-02 .. Thu 2025-06-05: 4 working days, all inside 2025.
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(firstYear.status()).isEqualTo("SUBMITTED");
        assertThat(firstYear.paidDays()).isEqualByComparingTo("4.00");

        // Mon 2026-07-13 .. Wed 2026-07-22: working days 13,14,15,16,17,20,21,22 = 8, all inside 2026, skipping the
        // 18th/19th weekend.
        LeaveRequestDto secondYear = serviceAt("2026-07-01").submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-22"), employee(employeeId));

        assertThat(secondYear.status()).isEqualTo("SUBMITTED");
        assertThat(secondYear.totalDays()).isEqualByComparingTo("8.00");
        assertThat(secondYear.paidDays()).isEqualByComparingTo("8.00");
        assertThat(secondYear.unpaidDays()).isEqualByComparingTo("0.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository().findQuotaYearSplits(secondYear.id());
        assertThat(splits).hasSize(1);
        // The discriminating figure: 8.00, not the flat 6.00 annual quota.
        assertThat(splits.get(0).quotaRemainingBefore()).isEqualByComparingTo("8.00");
        assertThat(splits.get(0).quotaRemainingAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void carriedDaysAreAvailableInTheFollowingYearButGoneTheYearAfterThatProvenFromBothSides() {
        // Non-accumulation (§5.3.5 "...ได้ในปีต่อไปเท่านั้น" -- only in the FOLLOWING year). The vacuous-fixture trap
        // this test is written to avoid: an expiry assertion for 2027 would pass trivially if 2026 never actually
        // received a carry-in in the first place.
        long employeeId = insertEmployee("CF-EXPIRE-001");
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(firstYear.paidDays()).isEqualByComparingTo("4.00");

        // --- Side 1: available in 2026 (N+1) ---
        LeaveBalanceDto balance2026 = vacationBalance(serviceAt("2026-07-01"), employeeId, 2026);
        assertThat(balance2026.annualQuotaDays()).isEqualByComparingTo("6.00");
        assertThat(balance2026.carriedInDays()).isEqualByComparingTo("2.00");
        assertThat(balance2026.approvedDays()).isEqualByComparingTo("0.00");
        assertThat(balance2026.remainingDays()).isEqualByComparingTo("8.00");

        // --- Side 2: gone in 2027 (N+2) --- (clock now past 2026 so 2026's own grant computes)
        LeaveBalanceDto balance2027 = vacationBalance(serviceAt("2027-06-01"), employeeId, 2027);
        // 2027 DOES receive a carry-in -- but it is 2026's own fully-unused 6.00-day quota (a FRESH grant earned by
        // 2026 itself), capped there, NOT 8.00 (which is what a bug that let the original 2025-earned days re-carry a
        // second hop would produce).
        assertThat(balance2027.carriedInDays()).isEqualByComparingTo("6.00");
        assertThat(balance2027.carriedInDays()).isNotEqualByComparingTo(new BigDecimal("8.00"));

        // Direct persisted-row proof, both facts on the SAME row: the system recorded that 2026 DID receive a 2-day
        // carry-in (own_quota/used/carried_in_days are the audit trail), yet the OUTPUT grant it produces
        // (carried_days) excludes that extra 2 -- capped at 2026's own 6.00.
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT own_quota_days, used_days, carried_in_days, carried_days
              FROM hr.leave_carryover
             WHERE employee_id = :employeeId AND leave_type_code = 'VACATION' AND earned_year = 2026
            """, new MapSqlParameterSource("employeeId", employeeId));
        assertThat((BigDecimal) row.get("own_quota_days")).isEqualByComparingTo("6.00");
        assertThat((BigDecimal) row.get("used_days")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) row.get("carried_in_days")).isEqualByComparingTo("2.00");
        assertThat((BigDecimal) row.get("carried_days")).isEqualByComparingTo("6.00");
    }

    // --- helpers ------------------------------------------------------------

    private LeaveBalanceDto vacationBalance(LeaveService service, long employeeId, int year) {
        return balanceFor(service, employeeId, year, "VACATION");
    }

    private LeaveBalanceDto balanceFor(LeaveService service, long employeeId, int year, String leaveTypeCode) {
        return service.balances(employee(employeeId), employeeId, year).stream()
            .filter(balance -> balance.leaveTypeCode().equals(leaveTypeCode))
            .findFirst()
            .orElseThrow();
    }

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Carry-forward integration test leave");
    }

    /** §5.3.5 pool choice (V161): same request, with an explicit pool preference. */
    private SubmitLeaveRequest submitRequest(
            long employeeId, String leaveTypeCode, String startDate, String endDate,
            LeaveQuotaPoolPreference preference) {
        return new SubmitLeaveRequest(
            employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate),
            "Carry-forward integration test leave", null, null, null, null, null, null, null, null, null,
            preference);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    // Hired 2015-01-01: comfortably clears VACATION's min_service_months floor (0) and
    // sits well outside proratedFirstYear's window for every earned_year used in this class except
    // the dedicated pro-ration test, which uses the 2-arg overload with an explicit hire date.
    private long insertEmployee(String code) {
        return insertEmployee(code, LocalDate.parse("2015-01-01"));
    }

    private long insertEmployee(String code, LocalDate hireDate) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("hireDate", hireDate), Long.class);
    }
}
