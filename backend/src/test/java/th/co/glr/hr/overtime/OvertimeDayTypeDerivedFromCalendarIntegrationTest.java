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

/** P0 fix: day_type/pay_rate_multiplier must be DERIVED, never DECLARED by the caller. Every case is written
 * wrong-way-round: it asserts the wrong multiplier was NEVER stored and NEVER paid (or that no row exists at
 * all), not merely that some request was rejected. */
class OvertimeDayTypeDerivedFromCalendarIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    /** Deliberately far enough in the future that no other fixture in this class (all pinned to "now + 10 days", i.e.
     * the current year) could plausibly collide with it. */
    private static final int FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA = 2099;

    private OvertimeService overtimeService;

    private long division;
    private long manager;
    private long staff;

    // A separate division with nobody in a "ผู้จัดการ" position -- the manager-less route
    // (OvertimeService#ceoDirectApprove), which needs its own proof that it also derives/freezes.
    private long managerlessDivision;
    private long managerlessStaff;

    // feat/ot-nonworkday-rate-suggestion: a six-day division, so Cases 6-7 below can prove the schedule TIERING is
    // what actually resolves.
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

        // Neither "SLS" nor "FAC" matches any V115-seeded OFFICE_5D/OPS_6D source_code, so `staff` and `managerlessStaff`
        // both fall through to CompanyWideWorkScheduleResolver's Mon-Fri default -- exactly the "Mon-Fri employee" shape
        // Cases 6/8/9 below need.
        opsSixDayDivision = insertDivision("OT6", "ฝ่ายทดสอบหกวัน");
        assignDivisionSchedule(opsSixDayDivision, "OPS_6D", LocalDate.of(2024, 10, 1), null);
        opsSixDayManager = insertEmployee("M006", opsSixDayDivision, null, "ผู้จัดการฝ่ายทดสอบหกวัน");
        opsSixDayStaff = insertEmployee("S006", opsSixDayDivision, opsSixDayManager, null);
    }

    // Case 1: an ordinary weekday with no hr.holiday row must resolve to WORKDAY -- and even when a caller supplies
    // no claim at all (the request(workDate, employeeId) overload below never sets one), pay is still exclusively
    // calendar/schedule-derived.

    /** The label an approver would see must be honest from the moment the row is created — see
     * OvertimeService#submit's comment. Asserted directly against the stored columns, before any approval runs. */
    @Test
    void weekdayOvertimeWithNoCalendarEntryStoresWorkdayMultiplier() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();

        OvertimeRequestDto created = overtimeService.submit(request(workDate, staff), employee(staff));

        assertThat(created.suggestedDayType()).isEqualTo("WORKDAY");
        assertThat(dayTypeOf(created.id())).isEqualTo("WORKDAY");
        assertThat(multiplierOf(created.id())).isEqualByComparingTo("1.50");
    }

    /** THE headline scenario from the defect report, reproduced with its exact figures: ฿30,000/month -> hourly base
     * 30000/30/8 = ฿125. 3 hours (18:00-21:00) on an ordinary weekday. Correct pay is 3 x 125 x 1.50 = ฿562.50. */
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

    /** The manager-less route (ceoDirectApprove) is a separate repository method from managerApprove with its own SQL
     * -- it needs its own proof it also derives and freezes from the calendar, not an assumption that fixing one
     * fixed both. */
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

    // Case 2: the fix is not "always 1.5" -- a genuine calendar holiday still pays the holiday rate, with no claim
    // made at all.

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

    // Case 3: the calendar can change between submit and approval (HR corrects it), same shape of problem
    // requirePayrollMonthOpen already guards against for the payroll-month gate. Proves the design choice to
    // re-derive AND FREEZE at approval (OvertimeService#calculate), not only at submit.

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

    /** The other half of "runs once": once manager approval has frozen the value, the final CEO sign-off must NOT
     * silently re-price the request out from under what the manager approved -- matching how
     * payable_minutes/salary_basis are already frozen at manager approval and never revisited by ceoApprove. */
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

    // Case 4: the claim-validation layer -- SubmitOvertimeRequest.dayType is a REQUEST the server validates but NEVER
    // trusts for pay, at any stage.

    /** feat/ot-nonworkday-rate-suggestion: THE row this branch changes from the original decision table. Owner ruling
     * 2026-08-08: the employee may submit a claim that disagrees with the suggestion and still have it accepted; the
     * disagreement is flagged for the approver instead of refused. */
    @Test
    void holidayClaimContradictedByTheCalendarIsAcceptedAndFlaggedNotRejected() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        // The calendar IS loaded for this year (at least one row) -- but not on workDate. Otherwise the
        // calendar-unverified flag (not the disagreement flag) would be what fires, which is a different case (see
        // holidayClaimWithNoCalendarDataForTheYearIsAcceptedAndFlagged below).
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

    /** The calendar has never been loaded for the work date's year AT ALL -- the claim can be neither confirmed nor
     * refused, so it is accepted but flagged in calculation_note for HR to review once the calendar is populated. */
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

    /** THE exact gap the original fix closed, proved end to end against real Postgres (the unit-level proof, including
     * its own mutation-check, lives in OvertimeServiceTest). Written wrong-way-round: asserts the note is PRESENT
     * where the pre-fix code left it absent. */
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

    /** The claim can under-state the true day type without consequence -- suggestDayType corrects it upward
     * regardless, exactly as it already does for a NO-claim submission. */
    @Test
    void workdayClaimOnAGenuineHolidayIsStoredAsHolidayRegardlessOfTheUnderclaim() {
        LocalDate holiday = aWeekdayWithNoHolidayRow();
        insertHoliday(holiday, "วันหยุดทดสอบ");

        long id = overtimeService.submit(request(holiday, staff, "WORKDAY"), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
    }

    /** calculation_note is written at BOTH submit time (a day-type flag, when one fires) and approval time
     * (OvertimeService#calculate's attendance-derived note) -- and managerApprove/ceoDirectApprove overwrite the
     * column wholesale. */
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

    /** ceoDirectApprove is a SEPARATE UPDATE statement from managerApprove. Otherwise identical to
     * #managerApprovalPreservesTheSubmitTimeUnverifiedClaimFlagInsteadOfClobberingIt, through the manager-less
     * employee / CEO-direct route instead. */
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

    /** THE wrong-way-round case CLAUDE.md's authorization-evidence rule calls out by name, and the one this fix's task
     * brief flags as "must not be skipped": a claim from the SUBMITTER never reaches pay_rate_multiplier, proven
     * through the manager-LESS / CEO-direct route too. */
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

    /** Authorization evidence, wrong-way-round: an actor forbidden from approving THIS request cannot move money
     * through the NEW ApproveOvertimeRequest#dayType field either. */
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

    // Case 6 (feat/ot-nonworkday-rate-suggestion, NEW): the schedule half of the suggestion -- Saturday/Sunday now
    // suggest HOLIDAY for a Mon-Fri employee, WITHOUT any hr.holiday row, and WITHOUT needing the calendar loaded for
    // the year at all (see resolveDayTypeSubmitNote's narrowing).

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

    // Case 7 (feat/ot-nonworkday-rate-suggestion, NEW): OPS_6D tiering. THE case a naive day-of-week fix gets wrong
    // -- Saturday is an ordinary WORKING day for a six-day schedule, and only the real TieredWorkScheduleResolver
    // (division-scope assignment here) can tell the difference from Case 6 above.

    @Test
    void saturdayIsSuggestedWorkdayForAnOps6dEmployeeUnlikeANaiveDayOfWeekCheck() {
        LocalDate saturday = aSaturdayWithNoHolidayRow();
        // The calendar being unloaded for this year is expected and orthogonal to what this test proves (schedule
        // tiering, not calendar flagging) -- a sentinel keeps the unrelated calendar-unverified flag from muddying the
        // assertions below.
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

    // Case 8 (feat/ot-nonworkday-rate-suggestion, NEW): the employee's claim may disagree with a non-workday
    // suggestion too -- accepted and flagged, never refused, same as Case 4's holiday-vs-workday direction but on the
    // SCHEDULE side of the suggestion this time.

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

    // Case 9 (feat/ot-nonworkday-rate-suggestion, NEW): the approver's decision. Absent/null dayType falls back to
    // the suggestion (already proved throughout Cases 1-8 above, which all approve() with no override); an EXPLICIT
    // override wins outright, in both directions.

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

    /** The freeze point does not move: on the two-stage route the MANAGER's decision is what freezes, and the CEO's
     * final sign-off inherits it -- ApproveOvertimeRequest#dayType sent to the SECOND approve call must be silently
     * ignored, exactly like a stale calendar change is. */
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

    /** A weekday (Mon-Fri), computed relative to "now" rather than hardcoded so this test does not bit-rot the way
     * RetroactiveOvertimeReachesPayrollIntegrationTest warns about, and far enough out that it is never treated as
     * backdated (no retroactive-window / reason-length rules engage). */
    private LocalDate aWeekdayWithNoHolidayRow() {
        LocalDate date = LocalDate.now(BANGKOK).plusDays(10);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** The next Saturday on/after "now + 10 days" -- same baseline as #aWeekdayWithNoHolidayRow so a weekday case and
     * a Saturday case in the same test run land on genuinely different dates. */
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

    /** A date in the SAME calendar year as date that is never equal to date itself -- used to make hasHolidaysForYear
     * true (the year is "loaded") without making date itself a holiday. */
    private LocalDate sentinelDateInSameYear(LocalDate date) {
        LocalDate jan1 = LocalDate.of(date.getYear(), 1, 1);
        return date.equals(jan1) ? jan1.plusDays(1) : jan1;
    }

    /** See #FAR_FUTURE_YEAR_WITH_NO_HOLIDAY_DATA's Javadoc for why this year and the defensive DELETE. Weekday-ness is
     * not load-bearing here (see #aWeekdayWithNoHolidayRow's Javadoc) but is kept for consistency with this class's
     * other fixtures. */
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

    /** 18:00-21:00, matching the defect report exactly (3 hours, 30,000/month base). No day-type claim -- these tests
     * are about DERIVATION, not the claim-validation layer (see the holidayClaim* /workdayClaim* tests in Case 4/8
     * above for that, which use the 3-arg overload below). */
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

    /** Covers 18:00-21:00 (the OT window in #request) with room either side, so OvertimeService#calculate derives
     * exactly 180 payable minutes -- clocking in well before and out well after keeps the overlap the full 3 hours,
     * matching the defect report's figures. */
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

    /** A ผู้จัดการ position is what makes an employee a manager in the database -- see the identical helper (and its
     * Javadoc) in RetroactiveOvertimeReachesPayrollIntegrationTest for why this must agree with the manager flag on
     * the matching UserPrincipal. */
    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }
}
