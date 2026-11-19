package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
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

/** Real-DB coverage for CommissionService#monthlySummary — GET /api/commissions/monthly-summary, the new
 * endpoint that replaces the frontend's former client-side re-implementation of the commission tier math (the
 * deleted commissionCalc.js). */
class CommissionMonthlySummaryIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private CommissionCalculator calculator;
    private EmployeeRepository employees;
    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย สรุปเดือน", "sm-monthlysummary@glr.co.th", "SA", "แผนกขาย");
        managerActor = principal(managerEmployeeId, "sales_manager");
        ceoEmployeeId = createEmployee("ผู้บริหาร สรุปเดือน", "ceo-monthlysummary@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = principal(ceoEmployeeId, "ceo");
    }

    // 3. Matches the authoritative calculator directly -- proves the endpoint reports the engine's own answer, not
    // a parallel computation that happens to agree today.

    @Test
    void tierCommissionMatchesTheAuthoritativeCalculatorDirectly() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ตรงกับเครื่องคำนวณ", "tier-matches-calc@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("1847966.93"));

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, PAYROLL_MONTH);
        BigDecimal base = calculator.monthlyTierBase(weighted);
        BigDecimal expected = calculator.progressiveCommission(base, commissions.findTiers());
        assertThat(dto.tierCommission()).isEqualByComparingTo(expected);
    }

    // 5. Authz, wrong-way-round -- a NEW gate + a NEW row-scope, required per CLAUDE.md.

    @Test
    void salesActor_requestingAnotherRepsSalesRepId_isForbidden() {
        wireService();
        long repA = createEmployee("พนักงานขาย เอ สรุป", "monthlysummary-repa@glr.co.th", "SA", "แผนกขาย");
        long repB = createEmployee("พนักงานขาย บี สรุป", "monthlysummary-repb@glr.co.th", "SA", "แผนกขาย");

        assertThatThrownBy(() -> commissionService.monthlySummary(repB, PAYROLL_MONTH, principal(repA, "sales")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // 6. Manual entries: an APPROVED manual ADJUSTMENT lands in manualTotal/totalCommission but never
    // commissionableBase; a MANAGER_APPROVED (not yet CEO-signed) one counts for neither.

    @Test
    void approvedManualAdjustment_landsInManualTotalAndTotalCommission_notInCommissionableBase() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับปรุงมือ", "monthlysummary-manual@glr.co.th", "SA", "แผนกขาย");
        seedCommissionRecord(salesRepId, new BigDecimal("107000.00")); // base 100,000.00 exactly

        commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("2000.00"),
            "monthly-summary test: approved adjustment", PAYROLL_MONTH, ceoActor); // CEO-created -> APPROVED

        CommissionMonthlySummaryDto dto = monthlySummaryAsSelf(salesRepId);

        assertThat(dto.manualTotal()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(dto.totalCommission())
            .isEqualByComparingTo(dto.tierCommission().add(dto.incentiveAmount()).add(new BigDecimal("2000.00")));
        // Not in the base -- the base reflects only the seeded 107,000.00 receipt.
        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    // Helpers

    private CommissionMonthlySummaryDto monthlySummaryAsSelf(long salesRepId) {
        return commissionService.monthlySummary(salesRepId, PAYROLL_MONTH, principal(salesRepId, "sales"));
    }

    /** Seeds one active commission_record with the given real-cash amount and no deductions via the real repository
     * -- mirrors CommissionCalcRefineIntegrationTest#seedCommissionRecord, without needing the full
     * ticket/deal/invoice-file fixture chain this endpoint doesn't touch. */
    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived) {
        return seedCommissionRecord(salesRepId, actualReceived, PAYROLL_MONTH);
    }

    private long seedCommissionRecord(long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-MONTHLYSUMMARY-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, payrollMonth, calculation);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
