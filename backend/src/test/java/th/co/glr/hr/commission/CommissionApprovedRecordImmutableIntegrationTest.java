package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * P0 fix (fix/commission-approved-record-immutable) -- real-DB, wrong-way-round acceptance
 * coverage for {@link CommissionService#updateDeductions}, which had NO {@code status == APPROVED}
 * guard and no {@link CommissionService} payroll-month-open check at all before this branch. Its
 * only guards were {@code VOID}/{@code REJECTED} -&gt; 409 and {@code MANUAL_KINDS} -&gt; 409 --
 * {@code APPROVED} was in range for a rewrite (a CEO-signed-off row), and {@link
 * CommissionRepository#updateCommissionAmountsForInvoice}'s {@code WHERE invoice_id = :invoiceId}
 * meant a CLAWBACK's own id could rewrite the ORIGINAL sale's shared invoice row too.
 *
 * <p>Concrete failure this closes: an APPROVED sale with {@code actual_received} = 1,070,000.00,
 * {@code weight_multiplier} = 1 (tier base 1,000,000.00, tier commission 6,250.00) could have its
 * weight_multiplier PATCHed to 3 by a sales_manager alone (3 passes {@code @Min(1) @Max(3)}),
 * reweighting to 3,210,000.00 -&gt; base 3,000,000.00 -&gt; tier commission 48,750.00, a 42,500.00
 * jump with zero re-approval, on a row the CEO already signed off.
 *
 * <p>Every guard case here is written wrong-way-round: the write must be REFUSED and the STORED
 * amounts/derived payroll total must be unchanged in real Postgres, not merely a 4xx status code --
 * matching this file's sibling {@link CommissionClosedMonthGuardIntegrationTest} and the reviewer
 * finding (F5) that a fixture change must never make a test pass for the wrong reason: every
 * refusal here asserts its OWN distinguishing Thai substring, not just {@code HttpStatus.CONFLICT}.
 * {@link AuditService}/{@link NotificationService} are mocked deliberately (side effects of an
 * already-decided outcome); every collaborator that participates in the guard decision or its
 * persistence (real {@link CommissionRepository}) is real.
 */
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
            mock(AttachmentRepository.class));

        long managerEmployeeId = createEmployee(employees, "ผู้จัดการฝ่ายขาย ล็อคยืนยัน", "sm-approvedlock@glr.co.th", "SA", "แผนกขาย");
        managerActor = actor(managerEmployeeId, "sales_manager");
        long ceoEmployeeId = createEmployee(employees, "ผู้บริหาร ล็อคยืนยัน", "ceo-approvedlock@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = actor(ceoEmployeeId, "ceo");
        salesRepId = createEmployee(employees, "พนักงานขาย ล็อคยืนยัน", "sales-approvedlock@glr.co.th", "SA", "แผนกขาย");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Point 1 -- APPROVED is frozen: a sales_manager cannot edit an already-approved record,
    // and the stored invoice_details/weight_multiplier are provably untouched, not just a 4xx.
    // ─────────────────────────────────────────────────────────────────────────────────────

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
                // Distinguishing substring (reviewer finding F5 pattern): must be refused for
                // "already approved", not coincidentally for VOID/REJECTED/manual-kind/closed-month.
                assertThat(e.getMessage()).contains("อนุมัติแล้วไม่สามารถแก้ไขได้");
            });

        InvoiceDetails after = commissions.findById(commissionId).orElseThrow().invoiceDetails();
        assertThat(after.grossAmount()).isEqualByComparingTo(before.grossAmount());
        assertThat(after.grossAmount()).isNotEqualByComparingTo(new BigDecimal("999999.00"));
        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.APPROVED);
    }

    /**
     * The exact defect scenario: 1,070,000.00 actual_received / 1x weight -&gt; base 1,000,000.00
     * -&gt; tier commission 6,250.00 (tiers 1-4 exactly filled, tier 5's lower bound not crossed --
     * see {@link TierConfig#defaults()}). A sales_manager ALONE PATCHes weightMultiplier to 3
     * (passes {@code @Min(1) @Max(3)}); unguarded, this reweights to 3,210,000.00 -&gt; base
     * 3,000,000.00 -&gt; tier commission 48,750.00 -- a 42,500.00 jump with zero re-approval, on a
     * row the CEO already signed off. Asserts the number {@link
     * CommissionService#payrollCommissionTotalsByEmployee} would hand to payroll, not a proxy for
     * it -- this IS the figure {@code PayrollService} reads for {@code PayrollComponent.COMMISSION_PAY}.
     */
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
        // The bug: this would be 48750.00 (or 63750.00 once the incentive ladder is configured)
        // instead of unchanged 6250.00 -- a 57,500.00-class overpayment that already cleared review.
        assertThat(totalAfter).isEqualByComparingTo(new BigDecimal("6250.00"));
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Point 2 -- the closed-payroll-month guard, mirroring the other six call sites: editing a
    // SUBMITTED/MANAGER_APPROVED record must still be refused once ITS month has closed
    // underneath it, even though that status never counted toward payroll on its own.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void updateDeductions_onSubmittedRecord_whoseMonthIsAlreadyProcessed_isRefused_recordUnchanged() {
        LocalDate processedMonth = LocalDate.of(2026, 4, 1);
        long commissionId = seedSubmittedTierCommission(salesRepId, processedMonth, new BigDecimal("50000.00"));
        insertProcessedPeriod(processedMonth);
        InvoiceDetails before = commissions.findById(commissionId).orElseThrow().invoiceDetails();

        UpdateCommissionDeductionsRequest request = new UpdateCommissionDeductionsRequest(
            new BigDecimal("1.00"), null, null, null, null, null, null, null, null,
            "should be refused -- payroll month already processed");

        assertThatThrownBy(() -> commissionService.updateDeductions(commissionId, request, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        InvoiceDetails after = commissions.findById(commissionId).orElseThrow().invoiceDetails();
        assertThat(after.grossAmount()).isEqualByComparingTo(before.grossAmount());
        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.SUBMITTED);
    }

    @Test
    void updateDeductions_onSubmittedRecord_whoseMonthIsSeedCovered_isRefused_withTheOtherMessage() {
        insertSeedCoverage(2026, LocalDate.of(2026, 6, 1));
        long commissionId = seedSubmittedTierCommission(salesRepId, LocalDate.of(2026, 5, 1), new BigDecimal("50000.00"));
        InvoiceDetails before = commissions.findById(commissionId).orElseThrow().invoiceDetails();

        UpdateCommissionDeductionsRequest request = new UpdateCommissionDeductionsRequest(
            new BigDecimal("1.00"), null, null, null, null, null, null, null, null,
            "should be refused -- payroll month is seed-covered");

        assertThatThrownBy(() -> commissionService.updateDeductions(commissionId, request, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("จ่ายนอกระบบ");
                assertThat(e.getMessage()).doesNotContain("ประมวลผลไปแล้ว");
            });

        InvoiceDetails after = commissions.findById(commissionId).orElseThrow().invoiceDetails();
        assertThat(after.grossAmount()).isEqualByComparingTo(before.grossAmount());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Point 3 -- a CLAWBACK shares invoice_id with the original SALE it reverses
    // (CommissionRepository#createClawback). PATCHing the CLAWBACK's own id must not be able to
    // rewrite the ORIGINAL sale's shared invoice_details row.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void updateDeductions_onClawbackId_isRefused_originalSalesInvoiceUnchanged() {
        long originalId = seedApprovedTierCommission(salesRepId, OPEN_MONTH, new BigDecimal("100000.00"));
        CommissionRecord original = commissions.findById(originalId).orElseThrow();
        long clawbackId = commissions.createClawback(
            original, managerActor.id(), currentPayrollMonth(), "ลูกค้าคืนสินค้า -- test clawback");
        InvoiceDetails originalBefore = commissions.findById(originalId).orElseThrow().invoiceDetails();
        InvoiceDetails clawbackBefore = commissions.findById(clawbackId).orElseThrow().invoiceDetails();
        // Sanity: the clawback really does share the original's invoice row (the mechanism the bug
        // exploited) -- if this ever stops being true the rest of this test is testing nothing.
        assertThat(clawbackBefore.id()).isEqualTo(originalBefore.id());

        UpdateCommissionDeductionsRequest attack = new UpdateCommissionDeductionsRequest(
            new BigDecimal("1.00"), null, null, null, null, null, null, null, null,
            "should be refused -- id is a CLAWBACK, amount is derived");

        assertThatThrownBy(() -> commissionService.updateDeductions(clawbackId, attack, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                // Distinguishing substring: proves the CLAWBACK-kind guard fired specifically, not
                // merely that SOME guard (e.g. the APPROVED-status one, which would also block a
                // clawback since it is created APPROVED) happened to also refuse it.
                assertThat(e.getMessage()).contains("รายการเรียกคืนค่าคอมมิชชั่นคำนวณจากรายการต้นทางโดยอัตโนมัติ");
            });

        InvoiceDetails originalAfter = commissions.findById(originalId).orElseThrow().invoiceDetails();
        assertThat(originalAfter.grossAmount()).isEqualByComparingTo(originalBefore.grossAmount());
        assertThat(originalAfter.grossAmount()).isNotEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(commissions.findById(originalId).orElseThrow().actualReceived())
            .isEqualByComparingTo(original.actualReceived());
        InvoiceDetails clawbackAfter = commissions.findById(clawbackId).orElseThrow().invoiceDetails();
        assertThat(clawbackAfter.grossAmount()).isEqualByComparingTo(clawbackBefore.grossAmount());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The guard is not "deny everything": the legitimate pre-final-approval review edit still
    // works, at both stages a manager/CEO can reach it from (SUBMITTED and MANAGER_APPROVED).
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void updateDeductions_onManagerApprovedRecord_byCeo_stillSucceeds() {
        long commissionId = seedSubmittedTierCommission(salesRepId, OPEN_MONTH, new BigDecimal("100000.00"));
        commissionService.approve(commissionId, managerActor); // -> MANAGER_APPROVED, still editable

        UpdateCommissionDeductionsRequest legitimateEdit = new UpdateCommissionDeductionsRequest(
            null, new BigDecimal("500.00"), null, null, null, null, null, null, null,
            "legitimate CEO review before final sign-off -- bank fee per statement");
        CommissionRecord edited = commissionService.updateDeductions(commissionId, legitimateEdit, ceoActor);

        assertThat(edited.invoiceDetails().bankFees()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(edited.status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        // Final amount is always recomputed from the stored fields, never set directly.
        assertThat(edited.actualReceived()).isEqualByComparingTo(new BigDecimal("99500.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Writes a real SUBMITTED SALE commission directly through the repository (bypassing
     * submit()'s own create-time guard -- fixture setup, not the thing under test), no deductions,
     * so actualReceived == grossAmount exactly. Mirrors {@code
     * CommissionClosedMonthGuardIntegrationTest#seedSubmittedTierCommission}. */
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

    /** Same as {@link #seedSubmittedTierCommission} but carried through the real manager + CEO
     * approve chain while the month is still open, producing a genuine APPROVED SALE record. */
    private long seedApprovedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        long commissionId = seedSubmittedTierCommission(salesRepId, payrollMonth, actualReceived);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private LocalDate currentPayrollMonth() {
        return LocalDate.now(ZoneId.of("Asia/Bangkok")).withDayOfMonth(1);
    }

    private void insertSeedCoverage(int taxYear, LocalDate coversThrough) {
        jdbc.update("""
            INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
            VALUES (:taxYear, :coversThrough, 'commission-approved-record-immutable test coverage')
            ON CONFLICT (tax_year) DO UPDATE SET covers_through = EXCLUDED.covers_through
            """, Map.of("taxYear", taxYear, "coversThrough", coversThrough));
    }

    private void insertProcessedPeriod(LocalDate payrollMonth) {
        jdbc.update("""
            INSERT INTO hr.payroll_period (payroll_month, period_start, period_end, pay_date, status)
            VALUES (
                :payrollMonth, :payrollMonth,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                (:payrollMonth + INTERVAL '1 month - 1 day')::date,
                'PROCESSED'
            )
            """, Map.of("payrollMonth", payrollMonth));
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
