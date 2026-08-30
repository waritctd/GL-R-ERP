package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.attendance.daily.AttendanceDailyCalculator;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyFilter;
import th.co.glr.hr.attendance.daily.AttendanceDailyRepository;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the CEO/HR "mark present" (WFH / stand-up) write path against the real service, the
 * real SQL and the real controller role gate — CLAUDE.md "Permission changes must ship evidence",
 * because this endpoint is both a new write path and a new authorization rule.
 *
 * <p>Wires the real {@link AttendanceController} — not a mock — so
 * {@code sessions.requireAnyRole(user, "ceo", "hr")} is exercised exactly as production runs it,
 * on top of {@link AttendanceService#markPresent}'s own scope check and the real
 * {@link AttendanceDailyRepository} SQL guards ({@code upsertWfhPresent} / {@code
 * clearWfhNotInRoster}).
 *
 * <p>Every authorization case here asks the question the wrong way round: can a caller who should
 * not be able to reach this endpoint or this data actually reach it.
 */
class AttendanceMarkPresentIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);

    private AttendanceController controller;
    private AttendanceDailyService dailyService;

    private long division;
    private long alice;
    private long bob;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        AttendanceDailyRepository dailyRepository = new AttendanceDailyRepository(jdbc);
        dailyService = new AttendanceDailyService(
            dailyRepository,
            new AttendanceDailyCalculator(),
            new CompanyWideWorkScheduleResolver(properties),
            new DbHolidayCalendar(jdbc));
        AttendanceService service = new AttendanceService(
            new AttendanceRepository(jdbc, new ObjectMapper()),
            new AttendanceDatParser(),
            properties,
            dailyService);
        // Real AttendanceMonthlySummaryService (not a mock) -- unrelated to markPresent, but this
        // file's own philosophy ("wires the real AttendanceController — not a mock") extends
        // naturally to the controller's other collaborator now that it has one; both LeaveRepository
        // and AttendanceMonthlySummaryExporter are cheap to construct for real.
        AttendanceMonthlySummaryService monthlySummaryService = new AttendanceMonthlySummaryService(
            service, dailyService, new LeaveRepository(jdbc), new AttendanceMonthlySummaryExporter());
        controller = new AttendanceController(service, monthlySummaryService, new SessionContext());

        division = insertDivision("SLS", "ฝ่ายขาย");
        alice = insertEmployee("A001", division);
        bob = insertEmployee("B001", division);
    }

    // --- (a) CEO and HR can mark present ------------------------------------

    @Test
    void ceoCanMarkPresentWithNoPunches() {
        AttendanceMarkPresentResponse response = markPresent(ceo(), WEDNESDAY, List.of(alice), "stand-up 08:30");

        assertThat(response.markedCount()).isEqualTo(1);
        AttendanceDailyDto day = dayFor(alice, WEDNESDAY);
        assertThat(day.status()).isEqualTo(AttendanceDayStatus.WFH);
        assertThat(day.manualOverride()).isTrue();
        assertThat(day.checkIn()).isNull();
        assertThat(day.checkOut()).isNull();
        assertThat(day.siteCode()).isEqualTo("WFH");
        assertThat(day.punchCount()).isZero();
    }

    @Test
    void hrCanMarkPresentWithNoPunches() {
        AttendanceMarkPresentResponse response = markPresent(hr(), WEDNESDAY, List.of(bob), null);

        assertThat(response.markedCount()).isEqualTo(1);
        AttendanceDailyDto day = dayFor(bob, WEDNESDAY);
        assertThat(day.status()).isEqualTo(AttendanceDayStatus.WFH);
        assertThat(day.manualOverride()).isTrue();
        assertThat(day.checkIn()).isNull();
        assertThat(day.checkOut()).isNull();
    }

    // --- (b) everyone else is forbidden --------------------------------------

    @Test
    void anEmployeeCannotMarkAnyoneEvenThemselvesPresent() {
        assertForbidden(() -> markPresent(employee(alice), WEDNESDAY, List.of(alice), null));
    }

    @Test
    void aSalesCallerCannotMarkPresent() {
        assertForbidden(() -> markPresent(sales(alice), WEDNESDAY, List.of(alice), null));
    }

    @Test
    void aDivisionManagerCannotMarkPresent() {
        assertForbidden(() -> markPresent(manager(alice, division), WEDNESDAY, List.of(bob), null));
    }

    // --- (c) resubmitting a narrower roster clears whoever was left off ------

    @Test
    void resubmittingWithoutSomeoneClearsTheirEarlierMark() {
        markPresent(ceo(), WEDNESDAY, List.of(alice, bob), null);
        assertThat(dayFor(alice, WEDNESDAY).status()).isEqualTo(AttendanceDayStatus.WFH);
        assertThat(dayFor(bob, WEDNESDAY).status()).isEqualTo(AttendanceDayStatus.WFH);

        AttendanceMarkPresentResponse response = markPresent(ceo(), WEDNESDAY, List.of(alice), null);

        assertThat(response.clearedCount()).isEqualTo(1);
        // alice was resubmitted: still marked WFH.
        assertThat(dayFor(alice, WEDNESDAY).status()).isEqualTo(AttendanceDayStatus.WFH);
        // bob was left off: the earlier WFH row is gone, not merely blanked.
        AttendanceDailyDto bobDay = dayFor(bob, WEDNESDAY);
        assertThat(bobDay.manualOverride()).isFalse();
        assertThat(bobDay.status()).isNotEqualTo(AttendanceDayStatus.WFH);
    }

    // --- (d) a WFH mark supersedes an existing scanner-derived row ------------

    @Test
    void markPresentSupersedesAPunchDerivedRow() {
        insertPunch(alice, at(WEDNESDAY, 8, 20));
        insertPunch(alice, at(WEDNESDAY, 17, 40));
        dailyService.recalculateRange(WEDNESDAY, WEDNESDAY, null);
        AttendanceDailyDto before = dayFor(alice, WEDNESDAY);
        assertThat(before.checkIn()).isNotNull();
        assertThat(before.checkOut()).isNotNull();
        assertThat(before.manualOverride()).isFalse();

        AttendanceMarkPresentResponse response = markPresent(ceo(), WEDNESDAY, List.of(alice), "stand-up");

        // The CEO ticked alice present, so the manual mark wins over the scan: the derived row is
        // overwritten to WFH. The raw punches are untouched (not asserted here — see the repository
        // javadoc), so un-marking + a recalc would restore the punch-derived view.
        assertThat(response.markedCount()).isEqualTo(1);
        AttendanceDailyDto after = dayFor(alice, WEDNESDAY);
        assertThat(after.status()).isEqualTo(AttendanceDayStatus.WFH);
        assertThat(after.manualOverride()).isTrue();
        assertThat(after.checkIn()).isNull();
        assertThat(after.checkOut()).isNull();
    }

    // --- (e) un-marking after a supersede restores the scan on recalc ---------

    @Test
    void unmarkingAfterSupersedeRestoresThePunchDerivedRowOnRecalc() {
        insertPunch(alice, at(WEDNESDAY, 8, 20));
        insertPunch(alice, at(WEDNESDAY, 17, 40));
        dailyService.recalculateRange(WEDNESDAY, WEDNESDAY, null);
        markPresent(ceo(), WEDNESDAY, List.of(alice), "stand-up");
        assertThat(dayFor(alice, WEDNESDAY).status()).isEqualTo(AttendanceDayStatus.WFH);

        // Drop alice from a later roster for the same date → her WFH row is cleared…
        markPresent(ceo(), WEDNESDAY, List.of(), null);
        // …and the nightly recalc rebuilds the punch-derived day from the untouched raw punches.
        dailyService.recalculateRange(WEDNESDAY, WEDNESDAY, null);

        AttendanceDailyDto restored = dayFor(alice, WEDNESDAY);
        assertThat(restored.manualOverride()).isFalse();
        assertThat(restored.checkIn()).isNotNull();
        assertThat(restored.checkOut()).isNotNull();
        assertThat(restored.status()).isNotEqualTo(AttendanceDayStatus.WFH);
    }

    // --- helpers --------------------------------------------------------------

    private AttendanceMarkPresentResponse markPresent(
            UserPrincipal user, LocalDate date, List<Long> employeeIds, String notes) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return controller.markPresent(new AttendanceMarkPresentRequest(date, employeeIds, notes), session);
    }

    private static void assertForbidden(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
            .isInstanceOf(ApiException.class)
            .extracting(exception -> ((ApiException) exception).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private interface ThrowingCallable {
        void call();
    }

    private AttendanceDailyDto dayFor(long employeeId, LocalDate date) {
        return dailyService.list(new AttendanceDailyFilter(employeeId, null, date, date)).stream()
            .filter(day -> day.employeeId() == employeeId)
            .findFirst()
            .orElseThrow();
    }

    private UserPrincipal ceo() {
        return new UserPrincipal(1L, "ceo@glr.co.th", "ceo", "ceo", alice, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(2L, "hr@glr.co.th", "hr", "hr", alice, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private UserPrincipal sales(long employeeId) {
        return new UserPrincipal(4L, "sales@glr.co.th", "sales", "sales", employeeId, true,
            LocalDate.now(), false, division, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(5L, "mgr@glr.co.th", "mgr", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, true);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("hireDate", LocalDate.of(2020, 1, 1));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private void insertPunch(long employeeId, OffsetDateTime at) {
        jdbc.update("""
            INSERT INTO hr.attendance_punch (site_code, badge_code, punch_time, work_date, employee_id)
            VALUES ('SHOWROOM', :badge, :at, :workDate, :employeeId)
            """,
            Map.of("badge", "B" + employeeId, "at", at,
                   "workDate", at.atZoneSameInstant(BANGKOK).toLocalDate(),
                   "employeeId", employeeId));
    }

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(BANGKOK).toOffsetDateTime();
    }
}
