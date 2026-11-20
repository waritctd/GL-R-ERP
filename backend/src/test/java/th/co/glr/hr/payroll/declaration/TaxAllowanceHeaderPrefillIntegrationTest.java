package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01HeaderPrefill;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The ล.ย.01 header prefill (owner decision #4, the read half) against real Postgres, through the real
 * TaxAllowanceDeclarationService and the real EmployeeRepository. What is actually at stake
 * hr_restricted.employee_pii.tax_id is PDPA-restricted. */
class TaxAllowanceHeaderPrefillIntegrationTest extends AbstractPostgresIntegrationTest {

    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;

    private static final String TAX_ID_B = "9876543210987";

    @BeforeEach
    void wireRealCollaborators() {
        service = new TaxAllowanceDeclarationService(
            new TaxAllowanceDeclarationRepository(jdbc),
            new PayrollRepository(jdbc),
            // THE REAL ONE. This is the whole point of the class -- see the header.
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — this class is about the header-prefill READ path.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("PREFILL-A", "สมชาย", "ใจดี", "โสด");
        employeeB = seedEmployee("PREFILL-B", "สมหญิง", "รักษ์ไทย", "สมรส");
    }

    // --- the wrong-way-round case ---------------------------------------------------------------

    /** A reads their own form; B's restricted tax ID must not be anywhere in the answer. Here the only way A's
     * response can carry a tax ID at all is if it leaked B's. */
    @Test
    void employeeCannotObtainAnotherEmployeesTaxIdThroughTheHeaderPrefill() {
        givenTaxId(employeeB, TAX_ID_B);
        // A has no employee_pii row at all -- the common production state.

        LorYor01HeaderPrefill prefill = prefillFor(employeeA);

        assertThat(prefill.taxpayerId())
            .as("employee A has no tax ID of their own; anything here came from another employee's row")
            .isNull();
        assertThat(prefill.firstNameTh())
            .as("the prefill must be A's identity, not whichever row the query happened to reach")
            .isEqualTo("สมชาย");
        assertThat(prefill.lastNameTh()).isEqualTo("ใจดี");
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Always through the real endpoint method, never the repository directly — the scoping is the service's. */
    private LorYor01HeaderPrefill prefillFor(long employeeId) {
        MyTaxAllowanceDeclarationsResponse response = service.getOwn(2026, employeeActor(employeeId));
        return response.headerPrefill();
    }

    private void givenTaxId(long employeeId, String taxId) {
        jdbc.update("INSERT INTO hr_restricted.employee_pii(employee_id, tax_id) VALUES (:id, :taxId)",
            new MapSqlParameterSource("id", employeeId).addValue("taxId", taxId));
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, String maritalStatus) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, is_active, first_name_th, last_name_th, marital_status)
            VALUES (:code, TRUE, :firstNameTh, :lastNameTh, :maritalStatus)
            RETURNING employee_id
            """,
            Map.of("code", code, "firstNameTh", firstNameTh, "lastNameTh", lastNameTh,
                "maritalStatus", maritalStatus),
            Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
