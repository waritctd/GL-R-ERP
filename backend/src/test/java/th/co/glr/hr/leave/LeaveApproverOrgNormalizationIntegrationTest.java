package th.co.glr.hr.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-Postgres coverage of V121's Part A (leave approver normalisation) and the Part B slice that moves
 * employee 10051 into the new แม่บ้าน department for approver purposes. */
class LeaveApproverOrgNormalizationIntegrationTest extends AbstractPostgresIntegrationTest {

    private String migrationSql;
    private LeaveRepository leaveRepository;
    private LeaveService leaveService;

    @BeforeEach
    void wireRealCollaboratorsAndReadMigration() throws IOException {
        leaveRepository = new LeaveRepository(jdbc);
        leaveService = new LeaveService(
            leaveRepository,
            mock(LeaveAttachmentRepository.class),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(EmployeeRepository.class));
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V121__org_normalize_approvers_and_schedules.sql")) {
            assertThat(in).as("V121 migration file must be on the test classpath").isNotNull();
            migrationSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyMappedDepartmentGetsItsOwnerRuledApproverAndTheOldAdHocApproversLoseTheRole() {
        long jintana = insertEmployee("10049", null, null, null, true);
        long jarinya = insertEmployee("10014", null, null, null, true);
        long ram = insertEmployee("10009", null, null, null, true);
        // The old ad-hoc paper approvers -- position พนักงาน, no longer authorised. Used below as each reportee's
        // WRONG starting manager, so the assertions prove they get REPLACED, not just that some value happens to
        // already be correct.
        long yutthana = insertEmployee("10063", null, null, null, true);
        long prangnet = insertEmployee("10079", null, null, null, true);

        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1");
        long salesSupport2 = insertDepartment("SALES2", "ฝ่ายสนับสนุนการขาย 2");
        long salesTeam = insertDepartment("SATM", "ทีมขาย");
        long warehouseCoded = insertDepartment("WH", "คลังสินค้า");
        long warehouseUncoded = insertDepartmentNullCode("คลังสินค้า");
        long purchasing = insertDepartment("PCIM", "จัดซื้อต่างประเทศ");
        long accounting = insertDepartment("AC", "บัญชี");
        long qc = insertDepartment("QC&ISO", "QC&ISO");

        long ss1Employee = insertEmployee("EMP-SS1", salesSupport1, null, yutthana, true);
        long ss2Employee = insertEmployee("EMP-SS2", salesSupport2, null, yutthana, true);
        long satmEmployee = insertEmployee("EMP-SATM", salesTeam, null, prangnet, true);
        long whCodedEmployee = insertEmployee("EMP-WHC", warehouseCoded, null, null, true);
        long whUncodedEmployee = insertEmployee("EMP-WHU", warehouseUncoded, null, null, true);
        long purchasingEmployee = insertEmployee("EMP-PCIM", purchasing, null, null, true);
        long accountingEmployee = insertEmployee("EMP-AC", accounting, null, null, true);
        long qcEmployee = insertEmployee("EMP-QC", qc, null, null, true);

        executeStatements(migrationSql);

        assertThat(reportsTo(ss1Employee)).as("Sales Support 1 -> จินตนา").isEqualTo(jintana);
        assertThat(reportsTo(ss2Employee)).as("Sales Support 2 -> จินตนา").isEqualTo(jintana);
        assertThat(reportsTo(satmEmployee)).as("ทีมขาย -> จินตนา").isEqualTo(jintana);
        assertThat(reportsTo(whCodedEmployee)).as("คลังสินค้า (WH) -> จริญญา").isEqualTo(jarinya);
        assertThat(reportsTo(whUncodedEmployee))
            .as("คลังสินค้า (NULL source_code duplicate) -> จริญญา").isEqualTo(jarinya);
        assertThat(reportsTo(purchasingEmployee)).as("จัดซื้อต่างประเทศ -> ราม").isEqualTo(ram);
        assertThat(reportsTo(accountingEmployee)).as("บัญชี -> ราม").isEqualTo(ram);
        assertThat(reportsTo(qcEmployee)).as("QC&ISO -> ราม").isEqualTo(ram);

        // The wrong-way-round case: neither old ad-hoc approver is anybody's manager any more, and this is asserted
        // where it actually matters -- through the real service's authorization gate, not just a raw SELECT.
        long leaveRequestId = insertSubmittedLeaveRequest(ss1Employee);

        assertThatThrownBy(() -> leaveService.approve(
                leaveRequestId, new ReviewLeaveRequest(null), employeeUser(yutthana)))
            .as("ยุทธนา is no longer this employee's manager and must be rejected")
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).getStatus())
            .isEqualTo(HttpStatus.FORBIDDEN);

        LeaveRequestDto approved = leaveService.approve(
            leaveRequestId, new ReviewLeaveRequest(null), employeeUser(jintana));
        assertThat(approved.status()).as("จินตนา, the corrected manager, can approve").isEqualTo("APPROVED");
    }

    // --- helpers ------------------------------------------------------------

    /** Strips -- line comments first, then splits into statements on ; -- dollar-quote aware: V121
     * contains a DO $$ ... $$ block whose body contains its own ; terminators that must NOT split the block apart. */
    private void executeStatements(String sql) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            withoutComments.append(line).append('\n');
        }
        String body = withoutComments.toString();
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollarQuote = false;
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("$$", i)) {
                inDollarQuote = !inDollarQuote;
                current.append("$$");
                i++;
                continue;
            }
            char c = body.charAt(i);
            if (c == ';' && !inDollarQuote) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        for (String statement : statements) {
            String trimmed = statement.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbc.getJdbcOperations().execute(trimmed);
        }
    }

    private Long reportsTo(long employeeId) {
        return jdbc.queryForObject(
            "SELECT reports_to_employee_id FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId), Long.class);
    }

    private long insertDepartment(String sourceCode, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING department_id
            """, Map.of("code", sourceCode, "name", nameTh), Long.class);
    }

    private long insertDepartmentNullCode(String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.department (source_code, name_th, is_active)
            VALUES (NULL, :name, TRUE) RETURNING department_id
            """, Map.of("name", nameTh), Long.class);
    }

    private long insertEmployee(String code, Long departmentId, Long divisionId, Long reportsToId, boolean active) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("departmentId", departmentId);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsToId);
        params.put("active", active);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee
                (employee_code, first_name_th, last_name_th, department_id, division_id,
                 reports_to_employee_id, is_active, hire_date)
            VALUES (:code, 'ทดสอบ', :code, :departmentId, :divisionId, :reportsTo, :active, DATE '2020-01-01')
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertSubmittedLeaveRequest(long employeeId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.leave_request
                (employee_id, leave_type_code, start_date, end_date, total_days, quota_year, reason,
                 quota_remaining_before, quota_remaining_after)
            VALUES (:employeeId, 'VACATION', DATE '2026-08-10', DATE '2026-08-10', 1.00, 2026,
                    'Integration test leave', 6.00, 5.00)
            RETURNING leave_request_id
            """, new MapSqlParameterSource("employeeId", employeeId), Long.class);
    }

    private UserPrincipal employeeUser(long employeeId) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Test", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
