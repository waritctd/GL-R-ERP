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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
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

/**
 * Commission-closed-month guard (fix/commission-closed-month-guard, stacked on V114's {@code
 * hr.payroll_seed_coverage}) — real-DB, wrong-way-round acceptance coverage.
 *
 * <p>Background: {@link CommissionService#createManualCommission} took a free-form {@code
 * payrollMonth} and the SALE path ({@link CommissionService#submit}/{@link
 * CommissionService#createFromDeal}) derived its month from a caller-supplied, backdatable {@code
 * invoiceDate} — either way a commission could land in a payroll month that can never be paid
 * (already {@code PROCESSED}, or paid outside the ERP and seed-covered per V114), silently
 * distorting {@link CommissionService#payrollReadySummary}. Owner decision: REFUSE (409), never
 * roll forward — rolling forward would change which month the commission is attributed to, which
 * is attribution logic, not a write guard. Review pass (findings F2/F3) widened the scope from the
 * three creation paths to all SIX sites that write or advance {@code sales.commission_record}:
 * {@link CommissionService#createManualCommission}, {@link CommissionService#submit}, {@link
 * CommissionService#createFromDeal}, {@link CommissionService#createClawback} (a fourth INSERT
 * path, easy to miss because it always targets the CURRENT month rather than a caller-supplied
 * one), and the two approval stages reachable through {@link CommissionService#approve} (manager-
 * then CEO-approve) — a commission created while its month was open can still be approved after
 * that month closes underneath it.
 *
 * <p>Mockito cannot prove this: a mocked {@link CommissionRepository} happily "passes" while the
 * guard SQL ({@code payrollMonthProcessed}/{@code payrollMonthSeedCovered}) checks nothing. Every
 * guard case here is written wrong-way-round — the write must be REFUSED and ZERO rows persisted
 * in real Postgres, not merely a 409 status code. {@link AuditService} and {@link
 * NotificationService} are mocked deliberately (side effects of an already-decided outcome, same
 * rationale as every other commission integration test in this package); every collaborator that
 * participates in the guard decision or its persistence (real {@link CommissionRepository}, real
 * {@link TicketRepository}, real {@link AttachmentRepository}) is real.
 */
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // createManualCommission — cases 1 & 2 from the plan.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /** The message here must be the OTHER fact -- "already processed", not "paid outside the system". */
    @Test
    void manualCommission_intoProcessedMonth_isRefused_zeroRowsWritten_withTheOtherMessage() {
        LocalDate processedMonth = LocalDate.of(2026, 4, 1);
        insertProcessedPeriod(processedMonth);
        int before = countCommissionRecords();

        assertThatThrownBy(() -> commissionService.createManualCommission(
                salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("5000.00"),
                "should be refused -- already processed", processedMonth, managerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
                assertThat(e.getMessage()).doesNotContain("จ่ายนอกระบบ");
            });

        assertThat(countCommissionRecords()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // createFromDeal (SALE path) — case 3. invoiceDate derives via saleCommissionPayrollMonth
    // (M+1): a 2026-05-15 invoice derives to payroll month 2026-06-01, inside coverage.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createFromDeal_derivingIntoSeedCoveredMonth_isRefused_nothingWritten() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));
        long ticketId = createClosedPaidTicket(salesRepId);
        int beforeCommissions = countCommissionRecords();
        int beforeInvoices = countInvoiceDetails();

        // createFromDeal throws CONFLICT for a DIFFERENT reason too (hasActiveCommissionForTicket)
        // -- assert the distinguishing Thai substring, not just the status, so a fixture change
        // could never make this pass for the wrong reason (reviewer finding F5).
        assertThatThrownBy(() -> commissionService.createFromDeal(
                ticketId, "INV-GUARD-CFD-" + UUID.randomUUID().toString().substring(0, 8),
                LocalDate.of(2026, 5, 15), new BigDecimal("10000.00"),
                null, null, null, null, null, null, null,
                invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("จ่ายนอกระบบ");
            });

        assertThat(countCommissionRecords()).isEqualTo(beforeCommissions);
        assertThat(countInvoiceDetails()).isEqualTo(beforeInvoices);
        // The guard runs before the invoice upload also flips the ticket's invoiceOnFile flag --
        // a refused create must leave the ticket's close gate untouched too.
        assertThat(tickets.hasInvoiceAttachment(ticketId)).isFalse();
    }

    @Test
    void createFromDeal_derivingIntoProcessedMonth_isRefused_nothingWritten() {
        LocalDate processedMonth = LocalDate.of(2026, 4, 1);
        insertProcessedPeriod(processedMonth);
        long ticketId = createClosedPaidTicket(salesRepId);
        int beforeCommissions = countCommissionRecords();

        // 2026-03-15 derives to payroll month 2026-04-01 -- processed.
        assertThatThrownBy(() -> commissionService.createFromDeal(
                ticketId, "INV-GUARD-CFD-PROC-" + UUID.randomUUID().toString().substring(0, 8),
                LocalDate.of(2026, 3, 15), new BigDecimal("10000.00"),
                null, null, null, null, null, null, null,
                invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        assertThat(countCommissionRecords()).isEqualTo(beforeCommissions);
        assertThat(tickets.hasInvoiceAttachment(ticketId)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // submit (SALE path, unlinked) — case 4. sourceTicketId is null, so resolveDealLinkage's
    // CLOSED_PAID check never runs -- proves the guard fires independently of deal linkage.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void submit_derivingIntoSeedCoveredMonth_isRefused_nothingWritten() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));
        int beforeCommissions = countCommissionRecords();
        int beforeInvoices = countInvoiceDetails();
        SubmitCommissionRequest request = unlinkedRequest(
            salesRepId, LocalDate.of(2026, 5, 15), "INV-GUARD-SUBMIT-" + UUID.randomUUID().toString().substring(0, 8));

        assertThatThrownBy(() -> commissionService.submit(request, invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("จ่ายนอกระบบ");
            });

        assertThat(countCommissionRecords()).isEqualTo(beforeCommissions);
        assertThat(countInvoiceDetails()).isEqualTo(beforeInvoices);
    }

    @Test
    void submit_derivingIntoProcessedMonth_isRefused_nothingWritten() {
        LocalDate processedMonth = LocalDate.of(2026, 4, 1);
        insertProcessedPeriod(processedMonth);
        int beforeCommissions = countCommissionRecords();
        SubmitCommissionRequest request = unlinkedRequest(
            salesRepId, LocalDate.of(2026, 3, 15), "INV-GUARD-SUBMIT-PROC-" + UUID.randomUUID().toString().substring(0, 8));

        assertThatThrownBy(() -> commissionService.submit(request, invoiceFile(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        assertThat(countCommissionRecords()).isEqualTo(beforeCommissions);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // createClawback (reviewer finding F2) — a FOURTH insert path into sales.commission_record,
    // guarded on currentPayrollMonth() (always the CURRENT month, never a caller-supplied one).
    // Simulates the real prod incident: the current month gets marked PROCESSED mid-month (23 Jul
    // 2026), which an unguarded clawback would otherwise land in undetected -- a clawback is a
    // NEGATIVE adjustment, so a refused one is the financially-safe direction (the correction is
    // blocked, not silently absorbed into a processed month's tier base).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createClawback_intoAlreadyProcessedCurrentMonth_isRefused_noClawbackRowWritten() {
        LocalDate currentMonth = LocalDate.now(ZoneId.of("Asia/Bangkok")).withDayOfMonth(1);
        long originalId = seedApprovedTierCommission(salesRepId, currentMonth, new BigDecimal("100000.00"));
        // The month closes AFTER the original SALE commission was already created and approved --
        // exactly the prod sequence (created while open, month marked PROCESSED mid-month).
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Approval stages (reviewer finding F3) — a commission created while its payroll month was
    // still open can still reach manager- or CEO-approval AFTER that month closes underneath it.
    // Guards on the RECORD's own payrollMonth, not today's date.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void ceoApprove_refusedOnceThePayrollMonthClosesUnderneath_statusUnchanged() {
        LocalDate month = LocalDate.of(2026, 4, 1);
        long commissionId = seedSubmittedTierCommission(salesRepId, month, new BigDecimal("50000.00"));
        commissionService.approve(commissionId, managerActor); // -> MANAGER_APPROVED, month still open
        // The month closes AFTER manager approval, while the request waits for the CEO.
        insertProcessedPeriod(month);

        assertThatThrownBy(() -> commissionService.approve(commissionId, ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ประมวลผลไปแล้ว");
            });

        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Boundary — case 5: the guard does not over-reach. Given coverage through 2026-06-01, a
    // June invoice derives (M+1) to July, the first OPEN month, and must succeed.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createManualCommission_intoFirstOpenMonthPastCoverage_succeeds() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));

        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"),
            "first open month past coverage", LocalDate.of(2026, 7, 1), managerActor);

        assertThat(created.payrollMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void submit_invoiceDerivingToFirstOpenMonthPastCoverage_succeeds() {
        insertSeedCoverage(TAX_YEAR, LocalDate.of(2026, 6, 1));
        // 2026-06-20 derives (M+1) to payroll month 2026-07-01 -- the first open month.
        SubmitCommissionRequest request = unlinkedRequest(
            salesRepId, LocalDate.of(2026, 6, 20), "INV-GUARD-BOUNDARY-" + UUID.randomUUID().toString().substring(0, 8));

        CommissionRecord created = commissionService.submit(request, invoiceFile(), accountActor);

        assertThat(created.payrollMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Data-driven, not hardcoded — case 6: with NO coverage row at all (UAT's shape today), a
    // month that would otherwise be covered succeeds. Proves the guard reads real coverage data
    // rather than a baked-in date.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void createManualCommission_withNoCoverageRowAtAll_succeedsEvenForAnOldMonth() {
        // No insertSeedCoverage call -- hr.payroll_seed_coverage starts empty in every fresh test
        // database, exactly UAT's current shape (no payroll_year_to_date_seed rows to derive from).
        CommissionRecord created = commissionService.createManualCommission(
            salesRepId, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"),
            "no coverage row exists anywhere", LocalDate.of(2026, 1, 1), managerActor);

        assertThat(created.payrollMonth()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private SubmitCommissionRequest unlinkedRequest(long salesRepId, LocalDate invoiceDate, String invoiceNumber) {
        return new SubmitCommissionRequest(
            null, salesRepId, invoiceNumber, invoiceDate, new BigDecimal("10000.00"),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Writes a real SUBMITTED SALE commission directly through the repository (bypassing {@code
     * submit()}'s own create-time guard entirely, deliberately -- this is fixture setup, not the
     * thing under test) at exactly {@code payrollMonth}, for the approval-stage guard tests: they
     * need a record whose month is still OPEN at creation time and only closes afterward, under
     * the test's control.
     */
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

    /**
     * Same as {@link #seedSubmittedTierCommission} but carried all the way through the real
     * manager- and CEO-approve chain while the month is still open, so the resulting record is a
     * genuine APPROVED SALE commission -- the only kind/status {@link
     * CommissionService#createClawback} accepts.
     */
    private long seedApprovedTierCommission(long salesRepId, LocalDate payrollMonth, BigDecimal actualReceived) {
        long commissionId = seedSubmittedTierCommission(salesRepId, payrollMonth, actualReceived);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    private MultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    /** Direct SQL, not the full 8-step ticket->deal fixture -- the guard is date-math, not deal
     * state, and every other test in this class only needs a ticket that already reads back as
     * CLOSED_PAID with a known owner, exactly what {@code createFromDeal} looks up. */
    private long createClosedPaidTicket(long createdBy) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, status, created_by, sales_stage)
            VALUES (:code, 'commission closed-month guard test ticket', 'draft', :createdBy, 'CLOSED_PAID')
            RETURNING ticket_id
            """,
            new MapSqlParameterSource()
                // sales.ticket.code is VARCHAR(20).
                .addValue("code", "T-CMG-" + UUID.randomUUID().toString().substring(0, 8))
                .addValue("createdBy", createdBy),
            Long.class);
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

    private int countInvoiceDetails() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales.invoice_details", Map.of(), Integer.class);
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
