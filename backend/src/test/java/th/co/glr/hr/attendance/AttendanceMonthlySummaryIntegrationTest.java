package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.attendance.daily.AttendanceDailyCalculator;
import th.co.glr.hr.attendance.daily.AttendanceDailyRepository;
import th.co.glr.hr.attendance.daily.AttendanceDailyService;
import th.co.glr.hr.attendance.schedule.CompanyWideWorkScheduleResolver;
import th.co.glr.hr.attendance.schedule.DbHolidayCalendar;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms the monthly summary EXPORT is scoped exactly like {@code AttendanceScopeIntegrationTest}
 * proves {@code /daily} is -- through the real {@link AttendanceMonthlySummaryService}, the real
 * {@link AttendanceService#resolveScope}, and a real workbook parsed back out with POI, against a
 * real Postgres. Mockito cannot prove a scope filter reaches the {@code WHERE} clause (the ~130
 * hand-wired services in this suite exist precisely because a mocked repository "passes" while the
 * SQL underneath does something else); this class exists because CLAUDE.md requires exactly that
 * proof for any change that touches who may read whose rows -- see this codebase's "Permission
 * changes must ship evidence" rule.
 *
 * <p>Every case here asks the question the wrong way round -- can this caller reach data they
 * should not -- rather than confirming they can reach their own, mirroring
 * {@link AttendanceScopeIntegrationTest}'s own stated design.
 *
 * <p>{@code @EnabledIf} is declared HERE, not inherited from {@link AbstractPostgresIntegrationTest}
 * -- JUnit's {@code @EnabledIf} is not {@code @Inherited}, so the annotation on the base class gates
 * nothing in any subclass (see that class's own javadoc, and CLAUDE.md).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class AttendanceMonthlySummaryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final LocalDate A_WORKDAY = LocalDate.of(2026, 7, 15); // a Wednesday
    private static final YearMonth JULY_2026 = YearMonth.of(2026, 7);

    private AttendanceMonthlySummaryService monthlySummaryService;

    private long salesDivision;
    private long factoryDivision;
    private long salesManagerId;
    private long salesStaffId;
    private long factoryStaffId;
    private String salesManagerCode;
    private String salesStaffCode;
    private String factoryStaffCode;

    @BeforeEach
    void wireRealCollaborators() {
        AppProperties properties = new AppProperties();
        AttendanceDailyRepository dailyRepository = new AttendanceDailyRepository(jdbc);
        AttendanceDailyService dailyService = new AttendanceDailyService(
            dailyRepository,
            new AttendanceDailyCalculator(),
            new CompanyWideWorkScheduleResolver(properties),
            new DbHolidayCalendar(jdbc));
        AttendanceService attendanceService = new AttendanceService(
            new AttendanceRepository(jdbc, new ObjectMapper()),
            new AttendanceDatParser(),
            properties,
            dailyService);
        // No schedule-aware constructor needed: findApprovedLeaveOverlapping (the only method this
        // test's dependency chain calls) is a plain overlap read, unaffected by which LeaveRepository
        // constructor built it -- see that constructor's own javadoc for why the 1-arg form exists
        // for exactly this situation.
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        monthlySummaryService = new AttendanceMonthlySummaryService(
            attendanceService, dailyService, leaveRepository, new AttendanceMonthlySummaryExporter());

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        salesManagerId = insertEmployee("M001", salesDivision);
        salesStaffId = insertEmployee("S001", salesDivision);
        factoryStaffId = insertEmployee("F001", factoryDivision);
        salesManagerCode = "M001";
        salesStaffCode = "S001";
        factoryStaffCode = "F001";

        // One ordinary day each, so every caller has something to find if scoping lets them.
        List.of(salesManagerId, salesStaffId, factoryStaffId).forEach(id -> {
            insertPunch(id, at(A_WORKDAY, 8, 20));
            insertPunch(id, at(A_WORKDAY, 17, 40));
        });
        dailyService.recalculateRange(A_WORKDAY, A_WORKDAY, null);
    }

    // --- hr / ceo -----------------------------------------------------------

    @Test
    void hrAndCeoGetEveryone() {
        assertThat(employeeCodesIn(export(hr(), JULY_2026, null, null)))
            .containsExactlyInAnyOrder(salesManagerCode, salesStaffCode, factoryStaffCode);
    }

    // --- ฝ่าย manager -------------------------------------------------------

    @Test
    void aManagersUnfilteredExportContainsOnlyTheirOwnDivision() {
        List<String> codes = employeeCodesIn(export(manager(salesManagerId, salesDivision), JULY_2026, null, null));

        assertThat(codes).containsExactlyInAnyOrder(salesManagerCode, salesStaffCode);
        assertThat(codes).doesNotContain(factoryStaffCode);
    }

    /**
     * The attack the {@code employeeId} parameter invites. A manager asking for a colleague OUTSIDE
     * their division must get a workbook with ZERO employee rows -- not an error, and definitely not
     * that colleague's attendance -- because {@code resolveScope} ANDs the requested id against the
     * manager's own division rather than merging the two into a wider grant.
     */
    @Test
    void aManagerAskingForAnOutOfDivisionEmployeeGetsAWorkbookWithNoEmployeeRows() {
        byte[] workbook = export(manager(salesManagerId, salesDivision), JULY_2026, factoryStaffId, null);

        assertThat(employeeCodesIn(workbook)).isEmpty();
    }

    /** Same attack via the OTHER parameter: the division id must be ignored for a manager, not
     * merged, so the request stays inside their own ฝ่าย instead of hopping to another. */
    @Test
    void aManagerCannotUseTheDivisionParameterToReachAnotherDivision() {
        List<String> codes =
            employeeCodesIn(export(manager(salesManagerId, salesDivision), JULY_2026, null, factoryDivision));

        assertThat(codes).doesNotContain(factoryStaffCode);
        assertThat(codes).containsExactlyInAnyOrder(salesManagerCode, salesStaffCode);
    }

    // --- plain employee -------------------------------------------------------

    @Test
    void aPlainEmployeesExportContainsOnlyThemselves() {
        assertThat(employeeCodesIn(export(employee(salesStaffId), JULY_2026, null, null)))
            .containsExactly(salesStaffCode);
    }

    @Test
    void anEmployeeAskingForSomeoneElseIsRejected() {
        UserPrincipal caller = employee(salesStaffId);
        assertThatThrownBy(() -> monthlySummaryService.export(caller, JULY_2026, factoryStaffId, null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ไม่มีสิทธิ์เข้าถึงรายการนี้");
    }

    // --- helpers ------------------------------------------------------------

    private byte[] export(UserPrincipal user, YearMonth month, Long employeeId, Long divisionId) {
        return monthlySummaryService.export(user, month, employeeId, divisionId);
    }

    /**
     * Every {@code รหัสพนักงาน} on the "สรุปรายเดือน" sheet's data rows -- i.e. every employee the
     * caller's scope let through, read back from the ACTUAL exported bytes (not the assembly step),
     * so this proves the whole pipeline end to end. Walks from the header row (identified by its
     * "ลำดับ" cell) to the first row lacking a code -- the blank separator / §76 footer row after the
     * data -- rather than hardcoding row indices, so a future layout tweak to the title block does
     * not need this test rewritten.
     */
    private static List<String> employeeCodesIn(byte[] xlsx) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheet("สรุปรายเดือน");
            List<String> codes = new ArrayList<>();
            boolean pastHeader = false;
            for (Row row : sheet) {
                if (!pastHeader) {
                    Cell first = row.getCell(0);
                    if (first != null && first.getCellType() == CellType.STRING
                            && "ลำดับ".equals(first.getStringCellValue())) {
                        pastHeader = true;
                    }
                    continue;
                }
                Cell codeCell = row.getCell(1);
                if (codeCell == null || codeCell.getCellType() != CellType.STRING
                        || codeCell.getStringCellValue().isBlank()) {
                    break;
                }
                codes.add(codeCell.getStringCellValue());
            }
            return codes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", salesManagerId, true,
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
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :hireDate, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "divisionId", divisionId, "hireDate", LocalDate.of(2020, 1, 1)),
            Long.class);
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
