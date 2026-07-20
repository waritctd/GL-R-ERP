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
import th.co.glr.hr.attendance.daily.AttendanceDailyCalculator;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDailyRepository;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.daily.AttendanceEmployeeOption;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms attendance authorization against the real service and the real SQL.
 *
 * <p>This exists because {@code mockApi.js} is explicitly <strong>not</strong> authoritative for
 * permissions — CLAUDE.md records a case (issue #199) where an agent read the mock's gates as the
 * backend's and reported a permission rule that production did not have. Unit tests on
 * {@code resolveScope} prove the decision; only this proves the decision survives the WHERE clause
 * and actually filters rows.
 *
 * <p>Every case here asks the question the wrong way round — can this caller reach data they should
 * not — rather than confirming they can reach their own.
 */
class AttendanceScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 7, 15);

    private AttendanceService service;
    private AttendanceDailyService dailyService;

    private long salesDivision;
    private long factoryDivision;
    private long salesManager;
    private long salesStaff;
    private long factoryStaff;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        AttendanceDailyRepository dailyRepository = new AttendanceDailyRepository(jdbc);
        dailyService = new AttendanceDailyService(
            dailyRepository,
            new AttendanceDailyCalculator(),
            new CompanyWideWorkScheduleResolver(properties));
        service = new AttendanceService(
            new AttendanceRepository(jdbc, new ObjectMapper()),
            new AttendanceDatParser(),
            properties,
            dailyService);

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        salesManager = insertEmployee("M001", salesDivision);
        salesStaff = insertEmployee("S001", salesDivision);
        factoryStaff = insertEmployee("F001", factoryDivision);

        // One ordinary day each, so every caller has something to find if scoping lets them.
        List.of(salesManager, salesStaff, factoryStaff).forEach(id -> {
            insertPunch(id, at(WEDNESDAY, 8, 20));
            insertPunch(id, at(WEDNESDAY, 17, 40));
        });
        dailyService.recalculateRange(WEDNESDAY, WEDNESDAY, null);
    }

    // --- hr / ceo -----------------------------------------------------------

    @Test
    void hrSeesEveryDivision() {
        List<AttendanceDailyDto> days = listDaily(hr(), null, null);

        assertThat(employeeIdsIn(days))
            .contains(salesManager, salesStaff, factoryStaff);
    }

    @Test
    void hrCanNarrowToOneDivisionAndGetsOnlyThatDivision() {
        List<AttendanceDailyDto> days = listDaily(hr(), null, salesDivision);

        assertThat(employeeIdsIn(days)).contains(salesManager, salesStaff);
        assertThat(employeeIdsIn(days)).doesNotContain(factoryStaff);
    }

    // --- ฝ่าย manager -------------------------------------------------------

    @Test
    void aManagerSeesTheirOwnDivisionOnly() {
        List<AttendanceDailyDto> days = listDaily(manager(salesStaffPrincipalId(), salesDivision), null, null);

        assertThat(employeeIdsIn(days)).contains(salesManager, salesStaff);
        assertThat(employeeIdsIn(days)).doesNotContain(factoryStaff);
    }

    /**
     * The attack the divisionId parameter invites. It must be ignored for a manager, not merged, so
     * the request silently stays inside their own ฝ่าย instead of hopping to another.
     */
    @Test
    void aManagerCannotUseTheDivisionParameterToReachAnotherDivision() {
        List<AttendanceDailyDto> days =
            listDaily(manager(salesStaffPrincipalId(), salesDivision), null, factoryDivision);

        assertThat(employeeIdsIn(days)).doesNotContain(factoryStaff);
        assertThat(employeeIdsIn(days)).contains(salesManager, salesStaff);
    }

    /**
     * Asking for a specific out-of-division employee must yield nothing rather than that person's
     * data — both predicates AND in the WHERE clause, which a mocked repository cannot demonstrate.
     */
    @Test
    void aManagerAskingForAnOutOfDivisionEmployeeGetsNoRows() {
        List<AttendanceDailyDto> days =
            listDaily(manager(salesStaffPrincipalId(), salesDivision), factoryStaff, null);

        assertThat(days).isEmpty();
    }

    // --- plain employee -----------------------------------------------------

    @Test
    void anEmployeeSeesOnlyTheirOwnDays() {
        List<AttendanceDailyDto> days = listDaily(employee(salesStaff), null, null);

        assertThat(employeeIdsIn(days)).containsOnly(salesStaff);
    }

    @Test
    void anEmployeeAskingForSomeoneElseIsRejected() {
        assertThatThrownBy(() -> listDaily(employee(salesStaff), factoryStaff, null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Forbidden");
    }

    @Test
    void anEmployeeCannotUseTheDivisionParameterToWiden() {
        List<AttendanceDailyDto> days = listDaily(employee(salesStaff), null, factoryDivision);

        assertThat(employeeIdsIn(days)).containsOnly(salesStaff);
    }

    // --- the employee picker ------------------------------------------------

    @Test
    void theEmployeePickerNeverOffersSomeoneTheCallerCannotRead() {
        List<AttendanceEmployeeOption> forManager =
            service.listEmployeeOptions(manager(salesStaffPrincipalId(), salesDivision));
        assertThat(forManager).extracting(AttendanceEmployeeOption::employeeId)
            .contains(salesManager, salesStaff)
            .doesNotContain(factoryStaff);

        List<AttendanceEmployeeOption> forEmployee = service.listEmployeeOptions(employee(salesStaff));
        assertThat(forEmployee).extracting(AttendanceEmployeeOption::employeeId)
            .containsOnly(salesStaff);

        assertThat(service.listEmployeeOptions(hr()))
            .extracting(AttendanceEmployeeOption::employeeId)
            .contains(salesManager, salesStaff, factoryStaff);
    }

    /** The ฝ่าย list the UI builds is derived from these options, so it inherits the same scope. */
    @Test
    void theDivisionsAManagerCanDeriveAreOnlyTheirOwn() {
        List<AttendanceEmployeeOption> options =
            service.listEmployeeOptions(manager(salesStaffPrincipalId(), salesDivision));

        assertThat(options).extracting(AttendanceEmployeeOption::divisionId)
            .containsOnly(salesDivision);
    }

    // --- helpers ------------------------------------------------------------

    private List<AttendanceDailyDto> listDaily(UserPrincipal user, Long employeeId, Long divisionId) {
        return service.listDaily(user, WEDNESDAY, WEDNESDAY, employeeId, divisionId);
    }

    private static List<Long> employeeIdsIn(List<AttendanceDailyDto> days) {
        return days.stream().map(AttendanceDailyDto::employeeId).distinct().toList();
    }

    /** The manager principal is linked to an employee inside the sales division. */
    private long salesStaffPrincipalId() {
        return salesManager;
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", salesManager, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal manager(long employeeId, long divisionId) {
        return new UserPrincipal(2L, "mgr@glr.co.th", "mgr", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, true);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(3L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, salesDivision, false);
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
