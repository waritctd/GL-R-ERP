package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/** - real-DB, wrong-way-round acceptance coverage for CommissionService#updateDeductions, which had NO status
 * == APPROVED guard and no CommissionService payroll-month-open check at all before this branch. */
class CommissionApprovedRecordImmutableIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate OPEN_MONTH = LocalDate.of(2026, 9, 1);

    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService commissionService;
    private long salesRepId;
    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireService() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));

        long managerEmployeeId = createEmployee(employees, "ผู้จัดการฝ่ายขาย ล็อคยืนยัน", "sm-approvedlock@glr.co.th", "SA", "แผนกขาย");
        managerActor = actor(managerEmployeeId, "sales_manager");
        long ceoEmployeeId = createEmployee(employees, "ผู้บริหาร ล็อคยืนยัน", "ceo-approvedlock@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = actor(ceoEmployeeId, "ceo");
        salesRepId = createEmployee(employees, "พนักงานขาย ล็อคยืนยัน", "sales-approvedlock@glr.co.th", "SA", "แผนกขาย");
    }

    // Point 1 -- APPROVED is frozen: a sales_manager cannot edit an already-approved record, and the stored
    // invoice_details/weight_multiplier are provably untouched, not just a 4xx.

    @Test
    void updateDeductions_onApprovedRecord_bySalesManager_isRefused_storedAmountsUnchanged() {
        long commissionId = seedApprovedTierCommission(salesRepId, OPEN_MONTH, new BigDecimal("1070000.00"));
        InvoiceDetails before = commissions.findById(commissionId).orElseThrow().invoiceDetails();

        UpdateCommissionDeductionsRequest attack = new UpdateCommissionDeductionsRequest(
            new BigDecimal("999999.00"), null, null, null, null, null, null, null, null,
            "should be refused -- record is already APPROVED");

        assertThatThrownBy(() -> commissionService.updateDeductions(commissionId, attack, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                // Distinguishing substring (reviewer finding F5 pattern): must be refused for "already approved", not
                // coincidentally for VOID/REJECTED/manual-kind/closed-month.
                assertThat(e.getMessage()).contains("อนุมัติแล้วไม่สามารถแก้ไขได้");
            });

        InvoiceDetails after = commissions.findById(commissionId).orElseThrow().invoiceDetails();
        assertThat(after.grossAmount()).isEqualByComparingTo(before.grossAmount());
        assertThat(after.grossAmount()).isNotEqualByComparingTo(new BigDecimal("999999.00"));
        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.APPROVED);
    }

    /** The exact defect scenario: 1,070,000.00 actual_received / 1x weight -> base 1,000,000.00 -> tier commission
     * 6,250.00, zero INCENTIVE (tiers 1-4 exactly filled, tier 5's lower bound not crossed -- see
     * TierConfig#defaults(); base is far under V108's lowest 3,000,000.00 INCENTIVE threshold). */
    @Test
    void updateDeductions_weightMultiplierExploitOnApprovedRecord_isRefused_commissionTotalStays6250() {
        long commissionId = seedApprovedTierCommission(salesRepId, OPEN_MONTH, new BigDecimal("1070000.00"));

        BigDecimal totalBefore = commissionService.payrollCommissionTotalsByEmployee(OPEN_MONTH).get(salesRepId);
        assertThat(totalBefore).isEqualByComparingTo(new BigDecimal("6250.00"));

        UpdateCommissionDeductionsRequest exploit = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 3,
            "should be refused -- reweighting an APPROVED record");

        assertThatThrownBy(() -> commissionService.updateDeductions(commissionId, exploit, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getMessage()).contains("อนุมัติแล้วไม่สามารถแก้ไขได้"));

        assertThat(commissions.findById(commissionId).orElseThrow().weightMultiplier()).isEqualTo(1);
        BigDecimal totalAfter = commissionService.payrollCommissionTotalsByEmployee(OPEN_MONTH).get(salesRepId);
        // The bug: this would be 63750.00 (48750.00 tier + 15000.00 INCENTIVE, both legs already live for OPEN_MONTH
        // via V108's real migration-seeded ladder -- not "once configured") instead of unchanged 6250.00.
        assertThat(totalAfter).isEqualByComparingTo(new BigDecimal("6250.00"));
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);
    }

    // The guard is not "deny everything": the legitimate pre-final-approval review edit still works, at both
    // stages a manager/CEO can reach it from (SUBMITTED and MANAGER_APPROVED).

    @Test
    void updateDeductions_onSubmittedRecord_bySalesManager_stillSucceeds() {
        long commissionId = seedSubmittedTierCommission(salesRepId, OPEN_MONTH, new BigDecimal("100000.00"));

        UpdateCommissionDeductionsRequest legitimateEdit = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 2,
            "legitimate manager review -- this receipt counts double per workbook policy");
        CommissionRecord edited = commissionService.updateDeductions(commissionId, legitimateEdit, managerActor);

        assertThat(edited.weightMultiplier()).isEqualTo(2);
        assertThat(commissions.findById(commissionId).orElseThrow().weightMultiplier()).isEqualTo(2);
        assertThat(edited.status()).isEqualTo(CommissionStatus.SUBMITTED);
    }

    // Helpers

    /** Writes a real SUBMITTED SALE commission directly through the repository (bypassing submit()'s own
     * create-time guard -- fixture setup, not the thing under test), no deductions, so actualReceived ==
     * grossAmount exactly. Mirrors CommissionClosedMonthGuardIntegrationTest#seedSubmittedTierCommission. */
    private long seedSubmittedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-APPROVEDLOCK-" + UUID.randomUUID().toString().substring(0, 8),
            payrollMonth.minusMonths(1).withDayOfMonth(15), actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, payrollMonth, calculation);
    }

    /** Same as #seedSubmittedTierCommission but carried through the real manager + CEO approve chain while the
     * month is still open, producing a genuine APPROVED SALE record. */
    private long seedApprovedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        long commissionId = seedSubmittedTierCommission(salesRepId, payrollMonth, actualReceived);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
