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
 * §5.3.5 VACATION carry-forward (V127, 2026-08-03): real-Postgres coverage of {@code
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
 * <p>Leave requires approval (2026-08-05): every rule-passing submission below now lands SUBMITTED,
 * not APPROVED (see {@link LeaveService#submit}'s owner-ruling comment) -- but this class's own
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
        // Employee uses 4 of a 6-day 2025 VACATION quota -> 2 unused -> carries into 2026. The
        // following year's true consumable total is asserted EXACTLY (8.00), not just "more than
        // 6": an 8-day 2026 request must come back fully PAID with zero unpaid days -- if carry-in
        // were not applied, remainingDays would still read 6.00 and this same request would
        // approve-with-split (6 paid / 2 unpaid), a materially different (and wrong) payroll
        // outcome for the employee.
        long employeeId = insertEmployee("CF-TOTAL-001");

        // Mon 2025-06-02 .. Thu 2025-06-05: 4 working days, all inside 2025.
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(firstYear.status()).isEqualTo("SUBMITTED");
        assertThat(firstYear.paidDays()).isEqualByComparingTo("4.00");

        // Mon 2026-07-13 .. Wed 2026-07-22: working days 13,14,15,16,17,20,21,22 = 8, all inside
        // 2026, skipping the 18th/19th weekend.
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
        // Non-accumulation (§5.3.5 "...ได้ในปีต่อไปเท่านั้น" -- only in the FOLLOWING year). Employee
        // uses 4 of 6 in 2025 (2 carry into 2026), then uses NOTHING AT ALL in 2026 -- both the
        // carried-in 2 and 2026's own 6 sit completely unused. The vacuous-fixture trap this test is
        // written to avoid: an expiry assertion for 2027 would pass trivially if 2026 never actually
        // received a carry-in in the first place. Both sides are proven on this ONE fixture: the
        // balances() call for 2026 proves the 2-day carry-in genuinely existed and was available
        // (8.00 total, not 6.00); the balances() call for 2027, PLUS a direct read of the persisted
        // hr.leave_carryover row, then prove that same 2-day figure did not survive a second year.
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
        // 2027 DOES receive a carry-in -- but it is 2026's own fully-unused 6.00-day quota (a FRESH
        // grant earned by 2026 itself), capped there, NOT 8.00 (which is what a bug that let the
        // original 2025-earned days re-carry a second hop would produce).
        assertThat(balance2027.carriedInDays()).isEqualByComparingTo("6.00");
        assertThat(balance2027.carriedInDays()).isNotEqualByComparingTo(new BigDecimal("8.00"));

        // Direct persisted-row proof, both facts on the SAME row: the system recorded that 2026 DID
        // receive a 2-day carry-in (own_quota/used/carried_in_days are the audit trail), yet the
        // OUTPUT grant it produces (carried_days) excludes that extra 2 -- capped at 2026's own 6.00.
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

    @Test
    void carryInDaysAreConsumedBeforeTheYearsOwnQuotaWhenComputingWhatCarriesForwardNext() {
        // THE consumption-order decision, isolated with a worked example matching
        // LeaveService#ensureCarryoverGrant's Javadoc exactly: 2025 leaves 2 unused (carries into
        // 2026, alongside 2026's own fresh 6.00 -- 8.00 total available), and the employee then
        // takes EXACTLY 6.00 days during 2026.
        //
        // Chosen order (carry-in FIRST, employee-favouring "use it or lose it"): the 2 about-to-be-
        // meaningless-if-unused carried days are spent first, then 4 of the renewable 6.00 own quota
        // -> 2.00 of own quota survives unused -> grants 2.00 into 2027.
        //
        // MUTATION-CHECK TARGET: the rejected alternative (own-quota-first) would instead exhaust
        // all 6.00 of own quota first, leaving the carry-in as the only unused remainder (worthless,
        // §5.3.5 forbids re-carrying it) -> 0.00 would grant into 2027. Flipping
        // LeaveService#ensureCarryoverGrant's formula to own-quota-first must turn this exact
        // assertion red (2.00 -> 0.00) and nothing else in this class.
        long employeeId = insertEmployee("CF-ORDER-001");
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(firstYear.paidDays()).isEqualByComparingTo("4.00");

        // Mon 2026-08-03 .. Mon 2026-08-10: working days 3,4,5,6,7,10 = 6, skipping the 8th/9th
        // weekend.
        LeaveRequestDto secondYear = serviceAt("2026-07-01").submit(
            submitRequest(employeeId, "VACATION", "2026-08-03", "2026-08-10"), employee(employeeId));
        assertThat(secondYear.status()).isEqualTo("SUBMITTED");
        assertThat(secondYear.totalDays()).isEqualByComparingTo("6.00");
        assertThat(secondYear.paidDays()).isEqualByComparingTo("6.00");

        BigDecimal grantInto2027 = leaveRepository()
            .findCarryover(employeeId, "VACATION", 2026)
            .orElseGet(() -> vacationBalance(serviceAt("2027-06-01"), employeeId, 2027).carriedInDays());
        assertThat(grantInto2027).isEqualByComparingTo("2.00");
    }

    @Test
    void aCrossYearRequestDrawsCarryInOnlyForTheYearThatActuallyReceivedIt() {
        // V118 interaction: a request spanning 31 Dec/1 Jan attributes each side to its OWN year
        // (LeaveQuotaYearSplit); carry-in must boost only the year that actually earned one. 2025
        // leaves 2 unused (carries into 2026 only) -- 2027 must NOT also see a boost.
        long employeeId = insertEmployee("CF-XY-001");
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(firstYear.paidDays()).isEqualByComparingTo("4.00");

        // Mon 2026-12-28 .. Fri 2027-01-01: working days 12/28,29,30,31 (4, all 2026) then 1/1 (1,
        // 2027) = 5 total.
        LeaveRequestDto crossYear = serviceAt("2026-12-01").submit(
            submitRequest(employeeId, "VACATION", "2026-12-28", "2027-01-01"), employee(employeeId));

        assertThat(crossYear.status()).isEqualTo("SUBMITTED");
        assertThat(crossYear.totalDays()).isEqualByComparingTo("5.00");
        assertThat(crossYear.paidDays()).isEqualByComparingTo("5.00");
        assertThat(crossYear.unpaidDays()).isEqualByComparingTo("0.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository().findQuotaYearSplits(crossYear.id());
        assertThat(splits).hasSize(2);
        assertThat(splits.get(0).quotaYear()).isEqualTo(2026);
        // 2026's OWN + 2025's 2-day carry-in = 8.00 -- the year that actually earned a carry-in.
        assertThat(splits.get(0).quotaRemainingBefore()).isEqualByComparingTo("8.00");
        assertThat(splits.get(0).quotaRemainingAfter()).isEqualByComparingTo("4.00");
        assertThat(splits.get(1).quotaYear()).isEqualTo(2027);
        // 2027 earned no carry-in of its own in this fixture -- the flat 6.00, NOT 8.00.
        assertThat(splits.get(1).quotaRemainingBefore()).isEqualByComparingTo("6.00");
        assertThat(splits.get(1).quotaRemainingAfter()).isEqualByComparingTo("5.00");
    }

    @Test
    void noOtherLeaveTypeCarriesForwardEvenWhenItsAnnualQuotaGoesLargelyUnused() {
        // Pin: §5.3.5 names VACATION alone. PERSONAL (chosen because, unlike SICK/MATERNITY, it
        // needs no attachment to auto-approve) leaves 5 of its 7-day 2025 quota unused -- if
        // carry-forward had been accidentally generalised past the leaveType.carriesForward() gate,
        // this would show a positive carriedInDays in 2026 and a persisted row below.
        long employeeId = insertEmployee("CF-PIN-001");
        LeaveRequestDto firstYear = serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "PERSONAL", "2025-06-02", "2025-06-03"), employee(employeeId));
        assertThat(firstYear.status()).isEqualTo("SUBMITTED");
        assertThat(firstYear.paidDays()).isEqualByComparingTo("2.00");

        LeaveBalanceDto personalBalance2026 = balanceFor(serviceAt("2026-07-01"), employeeId, 2026, "PERSONAL");
        assertThat(personalBalance2026.carriedInDays()).isEqualByComparingTo("0.00");

        Long carryoverRowsForNonVacationTypes = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.leave_carryover WHERE leave_type_code <> 'VACATION'
            """, Map.of(), Long.class);
        assertThat(carryoverRowsForNonVacationTypes).isZero();
    }

    @Test
    void noGrantIsComputedForAnEarnedYearBeforeTheCarryForwardBaselineYear() {
        // No historical backfill: 2024 predates LeaveService.CARRY_FORWARD_BASELINE_YEAR (2025) even
        // though 2024 is fully elapsed by the time this test reads 2025's balance. An employee who
        // left 5 of 6 VACATION days unused in 2024 must see ZERO carry-in for 2025, and no row is
        // ever written for earned_year=2024 -- this is a deliberate boundary, not a bug (see that
        // constant's Javadoc).
        long employeeId = insertEmployee("CF-BASELINE-001");
        // Mon 2024-06-03 (single working day) -- 1 of 6 used, 5 unused.
        LeaveRequestDto usage2024 = serviceAt("2024-05-01").submit(
            submitRequest(employeeId, "VACATION", "2024-06-03", "2024-06-03"), employee(employeeId));
        assertThat(usage2024.paidDays()).isEqualByComparingTo("1.00");

        LeaveBalanceDto balance2025 = vacationBalance(serviceAt("2025-06-01"), employeeId, 2025);
        assertThat(balance2025.carriedInDays()).isEqualByComparingTo("0.00");
        assertThat(balance2025.remainingDays()).isEqualByComparingTo("6.00");

        Long rowsFor2024 = jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.leave_carryover
             WHERE employee_id = :employeeId AND earned_year = 2024
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
        assertThat(rowsFor2024).isZero();
    }

    @Test
    void aProRatedFirstYearVacationQuotaCarriesForwardItsOwnLeftoverNotTheFlatSix() {
        // Design decision: a pro-rated first year (V120) DOES carry forward -- §5.3.5 states "ปีใด"
        // ("whatever year") with no stated exception for a first, pro-rated year, and it carries the
        // unused portion of THAT year's own pro-rated entitlement, not the flat 6.00. Hired
        // 2025-07-01: by 2025-12-31 (year-end), completed service is 5 whole months (ChronoUnit
        // truncates, matching LeaveService#employeeAnnualQuota elsewhere), so the pro-rated 2025
        // quota is 6.00 x 5/12 = 2.5, rounded to the nearest 0.5 = 2.50 (already exact). Zero days
        // used in 2025 -> the full 2.50 carries into 2026 -- the discriminating figure: NOT 6.00
        // (which a bug using the flat annual figure instead of the pro-rated one would produce), and
        // NOT 0.00 (which excluding pro-rated years from carry-forward entirely would produce).
        long employeeId = insertEmployee("CF-PRORATE-001", LocalDate.parse("2025-07-01"));

        LeaveBalanceDto balance2026 = vacationBalance(serviceAt("2026-07-01"), employeeId, 2026);
        assertThat(balance2026.carriedInDays()).isEqualByComparingTo("2.50");
    }

    // --- helpers ------------------------------------------------------------

    // ---------------------------------------------------------------------
    // §5.3.5 pool choice (V161): the split is now RECORDED per request, and the requester picks
    // the order. Fixture shared by the three tests below: 2025 uses 4 of its 6, carrying 2.00 into
    // 2026, which also has its own fresh 6.00 -- 8.00 available in 2026.
    // ---------------------------------------------------------------------

    @Test
    void theDefaultCarriedInFirstOrderIsRecordedOnTheRequestNotJustInferredAtYearEnd() {
        // Same numbers as #carryInDaysAreConsumedBeforeTheYearsOwnQuotaWhenComputingWhatCarriesForwardNext
        // above, but asserting the thing that test CANNOT see: before V161 the carry-in-first order
        // existed only as arithmetic inside the year-end carry-out formula, with nothing on the
        // request itself saying which pool paid for it. Now it is a recorded fact.
        long employeeId = insertEmployee("V161-DEFAULT-001");
        serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));

        // Mon 2026-08-03 .. Mon 2026-08-10 = 6 working days (3,4,5,6,7,10; 8-9 is the weekend).
        LeaveRequestDto taken = serviceAt("2026-07-01").submit(
            submitRequest(employeeId, "VACATION", "2026-08-03", "2026-08-10"), employee(employeeId));
        assertThat(taken.totalDays()).isEqualByComparingTo("6.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository().findQuotaYearSplits(taken.id());
        assertThat(splits).hasSize(1);
        // The 2.00 carry-in is spent FIRST, then 4.00 of 2026's own quota -- recorded, not inferred.
        assertThat(splits.get(0).carriedInDays()).isEqualByComparingTo("2.00");
        assertThat(splits.get(0).ownQuotaDays()).isEqualByComparingTo("4.00");
    }

    @Test
    void ownFirstSpendsTheRenewableQuotaAndLetsTheCarryInExpireUnused() {
        // THE test for V161's formula change. Identical numbers to the default-order test above, so
        // the ONLY difference is the requester's choice.
        //
        // Under OWN_FIRST the employee burns all 6.00 of 2026's own quota and never touches the
        // 2.00 carry-in, which then expires unused (§5.3.5 forbids re-carrying it). So NOTHING
        // carries into 2027.
        //
        // MUTATION-CHECK TARGET: reverting LeaveService#ensureCarryoverGrant to the OLD inference
        // `min(ownQuota, max(0, ownQuota + carriedIn - used))` computes min(6, max(0, 6+2-6)) =
        // 2.00 here -- silently granting 2.00 days that were never actually left over. This
        // assertion (0.00) is what catches that; it is the whole reason the formula had to stop
        // inferring and start reading own_quota_days.
        long employeeId = insertEmployee("V161-OWNFIRST-001");
        serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));
        assertThat(vacationBalance(serviceAt("2026-07-01"), employeeId, 2026).carriedInDays())
            .isEqualByComparingTo("2.00");

        LeaveRequestDto taken = serviceAt("2026-07-01").submit(
            submitRequest(employeeId, "VACATION", "2026-08-03", "2026-08-10",
                LeaveQuotaPoolPreference.OWN_FIRST),
            employee(employeeId));
        assertThat(taken.totalDays()).isEqualByComparingTo("6.00");

        List<LeaveQuotaYearSplit> splits = leaveRepository().findQuotaYearSplits(taken.id());
        assertThat(splits.get(0).ownQuotaDays()).isEqualByComparingTo("6.00");
        assertThat(splits.get(0).carriedInDays()).isEqualByComparingTo("0.00");

        BigDecimal grantInto2027 = leaveRepository()
            .findCarryover(employeeId, "VACATION", 2026)
            .orElseGet(() -> vacationBalance(serviceAt("2027-06-01"), employeeId, 2027).carriedInDays());
        assertThat(grantInto2027).isEqualByComparingTo("0.00");
    }

    @Test
    void aRequestLargerThanTheChosenPoolSpillsIntoTheOtherOneRatherThanBeingRejected() {
        // Spillover is the intended behaviour, explicitly NOT a rejection -- see
        // LeaveQuotaPoolPreference's Javadoc. 7 working days against OWN_FIRST: 2026's own 6.00 is
        // exhausted, and the remaining 1.00 falls to the carry-in pool rather than going unpaid.
        // Mon 2026-08-03 .. Tue 2026-08-11 = 7 working days (3,4,5,6,7,10,11).
        long employeeId = insertEmployee("V161-SPILL-001");
        serviceAt("2025-05-01").submit(
            submitRequest(employeeId, "VACATION", "2025-06-02", "2025-06-05"), employee(employeeId));

        LeaveRequestDto taken = serviceAt("2026-07-01").submit(
            submitRequest(employeeId, "VACATION", "2026-08-03", "2026-08-11",
                LeaveQuotaPoolPreference.OWN_FIRST),
            employee(employeeId));
        assertThat(taken.totalDays()).isEqualByComparingTo("7.00");
        // Fully paid: 7.00 fits inside the 8.00 both pools hold together.
        assertThat(taken.paidDays()).isEqualByComparingTo("7.00");
        assertThat(taken.unpaidDays()).isEqualByComparingTo("0.00");

        LeaveQuotaYearSplit split = leaveRepository().findQuotaYearSplits(taken.id()).get(0);
        assertThat(split.ownQuotaDays()).isEqualByComparingTo("6.00");
        assertThat(split.carriedInDays()).isEqualByComparingTo("1.00");
        // chk_lrqy_pool_matches_consumed, restated as an assertion: the two pools sum to exactly the
        // quota this row consumed. The DB would have rejected the INSERT otherwise, but asserting it
        // here names the invariant rather than relying on a constraint failing somewhere far away.
        assertThat(split.carriedInDays().add(split.ownQuotaDays()))
            .isEqualByComparingTo(split.quotaRemainingBefore().subtract(split.quotaRemainingAfter()));
    }

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

    // Hired 2015-01-01: comfortably clears VACATION's min_service_months floor (0, post-V120) and
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
