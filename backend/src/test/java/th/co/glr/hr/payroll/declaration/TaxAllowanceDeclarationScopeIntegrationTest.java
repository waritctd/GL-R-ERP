package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Confirms the tax-allowance declaration workflow's authorization against the real service and the real SQL —
 * the gap Mockito-based unit tests cannot cover (issue #28), same reasoning as AttendanceScopeIntegrationTest,
 * which this class is modelled on directly. */
import th.co.glr.hr.config.AppProperties;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceDeclarationScopeIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        payrollRepository = new PayrollRepository(jdbc);
        service = new TaxAllowanceDeclarationService(
            repository,
            payrollRepository,
            // A mock is enough for what THIS class asserts. The prefill's own scoping cannot be tested through a mock at
            // all (CLAUDE.md: a mocked repository passes while the SQL does something else), so it has its own class
            // against real Postgres -- TaxAllowanceHeaderPrefillIntegrationTest.
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            // Evidence upload/estimate are not exercised by this scope test (that is
            // TaxAllowanceAttachmentScopeIntegrationTest's and the estimate tests' job) — mocks are enough here.
            mock(FileStorageService.class),
            mock(PayrollService.class),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — every submitOwn call in this file writes a real TAX_ALLOWANCE_SUBMITTED row that
            // nothing in this class asserts on.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("TAD-A");
        employeeB = seedEmployee("TAD-B");
        // reviewed_by_id/applied_by_id/submitted_by_id are real FKs onto hr.employee — the HR/CEO actors below need a
        // real backing row, not just an arbitrary UserPrincipal id.
        hrEmployeeId = seedEmployee("TAD-HR");
        ceoEmployeeId = seedEmployee("TAD-CEO");
    }

    // --- read/write scoping ---------------------------------------------------------------------

    /** The rendered ล.ย.01 is at least as sensitive as the evidence behind it — it carries the employee's 13-digit
     * tax ID, home address and every declared amount on one page. Wrong-way-round per CLAUDE.md: assert the caller
     * who should NOT reach it gets nothing. */
    /** The signed ล.ย.01 is a real precondition, not just a UI courtesy (owner decision #3). The declaration must
     * also survive as PENDING — a refused approval that quietly advanced the status would be worse than no guard
     * at all. */
    @Test
    void hrCannotApproveADeclarationWithNoSignedFormAttached() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.approve(declaration.declarationId(), null, hrActor()))
            .as("approving an unsigned ล.ย.01 records HR as accepting a filing nobody attested to")
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.PENDING);
    }

    /** …and it goes through the moment the signed scan is there. */
    @Test
    void theSameDeclarationApprovesOnceTheSignedFormIsAttached() {
        TaxAllowanceDeclarationDto declaration = submit(employeeA, 2026, new BigDecimal("60000"));

        approveSigned(declaration.declarationId());

        assertThat(repository.findById(declaration.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
    }

    @Test
    void employeeCannotApproveTheirOwnDeclarationAndItStaysPending() {
        TaxAllowanceDeclarationDto own = submit(employeeA, 2026, new BigDecimal("60000"));

        assertThatThrownBy(() -> service.approve(own.declarationId(), null, employeeActor(employeeA)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(repository.findById(own.declarationId()).orElseThrow().status())
            .isEqualTo(TaxAllowanceDeclarationStatus.PENDING);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationDto submit(long employeeId, int taxYear, BigDecimal spouseAllowance) {
        return service.submitOwn(submitRequest(taxYear, spouseAllowance), employeeActor(employeeId));
    }

    /** Extracted so the draft-render test can build the same body without persisting it. */
    private TaxAllowanceDeclarationSubmitRequest submitRequest(int taxYear, BigDecimal spouseAllowance) {
        return new TaxAllowanceDeclarationSubmitRequest(
            taxYear,                 // taxYear
            spouseAllowance,         // spouseAllowance
            null, null, null, null,  // child, parentCare, disabledCare, maternity
            null, null, null,        // life, health, parentHealth
            null, null, null, null,  // rmf, ssf, pension, thaiEsg
            null, null, null, null,  // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,        // childCount, childCountDouble, disabledCareCount
            null,                    // disabilityCardHolder
            null,                    // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    /** Approves the way HR now has to: the signed ล.ย.01 must be attached first (owner decision #3). The failure
     * cases below deliberately do NOT use this — they assert on the role and status checks, which both run before
     * the signed-form check and so are unaffected by it. */
    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }
}
