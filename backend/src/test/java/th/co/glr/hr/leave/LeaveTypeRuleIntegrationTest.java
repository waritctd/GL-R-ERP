package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * §5 leave-rules-as-data (V116): real-Postgres coverage of the per-leave-type rule columns and
 * their enforcement in {@link LeaveService#submit} -- the SQL (V116's seeded {@code hr.leave_type}
 * rows, the {@code chk_leave_type_*} constraints, and above all {@code
 * ux_leave_once_per_employment}) is exactly what Mockito-based {@code LeaveServiceTest} cannot
 * reach; see that class for the gate-by-gate unit coverage (Mockito can fake any {@link
 * LeaveTypeDto}, but not real calendar math or a real unique index).
 */
class LeaveTypeRuleIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    // Wednesday 2026-07-01 09:00 Asia/Bangkok -- matches the fixed clock used by the other leave
    // integration tests, so every date below is comfortably past every seeded advance-notice value
    // (SICK/MATERNITY/MILITARY/LEAVE_WITHOUT_PAY=0, PERSONAL=1, VACATION=3) unless a test is
    // specifically about notice.
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
    void aNinetyEightDayMaternityRequestSplitsIntoFortyFivePaidAndFiftyThreeUnpaidDays() {
        long employeeId = insertEmployee("MAT-001", LocalDate.parse("2015-01-01"));

        // Mon 2026-01-05 .. Sun 2026-04-12: exactly 98 CALENDAR days (verified independently of
        // LeaveDayMath: 27 remaining days of January + 28 of February + 31 of March + 12 of April =
        // 98), INCLUDING every Saturday/Sunday inside the range -- §5.4 MATERNITY calendar-day
        // counting (V119). Before V119, this same range would have counted as roughly 70 WORKING
        // days, not 98 -- the defect this migration fixes. MATERNITY's 98-day quota fully covers the
        // request, but its 45-day paid_days_cap (V116, now bounding CALENDAR days) is what actually
        // determines the split -- this is the gate the MATERNITY row exists to prove.
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MATERNITY", "2026-01-05", "2026-04-12"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("98.00");
        assertThat(result.paidDays()).isEqualByComparingTo("45.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("53.00");
        // Review fix regression guard: quotaRemainingAfter tracks QUOTA consumption (98-day request
        // against a 98-day quota -> 0 remaining), NOT the paid-cap-narrowed paidDays figure. Before
        // the fix this stored 98 - 45 = 53.00 here -- a number that lied about how much MATERNITY
        // quota was actually left, contradicted by the very next balances() call (which sums
        // total_days, not paid_days, and would report 0).
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void aMaternityRequestCountsCalendarDaysButAnIdenticalSickRequestCountsOnlyWorkingDays() {
        // §5.4 MATERNITY calendar-day counting (V119): the vacuous-fixture guard, through the REAL
        // repository/SQL this time (LeaveServiceTest's Mockito-level companion proves the same thing
        // against a faked LeaveTypeDto). Mon 2026-07-13 .. Sun 2026-07-19 -- a full week, both
        // weekend days included -- must count 7 for MATERNITY (every day) but only 5 for SICK
        // (Mon-Fri only).
        //
        // The SICK side is submitted without an attachment, so it AUTO_REJECTs on the medical-
        // certificate gate -- irrelevant here, since totalDays is computed BEFORE the auto-reject
        // gates run and stored unconditionally on every submission (LeaveService#submit).
        long maternityEmployeeId = insertEmployee("MAT-CAL-001", LocalDate.parse("2015-01-01"));
        long sickEmployeeId = insertEmployee("SICK-CAL-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto maternityResult = leaveService.submit(
            submitRequest(maternityEmployeeId, "MATERNITY", "2026-07-13", "2026-07-19"),
            employee(maternityEmployeeId));
        LeaveRequestDto sickResult = leaveService.submit(
            submitRequest(sickEmployeeId, "SICK", "2026-07-13", "2026-07-19"),
            employee(sickEmployeeId));

        assertThat(maternityResult.status()).isEqualTo("APPROVED");
        assertThat(maternityResult.totalDays()).isEqualByComparingTo("7.00");
        assertThat(sickResult.status()).isEqualTo("AUTO_REJECTED");
        assertThat(sickResult.totalDays()).isEqualByComparingTo("5.00");
        // The critical negative assertion: the two must NOT be equal on this identical date range.
        assertThat(sickResult.totalDays()).isNotEqualByComparingTo(maternityResult.totalDays());
    }

    @Test
    void aSeededTraditionalHolidayInsideAMaternityRangeIsStillJustOneCountedDay() {
        // §5.4's calendar-day counting explicitly names "วันหยุดตามประเพณี" (traditional/public
        // holidays) alongside weekly holidays. CALENDAR_DAYS counts every date in the range with no
        // hr.holiday lookup at all (see LeaveDayCountBasis's Javadoc) -- so a real seeded holiday
        // landing on an ordinary WEEKDAY inside the range changes nothing: it was already going to be
        // counted as a plain calendar day. This test proves the presence of a real hr.holiday row
        // does not cause a double-count, an exclusion, or any other special-cased behaviour -- Mon
        // 2026-07-13 .. Fri 2026-07-17 (5 calendar days) with Wed 2026-07-15 seeded as a company
        // holiday must still total exactly 5.
        long employeeId = insertEmployee("MAT-HOLIDAY-001", LocalDate.parse("2015-01-01"));
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES ('2026-07-15', 'วันหยุดทดสอบ', 'COMPANY')
            """, Map.of());

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MATERNITY", "2026-07-13", "2026-07-17"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("5.00");
    }

    @Test
    void aNinetyDayMilitaryRequestIsAcceptedWithSixtyPaidAndThirtyUnpaidDays() {
        // Defect 2 fix (V120): §5.5 caps only the PAY (60 days/year), not the leave itself -- V116
        // wrongly seeded annual_quota_days=60 with no paid_days_cap at all, which capped the LEAVE
        // at 60 days and refused anything past it. Fixed by moving the real 60-day limit onto
        // paid_days_cap and raising annual_quota_days to a 366-day sentinel that can never itself
        // bind (see V120's migration comment).
        //
        // MUTATION-CHECK NOTE: the paidDays/unpaidDays split (60/30) alone happens to come out
        // IDENTICAL whether the 60-day limit lives on paid_days_cap (fixed) or on annual_quota_days
        // (V116's defect) -- LeaveService#submit's approve-and-split design means both seedings
        // bound the paid portion at 60 either way for THIS ONE request. What genuinely
        // distinguishes them is quotaRemainingAfter: under the V116 seed the 90-day request would
        // exhaust the (wrongly 60-day) "quota" down to 0 remaining; under the fix, 90 days consumed
        // out of the 366-day sentinel leaves 276 remaining -- proving the leave itself was never
        // actually capped. Asserting ONLY paidDays/unpaidDays here would be a vacuous regression
        // guard for this specific defect (see CLAUDE.md's fixture-supplies-what-production-lacks
        // trap) -- quotaRemainingAfter is the assertion that actually pins the fix.
        long employeeId = insertEmployee("MIL-001", LocalDate.parse("2015-01-01"));

        // Mon 2026-01-05 .. Fri 2026-05-08: exactly 90 working weekdays (verified independently of
        // LeaveDayMath, same method as the MATERNITY test below).
        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "MILITARY", "2026-01-05", "2026-05-08"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("90.00");
        assertThat(result.paidDays()).isEqualByComparingTo("60.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("30.00");
        assertThat(result.quotaRemainingAfter()).isEqualByComparingTo("276.00");
    }

    @Test
    void ordinationLeaveWithinTheFifteenDayPaidCapIsFullyPaid() {
        // Wrong-way-round complement to the maternity test: a SHORT ordination request (10 of the
        // 60-day quota, under the 15-day paid cap) must be entirely paid -- the cap must not bind
        // when it doesn't need to.
        long employeeId = insertEmployee("ORD-CAP-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-07-13", "2026-07-24"), // 10 working days
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("10.00");
        assertThat(result.paidDays()).isEqualByComparingTo("10.00");
        assertThat(result.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void vacationQuotaIsProratedUnderOneYearOfServiceAndFullAfterOneYear() {
        // Defect 1 fix (V120): §5.3's parenthetical grants a PRO-RATED quota to an employee under a
        // year of service, not an outright refusal -- V116's original bug seeded
        // min_service_months=12 as a hard eligibility floor, which is what this test used to assert
        // (vacationIsRefusedBelowTheTwelveMonthServiceFloor, pre-V120). Both sides asserted on ONE
        // test, same request shape, so it cannot pass by only ever constructing the easy (>1 year)
        // case.
        long underOneYear = insertEmployee("VAC-PRORATE-001", LocalDate.parse("2026-01-13")); // 6 months before the request below
        long overOneYear = insertEmployee("VAC-PRORATE-002", LocalDate.parse("2015-01-01"));

        // 6 completed months of service -> prorated quota = 6.00 * 6/12 = 3.00 (rounded to the
        // nearest 0.5 day -- see LeaveService#employeeAnnualQuota's Javadoc for the formula and its
        // interpretation caveat). Mon 2026-07-13 .. Thu 2026-07-16 is 4 working days: exactly 3 of
        // them paid, 1 unpaid -- this pins the 3.00 prorated figure exactly (not zero, not the full
        // 6.00 the pre-V120 gate would have refused outright, and not the 6.00 an under-pro-rated
        // fix would have wrongly granted from day one).
        LeaveRequestDto underOneYearResult = leaveService.submit(
            submitRequest(underOneYear, "VACATION", "2026-07-13", "2026-07-16"),
            employee(underOneYear));
        assertThat(underOneYearResult.status()).isEqualTo("APPROVED");
        assertThat(underOneYearResult.totalDays()).isEqualByComparingTo("4.00");
        assertThat(underOneYearResult.paidDays()).isEqualByComparingTo("3.00");
        assertThat(underOneYearResult.unpaidDays()).isEqualByComparingTo("1.00");
        assertThat(underOneYearResult.quotaRemainingAfter()).isEqualByComparingTo("0.00");

        // Wrong-way-round complement, IDENTICAL request shape: an employee comfortably past a year
        // of service gets the FULL 6.00-day quota, so all 4 working days are paid.
        LeaveRequestDto overOneYearResult = leaveService.submit(
            submitRequest(overOneYear, "VACATION", "2026-07-13", "2026-07-16"),
            employee(overOneYear));
        assertThat(overOneYearResult.status()).isEqualTo("APPROVED");
        assertThat(overOneYearResult.paidDays()).isEqualByComparingTo("4.00");
        assertThat(overOneYearResult.unpaidDays()).isEqualByComparingTo("0.00");
    }

    @Test
    void vacationIsGrantedAtExactlyTwelveMonthsOfService() {
        // Wrong-way-round complement: hired exactly 12 completed months before the request start
        // date must be ELIGIBLE ("at least 12 months", not "more than 12").
        long employeeId = insertEmployee("VAC-EXACT-001", LocalDate.parse("2025-07-13"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void vacationIsRefusedWhenTheEmployeeHasNoHireDateOnFile() {
        // DECISION (V116): a NULL hire_date does NOT silently pass a min-service gate. This is the
        // real-DB proof that LeaveRepository#findHireDate's NULL mapping (rs.getObject returning
        // null -> Optional.empty()) actually reaches LeaveService's fail-closed branch -- a
        // Mockito-mocked repository could return Optional.empty() "correctly" even if the real SQL
        // NULL-handling were broken.
        long employeeId = insertEmployeeWithNoHireDate("VAC-NOHIRE-001");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "VACATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("hire date is not on file");
    }

    @Test
    void personalLeaveMayExceedThreeConsecutiveDaysOnceTheEmployeeHasClearedOneYearOfService() {
        // Defect 3 fix (V120): the 2561-era blanket max_consecutive_days=3 rule this test USED to
        // assert (personalLeaveIsRefusedWhenItSpansMoreThanThreeConsecutiveDays, pre-V120) applied to
        // EVERY employee regardless of tenure. The current (2567) announcement text dropped
        // "ติดต่อกัน" ("consecutive") and moved its 3-day figure INSIDE the under-one-year
        // parenthesis -- an owner-ruled ANNUAL TOTAL ceiling for under-1-year employees only, not a
        // per-request span limit for everyone (see LeaveService#autoRejectNote's first-year-max-days
        // gate). PERSONAL's max_consecutive_days is now NULL (V120), so this exact 4-calendar-day
        // span that used to be refused must now be approved for a >1-year employee, wrong-way-round
        // proof the old rule is genuinely gone, not merely narrowed.
        long employeeId = insertEmployee("PERSONAL-LONG-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-16"), // Mon-Thu, 4 working days
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("4.00");
        assertThat(result.paidDays()).isEqualByComparingTo("4.00");
    }

    @Test
    void personalLeaveIsGrantedAtExactlyThreeConsecutiveDays() {
        long employeeId = insertEmployee("PERSONAL-EXACT-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-15"), // 3 calendar days
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.totalDays()).isEqualByComparingTo("3.00");
        assertThat(result.paidDays()).isEqualByComparingTo("3.00");
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.2 PERSONAL first-year total-days cap (V120, defect 3). SEPARATE rule from pro-ration and
    // from the probation gate above -- see LeaveService#autoRejectNote's Javadoc. Composes with
    // pro-ration as effectiveCap = min(proratedQuota, firstYearMaxDays); both directions of that
    // min() are proven below, plus the wrong-way-round proof above that the OLD blanket
    // consecutive-day rule this replaces is genuinely gone for a >1-year employee.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void personalLeaveFirstYearCapIsBoundByTheProratedQuotaWhenItIsBelowThreeDays() {
        // Hired 2026-05-13, probation_days=30 (ends 2026-06-12, comfortably before the requests
        // below) -- isolates this test from the SEPARATE probation gate. 2 completed months of
        // service by 2026-07-13 -> prorated quota = 7.00 * 2/12 = 1.1667, rounded to the nearest 0.5
        // = 1.00 -- BELOW the flat 3-day ceiling, so the prorated figure is what actually binds
        // (effectiveCap = min(1.00, 3.00) = 1.00), not the flat 3.
        long employeeId = insertEmployee("PERS-CAP-LOW-001", LocalDate.parse("2026-05-13"), 30);

        LeaveRequestDto allowed = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"), // 1 working day
            employee(employeeId));
        assertThat(allowed.status()).isEqualTo("APPROVED");
        assertThat(allowed.paidDays()).isEqualByComparingTo("1.00");

        LeaveRequestDto refused = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-20", "2026-07-21"), // 2 working days
            employee(employeeId));
        assertThat(refused.status()).isEqualTo("AUTO_REJECTED");
        assertThat(refused.systemNote()).contains("day(s) total per year");
    }

    @Test
    void personalLeaveFirstYearCapIsBoundByTheFlatThreeDaysWhenTheProratedQuotaIsAboveIt() {
        // Hired 2025-10-13, probation_days=30 -- 9 completed months of service by 2026-07-13 ->
        // prorated quota = 7.00 * 9/12 = 5.25, rounded to the nearest 0.5 = 5.50 -- ABOVE the flat
        // 3-day ceiling, so the flat figure is what actually binds (effectiveCap = min(5.50, 3.00) =
        // 3.00), the other side of the min() from the test above.
        long employeeId = insertEmployee("PERS-CAP-HIGH-001", LocalDate.parse("2025-10-13"), 30);

        LeaveRequestDto allowed = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-15"), // 3 working days
            employee(employeeId));
        assertThat(allowed.status()).isEqualTo("APPROVED");
        assertThat(allowed.paidDays()).isEqualByComparingTo("3.00");

        LeaveRequestDto refused = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-20", "2026-07-23"), // 4 working days
            employee(employeeId));
        assertThat(refused.status()).isEqualTo("AUTO_REJECTED");
        assertThat(refused.systemNote()).contains("day(s) total per year");
    }

    @Test
    void personalLeaveIsRefusedWithLessThanOneWorkingDayOfNotice() {
        // FIXED_NOW is Wed 2026-07-01 09:00 Bangkok; PERSONAL requires 1 day of notice. Requesting
        // leave for 2026-07-01 itself (same day, zero notice) must be refused.
        long employeeId = insertEmployee("PERSONAL-NOTICE-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-01", "2026-07-01"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("at least 1 day(s)");
    }

    @Test
    void ordinationCanBeUsedOnceThenIsRefusedForAnySubsequentRequestJavaLevel() {
        long employeeId = insertEmployee("ORD-ONCE-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto first = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeId));
        assertThat(first.status()).isEqualTo("APPROVED");

        // A second ORDINATION request, in a LATER quota year even -- once-per-employment is NOT
        // per-year -- must be refused by the Java-level check (hasOutstandingOrGrantedRequest).
        LeaveRequestDto second = leaveService.submit(
            submitRequest(employeeId, "ORDINATION", "2026-08-10", "2026-08-11"),
            employee(employeeId));

        assertThat(second.status()).isEqualTo("AUTO_REJECTED");
        assertThat(second.systemNote()).contains("once during your employment");
    }

    @Test
    void aDifferentEmployeesFirstOrdinationRequestIsUnaffectedByAnotherEmployeesGrant() {
        // Wrong-way-round complement: the once-per-employment guard is scoped PER employee, not
        // global -- proves the SQL predicate includes employee_id, not just leave_type_code.
        long employeeA = insertEmployee("ORD-A-001", LocalDate.parse("2015-01-01"));
        long employeeB = insertEmployee("ORD-B-001", LocalDate.parse("2015-01-01"));

        LeaveRequestDto forA = leaveService.submit(
            submitRequest(employeeA, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeA));
        assertThat(forA.status()).isEqualTo("APPROVED");

        LeaveRequestDto forB = leaveService.submit(
            submitRequest(employeeB, "ORDINATION", "2026-07-13", "2026-07-14"),
            employee(employeeB));
        assertThat(forB.status()).isEqualTo("APPROVED");
    }

    @Test
    void theDatabaseItselfRefusesASecondLiveOrdinationClaimEvenBypassingTheJavaCheck() {
        // The test above proves the NORMAL path (LeaveService's Java-level pre-check). This proves
        // the OTHER half the task calls for: that ux_leave_once_per_employment (V116) is a REAL
        // constraint, not just documentation -- by calling LeaveRepository#create directly, TWICE,
        // bypassing LeaveService#autoRejectNote entirely (the same way two concurrent requests would
        // both pass the Java check before either commits). If this constraint were ever dropped or
        // its WHERE clause narrowed, this is the test that would catch it; LeaveServiceTest's
        // DuplicateKeyException-catch test only proves the CATCH works, not that the INDEX exists.
        long employeeId = insertEmployee("ORD-RACE-001", LocalDate.parse("2015-01-01"));

        long firstId = createOrdinationRequestDirectly(employeeId, "2026-07-13", "2026-07-14", LeaveStatus.SUBMITTED);
        assertThat(firstId).isPositive();

        assertThatThrownBy(() ->
            createOrdinationRequestDirectly(employeeId, "2026-08-10", "2026-08-11", LeaveStatus.SUBMITTED))
            .isInstanceOf(DataAccessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────
    // §5.2 PERSONAL "passed probation" gate (review fix, V116). Real-DB proof that
    // LeaveRepository#findProbationDays' NULL-column mapping and LeaveService's
    // hire_date+probation_days arithmetic hold through the actual repository -- Mockito can fake
    // any Optional<Integer> a test wants, but not whether the SQL genuinely reads NULL as
    // Optional.empty() the way LeaveServiceTest's fallback tests assume.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void personalLeaveIsRefusedWhileTheEmployeeIsStillInProbation() {
        long employeeId = insertEmployee("PERS-PROB-001", LocalDate.parse("2026-06-20"), 90);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("passed probation");
    }

    @Test
    void personalLeaveIsGrantedImmediatelyWhenProbationDaysIsZeroOnTheEmployee() {
        // Wrong-way-round complement: probation_days = 0 on the real employee row must mean
        // eligible from the hire date itself (no ADDITIONAL waiting period), proven through the
        // actual SQL read, not a mocked one.
        //
        // V120 REVISION: this used to hire the employee on the EXACT request date (0 days of
        // service at request time). That combination is no longer representable together with an
        // APPROVED outcome now that PERSONAL is also prorated_first_year (V120, defect 1/3 fix) --
        // 0 completed months of service prorates to a genuine 0.00-day quota by
        // LeaveService#employeeAnnualQuota's own formula, which the NEW first-year-total-days cap
        // gate (defect 3) would then correctly refuse, for a DIFFERENT reason than probation. Hiring
        // 2 completed months before the request keeps this test isolated to the probation gate
        // (still trivially passed -- probation_days=0 means probationEndsOn == hire date, which is
        // always on/before any later request date) while giving pro-ration a non-zero (1.00-day)
        // quota that comfortably covers the single-day request, so this test cannot be confused with
        // the separate first-year-cap gate's own dedicated tests below.
        long employeeId = insertEmployee("PERS-PROB-002", LocalDate.parse("2026-05-01"), 0);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void personalLeaveFallsBackToTheDefaultProbationPeriodWhenProbationDaysIsNullOnTheEmployee() {
        // probation_days genuinely NULL in the database (not just an unstubbed mock) -> falls back
        // to SpecialMoneyPolicyEvaluator.DEFAULT_PROBATION_DAYS (119). Hired exactly 119 days before
        // the request date -> probation ends ON the request date -- "at least", not "strictly more
        // than" -- so this must be APPROVED.
        long employeeId = insertEmployee("PERS-PROB-003", LocalDate.parse("2026-03-16"), null);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void personalLeaveIsRefusedOneDayBeforeTheDefaultProbationPeriodEndsWhenProbationDaysIsNull() {
        // Same NULL-probation_days fallback, pinned from the other side: hired one day later (118
        // completed days, not 119) than the passing case above -> still short -> AUTO_REJECTED.
        long employeeId = insertEmployee("PERS-PROB-004", LocalDate.parse("2026-03-17"), null);

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("passed probation");
    }

    @Test
    void personalLeaveIsRefusedWhenTheEmployeeHasNoHireDateOnFileEitherForProbation() {
        // Real-DB companion to LeaveServiceTest's Mockito version -- proves a genuinely NULL
        // hire_date column does not silently pass PERSONAL's probation gate either (a separate code
        // path from the generic min-service NULL-hire_date check covered by
        // vacationIsRefusedWhenTheEmployeeHasNoHireDateOnFile above).
        long employeeId = insertEmployeeWithNoHireDate("PERS-PROB-005");

        LeaveRequestDto result = leaveService.submit(
            submitRequest(employeeId, "PERSONAL", "2026-07-13", "2026-07-13"),
            employee(employeeId));

        assertThat(result.status()).isEqualTo("AUTO_REJECTED");
        assertThat(result.systemNote()).contains("hire date is not on file");
    }

    // --- helpers ------------------------------------------------------------

    private SubmitLeaveRequest submitRequest(long employeeId, String leaveTypeCode, String startDate, String endDate) {
        return new SubmitLeaveRequest(employeeId, leaveTypeCode, LocalDate.parse(startDate), LocalDate.parse(endDate), "Integration test leave");
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private long insertEmployee(String code, LocalDate hireDate) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate)
            RETURNING employee_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("hireDate", hireDate), Long.class);
    }

    /**
     * probation_days is genuinely nullable on hr.employee (V1) -- pass {@code null} to leave it
     * NULL in the database and exercise LeaveRepository#findProbationDays' real NULL-column
     * mapping, not a mocked Optional.empty().
     */
    private long insertEmployee(String code, LocalDate hireDate, Integer probationDays) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date, probation_days)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, :hireDate, :probationDays)
            RETURNING employee_id
            """, new MapSqlParameterSource()
            .addValue("code", code)
            .addValue("hireDate", hireDate)
            .addValue("probationDays", probationDays),
            Long.class);
    }

    private long insertEmployeeWithNoHireDate(String code) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, current_salary, is_active, hire_date)
            VALUES (:code, :code, 'ทดสอบ', 30000, TRUE, NULL)
            RETURNING employee_id
            """, Map.of("code", code), Long.class);
    }

    /**
     * Calls {@link LeaveRepository#create} directly, bypassing every {@link LeaveService} check --
     * the point is to prove {@code ux_leave_once_per_employment} fires on its own, not that
     * LeaveService's Java-level guard works (that is covered elsewhere).
     */
    private long createOrdinationRequestDirectly(long employeeId, String startDate, String endDate, LeaveStatus status) {
        SubmitLeaveRequest request = submitRequest(employeeId, "ORDINATION", startDate, endDate);
        return leaveRepository.create(
            employeeId,
            employeeId,
            request,
            new BigDecimal("2.00"),
            new BigDecimal("2.00"),
            BigDecimal.ZERO,
            LocalDate.parse(startDate).getYear(),
            status,
            new BigDecimal("60.00"),
            new BigDecimal("58.00"),
            null,
            null, null, null, null, null
        );
    }
}
