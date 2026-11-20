package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
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

/** Manual commission entries (feat/commission-manual-adjustments) — real-DB acceptance coverage. */
class ManualCommissionIntegrationTest extends AbstractPostgresIntegrationTest {
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
    private UserPrincipal hrActor;

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
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย แมนนวล", "sm-manual@glr.co.th", "SA", "แผนกขาย");
        managerActor = new UserPrincipal(managerEmployeeId, managerEmployeeId + "@glr.co.th", "Sales Manager",
            "sales_manager", managerEmployeeId, true, LocalDate.now(), false, null, false);
        ceoEmployeeId = createEmployee("ผู้บริหาร แมนนวล", "ceo-manual@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = new UserPrincipal(ceoEmployeeId, ceoEmployeeId + "@glr.co.th", "CEO",
            "ceo", ceoEmployeeId, true, LocalDate.now(), false, null, false);
        hrActor = new UserPrincipal(999_777L, "hr-manual@glr.co.th", "HR", "hr",
            999_777L, true, LocalDate.now(), false, null, false);
    }

    // AUTHZ — wrong-way-round: a sales actor and a plain rep must NOT be able to create a manual commission entry.
    // Zero rows created, not just a 403 status code.

    @Test
    void salesActor_cannotCreateManualCommission_zeroRowsCreated() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย แมนนวล", "sales-manual@glr.co.th", "SA", "แผนกขาย");
        UserPrincipal salesActor = new UserPrincipal(salesRepId, salesRepId + "@glr.co.th", "Sales Rep", "sales",
            salesRepId, true, LocalDate.now(), false, null, false);
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"), "trying to self-approve", PAYROLL_MONTH, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // Positive-side sales_manager/ceo access, both are allowed (right-way-round sanity check alongside the
    // wrong-way-round tests above).

    @Test
    void managerAndCeo_canCreateManualCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย เข้าถึงได้", "sales-allowed-manual@glr.co.th", "SA", "แผนกขาย");

        CommissionRecord byManager = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "manager-created", PAYROLL_MONTH, managerActor);
        assertThat(byManager.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);

        CommissionRecord byCeo = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "ceo-created", PAYROLL_MONTH, ceoActor);
        assertThat(byCeo.status()).isEqualTo(CommissionStatus.APPROVED);
    }

    // Positive ADJUSTMENT: manager creates it (MANAGER_APPROVED, not yet in payroll), CEO approves it via the
    // EXISTING approve() chain (reused, not reimplemented), and payroll rises by EXACTLY the manual amount, on top
    // of the rep's tier commission.

    @Test
    void managerCreatesPositiveAdjustment_ceoApproves_addsExactlyToPayrollOnTopOfTierCommission() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับเพิ่ม", "adjust-plus@glr.co.th", "SA", "แผนกขาย");
        // Tier baseline: a real APPROVED SALE commission so there is a genuine non-zero tier commission to add the
        // manual amount "on top of".
        long saleCommissionId = seedApprovedTierCommission(salesRepId, new BigDecimal("857288.28"));

        BigDecimal beforeCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        BigDecimal beforeBase = repSummaryFor(payrollReadySummary(), salesRepId).commissionableBase();
        assertThat(beforeCommission).isGreaterThan(BigDecimal.ZERO);

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"),
            "takeover credit split — interim manual entry", PAYROLL_MONTH, managerActor);
        assertThat(created.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        assertThat(created.manualAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(created.invoiceDetails()).isNull();
        assertThat(created.sourceTicketId()).isNull();

        // Not yet APPROVED -- must NOT count toward payroll yet.
        BigDecimal stillPending = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        assertThat(stillPending).isEqualByComparingTo(beforeCommission);

        commissionService.approve(created.id(), ceoActor); // MANAGER_APPROVED -> APPROVED, reused chain
        CommissionRecord approved = commissions.findById(created.id()).orElseThrow();
        assertThat(approved.status()).isEqualTo(CommissionStatus.APPROVED);

        SalesRepCommissionSummaryDto after = repSummaryFor(payrollReadySummary(), salesRepId);
        assertThat(after.commissionAmount().subtract(beforeCommission)).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(after.manualAdjustmentAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        // The tier base itself is untouched by the manual entry -- only the final commission total gained the manual
        // amount.
        assertThat(after.commissionableBase()).isEqualByComparingTo(beforeBase);
        assertThat(saleCommissionId).isPositive();
    }

    // Negative ADJUSTMENT: reduces the payroll total by exactly the (negative) manual amount.

    @Test
    void negativeAdjustment_reducesPayrollTotalByExactAmount() {
        wireService();
        long salesRepId = createEmployee("พนักงานขาย ปรับลด", "adjust-minus@glr.co.th", "SA", "แผนกขาย");
        seedApprovedTierCommission(salesRepId, new BigDecimal("857288.28"));
        BigDecimal beforeCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("-2000.00"),
            "correction: overpaid last cycle", PAYROLL_MONTH, managerActor);
        commissionService.approve(created.id(), ceoActor);

        BigDecimal afterCommission = repSummaryFor(payrollReadySummary(), salesRepId).commissionAmount();
        assertThat(beforeCommission.subtract(afterCommission)).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    // Helpers

    private PayrollCommissionSummaryDto payrollReadySummary() {
        return commissionService.payrollReadySummary(PAYROLL_MONTH, hrActor);
    }

    private SalesRepCommissionSummaryDto repSummaryFor(PayrollCommissionSummaryDto summary, long salesRepId) {
        return summary.salesReps().stream()
            .filter(s -> s.salesRepId() == salesRepId)
            .findFirst()
            .orElseThrow();
    }

    /** Seeds one real, fully APPROVED (manager + CEO) SALE commission via the real repository — mirroring what
     * CommissionService#submit persists, without the full ticket/deal fixture chain this task doesn't touch. */
    private long seedApprovedTierCommission(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-MANUAL-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        long commissionId = commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private int countCommissionRecords() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.commission_record", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }
}
