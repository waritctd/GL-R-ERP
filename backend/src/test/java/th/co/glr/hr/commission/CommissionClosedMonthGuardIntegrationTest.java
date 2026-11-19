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

/** Commission-closed-month guard (fix/commission-closed-month-guard, stacked on V114's
 * hr.payroll_seed_coverage) — real-DB, wrong-way-round acceptance coverage. */
class CommissionClosedMonthGuardIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final int TAX_YEAR = 2026;

    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService commissionService;
    private TicketRepository tickets;
    private long salesRepId;
    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;

    @BeforeEach
    void wireService() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        tickets = new TicketRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            calculator,
            new FileStorageService("/tmp/glr-commission-closed-month-guard-test-" + UUID.randomUUID()),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            new AttachmentRepository(jdbc), new CeoApproverRepository(jdbc));

        long managerEmployeeId = createEmployee(employees, "ผู้จัดการฝ่ายขาย งวดปิด", "sm-closedmonth@glr.co.th", "SA", "แผนกขาย");
        managerActor = actor(managerEmployeeId, "sales_manager");
        long ceoEmployeeId = createEmployee(employees, "ผู้บริหาร งวดปิด", "ceo-closedmonth@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = actor(ceoEmployeeId, "ceo");
        long accountEmployeeId = createEmployee(employees, "บัญชี งวดปิด", "acct-closedmonth@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        accountActor = actor(accountEmployeeId, "account");
        salesRepId = createEmployee(employees, "พนักงานขาย งวดปิด", "sales-closedmonth@glr.co.th", "SA", "แผนกขาย");
    }

    // createManualCommission — cases 1 & 2 from the plan.

    @Test
    void manualCommission_intoSeedCoveredMonth_isRefused_zeroRowsWritten() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"),
                "should be refused -- seed-covered month", LocalDate.of(2026, 5, 1), managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("จ่ายนอกระบบ");
            });

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // createClawback (reviewer finding F2) — a FOURTH insert path into sales.commission_record, guarded on
    // currentPayrollMonth() (always the CURRENT month, never a caller-supplied one).

    @Test
    void createClawback_intoAlreadyProcessedCurrentMonth_isRefused_noClawbackRowWritten() {
        LocalDate currentMonth = LocalDate.now(ZoneId.of("Asia/Bangkok")).withDayOfMonth(1);
        long originalId = seedApprovedTierCommission(salesRepId, currentMonth, new BigDecimal("100000.00"));
        // The month closes AFTER the original SALE commission was already created and approved -- exactly the prod
        // sequence (created while open, month marked PROCESSED mid-month).
        insertProcessedPeriod(currentMonth);
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createClawback(
                originalId, new CreateClawbackRequest("ลูกค้าคืนสินค้า -- ควรถูกปฏิเสธ"), managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // Approval stages (reviewer finding F3) — a commission created while its payroll month was still open can
    // still reach manager- or CEO-approval AFTER that month closes underneath it. Guards on the RECORD's own
    // payrollMonth, not today's date.

    @Test
    void managerApprove_refusedOnceThePayrollMonthClosesUnderneath_statusUnchanged() {
        LocalDate month = LocalDate.of(2026, 4, 1);
        long commissionId = seedSubmittedTierCommission(salesRepId, month, new BigDecimal("50000.00"));
        // The month closes AFTER submission, while the request waits for the manager.
        insertProcessedPeriod(month);

        assertThatThrownBy(() -> commissionService.approve(commissionId, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.SUBMITTED);
    }

    // Boundary — case 5: the guard does not over-reach. Given coverage through 2026-06-01, a June invoice derives
    // (M+1) to July, the first OPEN month, and must succeed.

    @Test
    void createManualCommission_intoFirstOpenMonthPastCoverage_succeeds() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"),
            "first open month past coverage", LocalDate.of(2026, 7, 1), managerActor);

        assertThat(created.payrollMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    // Helpers

    /** Writes a real SUBMITTED SALE commission directly through the repository (bypassing submit's own create-time
     * guard entirely, deliberately. */
    private long seedSubmittedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            null, salesRepId, "INV-GUARD-SEED-" + UUID.randomUUID().toString().substring(0, 8),
            payrollMonth.minusMonths(1).withDayOfMonth(15), actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, payrollMonth, calculation);
    }

    /** Same as #seedSubmittedTierCommission but carried all the way through the real manager- and CEO-approve chain
     * while the month is still open, so the resulting record is a genuine APPROVED SALE commission -- the only
     * kind/status CommissionService#createClawback accepts. */
    private long seedApprovedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        long commissionId = seedSubmittedTierCommission(salesRepId, payrollMonth, actualReceived);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private void insertSeedCoverage(int taxYear, LocalDate coversThrough) {
        jdbc.update("""
            INSERT INTO hr.payroll_seed_coverage (tax_year, covers_through, note)
            VALUES (:taxYear, :coversThrough, 'commission-closed-month-guard test coverage')
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

    private int countCommissionRecords() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.commission_record", Map.of(), Integer.class);
        return count == null ? 0 : count;
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
