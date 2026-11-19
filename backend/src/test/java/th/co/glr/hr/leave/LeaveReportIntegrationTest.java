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

/** Printable leave-records report (รายงานสรุปใบลางาน, 2026-09): real-Postgres coverage of
 * LeaveService#teamLeaveReport/LeaveService#ownLeaveReport's scope, plus the rendered LeaveReportRenderer
 * PDF's extracted text (PDFBox PDFTextStripper, never a byte-length check). */
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

        // End-to-end proof: the rendered PDF's own extracted text must not carry the other manager's report -- proves
        // the scope holds through the WHOLE pipeline (assembly AND rendering), not just the intermediate DTO list.
        String text = extractText(renderer.toPdf(sections, 2026, null, true));
        assertThat(text).contains("LR-RPT-1");
        assertThat(text).doesNotContain("LR-RPT-2");
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
