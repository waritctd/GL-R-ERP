package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.ItemWeightMultiplierRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Verifies per-item stock commission weights through reservation, calculation, snapshotting, and payroll
 * aggregation. */
class StockItemWeightedCommissionIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate PAYROLL_MONTH = LocalDate.of(2026, 6, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15);

    private TicketRepository tickets;
    private TicketService ticketService;
    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService commissionService;
    private EmployeeRepository employees;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal salesManager;
    private UserPrincipal ceoActor;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        PricingRequestService pricingRequests = mock(PricingRequestService.class);
        when(pricingRequests.cancelOpenForTicket(anyLong(), anyString(), any(), any()))
            .thenReturn(new PricingRequestService.CancelOpenForTicketResult(0, List.of()));
        ticketService = new TicketService(tickets, notifications,
            new ObjectMapper(), customers, new QuotationRenderer(), pricingRequests, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        // tickets is the REAL repository (not mocked) — CommissionService's own computeItemDerivedWeight needs it to
        // read real ticket_item rows, exactly as it will in production.
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));

        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ownerId = createEmployee("เจ้าของดีล ถ่วงน้ำหนัก", "owner-itemweight@glr.co.th", "SA", "แผนกขาย");
        owner = principal(ownerId, "sales");
        long managerId = createEmployee("ผู้จัดการฝ่ายขาย ถ่วงน้ำหนัก", "manager-itemweight@glr.co.th", "SA", "แผนกขาย");
        salesManager = principal(managerId, "sales_manager");
        long ceoId = createEmployee("ซีอีโอ ถ่วงน้ำหนัก", "ceo-itemweight@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = principal(ceoId, "ceo");
    }

    // The workbook reconciliation, to the baht

    /** One rep, June 2026: a "normal" unlinked receipt of 352,003.93, plus seven single-item, FULLY stock-covered
     * tickets weighted x2 whose actualReceived sum to exactly 123,443.76. */
    @Test
    void juneReconciliation_normalPlusSevenFullyStockedTwoXLines_reproducesWorkbookExactly() {
        long salesRepId = ownerId;

        seedUnlinkedRecord(salesRepId, new BigDecimal("352003.93"));
        // Seven single-item, fully-stock, x2-weighted tickets. Individual amounts are arbitrary — only their SUM
        // matters to the reconciliation — chosen to sum to exactly 123,443.76.
        List<String> stockReceipts = List.of(
            "16000.00", "16000.00", "16000.00", "16000.00", "16000.00", "16000.00", "27443.76");
        BigDecimal stockReceiptTotal = BigDecimal.ZERO;
        for (String receipt : stockReceipts) {
            BigDecimal amount = new BigDecimal(receipt);
            stockReceiptTotal = stockReceiptTotal.add(amount);
            createFullyStockedTwoXRecord(salesRepId, amount);
        }
        assertThat(stockReceiptTotal).isEqualByComparingTo("123443.76");

        // Live-estimate path (simulate(), which reads sumActiveWeightedActualReceived's broader NOT IN
        // ('VOID','REJECTED') filter — every record here is SUBMITTED, so this already sees all eight).
        CommissionSimulationDto dto = simulateNoAdditionalReceipt(salesRepId);
        assertThat(dto.existingMonthlyBase()).isEqualByComparingTo("559711.64");
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo("2322.84");

        // Real payroll path (APPROVED-only, computeRepPayrollCommissions / payrollCommissionTotalsByEmployee) must
        // reach the IDENTICAL figure — this is the number that actually gets paid.
        for (long id : allCommissionIdsFor(salesRepId)) {
            commissionService.approve(id, salesManager);
            commissionService.approve(id, ceoActor);
        }
        Map<Long, BigDecimal> totals = commissionService.payrollCommissionTotalsByEmployee(PAYROLL_MONTH);
        assertThat(totals.get(salesRepId)).isEqualByComparingTo("2322.84");
    }

    // Reviewer finding (2026-08-16): the manager's record-level override must still reach real payroll money on a
    // record created through the REAL production paths, not a hand-built fixture that bypasses
    // CommissionCalculator#itemDerivedWeight entirely.

    /** Drives the REAL CommissionService#submit — including a real invoice file upload through real
     * FileStorageService/CommissionAttachmentRepository/AttachmentRepository — against an ORDINARY ticket: one
     * item, fully priced, at the column DEFAULT weight, no stock declared. */
    @Test
    void managersRecordLevelOverride_stillReachesPayroll_onARecordCreatedThroughRealSubmit() {
        long salesRepId = ownerId;
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        // Deliberately UNTOUCHED — no reserveStock, no setItemWeightMultipliers.
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);

        CommissionService realSubmitService = realCommissionService();
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-OVERRIDE-" + UUID.randomUUID(), INVOICE_DATE,
            new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // submit()'s SUBMIT_ROLES is {account, sales_manager, ceo} -- "sales" was removed from it entirely by Slice A2
        // (commission creation moved to the accountant's auto-create trigger).
        CommissionRecord created = realSubmitService.submit(request, invoiceFile(), salesManager);

        // The freeze must NOT have captured a redundant, control-defeating 1.000000.
        assertThat(created.effectiveWeightMultiplier()).isNull();

        UpdateCommissionDeductionsRequest setWeight = new UpdateCommissionDeductionsRequest(
            null, null, null, null, null, null, null, null, 2,
            "manager applies the record-level override -- this ticket carries no per-item signal");
        realSubmitService.updateDeductions(created.id(), setWeight, salesManager);
        realSubmitService.approve(created.id(), salesManager);
        realSubmitService.approve(created.id(), ceoActor);

        LocalDate realPayrollMonth = INVOICE_DATE.withDayOfMonth(1).plusMonths(1); // FLAG-10, M+1
        BigDecimal weighted = commissions.sumActiveWeightedActualReceived(salesRepId, realPayrollMonth);
        assertThat(weighted).isEqualByComparingTo("200000.00"); // 100,000 x 2, not x 1.
        BigDecimal expectedCommission = calculator.progressiveCommission(calculator.monthlyTierBase(new BigDecimal("200000.00")));
        Map<Long, BigDecimal> totals = realSubmitService.payrollCommissionTotalsByEmployee(realPayrollMonth);
        assertThat(totals.get(salesRepId)).isEqualByComparingTo(expectedCommission);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A CommissionService wired for a REAL submit/createFromDeal call. Mirrors
     * CommissionAutoCreateIntegrationTest's own wiring for the identical reason. AuditService/NotificationService
     * stay mocked — side effects of an already-decided outcome, not the money path under test. */
    private CommissionService realCommissionService() {
        return new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            calculator,
            new FileStorageService("/tmp/glr-item-weight-override-test-uploads"),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            new AttachmentRepository(jdbc), new CeoApproverRepository(jdbc));
    }

    private MultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf-bytes".getBytes());
    }

    /** Creates a fully stock-covered, x2-weighted, single-item ticket and its SUBMITTED commission record — the
     * repeating unit the reconciliation test sums seven of. */
    private void createFullyStockedTwoXRecord(long salesRepId, BigDecimal actualReceived) {
        long ticketId = createSingleItemTicketAtOrderReceived(new BigDecimal("10.00"), new BigDecimal("500.0000"));
        long itemId = onlyItemId(ticketId);
        ticketService.reserveStock(ticketId,
            new StockReservationRequest(List.of(new StockReservationRequest.Line(itemId, new BigDecimal("10.00"), null))),
            ceoActor);
        ticketService.setItemWeightMultipliers(ticketId,
            new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, 2))), salesManager);

        SubmitCommissionRequest request = zeroDeductionRequest(ticketId, salesRepId, actualReceived);
        InvoiceCalculation calculation = calculateInvoice(request);
        Optional<BigDecimal> effectiveWeight = calculator.itemDerivedWeight(
            toInputs(tickets.findById(ticketId).orElseThrow().items()), calculation.actualReceived());
        assertThat(effectiveWeight).isPresent();
        assertThat(effectiveWeight.get()).isEqualByComparingTo("2");

        long invoiceId = commissions.createInvoice(request);
        commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, PAYROLL_MONTH,
            calculation, null, false, effectiveWeight.orElse(null));
    }

    private void seedUnlinkedRecord(long salesRepId, BigDecimal actualReceived) {
        SubmitCommissionRequest request = zeroDeductionRequest(null, salesRepId, actualReceived);
        InvoiceCalculation calculation = calculateInvoice(request);
        long invoiceId = commissions.createInvoice(request);
        // Unlinked (sourceTicketId = null) — 6-arg overload, effectiveWeightMultiplier stays NULL, falls back to the
        // column default weight_multiplier = 1 via COALESCE, exactly like every pre-V148 unlinked record already does.
        commissions.createCommissionRecord(invoiceId, null, salesRepId, salesRepId, PAYROLL_MONTH, calculation);
    }

    private InvoiceCalculation calculateInvoice(SubmitCommissionRequest request) {
        return calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
    }

    private SubmitCommissionRequest zeroDeductionRequest(Long ticketId, long salesRepId, BigDecimal grossAmount) {
        return new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-ITEMWEIGHT-" + UUID.randomUUID(), INVOICE_DATE, grossAmount,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private List<CommissionCalculator.ItemStockWeightInput> toInputs(List<TicketItemDto> items) {
        return items.stream()
            .map(item -> new CommissionCalculator.ItemStockWeightInput(
                item.qty(), item.qtyFromStock(), item.approvedPrice(), item.proposedPrice(), item.weightMultiplier()))
            .toList();
    }

    /** A deal parked at DealStage#ORDER_RECEIVED (the stock-declaration stage floor) with exactly one item, at the
     * given qty/proposedPrice — mirrors StockDeclarationAuthzIntegrationTest#createTicketWithOneItem. */
    private long createSingleItemTicketAtOrderReceived(BigDecimal qty, BigDecimal proposedPrice) {
        CreateTicketRequest request = new CreateTicketRequest(
            "ดีลทดสอบถ่วงน้ำหนัก", "NORMAL", "ลูกค้าทดสอบถ่วงน้ำหนัก", null, null, null, null, null,
            List.of(new TicketItemRequest("Brand", "Model", null, null, "60x60", "Factory A",
                qty, null, "PIECE", null, null, null, proposedPrice, "THB")));
        long ticketId = tickets.create(request, tickets.nextTicketCode(), ownerId, "เจ้าของดีล ถ่วงน้ำหนัก");
        tickets.updateSalesStage(ticketId, DealStage.ORDER_RECEIVED);
        return ticketId;
    }

    private long onlyItemId(long ticketId) {
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        assertThat(items).hasSize(1);
        return items.get(0).id();
    }

    private List<Long> allCommissionIdsFor(long salesRepId) {
        return commissions.findRecords(salesRepId, PAYROLL_MONTH).stream().map(CommissionRecord::id).toList();
    }

    private CommissionSimulationDto simulateNoAdditionalReceipt(long salesRepId) {
        CommissionSimulatorRequest request = new CommissionSimulatorRequest(
            salesRepId, PAYROLL_MONTH, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return commissionService.simulate(request, principal(salesRepId, "sales"));
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-itemweight@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
