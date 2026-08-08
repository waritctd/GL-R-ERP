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
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix: {@code day_type}/{@code pay_rate_multiplier} must be DERIVED from {@code hr.holiday}
 * (V115), never DECLARED by the caller. Before this fix, {@code SubmitOvertimeRequest} carried a
 * client-supplied {@code dayType} field that {@code OvertimeService.parseDayType} wrote straight
 * into {@code overtime_request.pay_rate_multiplier} at INSERT, and neither approval stage ever
 * checked it against a calendar — an employee could self-declare HOLIDAY (3.00x) on an ordinary
 * Tuesday and be overpaid 0.5x-2x what the work was worth, with nothing in the approval UI to
 * contradict the lie.
 *
 * <p>The fix has two layers, both exercised here: (1) {@code OvertimeService} derives {@code
 * dayType} exclusively from {@link DbHolidayCalendar}, never from caller input (Cases 1-3 below),
 * and (2) {@code SubmitOvertimeRequest.dayType} is KEPT, not deleted, as a CLAIM the server
 * validates rather than trusts (Case 4 below) — see that record's Javadoc and {@code
 * OvertimeService#validateDayTypeClaim}. Either way, the claim never reaches {@code
 * pay_rate_multiplier}: an unrecognised claim is refused (400, same message as always), a claim the
 * calendar can actively disprove is refused (400, naming the date), a claim the calendar cannot yet
 * verify is accepted but flagged in {@code calculation_note} for HR, and a claim that UNDER-states
 * the true day type is silently corrected upward by {@link OvertimeService#deriveDayType} — the
 * claim can never suppress real holiday pay, only (harmlessly) fail to inflate it.
 *
 * <p>Drives the real {@link OvertimeService}, {@link OvertimeRepository} and {@link
 * DbHolidayCalendar} against real Postgres end to end (submit → manager/CEO approve → payroll
 * read) — Mockito cannot reach either the repository SQL or the money {@code
 * PayrollRepository#findApprovedOvertimePayByEmployee} actually computes.
 *
 * <p>Every case is written wrong-way-round: it asserts the wrong multiplier was NEVER stored and
 * NEVER paid (or that no row exists at all), not merely that some request was rejected.
 *
 * <p>MUTATION-CHECK RECORD, Cases 1-3 (derivation itself, ported from the reference branch this
 * class was ported from; actually run there, not simulated — see that branch's history for the
 * full transcript). Three separate mutations, each reverted before the next:
 *
 * <p><b>Mutation 1</b>: {@code OvertimeService#deriveDayType} changed to unconditionally {@code
 * return OvertimeDayType.WORKDAY;} (ignoring {@code holidayCalendar}). 3 of the 7 Case-1/2/3 tests
 * went red — {@code genuineHolidayGetsTheHolidayRate}, {@code
 * aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval}, and {@code
 * aMistakenHolidayEntryRemovedAfterSubmitIsPickedUpAndFrozenAtManagerApproval} — each "expected:
 * HOLIDAY but was: WORKDAY".
 *
 * <p><b>Mutation 2</b>: {@code deriveDayType} changed to unconditionally {@code return
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
 * guard inside {@code validateDayTypeClaim} (the branch that refuses a HOLIDAY claim the calendar
 * can actively disprove), so a contradicted claim would silently fall through and be accepted. See
 * this session's final report for the exact test(s) that reddened.
 *
 * <p><b>Mutation 5</b>: deleted the {@code if (!holidayCalendar.hasHolidaysForYear(...))} guard (the
 * branch that flags, rather than trusts blindly, a HOLIDAY claim for a year the calendar has never
 * been loaded for), so an unverifiable claim would fall through to the calendar-loaded check instead
 * of being flagged. See this session's final report for the exact test(s) that reddened.
 *
 * <p><b>Mutation 6</b>: deleted the {@code startsWith(DAY_TYPE_CLAIM_UNVERIFIED_NOTE_PREFIX)} guard
 * inside {@code preserveDayTypeClaimFlag}, so approval would unconditionally overwrite {@code
 * calculation_note} instead of appending to a submit-time flag. See this session's final report for
 * the exact test(s) that reddened.
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

    @BeforeEach
    void wireRealCollaborators() {
        overtimeService = new OvertimeService(
            new OvertimeRepository(jdbc),
            new ManagerApproverRepository(jdbc),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(),
            mock(AttendanceDailyService.class),
            new DbHolidayCalendar(jdbc));

        division = insertDivision("SLS", "ฝ่ายขาย");
        manager = insertEmployee("M001", division, null, "ผู้จัดการฝ่ายขาย");
        staff = insertEmployee("S001", division, manager, null);

        managerlessDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        managerlessStaff = insertEmployee("F001", managerlessDivision, null, null);
    }

    // -------------------------------------------------------------------------------------------
    // Case 1: an ordinary weekday with no hr.holiday row must resolve to WORKDAY -- and even when a
    // caller supplies no claim at all (the request(workDate, employeeId) overload below never sets
    // one), pay is still exclusively calendar-derived.
    // -------------------------------------------------------------------------------------------

    /**
     * The label an approver would see must be honest from the moment the row is created — see
     * {@code OvertimeService#submit}'s comment. Asserted directly against the stored columns,
     * before any approval runs.
     */
    @Test
    void weekdayOvertimeWithNoCalendarEntryStoresWorkdayMultiplier() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
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
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

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
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

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
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

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

        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());

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

        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());

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
     * "make {@code deriveDayType} always wrong" mutation trips this test's EARLIER assertion (right
     * after manager approval), not the late-change assertion this test exists to prove -- {@code
     * deriveDayType} is also called by {@code submit()}/{@code managerApprove()}, which this test
     * exercises first. Isolating the actual claim ("{@code ceoApprove} does not re-derive") needs a
     * mutation targeted at {@code ceoApprove} specifically (mutation 3), not at {@code
     * deriveDayType} globally.
     */
    @Test
    void aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");

        // Too late: the manager has already approved and frozen WORKDAY/1.50.
        insertHoliday(workDate, "สายเกินไป");

        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), staff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 4: the claim-validation layer -- SubmitOvertimeRequest.dayType is KEPT (this repo's
    // divergence from the ported branch, which deleted it) as a CLAIM the server validates but
    // NEVER trusts for pay. See SubmitOvertimeRequest's Javadoc and
    // OvertimeService#validateDayTypeClaim for the decision table these cases exercise.
    // -------------------------------------------------------------------------------------------

    /**
     * The exact P0 shape, caught one layer earlier: a HOLIDAY claim on an ordinary day is refused
     * outright when the calendar is loaded for the year and can actively disprove it -- no row is
     * ever created, not merely "some request was rejected".
     */
    @Test
    void holidayClaimContradictedByTheCalendarIsRejectedAndCreatesNoRow() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        // The calendar IS loaded for this year (at least one row) -- but not on workDate. This is
        // what makes the claim actively disprovable rather than merely unverifiable (contrast with
        // holidayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlagged below).
        //
        // MUST land in the SAME year as workDate, or the guard this proves (hasHolidaysForYear,
        // keyed on workDate.getYear()) never trips: a plain workDate.plusDays(1) rolls over to
        // January 1 of the NEXT year whenever workDate is December 31 (real dates this bites:
        // 2026-12-21, 2027-12-21, 2029-12-19/20/21, 2030-12-21, since aWeekdayWithNoHolidayRow is
        // "now + 10 days" nudged off a weekend), silently making the calendar look unloaded for
        // workDate's year and firing the flag branch instead of this test's expected 400. Jan 1 has
        // no "day before" in the same year, so it needs the opposite nudge (+1) instead of
        // withDayOfYear(1) colliding with itself.
        LocalDate sentinelHolidayDate = workDate.getDayOfYear() == 1
            ? workDate.plusDays(1)
            : workDate.withDayOfYear(1);
        insertHoliday(sentinelHolidayDate, "วันหยุดอื่นในปีเดียวกัน");

        assertThatThrownBy(() ->
                overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining(workDate.toString())
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countOvertimeRequestsFor(staff)).isZero();
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
     * comes exclusively from {@code deriveDayType}, i.e. WORKDAY here) but flagged in {@code
     * calculation_note} for HR to review once the calendar is populated.
     */
    @Test
    void holidayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlagged() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id))
            .as("an unverifiable claim never becomes pay -- deriveDayType alone decides, and the calendar says WORKDAY")
            .isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(calculationNoteOf(id))
            .isNotNull()
            .contains(String.valueOf(FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA))
            .contains("HOLIDAY");
    }

    /**
     * The claim can under-state the true day type without consequence -- {@code deriveDayType}
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
     * {@code calculation_note} is written at BOTH submit time (the claim-unverifiable flag, when it
     * fires) and approval time ({@code OvertimeService#calculate}'s attendance-derived note) -- and
     * {@code managerApprove}/{@code ceoDirectApprove} overwrite the column wholesale. Without the
     * append-not-clobber handling in {@code OvertimeService#preserveDayTypeClaimFlag}, approval would
     * silently erase the flag HR was supposed to review.
     */
    @Test
    void managerApprovalPreservesTheSubmitTimeUnverifiedClaimFlagInsteadOfClobberingIt() {
        LocalDate workDate = aFarFutureYearWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, staff, "HOLIDAY"), employee(staff)).id();
        String submitTimeNote = calculationNoteOf(id);
        assertThat(submitTimeNote).isNotNull();

        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());

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

        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

        assertThat(calculationNoteOf(id))
            .as("the submit-time flag must survive ceoDirectApprove too, alongside the new approval-time note -- not be replaced by it")
            .contains(submitTimeNote)
            .contains("Calculated from the overlap");
    }

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * A weekday (Mon-Fri), computed relative to "now" rather than hardcoded so this test does not
     * bit-rot the way {@code RetroactiveOvertimeReachesPayrollIntegrationTest} warns about, and far
     * enough out that it is never treated as backdated (no retroactive-window / reason-length
     * rules engage). Weekday-ness mirrors the defect report's "workDate: 2026-08-11, a Tuesday" for
     * narrative fidelity only -- it is NOT load-bearing to {@code OvertimeService#deriveDayType},
     * which reads {@code hr.holiday} exclusively and never looks at day-of-week (see {@code
     * HolidayCalendar}'s Javadoc: an employee's weekly non-workday is a distinct concept this
     * calendar does not answer).
     */
    private LocalDate aWeekdayWithNoHolidayRow() {
        LocalDate date = LocalDate.now(BANGKOK).plusDays(10);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
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

    /**
     * 18:00-21:00, matching the defect report exactly (3 hours, 30,000/month base). No day-type
     * claim -- these tests are about DERIVATION, not the claim-validation layer (see the {@code
     * holidayClaim*}/{@code workdayClaim*} tests in Case 4 above for that, which use the 3-arg
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

    /** Wrong-way-round assertion helper: proves a refused submit wrote NOTHING, not merely that it threw. */
    private int countOvertimeRequestsFor(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.overtime_request WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
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
