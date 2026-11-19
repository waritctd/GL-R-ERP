package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Issue #737 — the manual-commission rep picker's data source, GET /api/commissions/reps (backed by
 * CommissionService#listManualCommissionRepOptions), proved against real Postgres. Model and helper shape
 * follow ManualCommissionIntegrationTest and CommissionListScopeIntegrationTest in this package. */
class CommissionRepLookupIntegrationTest extends AbstractPostgresIntegrationTest {

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private EmployeeRepository employees;

    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesActor;
    private UserPrincipal hrActor;
    private UserPrincipal accountActor;
    private UserPrincipal employeeActor;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));

        long managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย รายชื่อ", "reps-manager@glr.co.th", "SA", "แผนกขาย");
        long ceoEmployeeId = createEmployee("ผู้บริหาร รายชื่อ", "reps-ceo@glr.co.th", "MD", "ผู้บริหาร");
        // Both actors carry their REAL division_id via the 3-arg principal()/divisionIdOf() helpers, deliberately,
        // even though the current code no longer reads divisionId for EITHER role (scoping is
        // division-of-the-TARGET-employee now, not division-of-the-caller).
        managerActor = principal(managerEmployeeId, "sales_manager", divisionIdOf(managerEmployeeId));
        ceoActor = principal(ceoEmployeeId, "ceo", divisionIdOf(ceoEmployeeId));

        // Denied roles are rejected at the role gate before any DB lookup (same as
        // CommissionListScopeIntegrationTest's importActor/employeeActor/etc.) — synthetic ids.
        salesActor = principal(999_711L, "sales");
        hrActor = principal(999_722L, "hr"); // hr CAN read /api/employees; must NOT reach this narrower list.
        accountActor = principal(999_733L, "account");
        employeeActor = principal(999_744L, "employee"); // DivisionAccessPolicy fallback role.
    }

    // ── 1-4: wrong-way-round — denied roles get FORBIDDEN, never a list ─────────────────────

    @Test
    void salesActor_isForbidden() {
        wireService();
        assertThatThrownBy(() -> commissionService.listManualCommissionRepOptions(salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── 5-6: positive side — issue #737's actual symptom, an employee who owns no deal ──────

    @Test
    void salesManager_seesAnEmployeeWhoOwnsNoDealAtAll() {
        wireService();
        // The whole point of #737: this employee has NO ticket, NO deal, nothing in sales.commission_record --
        // TicketRepository is mocked in this class (never touched by listManualCommissionRepOptions), so there is no
        // code path here that could even create one.
        long noDealEmployeeId = createEmployee("พนักงานขาย ไม่มีดีลเลย", "reps-no-deal@glr.co.th", "SA", "แผนกขาย");

        List<CommissionRepOptionDto> reps = commissionService.listManualCommissionRepOptions(managerActor);

        CommissionRepOptionDto found = reps.stream()
            .filter(rep -> rep.id() == noDealEmployeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("employee with no deal at all is missing from the rep picker"));
        assertThat(found.name()).isEqualTo("พนักงานขาย ไม่มีดีลเลย");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** Reads back the real division_id an employee's creation resolved to (via
     * EmployeeReferenceRepository#ensureDivision's get-or-create-by-source_code). */
    private long divisionIdOf(long employeeId) {
        return jdbc.queryForObject(
            "SELECT division_id FROM hr.employee WHERE employee_id = :id", Map.of("id", employeeId), Long.class);
    }

    /** Denied-role callers: no employee-linked division. */
    private static UserPrincipal principal(long employeeId, String role) {
        return principal(employeeId, role, null);
    }

    /** As #principal(long, String), plus an explicit divisionId — kept realistic for managerActor/ceoActor (see
     * #wireService()'s comment) even though the current scope rule no longer reads either actor's own division. */
    private static UserPrincipal principal(long employeeId, String role, Long divisionId) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, divisionId, false);
    }
}
