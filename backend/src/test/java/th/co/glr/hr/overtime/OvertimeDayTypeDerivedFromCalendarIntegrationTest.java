package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.attendance.schedule.TieredWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.WorkScheduleAssignmentRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix: {@code day_type}/{@code pay_rate_multiplier} must be DERIVED, never DECLARED by the
 * caller. Before the original fix, {@code SubmitOvertimeRequest} carried a client-supplied {@code
 * dayType} field that {@code OvertimeService.parseDayType} wrote straight into {@code
 * overtime_request.pay_rate_multiplier} at INSERT, and neither approval stage ever checked it
 * against a calendar — an employee could self-declare HOLIDAY (3.00x) on an ordinary Tuesday and be
 * overpaid 0.5x-2x what the work was worth, with nothing in the approval UI to contradict the lie.
 *
 * <p><b>feat/ot-nonworkday-rate-suggestion (this branch) is a POLICY CHANGE on top of that fix, on
 * explicit owner instruction</b> — see this fix's PR body for the full model. Three distinct
 * values, previously conflated into one:
 *
 * <table>
 *   <caption>suggested / requested / approved day type</caption>
 *   <tr><th>value</th><th>who sets it</th><th>becomes pay?</th></tr>
 *   <tr><td>suggested</td><td>system: {@code hr.holiday} OR the employee's resolved {@code
 *       WorkSchedule} non-workday</td><td>no</td></tr>
 *   <tr><td>requested</td><td>employee — pre-filled with the suggestion, freely changeable</td>
 *       <td>no</td></tr>
 *   <tr><td>approved</td><td>the approver, defaulted to the suggestion</td><td><b>yes</b></td></tr>
 * </table>
 *
 * <p>The security property from the original fix survives unchanged: a submitter can never move
 * their own money, because their field is a REQUEST, not a pay input, at every stage. What changed
 * is that the SERVER's own derivation also stops being a pay input by itself — an approver now
 * stands between every non-workday and its 3x (Cases 6-9 below). This class's Javadoc used to
 * assert the service "never looks at day-of-week" (see {@link #aWeekdayWithNoHolidayRow}'s old
 * Javadoc) — that is now FALSE for the SUGGESTION (day-of-week, via the employee's resolved
 * schedule, is exactly what makes a weekend suggest HOLIDAY), and this class's fixtures were
 * updated accordingly.
 *
 * <p>Drives the real {@link OvertimeService}, {@link OvertimeRepository}, {@link
 * DbHolidayCalendar} AND (new, this branch) the real {@link TieredWorkScheduleResolver} /
 * {@link WorkScheduleAssignmentRepository} against real Postgres end to end (submit → manager/CEO
 * approve → payroll read) — Mockito cannot reach the repository SQL, the schedule-resolution SQL,
 * or the money {@code PayrollRepository#findApprovedOvertimePayByEmployee} actually computes.
 *
 * <p>Every case is written wrong-way-round: it asserts the wrong multiplier was NEVER stored and
 * NEVER paid (or that no row exists at all), not merely that some request was rejected.
 *
 * <p>MUTATION-CHECK RECORD, Cases 1-3 (derivation itself, ported from the reference branch this
 * class was ported from; actually run there, not simulated — see that branch's history for the
 * full transcript). Three separate mutations, each reverted before the next:
 *
 * <p><b>Mutation 1</b>: {@code OvertimeService#suggestDayType} changed to unconditionally {@code
 * return OvertimeDayType.WORKDAY;} (ignoring {@code holidayCalendar}). 3 of the 7 Case-1/2/3 tests
 * went red — {@code genuineHolidayGetsTheHolidayRate}, {@code
 * aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval}, and {@code
 * aMistakenHolidayEntryRemovedAfterSubmitIsPickedUpAndFrozenAtManagerApproval} — each "expected:
 * HOLIDAY but was: WORKDAY".
 *
 * <p><b>Mutation 2</b>: {@code suggestDayType} changed to unconditionally {@code return
 * OvertimeDayType.HOLIDAY;} instead. 6 of 7 went red — every test except {@code
 * genuineHolidayGetsTheHolidayRate} (which expects HOLIDAY anyway) — each "expected: WORKDAY but
 * was: HOLIDAY".
 *
 * <p><b>Mutation 3</b>, targeting {@code
 * aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff} specifically: added {@code
 * dayType} re-derivation to the FINAL {@code ceoApprove} stage. Exactly 1 of 7 went red — that one
 * test, at its final assertion — confirming this mutation was cleanly isolated to the one property
 * it protects (that {@code ceoApprove} never re-derives).
 *
 * <p>MUTATION-CHECK RECORD, Case 4 (the claim-validation layer — THIS repo's divergence from the
 * ported branch, actually run in this session):
 *
 * <p><b>Mutation 4</b>: deleted the {@code if (!holidayCalendar.isHoliday(workDate)) throw ...}
 * guard inside {@code resolveDayTypeSubmitNote} (the branch that refused a HOLIDAY claim the
 * calendar could actively disprove — REMOVED entirely on this branch, see below). Superseded.
 *
 * <p><b>Mutation 5</b>: deleted the {@code if (!holidayCalendar.hasHolidaysForYear(...))} guard (the
 * branch that flags, rather than trusts blindly, a HOLIDAY claim for a year the calendar has never
 * been loaded for), so an unverifiable claim would fall through to the calendar-loaded check instead
 * of being flagged. See this session's final report for the exact test(s) that reddened.
 *
 * <p><b>Mutation 6</b>: deleted the {@code isDayTypeFlagNote} guard inside {@code
 * preserveDayTypeClaimFlag}, so approval would unconditionally overwrite {@code calculation_note}
 * instead of appending to a submit-time flag. See this session's final report for the exact
 * test(s) that reddened.
 *
 * <p>MUTATION-CHECK RECORD, Case 4 follow-up (widening the flag to fire regardless of the claim —
 * fixed in a later session; see {@code OvertimeServiceTest} for the unit tests it was run against):
 *
 * <p><b>Mutation 7</b>: reverted {@code resolveDayTypeSubmitNote}'s calendar-unverified check to
 * fire only when the claim is HOLIDAY — i.e. back to this class's original Case-4 shape.
 *
 * <p><b>Mutation 8</b>: made the unverified-calendar note unconditional (returned regardless of
 * {@code hasHolidaysForYear}), collapsing the distinction this fix exists to draw.
 *
 * <p>MUTATION-CHECK RECORD, THIS BRANCH (feat/ot-nonworkday-rate-suggestion) — see this session's
 * final report for the exact test(s) that reddened per mutation, and confirmation that reverting
 * each mutation returned the diff to empty:
 *
 * <p><b>Mutation 9</b> (the schedule half of the suggestion): {@code suggestDayType}'s
 * bulk/single-row overload changed to ignore {@code schedule.isWorkday(workDate)} and always
 * return WORKDAY when not a recorded holiday (i.e. reverted to the pre-branch holiday-only rule).
 *
 * <p><b>Mutation 10</b> (the approver override): {@link OvertimeService#calculate} changed to
 * ignore {@code approverDayType} unconditionally, always falling back to {@code suggestDayType}.
 *
 * <p><b>Mutation 11</b> (the removed refusal / claim-never-becomes-pay guarantee): {@code
 * submit}/{@code create} changed to store the CALLER's claim instead of the suggestion.
 *
 * <p><b>Mutation 12</b> (the freeze point): added {@code dayType} honouring to {@code ceoApprove}
 * (the SECOND approval stage), which must stay frozen from whatever {@code managerApprove} decided.
 */
class OvertimeDayTypeDerivedFromCalendarIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    /**
     * Deliberately far enough in the future that no other fixture in this class (all pinned to
     * "now + 10 days", i.e. the current year) could plausibly collide with it. Each {@code @Test}
     * already starts from a freshly cloned, fully-migrated database ({@code
     * AbstractPostgresIntegrationTest#resetSchema}, {@code @BeforeEach} — see that class's Javadoc),
     * so no row for this year can leak in from another test method or class. The far-future year plus
     * the defensive DELETE in {@link #aFarFutureYearWeekdayWithNoHolidayRow} is belt-and-suspenders
     * on top of that guarantee, not a substitute for it — see this fix's PR body: a "zero rows for
     * the year" fixture that turned out not to be zero would make that test vacuous.
     */
    private static final int FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA = 2099;

    private OvertimeService overtimeService;

    private long division;
    private long manager;
    private long staff;

    // A separate division with nobody in a "ผู้จัดการ" position -- the manager-less route
    // (OvertimeService#ceoDirectApprove), which needs its own proof that it also derives/freezes.
    private long managerlessDivision;
    private long managerlessStaff;

    // feat/ot-nonworkday-rate-suggestion: a six-day (OPS_6D) division, so Cases 6-7 below can prove
    // the schedule TIERING is what actually resolves -- not a naive day-of-week check that would
    // get this division's Saturday wrong. "OT6" is a source_code that collides with none of V115's
    // seeded OFFICE_5D/OPS_6D division codes ('HR','AC','PCIM','AM','SADS','QC','MD','MN','SA','SR'
    // / 'WH','PD','SV'), so this assignment is the ONLY thing that gives this division a schedule.
    private long opsSixDayDivision;
    private long opsSixDayManager;
    private long opsSixDayStaff;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        WorkScheduleAssignmentRepository scheduleRepository =
            new WorkScheduleAssignmentRepository(jdbc, properties);
        TieredWorkScheduleResolver scheduleResolver = new TieredWorkScheduleResolver(
            scheduleRepository, new CompanyWideWorkScheduleResolver(properties), properties);
        overtimeService = new OvertimeService(
            new OvertimeRepository(jdbc),
            new ManagerApproverRepository(jdbc),
            mock(AuditService.class),
            mock(NotificationService.class),
            properties,
            mock(AttendanceDailyService.class),
            new DbHolidayCalendar(jdbc),
            scheduleResolver, new CeoApproverRepository(jdbc));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", division, null, "ผู้จัดการฝ่ายขาย");
        staff = insertEmployee("S001", division, manager, null);

        managerlessDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        managerlessStaff = insertEmployee("F001", managerlessDivision, null, null);

        // Neither "SLS" nor "FAC" matches any V115-seeded OFFICE_5D/OPS_6D source_code, so `staff`
        // and `managerlessStaff` both fall through to CompanyWideWorkScheduleResolver's Mon-Fri
        // default -- exactly the "Mon-Fri employee" shape Cases 6/8/9 below need.
        opsSixDayDivision = insertDivision("OT6", "ฝ่ายทดสอบหกวัน");
        assignDivisionSchedule(opsSixDayDivision, "OPS_6D", LocalDate.of(2024, 10, 1), null);
        opsSixDayManager = insertEmployee("M006", opsSixDayDivision, null, "ผู้จัดการฝ่ายทดสอบหกวัน");
        opsSixDayStaff = insertEmployee("S006", opsSixDayDivision, opsSixDayManager, null);
    }

    // -------------------------------------------------------------------------------------------
    // Case 1: an ordinary weekday with no hr.holiday row must resolve to WORKDAY -- and even when a
    // caller supplies no claim at all (the request(workDate, employeeId) overload below never sets
    // one), pay is still exclusively calendar/schedule-derived.
    // -------------------------------------------------------------------------------------------

    /**
     * The label an approver would see must be honest from the moment the row is created — see
     * {@code OvertimeService#submit}'s comment. Asserted directly against the stored columns,
     * before any approval runs.
     */
    @Test
    void weekdayOvertimeWithNoCalendarEntryStoresWorkdayMultiplier() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();

        OvertimeRequestDto created = overtimeService.submit(request(workDate, staff), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("WORKDAY");
        assertThat(dayTypeOf(created.id())).isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");
    }

    /**
     * THE headline scenario from the defect report, reproduced with its exact figures: ฿30,000/month
     * -> hourly base 30000/30/8 = ฿125. 3 hours (18:00-21:00) on an ordinary weekday. Correct pay
     * is 3 x 125 x 1.50 = ฿562.50. The pre-fix bug let a caller declare HOLIDAY here and be paid
     * 3 x 125 x 3.00 = ฿1,125.00 — ฿562.50 overpaid on this one request. Runs the full submit ->
     * manager approve -> CEO approve chain and reads the money the same way payroll does, not a
     * stand-in calculation.
     */
    @Test
    void weekdayOvertimeWithNoCalendarEntryIsPaidAtWorkdayRate() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        overtimeService.approve(id, approve(), directManager());
        overtimeService.approve(id, approve(), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .as("3h x (30000/30/8) x 1.50 -- NOT x 3.00")
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    /**
     * The manager-less route ({@code ceoDirectApprove}) is a separate repository method from {@code
     * managerApprove} with its own SQL -- it needs its own proof it also derives and freezes from
     * the calendar, not an assumption that fixing one fixed both.
     */
    @Test
    void ceoDirectApprovalDerivesFromTheCalendarForManagerlessEmployeesToo() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, managerlessStaff, "F001");

        long id = overtimeService.submit(request(workDate, managerlessStaff), employee(managerlessStaff)).id();
        overtimeService.approve(id, approve(), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), managerlessStaff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 2: the fix is not "always 1.5" -- a genuine calendar holiday still pays the holiday
    // rate, with no claim made at all.
    // -------------------------------------------------------------------------------------------

    @Test
    void genuineHolidayGetsTheHolidayRate() {
        LocalDate holiday = aWeekdayWithNoHolidayRow();
        insertHoliday(holiday, "วันหยุดทดสอบ");
        insertPunchesCovering(holiday, staff, "S001");

        long id = overtimeService.submit(request(holiday, staff), employee(staff)).id();
        overtimeService.approve(id, approve(), directManager());
        overtimeService.approve(id, approve(), ceo());

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
        assertThat(overtimePayFor(holiday.withDayOfMonth(1), staff))
            .as("3h x (30000/30/8) x 3.00 -- the real holiday rate, not 1.50")
            .isEqualByComparingTo(new BigDecimal("1125.00"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 3: the calendar can change between submit and approval (HR corrects it), same shape of
    // problem requirePayrollMonthOpen already guards against for the payroll-month gate. Proves
    // the design choice to re-derive AND FREEZE at approval (OvertimeService#calculate), not only
    // at submit.
    // -------------------------------------------------------------------------------------------

    @Test
    void aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        // HR adds a late-breaking / corrected holiday entry before anyone has approved yet.
        insertHoliday(workDate, "ประกาศเพิ่มเติม");

        overtimeService.approve(id, approve(), directManager());

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
    }

    @Test
    void aMistakenHolidayEntryRemovedAfterSubmitIsPickedUpAndFrozenAtManagerApproval() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertHoliday(workDate, "รายการผิดพลาด");
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");

        // HR discovers the calendar entry was a mistake and removes it before anyone approves.
        deleteHoliday(workDate);

        overtimeService.approve(id, approve(), directManager());

        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
    }

    /**
     * The other half of "runs once": once manager approval has frozen the value, the final CEO
     * sign-off must NOT silently re-price the request out from under what the manager approved --
     * matching how {@code payable_minutes}/{@code salary_basis} are already frozen at manager
     * approval and never revisited by {@code ceoApprove}. A calendar change after manager approval
     * must not move money a human has already signed off on.
     *
     * <p>Mutation-testing note (see this class's own MUTATION-CHECK RECORD, mutation 2): a global
     * "make {@code suggestDayType} always wrong" mutation trips this test's EARLIER assertion (right
     * after manager approval), not the late-change assertion this test exists to prove -- {@code
     * suggestDayType} is also called by {@code submit()}/{@code managerApprove()}, which this test
     * exercises first. Isolating the actual claim ("{@code ceoApprove} does not re-derive") needs a
     * mutation targeted at {@code ceoApprove} specifically (mutation 12), not at {@code
     * suggestDayType} globally.
     */
    @Test
    void aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        overtimeService.approve(id, approve(), directManager());
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        // Too late: the manager has already approved and frozen WORKDAY/1.50.
        insertHoliday(workDate, "สายเกินไป");

        overtimeService.approve(id, approve(), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 4: the claim-validation layer -- SubmitOvertimeRequest.dayType is a REQUEST the server
    // validates but NEVER trusts for pay, at any stage. feat/ot-nonworkday-rate-suggestion changed
    // WHAT HAPPENS when the claim disagrees with the suggestion (accepted + flagged now, where it
    // used to be refused outright for the over-claim direction) -- see
    // OvertimeService#resolveDayTypeSubmitNote's Javadoc for the decision table these cases
    // exercise, and this file's header Javadoc for the three-value model.
    // -------------------------------------------------------------------------------------------

    /**
     * feat/ot-nonworkday-rate-suggestion: THE row this branch changes from the original decision
     * table. Previously this exact scenario (a HOLIDAY claim the calendar can actively disprove)
     * was refused outright — 400, no row created (see this class's git history for the deleted
     * {@code holidayClaimContradictedByTheCalendarIsRejectedAndCreatesNoRow}, this test's
     * predecessor). Owner ruling 2026-08-08: the employee may submit a claim that disagrees with
     * the suggestion and still have it accepted; the disagreement is flagged for the approver
     * instead of refused.
     *
     * <p>Wrong-way-round, the property that matters most here: the claimed HOLIDAY/3.00 must NEVER
     * reach {@code pay_rate_multiplier} — checked all the way through to final approval, with the
     * approver supplying NO override, so the approver's DEFAULT (the suggestion, WORKDAY/1.50) is
     * what actually gets paid, never the employee's claim.
     */
    @Test
    void holidayClaimContradictedByTheCalendarIsAcceptedAndFlaggedNotRejected() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        // The calendar IS loaded for this year (at least one row) -- but not on workDate. Otherwise
        // the calendar-unverified flag (not the disagreement flag) would be what fires, which is a
        // different case (see holidayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlagged below).
        LocalDate sentinelHolidayDate = workDate.getDayOfYear() == 1
            ? workDate.plusDays(1)
            : workDate.withDayOfYear(1);
        insertHoliday(sentinelHolidayDate, "วันหยุดอื่นในปีเดียวกัน");
        insertPunchesCovering(workDate, staff, "S001");

        OvertimeRequestDto created = overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("WORKDAY");
        assertThat(dayTypeOf(created.id()))
            .as("the claim never reaches the stored day_type, even at submit time")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");
        assertThat(calculationNoteOf(created.id()))
            .as("accepted and flagged for the approver, never refused")
            .isNotNull()
            .contains("ไม่ตรงกับที่ระบบแนะนำ");

        overtimeService.approve(created.id(), approve(), directManager());
        overtimeService.approve(created.id(), approve(), ceo());

        assertThat(dayTypeOf(created.id()))
            .as("the claim never reaches pay_rate_multiplier, even after full approval with no override")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .as("3h x (30000/30/8) x 1.50 -- NOT the claimed 3.00")
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    /** The fix is not "always refuse a HOLIDAY claim" -- one the calendar corroborates is accepted at the real rate, and needs no flag. */
    @Test
    void holidayClaimCorroboratedByTheCalendarIsAcceptedAtTheHolidayRateWithNoFlag() {
        LocalDate holiday = aWeekdayWithNoHolidayRow();
        insertHoliday(holiday, "วันหยุดทดสอบ");

        long id = overtimeService.submit(request(holiday, staff, "HOLIDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
        assertThat(calculationNoteOf(id)).isNull();
    }

    /**
     * The calendar has never been loaded for the work date's year AT ALL (zero rows -- distinct
     * from "loaded, and this date isn't in it", see {@code HolidayCalendar#hasHolidaysForYear}'s
     * Javadoc) -- the claim can be neither confirmed nor refused, so it is accepted (money still
     * comes exclusively from {@code suggestDayType}, i.e. WORKDAY here) but flagged in {@code
     * calculation_note} for HR to review once the calendar is populated. The note names the
     * unloaded YEAR, not the claim -- see {@link #workdayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlaggedToo}
     * below for proof this fires just as well with no HOLIDAY claim in sight.
     */
    @Test
    void holidayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlagged() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id))
            .as("an unverifiable claim never becomes pay -- suggestDayType alone decides, and the calendar/schedule say WORKDAY")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(calculationNoteOf(id))
            .as("the note is about the CALENDAR (names the year), not the claim -- it no longer mentions HOLIDAY at all, see resolveDayTypeSubmitNote's Javadoc")
            .isNotNull()
            .contains(String.valueOf(FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA));
    }

    /**
     * THE exact gap the original fix closed, proved end to end against real Postgres (the
     * unit-level proof, including its own mutation-check, lives in {@code OvertimeServiceTest}).
     * Before that fix, an unloaded calendar's silence was flagged ONLY when the caller happened to
     * claim HOLIDAY -- which {@code OvertimePanel.jsx}'s submit dropdown never defaults to. A
     * WORKDAY claim (the dropdown's default) in a year the calendar has zero rows for must ALSO
     * carry the flag: it is {@code suggestDayType}'s own WORKDAY default that is unverified, not the
     * claim. Written wrong-way-round: asserts the note is PRESENT where the pre-fix code left it
     * absent.
     */
    @Test
    void workdayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlaggedToo() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(request(workDate, staff, "WORKDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(calculationNoteOf(id))
            .as("a WORKDAY claim -- OvertimePanel.jsx's dropdown default -- must be flagged too: the suggestion is unverified independent of what was claimed")
            .isNotNull()
            .contains(String.valueOf(FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA));
    }

    /**
     * The claim can under-state the true day type without consequence -- {@code suggestDayType}
     * corrects it upward regardless, exactly as it already does for a NO-claim submission (Case 3
     * above proves the calendar-change direction; this proves the claim itself cannot suppress real
     * holiday pay by declaring WORKDAY on a day the calendar says is a HOLIDAY).
     */
    @Test
    void workdayClaimOnAGenuineHolidayIsStoredAsHolidayRegardlessOfTheUnderclaim() {
        LocalDate holiday = aWeekdayWithNoHolidayRow();
        insertHoliday(holiday, "วันหยุดทดสอบ");

        long id = overtimeService.submit(request(holiday, staff, "WORKDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
    }

    /**
     * {@code calculation_note} is written at BOTH submit time (a day-type flag, when one fires) and
     * approval time ({@code OvertimeService#calculate}'s attendance-derived note) -- and {@code
     * managerApprove}/{@code ceoDirectApprove} overwrite the column wholesale. Without the
     * append-not-clobber handling in {@code OvertimeService#preserveDayTypeClaimFlag}, approval would
     * silently erase the flag the approver was supposed to review.
     */
    @Test
    void managerApprovalPreservesTheSubmitTimeUnverifiedClaimFlagInsteadOfClobberingIt() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff)).id();
        String submitTimeNote = calculationNoteOf(id);
        assertThat(submitTimeNote).isNotNull();

        overtimeService.approve(id, approve(), directManager());

        assertThat(calculationNoteOf(id))
            .as("the submit-time flag must survive approval, alongside the new approval-time note -- not be replaced by it")
            .contains(submitTimeNote)
            .contains("Calculated from the overlap");
    }

    /**
     * {@code ceoDirectApprove} is a SEPARATE {@code UPDATE} statement from {@code managerApprove}
     * (see {@code OvertimeRepository}) -- each independently overwrites {@code calculation_note}
     * wholesale, so proving the append-not-clobber behaviour on the manager route says nothing
     * about the manager-less route; it needs its own proof, same as Case 1's {@code
     * ceoDirectApprovalDerivesFromTheCalendarForManagerlessEmployeesToo} above. Otherwise identical
     * to {@link #managerApprovalPreservesTheSubmitTimeUnverifiedClaimFlagInsteadOfClobberingIt},
     * through the manager-less employee / CEO-direct route instead.
     */
    @Test
    void ceoDirectApprovalPreservesTheSubmitTimeUnverifiedClaimFlagInsteadOfClobberingIt() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, managerlessStaff, "F001");

        long id = overtimeService.submit(request(workDate, managerlessStaff, "HOLIDAY"), employee(managerlessStaff)).id();
        String submitTimeNote = calculationNoteOf(id);
        assertThat(submitTimeNote).isNotNull();

        overtimeService.approve(id, approve(), ceo());

        assertThat(calculationNoteOf(id))
            .as("the submit-time flag must survive ceoDirectApprove too, alongside the new approval-time note -- not be replaced by it")
            .contains(submitTimeNote)
            .contains("Calculated from the overlap");
    }

    /**
     * THE wrong-way-round case CLAUDE.md's authorization-evidence rule calls out by name, and the
     * one this fix's task brief flags as "must not be skipped": a claim from the SUBMITTER never
     * reaches {@code pay_rate_multiplier}, proven through the manager-LESS / CEO-direct route too --
     * a SEPARATE {@code UPDATE} from {@code managerApprove}'s (see {@code
     * OvertimeRepository#ceoDirectApprove}), so proving the property on one route says nothing
     * about the other.
     */
    @Test
    void aSubmittedDayTypeClaimNeverReachesPayRateMultiplierOnTheManagerlessRouteEither() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        LocalDate sentinelHolidayDate = workDate.getDayOfYear() == 1
            ? workDate.plusDays(1)
            : workDate.withDayOfYear(1);
        insertHoliday(sentinelHolidayDate, "วันหยุดอื่นในปีเดียวกัน");
        insertPunchesCovering(workDate, managerlessStaff, "F001");

        long id = overtimeService.submit(request(workDate, managerlessStaff, "HOLIDAY"), employee(managerlessStaff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        overtimeService.approve(id, approve(), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id))
            .as("the claim never reaches pay_rate_multiplier on the manager-less route either")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), managerlessStaff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    /**
     * Authorization evidence, wrong-way-round: an actor forbidden from approving THIS request
     * cannot move money through the NEW {@link ApproveOvertimeRequest#dayType} field either --
     * {@code requireManager}'s self-exclusion must refuse the attempt before {@code
     * parseOvertimeDayType}/{@code calculate} ever run, so the row is left completely untouched,
     * not merely "some exception was thrown". Replays the exact P0 shape this whole file exists to
     * close (self-declaring HOLIDAY to inflate one's own pay), through the new field specifically.
     */
    @Test
    void anEmployeeCannotInflateTheirOwnPayThroughTheNewApproverDayTypeFieldBySelfApproving() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        assertThatThrownBy(() ->
                overtimeService.approve(id, new ApproveOvertimeRequest("ok", "HOLIDAY"), employee(staff)))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
        assertThat(dayTypeOf(id))
            .as("a rejected self-approve attempt must not move dayType/pay_rate_multiplier at all -- the HOLIDAY override never took effect")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
    }

    // -------------------------------------------------------------------------------------------
    // Case 6 (feat/ot-nonworkday-rate-suggestion, NEW): the schedule half of the suggestion --
    // Saturday/Sunday now suggest HOLIDAY for a Mon-Fri employee, WITHOUT any hr.holiday row, and
    // WITHOUT needing the calendar loaded for the year at all (see resolveDayTypeSubmitNote's
    // narrowing). No dayType claim in these two -- Case 8 below covers the claim/disagreement
    // layer for a non-workday specifically.
    // -------------------------------------------------------------------------------------------

    @Test
    void saturdaySuggestsHolidayForAMonFriEmployeeAndApprovingWithNoOverridePaysTheHolidayRate() {
        LocalDate saturday = aSaturdayWithNoHolidayRow();
        insertPunchesCovering(saturday, staff, "S001");

        OvertimeRequestDto created = overtimeService.submit(request(saturday, staff), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("HOLIDAY");
        assertThat(dayTypeOf(created.id())).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("3.00");
        assertThat(calculationNoteOf(created.id()))
            .as("a schedule-derived non-workday needs no calendar data to be certain -- narrowed calendar-unverified flag must NOT fire")
            .isNull();

        overtimeService.approve(created.id(), approve(), directManager());
        overtimeService.approve(created.id(), approve(), ceo());

        assertThat(dayTypeOf(created.id())).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("3.00");
        assertThat(overtimePayFor(saturday.withDayOfMonth(1), staff))
            .as("3h x (30000/30/8) x 3.00 -- confirmed by the approver, not silently paid")
            .isEqualByComparingTo(new BigDecimal("1125.00"));
    }

    @Test
    void sundaySuggestsHolidayForAMonFriEmployee() {
        LocalDate sunday = aSundayWithNoHolidayRow();

        OvertimeRequestDto created = overtimeService.submit(request(sunday, staff), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("HOLIDAY");
        assertThat(dayTypeOf(created.id())).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("3.00");
    }

    // -------------------------------------------------------------------------------------------
    // Case 7 (feat/ot-nonworkday-rate-suggestion, NEW): OPS_6D tiering. THE case a naive
    // day-of-week fix gets wrong -- Saturday is an ordinary WORKING day for a six-day schedule, and
    // only the real TieredWorkScheduleResolver (division-scope assignment here) can tell the
    // difference from Case 6 above.
    // -------------------------------------------------------------------------------------------

    @Test
    void saturdayIsSuggestedWorkdayForAnOps6dEmployeeUnlikeANaiveDayOfWeekCheck() {
        LocalDate saturday = aSaturdayWithNoHolidayRow();
        // The calendar being unloaded for this year is expected and orthogonal to what this test
        // proves (schedule tiering, not calendar flagging) -- a sentinel keeps the unrelated
        // calendar-unverified flag from muddying the assertions below.
        insertHoliday(sentinelDateInSameYear(saturday), "e2e: sentinel, unrelated to this case");
        insertPunchesCovering(saturday, opsSixDayStaff, "S006");

        OvertimeRequestDto created =
            overtimeService.submit(request(saturday, opsSixDayStaff), employee(opsSixDayStaff));

        assertThat(created.suggestedDayType())
            .as("OPS_6D's Saturday is an ordinary working day -- NOT a naive day-of-week HOLIDAY")
            .isEqualTo("WORKDAY");
        assertThat(dayTypeOf(created.id())).isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");

        overtimeService.approve(created.id(), approve(), opsSixDayManagerUser());
        overtimeService.approve(created.id(), approve(), ceo());

        assertThat(dayTypeOf(created.id())).isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(saturday.withDayOfMonth(1), opsSixDayStaff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    @Test
    void sundayIsSuggestedHolidayForAnOps6dEmployee() {
        LocalDate sunday = aSundayWithNoHolidayRow();
        insertPunchesCovering(sunday, opsSixDayStaff, "S006");

        OvertimeRequestDto created =
            overtimeService.submit(request(sunday, opsSixDayStaff), employee(opsSixDayStaff));

        assertThat(created.suggestedDayType())
            .as("Sunday is OPS_6D's one day off, same rule shape as a five-day schedule's weekend")
            .isEqualTo("HOLIDAY");

        overtimeService.approve(created.id(), approve(), opsSixDayManagerUser());
        overtimeService.approve(created.id(), approve(), ceo());

        assertThat(dayTypeOf(created.id())).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("3.00");
        assertThat(overtimePayFor(sunday.withDayOfMonth(1), opsSixDayStaff))
            .isEqualByComparingTo(new BigDecimal("1125.00"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 8 (feat/ot-nonworkday-rate-suggestion, NEW): the employee's claim may disagree with a
    // non-workday suggestion too -- accepted and flagged, never refused, same as Case 4's
    // holiday-vs-workday direction but on the SCHEDULE side of the suggestion this time.
    // -------------------------------------------------------------------------------------------

    @Test
    void employeeClaimingWorkdayOnASuggestedHolidaySaturdayIsAcceptedAndFlaggedNotRejected() {
        LocalDate saturday = aSaturdayWithNoHolidayRow();

        OvertimeRequestDto created = overtimeService.submit(request(saturday, staff, "WORKDAY"), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("HOLIDAY");
        assertThat(dayTypeOf(created.id()))
            .as("stored day_type still comes from the SUGGESTION, never the claim")
            .isEqualTo("HOLIDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("3.00");
        assertThat(calculationNoteOf(created.id()))
            .as("the employee's claim disagreed with the suggestion -- flagged, never a 400")
            .isNotNull()
            .contains("ไม่ตรงกับที่ระบบแนะนำ");
    }

    // -------------------------------------------------------------------------------------------
    // Case 9 (feat/ot-nonworkday-rate-suggestion, NEW): the approver's decision. Absent/null
    // dayType falls back to the suggestion (already proved throughout Cases 1-8 above, which all
    // approve() with no override); an EXPLICIT override wins outright, in both directions.
    // -------------------------------------------------------------------------------------------

    @Test
    void managerOverridingTheSuggestionToWorkdayOnASaturdayStoresTheLowerRate() {
        LocalDate saturday = aSaturdayWithNoHolidayRow();
        insertPunchesCovering(saturday, staff, "S001");

        long id = overtimeService.submit(request(saturday, staff), employee(staff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");

        overtimeService.approve(
            id, new ApproveOvertimeRequest("data looked wrong, this was an ordinary shift", "WORKDAY"), directManager());

        assertThat(dayTypeOf(id))
            .as("the approver's explicit override wins over the suggestion")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
    }

    /** The other direction: the approver may also UPGRADE an ordinary-suggested day to the holiday rate. */
    @Test
    void managerOverridingTheSuggestionToHolidayOnAnOrdinaryWeekdayStoresTheHigherRate() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        overtimeService.approve(
            id, new ApproveOvertimeRequest("HR confirmed an unrecorded public holiday", "HOLIDAY"), directManager());

        assertThat(dayTypeOf(id))
            .as("the approver's explicit override wins over the suggestion, in the upgrade direction too")
            .isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
    }

    /**
     * The freeze point does not move (this fix's explicit scope decision): on the two-stage route
     * the MANAGER's decision (explicit override here) is what freezes, and the CEO's final sign-off
     * inherits it -- {@link ApproveOvertimeRequest#dayType} sent to the SECOND approve call must be
     * silently ignored, exactly like a stale calendar change is (see {@link
     * #aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff}).
     */
    @Test
    void theCeosFinalSignOffCannotOverrideWhatTheManagerAlreadyFroze() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        overtimeService.approve(id, new ApproveOvertimeRequest("ok", "HOLIDAY"), directManager());
        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");

        // The CEO tries to downgrade at the final sign-off -- must be silently ignored.
        overtimeService.approve(id, new ApproveOvertimeRequest("ok", "WORKDAY"), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id))
            .as("ceoApprove must never honour dayType -- the manager's decision is frozen")
            .isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("1125.00"));
    }

    /** A malformed (non-blank, non-WORKDAY/HOLIDAY) approver dayType is a 400 about syntax, same message as a malformed claim. */
    @Test
    void aMalformedApproverDayTypeIsRejectedWithNoRowChanged() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();

        assertThatThrownBy(() ->
                overtimeService.approve(id, new ApproveOvertimeRequest("ok", "TUESDAY"), directManager()))
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(statusOf(id)).isEqualTo("SUBMITTED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
    }

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * A weekday (Mon-Fri), computed relative to "now" rather than hardcoded so this test does not
     * bit-rot the way {@code RetroactiveOvertimeReachesPayrollIntegrationTest} warns about, and far
     * enough out that it is never treated as backdated (no retroactive-window / reason-length
     * rules engage).
     */
    private LocalDate aWeekdayWithNoHolidayRow() {
        LocalDate date = LocalDate.now(BANGKOK).plusDays(10);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    /**
     * The next Saturday on/after "now + 10 days" -- same baseline as {@link
     * #aWeekdayWithNoHolidayRow} so a weekday case and a Saturday case in the same test run land on
     * genuinely different dates.
     */
    private LocalDate aSaturdayWithNoHolidayRow() {
        LocalDate date = LocalDate.now(BANGKOK).plusDays(10);
        while (date.getDayOfWeek() != DayOfWeek.SATURDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** The next Sunday on/after "now + 10 days" -- see {@link #aSaturdayWithNoHolidayRow}'s Javadoc. */
    private LocalDate aSundayWithNoHolidayRow() {
        LocalDate date = LocalDate.now(BANGKOK).plusDays(10);
        while (date.getDayOfWeek() != DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    /**
     * A date in the SAME calendar year as {@code date} that is never equal to {@code date} itself
     * -- used to make {@code hasHolidaysForYear} true (the year is "loaded") without making {@code
     * date} itself a holiday. Same technique (including the January-1st edge case) as {@code
     * write-overtime-holiday.spec.js}'s identically-named helper and this class's own inline use in
     * the Case 4/8 tests above.
     */
    private LocalDate sentinelDateInSameYear(LocalDate date) {
        LocalDate jan1 = LocalDate.of(date.getYear(), 1, 1);
        return date.equals(jan1) ? jan1.plusDays(1) : jan1;
    }

    /**
     * See {@link #FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA}'s Javadoc for why this year and the
     * defensive DELETE. Weekday-ness is not load-bearing here (see {@link
     * #aWeekdayWithNoHolidayRow}'s Javadoc) but is kept for consistency with this class's other
     * fixtures.
     */
    private LocalDate aFarFutureYearWeekdayWithNoHolidayRow() {
        jdbc.update(
            "DELETE FROM hr.holiday WHERE EXTRACT(YEAR FROM holiday_date) = :year",
            Map.of("year", FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA));
        LocalDate date = LocalDate.of(FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA, 6, 15);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private void insertHoliday(LocalDate date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :name, 'BANK')
            """, Map.of("date", date, "name", nameTh));
    }

    private void deleteHoliday(LocalDate date) {
        jdbc.update("DELETE FROM hr.holiday WHERE holiday_date = :date", Map.of("date", date));
    }

    private long scheduleIdByCode(String code) {
        return jdbc.queryForObject(
            "SELECT work_schedule_id FROM hr.work_schedule WHERE code = :code",
            Map.of("code", code), Long.class);
    }

    /** Same SQL shape as {@code AttendanceDailyScheduleAwareRecalculationIntegrationTest#assignDepartment}, DIVISION scope instead. */
    private void assignDivisionSchedule(long divisionId, String scheduleCode, LocalDate from, LocalDate to) {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeId", divisionId);
        params.put("workScheduleId", scheduleIdByCode(scheduleCode));
        params.put("from", from);
        params.put("to", to);
        jdbc.update("""
            INSERT INTO hr.work_schedule_assignment
                (scope_type, scope_id, work_schedule_id, effective_from, effective_to)
            VALUES ('DIVISION', :scopeId, :workScheduleId, :from, :to)
            """, params);
    }

    /**
     * 18:00-21:00, matching the defect report exactly (3 hours, 30,000/month base). No day-type
     * claim -- these tests are about DERIVATION, not the claim-validation layer (see the {@code
     * holidayClaim*}/{@code workdayClaim*} tests in Case 4/8 above for that, which use the 3-arg
     * overload below).
     */
    private SubmitOvertimeRequest request(LocalDate workDate, long employeeId) {
        return request(workDate, employeeId, null);
    }

    /** Same shape as the 2-arg overload, but with an explicit day-type CLAIM to validate. */
    private SubmitOvertimeRequest request(LocalDate workDate, long employeeId, String dayTypeClaim) {
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            employeeId, workDate, startAt, startAt.plusHours(3), dayTypeClaim,
            "Customer escalation kept the line running past close");
    }

    /** A plain "approve, no override" request -- the common case throughout Cases 1-8, which all rely on the suggestion as the default. */
    private ApproveOvertimeRequest approve() {
        return new ApproveOvertimeRequest("ok", null);
    }

    /**
     * Covers 18:00-21:00 (the OT window in {@link #request}) with room either side, so {@code
     * OvertimeService#calculate} derives exactly 180 payable minutes -- clocking in well before and
     * out well after keeps the overlap the full 3 hours, matching the defect report's figures.
     */
    private void insertPunchesCovering(LocalDate workDate, long employeeId, String badge) {
        insertPunch(workDate.atTime(8, 0).atOffset(ZoneOffset.ofHours(7)), workDate, employeeId, badge);
        insertPunch(workDate.atTime(21, 30).atOffset(ZoneOffset.ofHours(7)), workDate, employeeId, badge);
    }

    private void insertPunch(OffsetDateTime at, LocalDate workDate, long employeeId, String badge) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """, Map.of("badge", badge, "at", at, "workDate", workDate, "employeeId", employeeId));
    }

    private String dayTypeOf(long id) {
        return jdbc.queryForObject(
            "SELECT day_type FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), String.class);
    }

    private BigDecimal multiplierOf(long id) {
        return jdbc.queryForObject(
            "SELECT pay_rate_multiplier FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), BigDecimal.class);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject(
            "SELECT status FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), String.class);
    }

    /** Nullable -- {@code calculation_note} is NULL until either a submit-time flag or an approval-time note writes it. */
    private String calculationNoteOf(long id) {
        return jdbc.queryForObject(
            "SELECT calculation_note FROM hr.overtime_request WHERE overtime_request_id = :id",
            Map.of("id", id), String.class);
    }

    private BigDecimal overtimePayFor(LocalDate payrollMonth, long employeeId) {
        return new PayrollRepository(jdbc)
            .findApprovedOvertimePayByEmployee(payrollMonth)
            .getOrDefault(employeeId, BigDecimal.ZERO);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, "emp@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal directManager() {
        return new UserPrincipal(manager, "mgr@glr.co.th", "Manager", "employee",
            manager, true, LocalDate.now(), false, division, true);
    }

    private UserPrincipal opsSixDayManagerUser() {
        return new UserPrincipal(opsSixDayManager, "mgr6@glr.co.th", "Manager6", "employee",
            opsSixDayManager, true, LocalDate.now(), false, opsSixDayDivision, true);
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(manager, "ceo@glr.co.th", "CEO", "ceo",
            manager, true, LocalDate.now(), false, null, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, long divisionId, Long reportsTo, String positionNameTh) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsTo);
        params.put("salary", SALARY);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, current_salary,
                                     position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :salary,
                    :positionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    /**
     * A ผู้จัดการ position is what makes an employee a manager <em>in the database</em> -- see the
     * identical helper (and its Javadoc) in {@code RetroactiveOvertimeReachesPayrollIntegrationTest}
     * for why this must agree with the {@code manager} flag on the matching {@link UserPrincipal}.
     */
    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }
}
