package th.co.glr.hr.overtime;

import static org.assertj.core.api.Assertions.assertThat;
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
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.ManagerApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix: {@code day_type}/{@code pay_rate_multiplier} must be DERIVED from {@code hr.holiday}
 * (V115), never DECLARED by the caller in {@code SubmitOvertimeRequest.dayType}. Before this fix,
 * {@code OvertimeService.parseDayType} wrote the client's claim straight into {@code
 * overtime_request.pay_rate_multiplier} at INSERT, and neither approval stage ever checked it
 * against a calendar — an employee could self-declare HOLIDAY (3.00x) on an ordinary Tuesday and be
 * overpaid 0.5x-2x what the work was worth, with nothing in the approval UI to contradict the lie.
 *
 * <p>Drives the real {@link OvertimeService}, {@link OvertimeRepository} and {@link
 * DbHolidayCalendar} against real Postgres end to end (submit → manager/CEO approve → payroll
 * read) — Mockito cannot reach either the repository SQL or the money {@code
 * PayrollRepository#findApprovedOvertimePayByEmployee} actually computes.
 *
 * <p>Every "declared a lie" case is written wrong-way-round: it asserts the bad multiplier was
 * NEVER stored and NEVER paid, not merely that the request was rejected.
 *
 * <p>MUTATION-CHECK RECORD (actually run, not simulated): temporarily reverted {@code
 * OvertimeService#submit} to trust {@code request.dayType()} again (the exact pre-fix logic) and
 * {@code OvertimeService#calculate} to read {@code OvertimeDayType.valueOf(request.dayType())}
 * off the row instead of calling {@code deriveDayType} (i.e. approval no longer re-checks the
 * calendar either), then ran the whole {@code th.co.glr.hr.overtime} package. Exactly 6 tests in
 * this class went red -- {@code weekdayOvertimeDeclaredHolidayStoresWorkdayMultiplierNotHoliday},
 * {@code weekdayOvertimeDeclaredHolidayIsPaidAtWorkdayRateNotTheOverpaidHolidayRate}, {@code
 * ceoDirectApprovalForAManagerlessEmployeeAlsoIgnoresTheDeclaredLie}, {@code
 * genuineHolidayStillGetsTheHolidayRateEvenWhenDeclaredAsWorkday}, {@code
 * aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval}, {@code
 * aMistakenHolidayEntryRemovedAfterSubmitIsPickedUpAndFrozenAtManagerApproval} -- every one with
 * "expected: WORKDAY but was: HOLIDAY" (or the reverse) at the exact stored-value assertion, plus
 * one test in {@code OvertimeServiceTest} ({@code
 * divisionManagerSubmissionIgnoresADeclaredDayTypeTheCalendarDisagreesWith}, which errored with
 * the unstubbed-mock NOT_FOUND symptom of the same mutation at the Mockito layer). The other 69
 * tests in the package stayed green, including {@code
 * noDayTypeDeclaredAtAllStillDerivesSafelyToWorkdayWhenThereIsNoCalendarEntry} and {@code
 * aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff} -- both correctly
 * NOT mutation-sensitive to this particular defect (see their own Javadoc). Reverted both methods
 * and independently re-read the restored bodies (not {@code git diff --stat}); the full {@code
 * th.co.glr.hr.overtime} package re-ran 76/76 green afterwards.
 */
class OvertimeDayTypeDerivedFromCalendarIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final BigDecimal SALARY = new BigDecimal("30000.00");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

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
    // Case 1: the core defect. A weekday, self-declared HOLIDAY, calendar disagrees.
    // -------------------------------------------------------------------------------------------

    /**
     * The label an approver would see must be honest from the moment the row is created, not only
     * after an approval action happens to fix it — see {@code OvertimeService#submit}'s comment.
     * Asserted directly against the stored columns, before any approval runs.
     */
    @Test
    void weekdayOvertimeDeclaredHolidayStoresWorkdayMultiplierNotHoliday() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(
            request(workDate, "HOLIDAY", staff), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
    }

    /**
     * THE headline scenario from the defect report, reproduced with its exact figures: ฿30,000/month
     * -> hourly base 30000/30/8 = ฿125. 3 hours (18:00-21:00) declared HOLIDAY on an ordinary
     * weekday. Correct pay is 3 x 125 x 1.50 = ฿562.50. The bug paid 3 x 125 x 3.00 = ฿1,125.00 —
     * ฿562.50 overpaid on this one request. Runs the full submit -> manager approve -> CEO approve
     * chain and reads the money the same way payroll does, not a stand-in calculation.
     */
    @Test
    void weekdayOvertimeDeclaredHolidayIsPaidAtWorkdayRateNotTheOverpaidHolidayRate() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, "HOLIDAY", staff), employee(staff)).id();
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
     * managerApprove} with its own SQL -- it needs its own proof it also derives and freezes, not
     * an assumption that fixing one fixed both.
     */
    @Test
    void ceoDirectApprovalForAManagerlessEmployeeAlsoIgnoresTheDeclaredLie() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, managerlessStaff, "F001");

        long id = overtimeService.submit(
            request(workDate, "HOLIDAY", managerlessStaff), employee(managerlessStaff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
        assertThat(overtimePayFor(workDate.withDayOfMonth(1), managerlessStaff))
            .isEqualByComparingTo(new BigDecimal("562.50"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 2: the fix is not "always 1.5" -- a genuine holiday still pays the holiday rate, even
    // when the caller declares (or under-declares) WORKDAY.
    // -------------------------------------------------------------------------------------------

    @Test
    void genuineHolidayStillGetsTheHolidayRateEvenWhenDeclaredAsWorkday() {
        LocalDate holiday = aWeekdayWithNoHolidayRow();
        insertHoliday(holiday, "วันหยุดทดสอบ");
        insertPunchesCovering(holiday, staff, "S001");

        // Declares WORKDAY -- the wrong direction of lie (or just a stale/lazy client default).
        // The point is that the SERVER decides, not the declaration, in either direction.
        long id = overtimeService.submit(request(holiday, "WORKDAY", staff), employee(staff)).id();
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), directManager());
        overtimeService.approve(id, new ReviewOvertimeRequest("ok"), ceo());

        assertThat(dayTypeOf(id)).isEqualTo("HOLIDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("3.00");
        assertThat(overtimePayFor(holiday.withDayOfMonth(1), staff))
            .as("3h x (30000/30/8) x 3.00 -- the real holiday rate, not 1.50")
            .isEqualByComparingTo(new BigDecimal("1125.00"));
    }

    // -------------------------------------------------------------------------------------------
    // Case 3: no calendar entry at all -- point 3's conclusion. Absence resolves to WORKDAY (the
    // cheaper multiplier), matching HolidayCalendar#isHoliday's existing meaning everywhere else
    // it is read (attendance, leave), not a bespoke default invented for overtime.
    // -------------------------------------------------------------------------------------------

    /**
     * NOT a security/mutation-sensitive case (confirmed by this class's own mutation-check: a
     * null declaration defaults to WORKDAY under the OLD trust-the-client code too, so this test
     * alone would stay green even with the fix fully reverted). Its job is narrower: a genuinely
     * absent/null {@code dayType} -- e.g. an older client that never sends the field -- must not
     * NPE or throw, and must resolve to the same safe default as an adversarial declaration would.
     * {@code weekdayOvertimeDeclaredHolidayStoresWorkdayMultiplierNotHoliday} above is the test
     * that actually proves the calendar, not the declaration, decides.
     */
    @Test
    void noDayTypeDeclaredAtAllStillDerivesSafelyToWorkdayWhenThereIsNoCalendarEntry() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();

        long id = overtimeService.submit(request(workDate, null, staff), employee(staff)).id();

        assertThat(dayTypeOf(id)).isEqualTo("WORKDAY");
        assertThat(multiplierOf(id)).isEqualByComparingTo("1.50");
    }

    // -------------------------------------------------------------------------------------------
    // Case 4: the calendar can change between submit and approval (HR corrects it), same shape of
    // problem requirePayrollMonthOpen already guards against for the payroll-month gate. Proves the
    // design choice to re-derive AND FREEZE at approval (OvertimeService#calculate), not only at
    // submit.
    // -------------------------------------------------------------------------------------------

    @Test
    void aHolidayAddedToTheCalendarAfterSubmitIsPickedUpAndFrozenAtManagerApproval() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, "WORKDAY", staff), employee(staff)).id();
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

        long id = overtimeService.submit(request(workDate, "WORKDAY", staff), employee(staff)).id();
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
     */
    @Test
    void aCalendarChangeAfterManagerApprovalIsNotPickedUpByTheFinalCeoSignOff() {
        LocalDate workDate = aWeekdayWithNoHolidayRow();
        insertPunchesCovering(workDate, staff, "S001");

        long id = overtimeService.submit(request(workDate, "WORKDAY", staff), employee(staff)).id();
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

    private void insertHoliday(LocalDate date, String nameTh) {
        jdbc.update("""
            INSERT INTO hr.holiday (holiday_date, name_th, source)
            VALUES (:date, :name, 'BANK')
            """, Map.of("date", date, "name", nameTh));
    }

    private void deleteHoliday(LocalDate date) {
        jdbc.update("DELETE FROM hr.holiday WHERE holiday_date = :date", Map.of("date", date));
    }

    /** 18:00-21:00, matching the defect report exactly (3 hours, 30,000/month base). */
    private SubmitOvertimeRequest request(LocalDate workDate, String declaredDayType, long employeeId) {
        OffsetDateTime startAt = workDate.atTime(18, 0).atOffset(ZoneOffset.ofHours(7));
        return new SubmitOvertimeRequest(
            employeeId, workDate, startAt, startAt.plusHours(3), declaredDayType,
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
