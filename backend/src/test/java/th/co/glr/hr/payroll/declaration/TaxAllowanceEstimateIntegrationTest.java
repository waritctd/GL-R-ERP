package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollAllowanceEstimateResult;
import th.co.glr.hr.payroll.PayrollCalculator;
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

/**
 * Tax-effect estimate (decision #4, "what this saves me") — driven through the real {@link
 * PayrollService#estimateAllowanceEffect}, real {@link PayrollCalculator}, and real Postgres, per
 * {@code AttendanceScopeIntegrationTest}'s house standard. This is the pin against ever
 * reimplementing Thai tax math anywhere else: every assertion compares the estimate's own numbers
 * against a REAL {@link PayrollService#preview} run for the same employee/month, never a
 * hand-computed expectation.
 */
class TaxAllowanceEstimateIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository declarationRepository;
    private TaxAllowanceDeclarationService service;
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

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
            mock(AttachmentRepository.class));
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
            mock(FileStorageService.class),
            payrollService,
            new NotificationRepository(jdbc));

        hrEmployeeId = seedEmployee("EST-HR", new BigDecimal("50000.00"), "M");
    }

    /**
     * The request DTO ({@code TaxAllowanceDeclarationSubmitRequest}, reused verbatim as the
     * estimate body) has no {@code employeeId} field at all — {@code estimateOwn} resolves the
     * employee EXCLUSIVELY from {@code actor.employeeId()}. Pinned here not by trying to forge a
     * field that does not exist, but by proving the numbers themselves never cross between two
     * employees with deliberately different salaries: if the guard were broken and one call could
     * read the other's data, these two results would collide.
     */
    @Test
    void employeeCannotEstimateForAnotherEmployeeTheNumbersNeverCrossOver() {
        long employeeA = seedEmployee("EST-A", new BigDecimal("30000.00"), "M");
        long employeeB = seedEmployee("EST-B", new BigDecimal("90000.00"), "M");

        PayrollAllowanceEstimateResult resultA =
            service.estimateOwn(estimateRequest(new BigDecimal("60000")), employeeActor(employeeA));
        PayrollAllowanceEstimateResult resultB =
            service.estimateOwn(estimateRequest(new BigDecimal("60000")), employeeActor(employeeB));

        assertThat(resultA.estimateAvailable()).isTrue();
        assertThat(resultB.estimateAvailable()).isTrue();
        assertThat(resultA.baselineWithholdingTax())
            .as("A's estimate must reflect A's own ฿30,000 salary, never B's ฿90,000 one")
            .isNotEqualByComparingTo(resultB.baselineWithholdingTax());
        assertThat(resultA.proposedWithholdingTax()).isNotEqualByComparingTo(resultB.proposedWithholdingTax());
    }

    @Test
    void estimateForADailyRateEmployeeReportsUnavailableRatherThanAFabricatedZero() {
        long employeeId = seedEmployee("EST-D", new BigDecimal("450.00"), "D");

        PayrollAllowanceEstimateResult result =
            service.estimateOwn(estimateRequest(new BigDecimal("60000")), employeeActor(employeeId));

        assertThat(result.estimateAvailable())
            .as("no daysWorked exists outside a real run -- must not silently estimate against a ฿0 gross")
            .isFalse();
        assertThat(result.unavailableReason()).isNotBlank();
        assertThat(result.baselineWithholdingTax()).isNull();
        assertThat(result.proposedWithholdingTax()).isNull();
    }

    /**
     * THE anti-drift pin: estimate, then actually submit/approve/apply the SAME declaration and run
     * a real {@link PayrollService#preview} for the same month — {@code withholdingTax} must be
     * byte-for-byte equal. If the estimate's private calculation ever diverged from the real
     * {@code calculateLine} path (a different collaborator wired, a merge-order bug), this is the
     * test that would catch it — no other assertion in this suite compares an estimate against a
     * real run.
     */
    @Test
    void estimateIsByteForByteEqualToARealApplyAndPreviewForTheSameMonth() {
        long employeeId = seedEmployee("EST-P", new BigDecimal("50000.00"), "M");
        BigDecimal spouseAllowance = new BigDecimal("60000");

        PayrollAllowanceEstimateResult estimate =
            service.estimateOwn(estimateRequest(spouseAllowance), employeeActor(employeeId));
        assertThat(estimate.estimateAvailable()).isTrue();

        TaxAllowanceDeclarationDto declaration =
            service.submitOwn(submitRequest(spouseAllowance), employeeActor(employeeId));
        service.approve(declaration.declarationId(), null, hrActor());
        service.apply(declaration.declarationId(), null, hrActor());

        PayrollPeriodDto realPeriod = payrollService.preview(
            new ProcessPayrollRequest(LocalDate.of(2026, 6, 1), List.of()), hrActor());
        PayrollLineDto realLine = realPeriod.lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));

        assertThat(estimate.proposedWithholdingTax())
            .as("the estimate's proposed figure must byte-for-byte match a real preview after apply")
            .isEqualByComparingTo(realLine.withholdingTax());
        assertThat(estimate.proposedTaxAllowanceTotal()).isEqualByComparingTo(realLine.taxAllowanceTotal());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private TaxAllowanceDeclarationSubmitRequest estimateRequest(BigDecimal spouseAllowance) {
        return submitRequest(spouseAllowance);
    }

    private TaxAllowanceDeclarationSubmitRequest submitRequest(BigDecimal spouseAllowance) {
        return new TaxAllowanceDeclarationSubmitRequest(
            2026,                     // taxYear
            6,                        // effectiveMonth
            spouseAllowance,          // spouseAllowance
            null, null, null, null,   // child, parentCare, disabledCare, maternity
            null, null, null,         // life, health, parentHealth
            null, null, null, null,   // rmf, ssf, pension, thaiEsg
            null, null, null, null,   // homeLoan, educationDonation, generalDonation, politicalDonation
            null, null, null,         // childCount, childCountDouble, disabledCareCount
            null,                     // disabilityCardHolder
            null,                     // parentCareCount
            null);                    // documentReference
    }

    private long seedEmployee(String code, BigDecimal salary, String payType) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, current_salary, pay_type, is_active)
            VALUES (:code, :salary, :payType, TRUE)
            RETURNING employee_id
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("code", code)
                .addValue("salary", salary)
                .addValue("payType", payType),
            Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true, LocalDate.now(), false, null, false);
    }
}
