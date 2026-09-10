package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Printable leave-records report (รายงานสรุปใบลางาน, 2026-09): real-Postgres coverage of {@link
 * LeaveService#teamLeaveReport}/{@link LeaveService#ownLeaveReport}'s scope, plus the rendered
 * {@link LeaveReportRenderer} PDF's extracted text (PDFBox {@link PDFTextStripper}, never a
 * byte-length check).
 *
 * <p>{@code teamLeaveReport} is built entirely on {@link LeaveService#teamBalances}, which already
 * resolves "who is my team" via {@link LeaveRepository#findEmployeeOptions} -- the SAME query
 * {@link LeaveTeamBalancesIntegrationTest} already pins. This class is that class's sibling for the
 * REPORT surface: it does not re-litigate {@code findEmployeeOptions}' own SQL (already covered
 * there), it proves the report ASSEMBLY never re-introduces a caller-supplied employee id or a
 * second scope predicate on top of it -- see {@link LeaveService#teamLeaveReport}'s Javadoc for why
 * that is the one thing genuinely new here.
 *
 * <p>Written WRONG-WAY-ROUND throughout (CLAUDE.md's "Permission changes must ship evidence"):
 * {@link #teamReportManagerCannotSeeAnotherManagersDirectReport} asserts a colleague's report is
 * genuinely ABSENT -- from the returned DTO list AND from the rendered PDF's own extracted text --
 * not merely that the caller's own report is present.
 *
 * <p><b>Mutation-check (see PR body for the run):</b> temporarily replace {@code
 * teamBalances(user, resolvedYear)} inside {@link LeaveService#teamLeaveReport} with {@code
 * leaveRepository.findEmployeeOptions(actorEmployeeId, true)} mapped to empty balances (i.e.
 * hardcode {@code includeAll = true}, simulating a developer bypassing the reused scope call) --
 * confirms {@link #teamReportManagerCannotSeeAnotherManagersDirectReport} goes red and no other
 * test in this class does, then revert BY HAND (never {@code git checkout --}, which would also
 * discard any other uncommitted work).
 */
class LeaveReportIntegrationTest extends AbstractPostgresIntegrationTest {

    private LeaveService leaveService;
    private final LeaveReportRenderer renderer = new LeaveReportRenderer();

    @BeforeEach
    void wireRealCollaborators() {
        LeaveRepository leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class));
    }

    // --- wrong-way-round: what each caller CANNOT reach ----------------------------------------

    @Test
    void teamReportManagerCannotSeeAnotherManagersDirectReport() {
        long manager1 = insertEmployee("LR-MGR-1", null, true);
        long manager2 = insertEmployee("LR-MGR-2", null, true);
        long report1 = insertEmployee("LR-RPT-1", manager1, true);
        long report2 = insertEmployee("LR-RPT-2", manager2, true);
        insertApprovedLeave(report1, "VACATION", LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 3), "1.00");
        insertApprovedLeave(report2, "VACATION", LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 4), "1.00");

        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(employee(manager1), 2026, null);

        assertThat(sections).extracting(LeaveReportEmployeeDto::employeeId).containsExactly(report1);
        assertThat(sections).extracting(LeaveReportEmployeeDto::employeeId)
            .as("a colleague's direct report must never leak into this manager's grouped report")
            .doesNotContain(report2);
        assertThat(sections.get(0).requests())
            .as("and their leave rows must never leak in either")
            .extracting(LeaveRequestDto::employeeId)
            .doesNotContain(report2);

        // End-to-end proof: the rendered PDF's own extracted text must not carry the other
        // manager's report -- proves the scope holds through the WHOLE pipeline (assembly AND
        // rendering), not just the intermediate DTO list.
        String text = extractText(renderer.toPdf(sections, 2026, null, true));
        assertThat(text).contains("LR-RPT-1");
        assertThat(text).doesNotContain("LR-RPT-2");
    }

    @Test
    void ownReportNeverIncludesAnotherEmployeesRecords() {
        long employeeA = insertEmployee("LR-OWN-A", null, true);
        long employeeB = insertEmployee("LR-OWN-B", null, true);
        insertApprovedLeave(employeeA, "VACATION", LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 3), "1.00");
        insertApprovedLeave(employeeB, "VACATION", LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 4), "1.00");

        List<LeaveReportEmployeeDto> sections = leaveService.ownLeaveReport(employee(employeeA), 2026, null);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).employeeId()).isEqualTo(employeeA);
        assertThat(sections.get(0).requests())
            .as("an employee's own report must never carry another employee's rows")
            .extracting(LeaveRequestDto::employeeId)
            .containsOnly(employeeA);

        String text = extractText(renderer.toPdf(sections, 2026, null, false));
        assertThat(text).contains("LR-OWN-A");
        assertThat(text).doesNotContain("LR-OWN-B");
    }

    @Test
    void managerWithNoDirectReportsGetsAnEmptyReportNotAnError() {
        long plainEmployee = insertEmployee("LR-PLAIN", null, true);

        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(employee(plainEmployee), 2026, null);

        assertThat(sections).isEmpty();
        // Renders a friendly empty-team page rather than throwing.
        String text = extractText(renderer.toPdf(sections, 2026, null, true));
        assertThat(text).contains("รายงานสรุปใบลางาน");
    }

    @Test
    void hrTeamReportStillCoversEveryEmployeeExistingCanViewAllSemantics() {
        long manager1 = insertEmployee("LR-HR-MGR1", null, true);
        long manager2 = insertEmployee("LR-HR-MGR2", null, true);
        long report1 = insertEmployee("LR-HR-RPT1", manager1, true);
        long report2 = insertEmployee("LR-HR-RPT2", manager2, true);

        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(hr(), 2026, null);

        assertThat(sections).extracting(LeaveReportEmployeeDto::employeeId)
            .as("HR keeps its existing whole-company reach -- this endpoint must not regress it")
            .contains(manager1, manager2, report1, report2);
    }

    // --- PDF text-extraction assertions (PDFBox PDFTextStripper, never byte-length) -------------

    @Test
    void totalDaysPrintsAsDaysAndHoursNeverADecimal() {
        long divisionId = insertDivision("ฝ่ายทดสอบรายงานใบลา");
        long manager = insertEmployee("LR-FMT-MGR", null, true);
        long report = insertEmployeeWithDivision("LR-FMT-RPT", manager, divisionId, true);
        // 1.56 round-trips to EXACTLY "1 วัน 4 ชม. 30 น." -- see LeaveDayMath#formatDuration's
        // Javadoc: 1.56 * 480 = 748.8 minutes, snapped to the nearest 5-minute step = 750 minutes
        // = 1 whole day (480min) + a 270-minute (4h30m) remainder.
        insertApprovedLeave(report, "PERSONAL", LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 12), "1.56");

        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(employee(manager), 2026, null);
        String text = extractText(renderer.toPdf(sections, 2026, null, true));

        assertThat(text).contains("1 วัน 4 ชม. 30 น.");
        assertThat(text).doesNotContain("1.56");
        assertThat(text).contains("ฝ่ายทดสอบรายงานใบลา");
    }

    @Test
    void quotaSummaryBlockReadsTheSameNumbersTeamBalancesPutsOnScreen() {
        long manager = insertEmployee("LR-QT-MGR", null, true);
        long report = insertEmployee("LR-QT-RPT", manager, true);
        insertApprovedLeave(report, "VACATION", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 2), "1.00");

        List<LeaveTeamMemberBalanceDto> teamBalances = leaveService.teamBalances(employee(manager), 2026);
        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(employee(manager), 2026, null);

        LeaveBalanceDto expected = balanceFor(teamBalances.get(0).balances(), "VACATION");
        LeaveBalanceDto actual = balanceFor(sections.get(0).balances(), "VACATION");
        assertThat(actual.remainingDays()).isEqualByComparingTo(expected.remainingDays());
        assertThat(actual.approvedDays()).isEqualByComparingTo(expected.approvedDays());
        assertThat(actual.annualQuotaDays()).isEqualByComparingTo(expected.annualQuotaDays());

        String text = extractText(renderer.toPdf(sections, 2026, null, true));
        assertThat(text).contains(LeaveDayMath.formatDuration(expected.remainingDays()));
    }

    @Test
    void anInertQuotaRowIsDroppedButAnUnusedENTITLEMENTIsKept() {
        long manager = insertEmployee("LR-INERT-MGR", null, true);
        long report = insertEmployee("LR-INERT-RPT", manager, true);

        List<LeaveReportEmployeeDto> sections = leaveService.teamLeaveReport(employee(manager), 2026, null);
        String text = extractText(renderer.toPdf(sections, 2026, null, true));

        // LEAVE_WITHOUT_PAY carries a 0-day statutory quota and this employee has used none of it,
        // so the row can tell the reader nothing and must not take a line.
        assertThat(text)
            .as("a wholly inert quota row must be dropped")
            .doesNotContain("ลาไม่รับค่าจ้าง");
        // VACATION is unused too, but the employee HOLDS the entitlement -- that is the most useful
        // line on the page for someone who has taken none of it, so it must survive the same filter.
        assertThat(text)
            .as("an unused entitlement is NOT inert and must be kept")
            .contains("ลาพักร้อน");
    }

    private LeaveBalanceDto balanceFor(List<LeaveBalanceDto> balances, String leaveTypeCode) {
        return balances.stream()
            .filter(balance -> balance.leaveTypeCode().equals(leaveTypeCode))
            .findFirst()
            .orElseThrow();
    }

    private String extractText(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // --- fixture helpers -------------------------------------------------------------------------

    private long insertEmployee(String code, Long reportsToId, boolean active) {
        return insertEmployeeWithDivision(code, reportsToId, null, active);
    }

    private long insertEmployeeWithDivision(String code, Long reportsToId, Long divisionId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("reportsTo", reportsToId);
        params.put("divisionId", divisionId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th,
                 reports_to_employee_id, division_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :reportsTo, :divisionId, :active, DATE '2020-01-01')
            RETURNING employee_id
            """, new MapSqlParameterSource(params), Long.class);
    }

    private long insertDivision(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (name_th, is_active) VALUES (:name, TRUE) RETURNING division_id
            """, Map.of("name", nameTh), Long.class);
    }

    private long insertApprovedLeave(
            long employeeId, String leaveTypeCode, LocalDate startDate, LocalDate endDate, String totalDays) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("leaveTypeCode", leaveTypeCode);
        params.put("start", startDate);
        params.put("end", endDate);
        params.put("totalDays", new BigDecimal(totalDays));
        params.put("quotaYear", startDate.getYear());
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 status, quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, :leaveTypeCode, :start, :end, :totalDays, :quotaYear,
                    'Integration test leave', 'APPROVED', 6.00, 5.00)
            RETURNING leave_request_id
            """, new MapSqlParameterSource(params), Long.class);
    }

    private UserPrincipal employee(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(9001L, "hr@glr.co.th", "HR", "hr", 9001L, true, LocalDate.now(), false, null, false);
    }
}
