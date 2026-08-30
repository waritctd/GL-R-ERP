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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage of V121's Part A (leave approver normalisation) and the Part B slice that
 * moves employee 10051 into the new แม่บ้าน department for approver purposes.
 *
 * <p>The Testcontainers golden template migrates V121 ONCE against an empty {@code hr.employee} /
 * {@code hr.department}, so every one of V121's UPDATE/INSERT statements genuinely no-ops during
 * that one real run (none of the production employee_codes or department source_codes exist yet).
 * Every test here inserts fixture rows carrying those EXACT production codes ('10049', '10014',
 * '10009', 'SALES', 'WH', ...) and then replays V121's real SQL text (read verbatim from the
 * classpath, never re-typed here, so this cannot drift from the shipped migration) against them --
 * the same technique used by {@code PayrollComponentTaxTreatmentBackfillIntegrationTest} for V100.
 *
 * <p>{@code LeaveService} already enforces "HR/CEO or the employee's direct manager may approve" by
 * reading {@code hr.employee.reports_to_employee_id} (see {@code LeaveService#isDirectManager}).
 * This class does not test that rule -- it proves V121 writes the DATA the rule reads correctly,
 * and (via the wrong-way-round {@code approve()} case) that the rule really does key off the
 * corrected data end to end, not just that a SELECT returns the right number.
 */
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
        // The old ad-hoc paper approvers -- position พนักงาน, no longer authorised. Used below as
        // each reportee's WRONG starting manager, so the assertions prove they get REPLACED, not
        // just that some value happens to already be correct.
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

        // The wrong-way-round case: neither old ad-hoc approver is anybody's manager any more, and
        // this is asserted where it actually matters -- through the real service's authorization
        // gate, not just a raw SELECT. A Sales Support 1 employee's leave can no longer be approved
        // by ยุทธนา (the old paper approver); it CAN be approved by จินตนา (the corrected data).
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

    @Test
    void theTwoDepartmentManagersKeepReportingToRamWhenNotSelfFiled() {
        // Owner-verified against prod (not an inference): จินตนา and จริญญา BOTH already carry
        // reports_to_employee_id -> 10009 today. This is the "normal", not-self-filed case (neither
        // is under one of the departments she manages) -- the migration must PRESERVE this, not
        // merely avoid breaking it. See aManagerNeverEndsUpReportingToHerself for the adversarial
        // self-filed case.
        long ram = insertEmployee("10009", null, null, null, true);
        long jintana = insertEmployee("10049", null, null, ram, true);
        long jarinya = insertEmployee("10014", null, null, ram, true);

        executeStatements(migrationSql);

        assertThat(reportsTo(jintana)).as("จินตนา still reports to ราม after migration").isEqualTo(ram);
        assertThat(reportsTo(jarinya)).as("จริญญา still reports to ราม after migration").isEqualTo(ram);
    }

    @Test
    void warehouseEmployeeWithAnOddExistingManagerMovesToTheDesignatedApprover() {
        // Prod fact (owner-verified, not an inference): 10017 ศรรักษ์ โคตรเมือง, ผู้ช่วยผู้จัดการ in
        // คลังสินค้า, currently reports to 10001 (the chairman) -- not จริญญา. He is not the
        // designated warehouse approver; จริญญา is. His position (ผู้ช่วยผู้จัดการ) does not exempt
        // him from this migration -- it maps by DEPARTMENT only, never by position, so he still
        // moves to 10014 like every other active employee in the department.
        long jarinya = insertEmployee("10014", null, null, null, true);
        long chairman = insertEmployee("10001", null, null, null, true);
        long warehouse = insertDepartment("WH", "คลังสินค้า");
        long sornrak = insertEmployee("10017", warehouse, null, chairman, true);

        assertThat(reportsTo(sornrak)).as("starts on the odd, non-approver line").isEqualTo(chairman);

        executeStatements(migrationSql);

        assertThat(reportsTo(sornrak))
            .as("10017 moves off the chairman onto the designated warehouse approver จริญญา")
            .isEqualTo(jarinya);
    }

    @Test
    void warehouseEmployeeWithNoExistingManagerGetsOneAssigned() {
        // Prod fact (owner-verified, not an inference): 10025 วิรัตน์ ชื่นชอบวงศ์, พนักงาน in
        // คลังสินค้า, currently has reports_to_employee_id = NULL. A NULL -> real value assignment
        // is a different code path from replacing an existing value (an UPDATE filtered on
        // "reports_to_employee_id IS NOT NULL", or any other accidental narrowing, would silently
        // skip him) -- asserted on its own rather than folded into a department-level test so a
        // regression here cannot hide behind an already-passing non-NULL case.
        long jarinya = insertEmployee("10014", null, null, null, true);
        long warehouse = insertDepartment("WH", "คลังสินค้า");
        long wirat = insertEmployee("10025", warehouse, null, null, true);

        assertThat(reportsTo(wirat)).as("starts with no manager at all").isNull();

        executeStatements(migrationSql);

        assertThat(reportsTo(wirat))
            .as("NULL must become the designated warehouse approver, not stay NULL")
            .isEqualTo(jarinya);
    }

    @Test
    void aManagerNeverEndsUpReportingToHerself() {
        // Defensive edge case the migration's own comment calls out: if จินตนา or จริญญา is ever
        // filed INTO one of the very departments she manages, the blanket department UPDATE must
        // not point her at herself -- she keeps reporting to ราม instead.
        long ram = insertEmployee("10009", null, null, null, true);
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1");
        long warehouse = insertDepartment("WH", "คลังสินค้า");
        long jintana = insertEmployee("10049", salesSupport1, null, null, true);
        long jarinya = insertEmployee("10014", warehouse, null, null, true);

        executeStatements(migrationSql);

        assertThat(reportsTo(jintana)).as("จินตนา reports to ราม, never to herself").isEqualTo(ram);
        assertThat(reportsTo(jintana)).isNotEqualTo(jintana);
        assertThat(reportsTo(jarinya)).as("จริญญา reports to ราม, never to herself").isEqualTo(ram);
        assertThat(reportsTo(jarinya)).isNotEqualTo(jarinya);

        // The CHECK constraint the task asked for already exists (hr.employee.chk_no_self_manager,
        // V1) -- this proves it, directly, by attempting the exact write V121's guards exist to
        // prevent and confirming Postgres rejects it outright.
        assertThatThrownBy(() -> jdbc.getJdbcOperations().execute(
                "UPDATE hr.employee SET reports_to_employee_id = employee_id WHERE employee_id = " + jintana))
            .as("chk_no_self_manager must reject a direct attempt at self-reference")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hrAndAdministrationRowsAreUntouchedByTheMigration() {
        long hrDept = insertDepartment("HR", "บุคคล");
        long adminDept = insertDepartment("MD", "บริหาร");
        long arbitraryPriorManager = insertEmployee("EMP-PRIOR-MGR", null, null, null, true);
        long hrEmployee = insertEmployee("EMP-HR-1", hrDept, null, arbitraryPriorManager, true);
        long adminEmployee = insertEmployee("EMP-ADM-1", adminDept, null, arbitraryPriorManager, true);

        Long hrBefore = reportsTo(hrEmployee);
        Long adminBefore = reportsTo(adminEmployee);
        assertThat(hrBefore).isEqualTo(arbitraryPriorManager);
        assertThat(adminBefore).isEqualTo(arbitraryPriorManager);

        executeStatements(migrationSql);

        assertThat(reportsTo(hrEmployee)).as("HR is out of scope -- unchanged").isEqualTo(hrBefore);
        assertThat(reportsTo(adminEmployee)).as("บริหาร is out of scope -- unchanged").isEqualTo(adminBefore);
    }

    @Test
    void theThreeExplicitlyIgnoredEmployeesAreUnchanged() {
        long arbitraryPriorManager = insertEmployee("EMP-PRIOR-MGR-2", null, null, null, true);
        long ignored1 = insertEmployee("10016", null, null, arbitraryPriorManager, true);
        long ignored2 = insertEmployee("10030", null, null, arbitraryPriorManager, true);
        long ignored3 = insertEmployee("GLR-1001", null, null, arbitraryPriorManager, true);

        executeStatements(migrationSql);

        assertThat(reportsTo(ignored1)).isEqualTo(arbitraryPriorManager);
        assertThat(reportsTo(ignored2)).isEqualTo(arbitraryPriorManager);
        assertThat(reportsTo(ignored3)).isEqualTo(arbitraryPriorManager);
    }

    @Test
    void theNoOrphanApproverGuardRejectsAnActiveEmployeeInAMappedDepartmentWithNoApprover() {
        // Proves the DO $$ ... $$ post-condition guard at the end of Part A CAN actually fire --
        // an assertion never observed to fail is not evidence (CLAUDE.md's mutation-check rule,
        // applied here to a guard written in SQL rather than Java). Deliberately omit the '10049'
        // approver row itself: with no employee_code '10049' to join against, the SALES department
        // UPDATE above no-ops for this employee (its JOIN matches nothing), leaving her
        // reports_to_employee_id NULL exactly as seeded -- precisely the silently-orphaned state the
        // guard exists to catch.
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1");
        long orphan = insertEmployee("EMP-ORPHAN", salesSupport1, null, null, true);
        assertThat(reportsTo(orphan)).isNull();

        assertThatThrownBy(() -> executeStatements(migrationSql))
            .as("the no-orphan-approver guard must reject a mapped-department employee left with no approver")
            .hasMessageContaining("V121")
            .hasMessageContaining("no leave approver");
    }

    @Test
    void theNoOrphanApproverGuardDoesNotFireWhenTheApproverIsPresent() {
        // The other side of the same fixture shape as the test above (CLAUDE.md's "assert both
        // sides on one fixture" rule, applied to a guard that fires vs. one that doesn't): identical
        // department and employee, but with 10049 present this time so the UPDATE actually assigns
        // an approver -- the guard must NOT fire, and the replay must complete normally.
        long jintana = insertEmployee("10049", null, null, null, true);
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1");
        long notOrphaned = insertEmployee("EMP-NOT-ORPHAN", salesSupport1, null, null, true);

        executeStatements(migrationSql);

        assertThat(reportsTo(notOrphaned)).isEqualTo(jintana);
    }

    @Test
    void inactiveEmployeesInAMappedDepartmentAreUntouched() {
        long jintana = insertEmployee("10049", null, null, null, true);
        long salesSupport1 = insertDepartment("SALES", "ฝ่ายสนับสนุนการขาย 1");
        long yutthana = insertEmployee("10063", null, null, null, true);
        long inactiveEmployee = insertEmployee("EMP-INACTIVE", salesSupport1, null, yutthana, false);

        executeStatements(migrationSql);

        assertThat(reportsTo(inactiveEmployee))
            .as("is_active = FALSE rows keep whatever they had")
            .isEqualTo(yutthana);
        assertThat(reportsTo(inactiveEmployee)).isNotEqualTo(jintana);
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Strips {@code --} line comments first, then splits into statements on {@code ;} -- mirrors
     * PayrollComponentTaxTreatmentBackfillIntegrationTest#executeStatements, EXTENDED to be
     * dollar-quote aware: V121 contains a {@code DO $$ ... $$} block (the "nobody left without an
     * approver" post-condition) whose body contains its own {@code ;} terminators that must NOT
     * split the block apart. A semicolon is only a statement boundary when it appears OUTSIDE a
     * {@code $$ ... $$} region.
     */
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
