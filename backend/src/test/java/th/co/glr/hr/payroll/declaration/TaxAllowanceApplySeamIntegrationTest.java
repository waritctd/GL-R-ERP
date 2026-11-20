package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollCalculator;
import th.co.glr.hr.payroll.PayrollComponent;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollPeriodDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayslipRenderer;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Pins the APPROVAL seam — through the REAL PayrollRepository#findTaxAllowancesByEmployee SQL and the REAL
 * PayrollService#preview, never a unit-test stand-in. Decision #2 was reversed by the owner on 2026-08-31. */
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;

import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;

class TaxAllowanceApplySeamIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository declarationRepository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    private long employeeId;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        declarationRepository = new TaxAllowanceDeclarationRepository(jdbc);
        payrollRepository = new PayrollRepository(jdbc);

        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties(),
            new th.co.glr.hr.payroll.obligation.DeductionObligationService(
                new th.co.glr.hr.payroll.obligation.DeductionObligationRepository(jdbc),
                mock(th.co.glr.hr.employee.EmployeeRepository.class),
                mock(AuditService.class),
                new th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallRepository(jdbc)));

        service = new TaxAllowanceDeclarationService(
            declarationRepository,
            payrollRepository,
            mock(EmployeeRepository.class),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            // Evidence upload is not exercised here — a mock is enough.
            mock(FileStorageService.class),
            // The REAL PayrollService, wired above — this class's byte-for-byte pinning test needs estimateAllowanceEffect
            // to run the SAME calculator instance #preview does.
            payrollService,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — this class is about the apply() seam / byte-for-byte pinning.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeId = seedEmployee("SEAM-EMP", new BigDecimal("50000.00"));
        hrEmployeeId = seedEmployee("SEAM-HR", new BigDecimal("50000.00"));
        seedRegularTaxTreatment(employeeId, 2026, PayrollComponent.SPECIAL_PAY_1);
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
    }

    /** The reversed decision #2: approval alone is go-live, with no second Apply call anywhere. */
    @Test
    void approvingPutsItInTheParentTableAsVerifiedWithNoSeparateApply() {
        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));
        approveSigned(declaration.declarationId());

        TaxAllowanceDeclarationDto approved = declarationRepository.findById(declaration.declarationId()).orElseThrow();
        assertThat(approved.status()).isEqualTo(TaxAllowanceDeclarationStatus.APPROVED);
        assertThat(approved.appliedAt()).as("approve() promotes in its own transaction now").isNotNull();
        assertThat(approved.appliedEffectiveMonth())
            .as("whole tax year, never a chosen month")
            .isEqualTo(1);
        assertThat(approved.expiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));

        // A second promotion is refused -- the applied_at IS NULL guard in markApplied.
        assertThatThrownBy(() -> service.apply(declaration.declarationId(), hrActor()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(countEmployeeTaxAllowanceRows(employeeId)).isEqualTo(1);
        String verificationStatus = jdbc.queryForObject(
            "SELECT verification_status FROM hr.employee_tax_allowance WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
        assertThat(verificationStatus).isEqualTo("VERIFIED");

        Map<Long, th.co.glr.hr.payroll.PayrollTaxAllowanceInput> resolved =
            payrollRepository.findTaxAllowancesByEmployee(LocalDate.of(2026, 6, 1));
        assertThat(resolved).containsKey(employeeId);
        assertThat(resolved.get(employeeId).spouseAllowance()).isEqualByComparingTo("60000.00");
    }

    @Test
    void aFullPayrollPreviewIsUnchangedBySubmittingAndThenMovesOnApproval() {
        PayrollLineDto before = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));

        TaxAllowanceDeclarationDto declaration = submit(new BigDecimal("60000"));
        PayrollLineDto afterSubmit = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));
        assertThat(afterSubmit.taxAllowanceTotal())
            .as("submitting must not move payroll at all")
            .isEqualByComparingTo(before.taxAllowanceTotal());
        assertThat(afterSubmit.withholdingTax()).isEqualByComparingTo(before.withholdingTax());

        approveSigned(declaration.declarationId());
        PayrollLineDto afterApprove = lineFor(payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrPrincipal()));
        assertThat(afterApprove.taxAllowanceTotal())
            .as("approving IS go-live since 2026-08-31 — June must now see the ฿60,000")
            .isEqualByComparingTo(before.taxAllowanceTotal().add(new BigDecimal("60000.00")));
        assertThat(afterApprove.withholdingTax())
            .as("and less allowance-free income means strictly less withholding")
            .isLessThan(before.withholdingTax());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationDto submit(BigDecimal spouseAllowance) {
        TaxAllowanceDeclarationSubmitRequest request = new TaxAllowanceDeclarationSubmitRequest(
            2026,                     // taxYear
            spouseAllowance,          // spouseAllowance
            null, null, null, null,   // child, parentCare, disabledCare, maternity
            null, null, null,         // life, health, parentHealth
            null, null, null, null,   // rmf, ssf, pension, thaiEsg
            null, null, null, null,   // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,         // childCount, childCountDouble, disabledCareCount
            null,                     // disabilityCardHolder
            null,                     // parentCareCount
            null,                    // documentReference
            null);                   // lorYor01 — no ล.ย.01 form detail in this fixture
        return service.submitOwn(request, employeeActor());
    }

    private int countEmployeeTaxAllowanceRows(long employeeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.employee_tax_allowance WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Integer.class);
        return count == null ? 0 : count;
    }

    private PayrollLineDto lineFor(PayrollPeriodDto period) {
        return period.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private long seedEmployee(String code, BigDecimal salary) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, current_salary, is_active) VALUES (:code, :salary, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code, "salary", salary), Long.class);
    }

    private UserPrincipal employeeActor() {
        return new UserPrincipal(employeeId, "seam@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrPrincipal() {
        return hrActor();
    }

    /** Approves the way HR now has to: the signed ล.ย.01 must be attached first (owner decision #3). The failure
     * cases below deliberately do NOT use this — they assert on the role and status checks, which both run before
     * the signed-form check and so are unaffected by it. */
    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }
}
