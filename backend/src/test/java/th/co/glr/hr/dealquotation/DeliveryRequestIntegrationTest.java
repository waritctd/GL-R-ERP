package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.RoundDto;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.UpdateRequest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestRepository;
import th.co.glr.hr.deliveryrequest.TaxInvoicePdfReaderTest;
import th.co.glr.hr.deliveryrequest.DeliveryRequestService;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos.SystemDocRef;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.deposit.RemainingInvoiceService;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * V236 delivery rounds (sales side), against a real database: the request is sent with its coverage lines, one round
 * is open per order, a later round covers only what no earlier round does, only the owning side may write, files are
 * type-checked, and the warehouse division is told.
 */
class DeliveryRequestIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {
    private DeliveryRequestService rounds;
    private UserPrincipal otherSales;
    private UserPrincipal warehouse;
    private static final java.time.LocalDate TODAY = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
    private long warehouseEmployeeId;
    private DepositNoticeService depositNotices;

    @BeforeEach
    void wireRounds() throws Exception {
        TicketRepository tickets = new TicketRepository(jdbc);
        depositNotices = mock(DepositNoticeService.class);
        // The buyer lookup is the real one (owner rule #65); every other call on this service stays a mock.
        DepositNoticeService realNotices = new DepositNoticeService(new th.co.glr.hr.deposit.DepositNoticeRepository(jdbc), tickets,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new th.co.glr.hr.deposit.DepositNoticePdfRenderer(),
            new th.co.glr.hr.customer.CustomerRepository(jdbc), new th.co.glr.hr.customerquotation.CustomerQuotationRepository(jdbc),
            th.co.glr.hr.support.DocumentBundles.noDownload(jdbc), new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc));
        when(depositNotices.quotationBuyer(any(), any())).thenAnswer(i -> realNotices.quotationBuyer(i.getArgument(0), i.getArgument(1)));
        FileStorageService storage = new FileStorageService(Files.createTempDirectory("glr-rounds").toString());
        rounds = transactional(new DeliveryRequestService(new DeliveryRequestRepository(jdbc), tickets, ticketService,
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new AttachmentRepository(jdbc), storage,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), quotationService, depositNotices,
            mock(RemainingInvoiceService.class), mock(ImportRequestService.class),
            new th.co.glr.hr.deliveryrequest.PrePortal(tickets, new DeliveryRequestRepository(jdbc), quotationService, ticketService)));
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        otherSales = actor(createEmployee(employees, "พนักงานขาย อีกคน", "sales-other-dr@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
        warehouseEmployeeId = createEmployee(employees, "พนักงานคลัง ทดสอบ", "wh-dr@glr.co.th", "WH", "คลังสินค้า", null);
        warehouse = actor(warehouseEmployeeId, "warehouse");
    }

    @Test
    void send_writesCoverageLines_refusesASecondOpenRound_andTheNextRoundCoversTheRest() {
        long ticketId = confirmedDeal();
        RoundDto draft = rounds.createDraft(ticketId, null, salesActor);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.roundNo()).isEqualTo(1);
        assertThat(rounds.createDraft(ticketId, null, salesActor).id()).as("same open draft").isEqualTo(draft.id());

        RoundDto sent = rounds.send(draft.id(), null, salesActor);
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.lines()).isNotEmpty();
        assertThat(sent.lines().stream().map(l -> l.coveredQty()).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo(jdbc.queryForObject("SELECT SUM(qty) FROM sales.ticket_item WHERE ticket_id = :t",
                Map.of("t", ticketId), BigDecimal.class));
        refusal(() -> rounds.send(draft.id(), null, salesActor), HttpStatus.CONFLICT);
        assertThat(rounds.list(ticketId, salesActor).canStart()).isFalse();
        assertThat(eventKinds(ticketId)).contains("DELIVERY_REQUEST_SENT");

        // Round 1 finished covering half of every line; round 2 then covers the other half only.
        jdbc.update("UPDATE sales.delivery_request_line SET qty = qty / 2 WHERE delivery_request_id = :id", Map.of("id", draft.id()));
        jdbc.update("UPDATE sales.delivery_request SET status = 'INVOICED' WHERE delivery_request_id = :id", Map.of("id", draft.id()));
        RoundDto second = rounds.send(rounds.createDraft(ticketId, null, salesActor).id(), null, salesActor);
        assertThat(second.roundNo()).isEqualTo(2);
        assertThat(second.lines().get(0).coveredQty())
            .isEqualByComparingTo(sent.lines().get(0).coveredQty().subtract(sent.lines().get(0).coveredQty().divide(new BigDecimal("2"))));

        // Everything now covered: nothing left to request.
        jdbc.update("UPDATE sales.delivery_request SET status = 'INVOICED' WHERE delivery_request_id = :id", Map.of("id", second.id()));
        refusal(() -> rounds.createDraft(ticketId, null, salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void writes_areForTheOwnerOnly_readIsTheDealRead_andFilesAreTypeChecked() {
        long ticketId = confirmedDeal();
        refusal(() -> rounds.createDraft(ticketId, null, otherSales), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.createDraft(ticketId, null, warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.createDraft(ticketId, null, salesManagerActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.list(ticketId, warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.list(ticketId, otherSales), HttpStatus.FORBIDDEN);
        assertThat(rounds.list(ticketId, salesManagerActor).canStart()).as("read-only viewer").isFalse();

        RoundDto draft = rounds.createDraft(ticketId, null, salesActor);
        refusal(() -> rounds.send(draft.id(), null, warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.update(draft.id(), new UpdateRequest(null, "x", null), otherSales), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1}),
            warehouse), HttpStatus.FORBIDDEN);
        // The warehouse cannot record on a draft it may not read: refused, and nothing is written.
        var drafted = new th.co.glr.hr.ticket.RoundDeliveryRequest(null, true, null, null, null, "ส่งก่อน");
        refusal(() -> rounds.recordDelivery(draft.id(), drafted, warehouse), HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE ticket_id = :t", Map.of("t", ticketId), Integer.class)).isZero();

        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "run.exe", "application/octet-stream",
            new byte[] {1}), salesActor), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "page.html", "text/html", new byte[] {1}),
            salesActor), HttpStatus.BAD_REQUEST);
        RoundDto withFile = rounds.addFile(draft.id(), new MockMultipartFile("file", "slip.jpg", "image/jpeg",
            new byte[] {1, 2, 3}), salesActor);
        assertThat(withFile.files()).singleElement().satisfies(f -> {
            assertThat(f.fileName()).isEqualTo("slip.jpg");
            assertThat(f.uploaderSide()).isEqualTo("SALES");
        });
    }

    @Test
    void warehouse_readsASentRoundWithoutMoney_forwardsOnceToFlagHolders_returnsWithAReason_andFilesAreSidedWarehouse() {
        long ticketId = confirmedDeal();
        refusal(() -> rounds.list(ticketId, warehouse), HttpStatus.FORBIDDEN);
        RoundDto draft = rounds.createDraft(ticketId, null, salesActor);
        refusal(() -> rounds.list(ticketId, warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.forward(draft.id(), salesActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.forward(draft.id(), warehouse), HttpStatus.CONFLICT);
        rounds.send(draft.id(), null, salesActor);

        var view = rounds.list(ticketId, warehouse);
        assertThat(view.approvedQuotations()).as("no money").isEmpty();
        assertThat(view.canStart()).isFalse();
        assertThat(view.rounds()).singleElement().satisfies(r -> {
            assertThat(r.viewer().canReview()).isTrue();
            assertThat(r.viewer().canEdit()).isFalse();
        });
        refusal(() -> ticketService.get(ticketId, warehouse), HttpStatus.FORBIDDEN);

        RoundDto withFile = rounds.addFile(draft.id(), new MockMultipartFile("file", "site.jpg", "image/jpeg",
            new byte[] {1}), warehouse);
        assertThat(withFile.files()).singleElement().extracting(f -> f.uploaderSide()).isEqualTo("WAREHOUSE");

        // Sent back: needs a reason, tells owner (not the warehouse person), sales edits and re-sends.
        refusal(() -> rounds.sendBack(draft.id(), new DeliveryRequestDtos.ReturnRequest(List.of(), " "), warehouse),
            HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.sendBack(draft.id(), new DeliveryRequestDtos.ReturnRequest(List.of("PAYMENT_PROOF"), null),
            salesActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.sendBack(draft.id(), new DeliveryRequestDtos.ReturnRequest(java.util.Collections.singletonList(null), null),
            warehouse), HttpStatus.BAD_REQUEST);
        RoundDto returned = rounds.sendBack(draft.id(),
            new DeliveryRequestDtos.ReturnRequest(List.of("PAYMENT_PROOF"), "สลิปไม่ชัด"), warehouse);
        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThat(returned.returnReasonCodes()).containsExactly("PAYMENT_PROOF");
        assertThat(returned.returnReasonText()).isEqualTo("สลิปไม่ชัด");
        assertThat(notices(salesActor.id(), "DELIVERY_REQUEST_RETURNED")).singleElement().asString().contains("หลักฐานการชำระเงิน");
        assertThat(notices(warehouseEmployeeId, "DELIVERY_REQUEST_RETURNED")).isEmpty();
        refusal(() -> rounds.forward(draft.id(), warehouse), HttpStatus.CONFLICT);
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "b.jpg", "image/jpeg", new byte[] {1}),
            warehouse), HttpStatus.CONFLICT);
        assertThat(rounds.send(draft.id(), null, salesActor).returnReasonCodes()).as("re-send clears the reason").isEmpty();
        RoundDto detailOnly = rounds.sendBack(draft.id(), new DeliveryRequestDtos.ReturnRequest(List.of(), "ขอเลขที่สั่งซื้อ"), warehouse);
        assertThat(detailOnly.returnReasonCodes()).isEmpty();
        assertThat(detailOnly.returnReasonText()).isEqualTo("ขอเลขที่สั่งซื้อ");
        rounds.send(draft.id(), null, salesActor);

        // Forward: with no flag holder anywhere it is refused and the round stays SENT.
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        // The import division holds the grant here, and still does not count: it is price-free, so nobody can issue.
        long importHolder = createEmployee(employees, "ฝ่ายนำเข้า ถือสิทธิ์", "imp-holder-dr@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", importHolder));
        refusal(() -> rounds.forward(draft.id(), warehouse), HttpStatus.CONFLICT);
        assertThat(rounds.list(ticketId, warehouse).rounds().get(0).status()).isEqualTo("SENT");
        // Then only an active can_issue_tax_invoice holder is told (an account user without the flag is not); a second forward is a 409.
        long issuer = createEmployee(employees, "ผู้ออกใบกำกับ ทดสอบ", "issuer-dr@glr.co.th", "AC", "บัญชี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", issuer));
        RoundDto forwarded = rounds.forward(draft.id(), warehouse);
        assertThat(forwarded.status()).isEqualTo("FORWARDED");
        assertThat(notices(issuer, "DELIVERY_REQUEST_FORWARDED")).hasSize(1);
        assertThat(notices(importHolder, "DELIVERY_REQUEST_FORWARDED")).as("import is never an issuer").isEmpty();
        long noFlag = createEmployee(employees, "บัญชี ไม่มีสิทธิ์", "acc-noflag-dr@glr.co.th", "AC", "บัญชี", null);
        assertThat(notices(noFlag, "DELIVERY_REQUEST_FORWARDED")).isEmpty();
        assertThat(notices(salesActor.id(), "DELIVERY_REQUEST_FORWARDED")).isEmpty();
        assertThat(notices(warehouseEmployeeId, "DELIVERY_REQUEST_FORWARDED")).isEmpty();
        refusal(() -> rounds.forward(draft.id(), warehouse), HttpStatus.CONFLICT);
        refusal(() -> rounds.sendBack(draft.id(), new DeliveryRequestDtos.ReturnRequest(List.of("PAYMENT_PROOF"), null),
            warehouse), HttpStatus.CONFLICT);
        assertThat(rounds.addFile(draft.id(), new MockMultipartFile("file", "c.jpg", "image/jpeg", new byte[] {1}),
            warehouse).files()).hasSize(2);
        assertThat(eventKinds(ticketId)).contains("DELIVERY_REQUEST_RETURNED", "DELIVERY_REQUEST_FORWARDED");
    }

    @Test
    void taxInvoice_recordsOnlyThePartNotAlreadyRecorded_onceTheIssuerAloneMayConfirm_andIsLiveWhereInvoiceOnFileIs() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId));
        TicketRepository tickets = new TicketRepository(jdbc);
        assertThat(tickets.payableAmount(ticketId)).isEqualByComparingTo("85065.00");
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long issuerId = createEmployee(employees, "ผู้ออกใบกำกับ สอง", "issuer2-dr@glr.co.th", "QC", "คิวซี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", issuerId));
        UserPrincipal issuer = actor(issuerId, "employee"); // a role with no deal access: the grant is what admits it
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        // The read-all grant opens the deal's reads but never sales' private DRAFT round.
        refusal(() -> rounds.list(ticketId, issuer), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :id", Map.of("id", issuerId));
        assertThat(rounds.list(ticketId, issuer).rounds()).isEmpty();
        rounds.send(rid, null, salesActor);
        // Once sent the same flagged reader does see the round (so the empty list above was the draft filter, not a blanket no).
        assertThat(rounds.list(ticketId, issuer).rounds()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("SENT"));
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = FALSE WHERE employee_id = :id", Map.of("id", issuerId));
        refusal(() -> rounds.list(ticketId, issuer), HttpStatus.FORBIDDEN);
        rounds.forward(rid, warehouse);

        // Only the holder, only the forwarded round of this order; a rep or the warehouse is refused.
        var pdf = new MockMultipartFile("file", "iv.pdf", "application/pdf", TaxInvoicePdfReaderTest.pdf(
            "Tax Invoice No. IV2611-0042", "Date 19/11/2569", "Grand Total 85,065.00"));
        refusal(() -> rounds.attachTaxInvoice(rid, pdf, salesActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.attachTaxInvoice(rid, pdf, warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.attachTaxInvoice(rid, new MockMultipartFile("file", "iv.pdf", "application/pdf", new byte[] {1, 2, 3}),
            issuer), HttpStatus.BAD_REQUEST);
        var confirm = new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0042", java.time.LocalDate.of(2026, 11, 19),
            new BigDecimal("85065.00"));
        refusal(() -> rounds.confirmInvoice(rid, confirm, issuer), HttpStatus.CONFLICT); // no file yet
        assertThat(rounds.list(ticketId, issuer).rounds()).singleElement().satisfies(r -> assertThat(r.viewer().canIssue()).isTrue());

        var up = rounds.attachTaxInvoice(rid, pdf, issuer);
        assertThat(up.readOk()).isTrue();
        assertThat(up.read().invoiceNumber()).isEqualTo("IV2611-0042");
        assertThat(up.read().total()).isEqualByComparingTo("85065.00");
        assertThat(tickets.hasInvoiceAttachment(ticketId)).as("an unconfirmed file is not an invoice on file").isFalse();
        refusal(() -> rounds.confirmInvoice(rid, confirm, salesActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.confirmInvoice(rid, new DeliveryRequestDtos.ConfirmInvoiceRequest(" ", confirm.invoiceDate(),
            confirm.total()), issuer), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.confirmInvoice(rid, new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0042",
            java.time.LocalDate.now().plusDays(2), confirm.total()), issuer), HttpStatus.BAD_REQUEST);

        // The deposit (70,887.50) is already on the order: only the 14,177.50 not yet recorded counts, so nothing doubles.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, source)
            VALUES (:t, 'DEPOSIT', 70887.50, 'THB', now(), :u, 'MANUAL')
            """, Map.of("t", ticketId, "u", issuerId));
        RoundDto done = rounds.confirmInvoice(rid, confirm, issuer);
        assertThat(done.status()).isEqualTo("INVOICED");
        assertThat(done.invoice().total()).isEqualByComparingTo("85065.00");
        assertThat(done.invoice().recordedAmount()).isEqualByComparingTo("14177.50");
        assertThat(jdbc.queryForObject("SELECT amount FROM sales.payment_receipt WHERE source = 'TAX_INVOICE' AND ticket_id = :t",
            Map.of("t", ticketId), BigDecimal.class)).isEqualByComparingTo("14177.50");
        assertThat(tickets.payableAmount(ticketId).subtract(tickets.sumPaid(ticketId))).isEqualByComparingTo("0.00");
        assertThat(tickets.hasInvoiceAttachment(ticketId)).isTrue();
        assertThat(eventKinds(ticketId)).contains("TAX_INVOICE_CONFIRMED");
        refusal(() -> rounds.confirmInvoice(rid, confirm, issuer), HttpStatus.CONFLICT);
        assertThat(rounds.list(ticketId, warehouse).rounds().get(0).invoice().total()).as("no money for the warehouse").isNull();
        assertThat(notices(warehouseEmployeeId, "TAX_INVOICE_CONFIRMED")).hasSize(1);
        assertThat(notices(salesActor.id(), "TAX_INVOICE_CONFIRMED")).hasSize(1);
        assertThat(notices(issuerId, "TAX_INVOICE_CONFIRMED")).isEmpty();
    }

    @Test
    void taxInvoice_laterRoundCountsOnlyWhatEarlierRoundsLeft_theWorkedExample() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId));
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long issuerId = createEmployee(employees, "ผู้ออกใบกำกับ สาม", "issuer3-dr@glr.co.th", "AC", "บัญชี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", issuerId));
        UserPrincipal issuer = actor(issuerId, "account");
        TicketRepository tickets = new TicketRepository(jdbc);

        long r1 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r1, null, salesActor);
        rounds.forward(r1, warehouse);
        invoice(r1, "IV2611-0001", "70887.50", issuer);
        assertThat(tickets.sumPaid(ticketId)).isEqualByComparingTo("70887.50");
        assertThat(jdbc.queryForObject("SELECT kind FROM sales.payment_receipt WHERE receipt_ref = 'IV2611-0001'", Map.of(), String.class))
            .as("an invoice is never the deposit").isEqualTo("BALANCE");

        jdbc.update("UPDATE sales.delivery_request_line SET qty = qty / 2 WHERE delivery_request_id = :id", Map.of("id", r1));
        long r2 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r2, null, salesActor);
        rounds.forward(r2, warehouse);
        invoice(r2, "IV2611-0002", "14177.50", issuer);
        assertThat(tickets.payableAmount(ticketId).subtract(tickets.sumPaid(ticketId))).isEqualByComparingTo("0.00");
        List<BigDecimal> recorded = jdbc.queryForList("SELECT recorded_amount FROM sales.delivery_request WHERE ticket_id = :t ORDER BY round_no",
            Map.of("t", ticketId), BigDecimal.class);
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0)).isEqualByComparingTo("70887.50");
        assertThat(recorded.get(1)).isEqualByComparingTo("14177.50");

        // The ceiling: the order's invoices together never exceed what it is worth; the refusal says what is left.
        jdbc.update("UPDATE sales.delivery_request_line SET qty = qty / 2 WHERE delivery_request_id = :id", Map.of("id", r2));
        long r3 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r3, null, salesActor);
        rounds.forward(r3, warehouse);
        rounds.attachTaxInvoice(r3, new MockMultipartFile("file", "IV2611-0003.pdf", "application/pdf",
            TaxInvoicePdfReaderTest.pdf("No. IV2611-0003", "Grand Total 1.00")), issuer);
        var over = new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0003", java.time.LocalDate.now(), new BigDecimal("1.01"));
        // ฿1.00 over is tolerated (Express rounds VAT per line); ฿1.01 over is refused, saying what is left.
        assertThat(refusal(() -> rounds.confirmInvoice(r3, over, issuer), HttpStatus.BAD_REQUEST).getMessage()).contains("฿0.00");

        // Only the LATEST invoiced round can be voided (r1 rests under r2's money); r3 waiting FORWARDED does not matter.
        refusal(() -> rounds.voidInvoice(r1, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), issuer), HttpStatus.CONFLICT);
        var shown = rounds.list(ticketId, issuer).rounds();
        assertThat(shown.stream().filter(r -> r.id() == r1).findFirst().orElseThrow().viewer()).satisfies(v -> {
            assertThat(v.canVoid()).isFalse();
            assertThat(v.voidBlockedReason()).contains("รอบส่งที่ 2");
        });
        assertThat(shown.stream().filter(r -> r.id() == r2).findFirst().orElseThrow().viewer().canVoid()).isTrue();

        // Void: only the TAX_INVOICE receipt of that round goes, outstanding is restored exactly, the round is FORWARDED again.
        refusal(() -> rounds.voidInvoice(r2, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), salesActor), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.voidInvoice(r2, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> rounds.voidInvoice(r2, new DeliveryRequestDtos.VoidInvoiceRequest(" "), issuer), HttpStatus.BAD_REQUEST);
        jdbc.update("INSERT INTO sales.delivery_record (ticket_id, source, delivered_by, delivery_request_id) VALUES (:t, 'WAREHOUSE', :u, :r)",
            Map.of("t", ticketId, "u", issuerId, "r", r2));
        refusal(() -> rounds.voidInvoice(r2, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), issuer), HttpStatus.CONFLICT);
        jdbc.update("DELETE FROM sales.delivery_record WHERE delivery_request_id = :r", Map.of("r", r2));
        RoundDto back = rounds.voidInvoice(r2, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), issuer);
        assertThat(back.status()).isEqualTo("FORWARDED");
        assertThat(back.invoice()).isNull();
        assertThat(tickets.payableAmount(ticketId).subtract(tickets.sumPaid(ticketId))).isEqualByComparingTo("14177.50");
        assertThat(tickets.sumPaid(ticketId)).isEqualByComparingTo("70887.50");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.payment_receipt WHERE receipt_ref = 'IV2611-0001'", Map.of(), Integer.class)).isEqualTo(1);
        assertThat(eventKinds(ticketId)).contains("TAX_INVOICE_VOIDED");
        assertThat(notices(salesActor.id(), "TAX_INVOICE_VOIDED")).hasSize(1);
        // The number is free again, and the same round can be confirmed anew.
        rounds.confirmInvoice(r2, new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0002", java.time.LocalDate.now(),
            new BigDecimal("14177.50")), issuer);
        assertThat(tickets.payableAmount(ticketId).subtract(tickets.sumPaid(ticketId))).isEqualByComparingTo("0.00");
    }

    @Test
    void aMistypedInvoiceIsVoidedBesideAnotherForwardedRound_theOrderIsNeverJammed_andPaymentStatusFollowsTheMoney() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId)); // payable 85,065
        UserPrincipal issuer = issuer("issuer5-dr@glr.co.th");
        TicketRepository tickets = new TicketRepository(jdbc);
        String before = paymentStatus(ticketId);

        long r1 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r1, null, salesActor);
        rounds.forward(r1, warehouse);
        invoice(r1, "IV2611-0101", "70000.00", issuer); // typed too large: it should have been 50,000
        assertThat(paymentStatus(ticketId)).as("the invoice money advanced the track").isNotEqualTo(before);
        jdbc.update("UPDATE sales.delivery_request_line SET qty = qty / 2 WHERE delivery_request_id = :id", Map.of("id", r1));
        long r2 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r2, null, salesActor);
        rounds.forward(r2, warehouse);
        rounds.attachTaxInvoice(r2, new MockMultipartFile("file", "IV2611-0102.pdf", "application/pdf",
            TaxInvoicePdfReaderTest.pdf("No. IV2611-0102", "Grand Total 30,000.00")), issuer);
        var second = new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0102", java.time.LocalDate.now(), new BigDecimal("30000.00"));
        refusal(() -> rounds.confirmInvoice(r2, second, issuer), HttpStatus.BAD_REQUEST); // 100,000 > 85,065 + 1

        // r1 is the latest invoiced round, r2 only waits FORWARDED: the void works, and the money, status and number go back.
        rounds.voidInvoice(r1, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด"), issuer);
        assertThat(tickets.sumPaid(ticketId)).isEqualByComparingTo("0");
        assertThat(paymentStatus(ticketId)).as("no money left: status back, stage untouched").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT message FROM sales.ticket_event WHERE kind = 'TAX_INVOICE_VOIDED' AND ticket_id = :t",
            Map.of("t", ticketId), String.class)).contains("ไม่ย้อนกลับ");
        rounds.confirmInvoice(r1, new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0101", java.time.LocalDate.now(),
            new BigDecimal("50000.00")), issuer);
        assertThat(rounds.confirmInvoice(r2, second, issuer).status()).isEqualTo("INVOICED");
        assertThat(tickets.sumPaid(ticketId)).isEqualByComparingTo("80000.00");
    }

    @Test
    void voidSubtractsExactlyWhatTheInvoiceReceiptDidToTheExpectedPayments() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId));
        UserPrincipal issuer = issuer("issuer6-dr@glr.co.th");
        long r1 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r1, null, salesActor);
        rounds.forward(r1, warehouse);
        var confirm = (java.util.function.Function<String, DeliveryRequestDtos.ConfirmInvoiceRequest>) total ->
            new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0301", java.time.LocalDate.now(), new BigDecimal(total));
        var void_ = new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด");
        rounds.attachTaxInvoice(r1, new MockMultipartFile("file", "IV2611-0301.pdf", "application/pdf",
            TaxInvoicePdfReaderTest.pdf("No. IV2611-0301", "Grand Total 100.00")), issuer);

        // 1) The receipt of 100 filled row A (60) and put 40 on row B: after the void both show nothing from it.
        long a = expectedRow(ticketId, "ON_DELIVERY", "60.00", 0);
        long b = expectedRow(ticketId, "BALANCE", "100.00", 7);
        rounds.confirmInvoice(r1, confirm.apply("100.00"), issuer);
        assertThat(expected(a)).containsEntry("status", "RECEIVED");
        assertThat((BigDecimal) expected(b).get("received_amount")).isEqualByComparingTo("40.00");
        rounds.voidInvoice(r1, void_, issuer);
        for (long id : new long[] {a, b}) {
            assertThat(expected(id)).containsEntry("status", "OPEN").containsEntry("receipt_id", null);
            assertThat((BigDecimal) expected(id).get("received_amount")).isEqualByComparingTo("0");
        }

        // 2) Row A (100) had 60 from a manual receipt; the invoice receipt of 50 completed it and spilled 10: after the
        //    void A shows 60 again and the next row 0.
        jdbc.update("DELETE FROM sales.expected_payment WHERE ticket_id = :t", Map.of("t", ticketId));
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, source)
            VALUES (:t, 'BALANCE', 60, 'THB', now(), :u, 'MANUAL')
            """, Map.of("t", ticketId, "u", issuer.id()));
        long a2 = expectedRow(ticketId, "ON_DELIVERY", "100.00", 0);
        long b2 = expectedRow(ticketId, "BALANCE", "100.00", 7);
        jdbc.update("UPDATE sales.expected_payment SET received_amount = 60 WHERE expected_payment_id = :id", Map.of("id", a2));
        rounds.confirmInvoice(r1, confirm.apply("110.00"), issuer); // paid 60 -> 110 counted: 50 recorded
        assertThat(expected(a2)).containsEntry("status", "RECEIVED");
        assertThat((BigDecimal) expected(b2).get("received_amount")).isEqualByComparingTo("10.00");
        rounds.voidInvoice(r1, void_, issuer);
        assertThat(expected(a2)).containsEntry("status", "OPEN");
        assertThat((BigDecimal) expected(a2).get("received_amount")).isEqualByComparingTo("60.00");
        assertThat((BigDecimal) expected(b2).get("received_amount")).isEqualByComparingTo("0");

        // 3) The manual 60 was recorded before any expected row existed: a void never moves it onto a row.
        jdbc.update("DELETE FROM sales.expected_payment WHERE ticket_id = :t", Map.of("t", ticketId));
        long a3 = expectedRow(ticketId, "ON_DELIVERY", "100.00", 0);
        rounds.confirmInvoice(r1, confirm.apply("110.00"), issuer); // 50 recorded: row A3 shows 50
        assertThat((BigDecimal) expected(a3).get("received_amount")).isEqualByComparingTo("50.00");
        rounds.voidInvoice(r1, void_, issuer);
        assertThat((BigDecimal) expected(a3).get("received_amount")).isEqualByComparingTo("0");
        assertThat(expected(a3)).containsEntry("status", "OPEN");
    }

    @Test
    void aVoidIsRefusedOnceAnyLaterMoneyOrExpressMatchRestsOnTheInvoice_andAQueuedTwinNeverBlocksIt() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId));
        UserPrincipal issuer = issuer("issuer7-dr@glr.co.th");
        long r1 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r1, null, salesActor);
        rounds.forward(r1, warehouse);
        var body = new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ยอดผิด");
        invoice(r1, "IV2611-0401", "1000.00", issuer);
        long receipt = jdbc.queryForObject("SELECT receipt_id FROM sales.payment_receipt WHERE source = 'TAX_INVOICE' AND ticket_id = :t",
            Map.of("t", ticketId), Long.class);

        // A queued Express row that merely looks like this money does not block the void: the row survives, suggestion cleared.
        long importId = jdbc.queryForObject("INSERT INTO sales.receipt_import (file_name, uploaded_by) VALUES ('t.csv', :u) RETURNING import_id",
            Map.of("u", issuer.id()), Long.class);
        jdbc.update("""
            INSERT INTO sales.receipt_import_row (import_id, doc_no, doc_date, amount, reason, suggested_receipt_id)
            VALUES (:i, 'RE-V-1', CURRENT_DATE, 1000, 'POSSIBLE_MANUAL_DUPLICATE', :r)
            """, Map.of("i", importId, "r", receipt));
        rounds.voidInvoice(r1, body, issuer);
        assertThat(jdbc.queryForMap("SELECT status, suggested_receipt_id FROM sales.receipt_import_row WHERE doc_no = 'RE-V-1'", Map.of()))
            .containsEntry("status", "UNMATCHED").containsEntry("suggested_receipt_id", null);

        // A row RESOLVED against the receipt blocks it, with the reason shown to the viewer.
        invoice(r1, "IV2611-0401", "1000.00", issuer);
        long receipt2 = jdbc.queryForObject("SELECT receipt_id FROM sales.payment_receipt WHERE source = 'TAX_INVOICE' AND ticket_id = :t",
            Map.of("t", ticketId), Long.class);
        jdbc.update("""
            INSERT INTO sales.receipt_import_row (import_id, doc_no, doc_date, amount, reason, status, resolved_ticket_id, receipt_id,
                   resolved_by, resolved_at)
            VALUES (:i, 'RE-V-2', CURRENT_DATE, 1000, 'POSSIBLE_MANUAL_DUPLICATE', 'RESOLVED', :t, :r, :u, now())
            """, Map.of("i", importId, "t", ticketId, "r", receipt2, "u", issuer.id()));
        assertThat(refusal(() -> rounds.voidInvoice(r1, body, issuer), HttpStatus.CONFLICT).getMessage()).contains("Express");
        assertThat(rounds.list(ticketId, issuer).rounds().get(0).viewer().voidBlockedReason()).contains("Express");
        jdbc.update("DELETE FROM sales.receipt_import_row WHERE doc_no = 'RE-V-2'", Map.of());

        // Any receipt recorded after the invoice blocks it: account adjusts the amount instead.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, source)
            VALUES (:t, 'BALANCE', 5, 'THB', now(), :u, 'MANUAL')
            """, Map.of("t", ticketId, "u", issuer.id()));
        assertThat(refusal(() -> rounds.voidInvoice(r1, body, issuer), HttpStatus.CONFLICT).getMessage())
            .contains("มีการบันทึกรับเงินหลังใบกำกับนี้แล้ว ให้ฝ่ายบัญชีปรับยอด");
        assertThat(rounds.list(ticketId, issuer).rounds().get(0).viewer().canVoid()).isFalse();
    }

    @Test
    void aVoidIsRefusedOnceTheDealIsConfirmedPaidInFull_whichWritesNoReceipt() throws Exception {
        long ticketId = confirmedDeal();
        UserPrincipal issuer = issuer("issuer8-dr@glr.co.th");
        long r1 = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(r1, null, salesActor);
        rounds.forward(r1, warehouse);
        invoice(r1, "IV2611-0501", "1000.00", issuer);
        // confirmFinalPayment with nothing outstanding only walks the track to FULLY_PAID (no receipt): the state it leaves.
        jdbc.update("UPDATE sales.ticket SET payment_status = 'FULLY_PAID' WHERE ticket_id = :t", Map.of("t", ticketId));
        assertThat(refusal(() -> rounds.voidInvoice(r1, new DeliveryRequestDtos.VoidInvoiceRequest("x"), issuer), HttpStatus.CONFLICT)
            .getMessage()).contains("ยืนยันรับชำระครบแล้ว");
    }

    @Test
    void account_readsSentRoundsOnlyAndActsOnNone_importIsPriceFree_andAManualReceiptWithTheSameNumberIsExplained() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.quotation SET total_amount = 79500 WHERE ticket_id = :t", Map.of("t", ticketId));
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long issuerId = createEmployee(employees, "ผู้ออกใบกำกับ สี่", "issuer4-dr@glr.co.th", "QC", "คิวซี", null);
        long importId = createEmployee(employees, "ฝ่ายนำเข้า ถือสิทธิ์", "import-dr@glr.co.th", "OPS", "ฝ่ายปฏิบัติการ", null);
        long accountId = createEmployee(employees, "บัญชี ดูอย่างเดียว", "acc-view-dr@glr.co.th", "AC", "บัญชี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id IN (:a, :b)", Map.of("a", issuerId, "b", importId));
        UserPrincipal issuer = actor(issuerId, "employee");
        UserPrincipal importer = actor(importId, "import");
        UserPrincipal account = actor(accountId, "account");
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(rid, null, salesActor);
        assertThat(rounds.list(ticketId, account).rounds()).as("sent: readable, no actions").singleElement()
            .satisfies(r -> assertThat(r.viewer().canEdit() || r.viewer().canIssue() || r.viewer().canReview()).isFalse());
        rounds.forward(rid, warehouse);
        rounds.attachTaxInvoice(rid, new MockMultipartFile("file", "IV2611-0050.pdf", "application/pdf",
            TaxInvoicePdfReaderTest.pdf("No. IV2611-0050", "Grand Total 1,000.00")), issuer);

        // The account person typed that number as a manual receipt already: the confirm says exactly that, and nothing is kept.
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, source, receipt_ref)
            VALUES (:t, 'DEPOSIT', 10, 'THB', now(), :u, 'MANUAL', 'IV2611-0050')
            """, Map.of("t", ticketId, "u", accountId));
        var confirm = new DeliveryRequestDtos.ConfirmInvoiceRequest("IV2611-0050", java.time.LocalDate.now(), new BigDecimal("1000.00"));
        assertThat(refusal(() -> rounds.confirmInvoice(rid, confirm, issuer), HttpStatus.CONFLICT).getMessage())
            .contains("ฝ่ายบัญชีบันทึกเองไว้แล้ว");
        assertThat(rounds.list(ticketId, issuer).rounds().get(0).status()).isEqualTo("FORWARDED");

        jdbc.update("DELETE FROM sales.payment_receipt WHERE receipt_ref = 'IV2611-0050'", Map.of());
        rounds.confirmInvoice(rid, confirm, issuer);
        // The import role cannot issue even holding the grant: a Thai 403 on each act, and no issuer step.
        for (Runnable act : List.<Runnable>of(
            () -> rounds.confirmInvoice(rid, confirm, importer),
            () -> rounds.voidInvoice(rid, new DeliveryRequestDtos.VoidInvoiceRequest("x"), importer),
            () -> rounds.attachTaxInvoice(rid, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1}), importer))) {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(th.co.glr.hr.common.ApiException.class, act::run).getMessage())
                .isEqualTo("บทบาทนำเข้าไม่สามารถออกใบกำกับได้");
        }
        // Nor does the grant open the rounds to import: it reads no deal, so the list is refused (price-free by construction).
        refusal(() -> rounds.list(ticketId, importer), HttpStatus.FORBIDDEN);
    }

    @Test
    void aDeliveryNeedsTheRoundsInvoice_warehouseRecordsIt_itIsLinkedToTheRound_andTheInvoiceCannotThenBeVoided() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'GOODS_RECEIVED' WHERE ticket_id = :t", Map.of("t", ticketId));
        // The goods-received EVENT is the permanent fact a later partial delivery relies on (warehouseDeliveryAvailable).
        new TicketRepository(jdbc).addEvent(ticketId, salesActor.id(), "เจ้าของ", "GOODS_RECEIVED", null, null, null);
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long issuerId = createEmployee(employees, "ผู้ออกใบกำกับ สี่", "issuer4-dr@glr.co.th", "AC", "บัญชี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", issuerId));
        UserPrincipal issuer = actor(issuerId, "account");
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(rid, null, salesActor);
        rounds.forward(rid, warehouse);
        var all = new th.co.glr.hr.ticket.RoundDeliveryRequest(null, true, null, "คุณลูกค้า", null, null);

        // Before the invoice both need a note; nothing is written without one.
        assertThat(refusal(() -> rounds.recordDelivery(rid, all, warehouse), HttpStatus.CONFLICT).getDetails())
            .containsEntry("code", "NO_INVOICE_NOTE_REQUIRED");
        assertThat(refusal(() -> rounds.recordDelivery(rid, all, salesActor), HttpStatus.CONFLICT).getDetails())
            .containsEntry("code", "NO_INVOICE_NOTE_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE ticket_id = :t", Map.of("t", ticketId), Integer.class)).isZero();

        invoice(rid, "IV2611-0044", "1000", issuer);
        RoundDto confirmed = rounds.list(ticketId, warehouse).rounds().get(0);
        assertThat(confirmed.viewer().canRecordDelivery()).isTrue();
        var line = confirmed.lines().get(0);
        // A partial record by warehouse staff leaves the rest deliverable; more than the rest is refused.
        var one = new th.co.glr.hr.ticket.RoundDeliveryRequest(
            List.of(new th.co.glr.hr.ticket.RecordDeliveryRequest.Line(line.itemId(), BigDecimal.ONE)), null, null, null, null, null);
        RoundDto partial = rounds.recordDelivery(rid, one, warehouse);
        assertThat(partial.lines().get(0).deliveredQty()).isEqualByComparingTo("1");
        assertThat(partial.viewer().canRecordDelivery()).isTrue();
        var tooMany = new th.co.glr.hr.ticket.RoundDeliveryRequest(List.of(
            new th.co.glr.hr.ticket.RecordDeliveryRequest.Line(line.itemId(), line.coveredQty())), null, null, null, null, null);
        assertThat(refusal(() -> rounds.recordDelivery(rid, tooMany, warehouse), HttpStatus.CONFLICT).getDetails())
            .containsEntry("code", "ROUND_QTY_EXCEEDED");
        // The invoice cannot be voided once goods are recorded against it, and each record is linked to the round.
        assertThat(rounds.list(ticketId, issuer).rounds().get(0).viewer().canVoid()).isFalse();
        refusal(() -> rounds.voidInvoice(rid, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ผิด"), issuer), HttpStatus.CONFLICT);
        RoundDto done = rounds.recordDelivery(rid, all, salesActor);
        assertThat(done.viewer().canRecordDelivery()).isFalse();
        assertThat(jdbc.queryForList("SELECT delivery_request_id FROM sales.delivery_record WHERE ticket_id = :t", Map.of("t", ticketId), Long.class))
            .containsOnly(rid).hasSize(2);
        assertThat(notices(salesActor.id(), "DELIVERY_RECORDED")).hasSize(1).allMatch(m -> m.startsWith("ส่งของแล้ว 1 แผ่น"));
        refusal(() -> rounds.recordDelivery(rid, all, salesActor), HttpStatus.CONFLICT);
    }

    @Test
    void goodsSentWithoutTheRound_leaveItShownAsDelivered_andItsInvoiceCannotBeVoided() throws Exception {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'GOODS_RECEIVED' WHERE ticket_id = :t", Map.of("t", ticketId));
        new TicketRepository(jdbc).addEvent(ticketId, salesActor.id(), "เจ้าของ", "GOODS_RECEIVED", null, null, null);
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long issuerId = createEmployee(employees, "ผู้ออกใบกำกับ ห้า", "issuer5-dr@glr.co.th", "AC", "บัญชี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", issuerId));
        UserPrincipal issuer = actor(issuerId, "account");
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(rid, null, salesActor);
        rounds.forward(rid, warehouse);
        invoice(rid, "IV2611-0055", "1000", issuer);
        assertThat(rounds.list(ticketId, salesActor).rounds().get(0).viewer().canRecordDelivery()).isTrue();

        // Everything goes out through the note path (no round named): the round must stop offering a record button.
        var lines = rounds.list(ticketId, salesActor).rounds().get(0).lines().stream()
            .map(l -> new th.co.glr.hr.ticket.RecordDeliveryRequest.Line(l.itemId(), l.coveredQty())).toList();
        ticketService.recordPartialDelivery(ticketId, new th.co.glr.hr.ticket.RecordDeliveryRequest(
            "WAREHOUSE", null, lines, null, null, null, "PO-9"), salesActor);
        RoundDto round = rounds.list(ticketId, issuer).rounds().get(0);
        assertThat(round.lines()).allMatch(l -> l.deliveredQty().compareTo(l.coveredQty()) == 0);
        assertThat(round.viewer().canRecordDelivery()).isFalse();
        assertThat(round.viewer().canVoid()).isFalse();
        assertThat(round.viewer().voidBlockedReason()).isNotBlank();
        refusal(() -> rounds.voidInvoice(rid, new DeliveryRequestDtos.VoidInvoiceRequest("พิมพ์ผิด"), issuer), HttpStatus.CONFLICT);
    }

    @Test
    void aRoundNotYetInvoicedCapsWhatIsRecordedAgainstIt_soASecondRecordBeyondItsCoverIsRefused() {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'GOODS_RECEIVED' WHERE ticket_id = :t", Map.of("t", ticketId));
        new TicketRepository(jdbc).addEvent(ticketId, salesActor.id(), "เจ้าของ", "GOODS_RECEIVED", null, null, null);
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(rid, null, salesActor);
        // The round covers half of every line.
        jdbc.update("UPDATE sales.delivery_request_line SET qty = qty / 2 WHERE delivery_request_id = :id", Map.of("id", rid));
        var half = new th.co.glr.hr.ticket.RoundDeliveryRequest(null, true, null, null, null, "ส่งก่อน");
        rounds.recordDelivery(rid, half, salesActor);
        assertThat(refusal(() -> rounds.recordDelivery(rid, half, salesActor), HttpStatus.CONFLICT).getMessage()).isNotBlank();
        BigDecimal delivered = jdbc.queryForObject("SELECT SUM(qty_delivered) FROM sales.ticket_item WHERE ticket_id = :t",
            Map.of("t", ticketId), BigDecimal.class);
        BigDecimal covered = jdbc.queryForObject("SELECT SUM(qty) FROM sales.delivery_request_line WHERE delivery_request_id = :id",
            Map.of("id", rid), BigDecimal.class);
        assertThat(delivered).isEqualByComparingTo(covered);
    }

    @Test
    void forward_isNotRefusedWhenTheWarehousePersonIsTheOnlyIssuer() {
        long ticketId = confirmedDeal();
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", warehouseEmployeeId));
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        rounds.send(rid, null, salesActor);
        assertThat(rounds.forward(rid, warehouse).status()).isEqualTo("FORWARDED");
        assertThat(notices(warehouseEmployeeId, "DELIVERY_REQUEST_FORWARDED")).as("the bell still leaves the actor out").isEmpty();
    }

    @Test
    void trip_isTheWarehouseManagersOnly_deliversWhatCanLeave_leavesTheDelayedAndBlockedOnes_andSalesSeesThePlan() throws Exception {
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        UserPrincipal manager = actor(createEmployee(employees, "หัวหน้าคลัง รอบส่ง", "wh-mgr-trip@glr.co.th", "WH", "คลังสินค้า", "ผู้จัดการ"), "warehouse");
        UserPrincipal issuer = issuer("issuer-trip@glr.co.th");
        var trips = transactional(new th.co.glr.hr.deliverytrip.DeliveryTripService(new th.co.glr.hr.deliverytrip.DeliveryTripRepository(jdbc),
            rounds, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), transactionManager));
        long[] rid = new long[3];
        long[] ticket = new long[3];
        for (int i = 0; i < 3; i++) {
            ticket[i] = confirmedDeal();
            rid[i] = rounds.createDraft(ticket[i], null, salesActor).id();
            long draft = rid[i];
            if (i == 2) refusal(() -> rounds.update(draft, new UpdateRequest(null, null, null, TODAY.minusDays(1), null), salesActor), HttpStatus.BAD_REQUEST);
            if (i == 2) {
                rounds.update(draft, new UpdateRequest(null, null, null, TODAY.plusDays(3), null), salesActor);
                assertThat(rounds.update(draft, new UpdateRequest(null, "x", null), salesActor).requestedDate())
                    .as("a save without the date keeps it").isEqualTo(TODAY.plusDays(3));
                assertThat(rounds.update(draft, new UpdateRequest(null, null, null, null, true), salesActor).requestedDate()).isNull();
                rounds.update(draft, new UpdateRequest(null, null, null, TODAY.plusDays(3), null), salesActor);
            }
            rounds.send(rid[i], null, salesActor);
            rounds.forward(rid[i], warehouse);
            invoice(rid[i], "IV2611-9" + i, "1000", issuer);
        }
        // Only the first order's goods are in the warehouse.
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'GOODS_RECEIVED' WHERE ticket_id = :t", Map.of("t", ticket[0]));
        new TicketRepository(jdbc).addEvent(ticket[0], salesActor.id(), "เจ้าของ", "GOODS_RECEIVED", null, null, null);

        assertThat(trips.home(manager).waiting()).extracting(r -> r.roundId()).contains(rid[0], rid[1], rid[2]);
        assertThat(trips.home(manager).waiting().get(0).requestedDate()).as("requested dates sort first").isEqualTo(TODAY.plusDays(3));
        refusal(() -> trips.home(warehouse), HttpStatus.FORBIDDEN);
        refusal(() -> trips.home(salesActor), HttpStatus.FORBIDDEN);
        var all = List.of(rid[0], rid[1], rid[2]);
        refusal(() -> trips.create(new th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest(TODAY.minusDays(1), "สายบางนา", null, null, null, all), manager),
            HttpStatus.BAD_REQUEST);
        var request = new th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest(TODAY.plusDays(1), "สายบางนา", "เช้า", null, null, all);
        var trip = trips.create(request, manager);
        assertThat(trips.home(manager).waiting()).extracting(r -> r.roundId()).doesNotContain(rid[0], rid[1], rid[2]);
        refusal(() -> trips.create(request, manager), HttpStatus.CONFLICT);
        assertThat(rounds.list(ticket[0], salesActor).rounds().get(0).trip()).satisfies(p -> {
            assertThat(p.state()).isEqualTo("PLANNED");
            assertThat(p.deliveryDate()).isEqualTo(TODAY.plusDays(1));
        });
        assertThat(notices(salesActor.id(), "DELIVERY_TRIP")).hasSize(3);

        // An update without roundIds changes the fields only.
        trips.update(trip.id(), new th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest(TODAY.plusDays(1), "สายบางนา", "บ่าย", null, null, null), manager);
        assertThat(trips.home(manager).trips().get(0).rounds()).hasSize(3);
        trips.update(trip.id(), new th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest(TODAY.plusDays(2), "สายบางนา", "บ่าย", null, null, null), manager);
        assertThat(notices(salesActor.id(), "DELIVERY_TRIP")).as("one notice for the date change, not one per order").hasSize(4);
        trips.delay(trip.id(), rid[2], true, manager);
        var pressed = trips.deliver(trip.id(), manager);
        assertThat(pressed.results()).extracting(r -> r.roundId(), r -> r.delivered())
            .containsExactly(org.assertj.core.groups.Tuple.tuple(rid[0], true), org.assertj.core.groups.Tuple.tuple(rid[1], false));
        assertThat(pressed.results().get(1).reason()).isNotBlank();
        assertThat(pressed.trip().status()).as("a blocked order keeps the trip open").isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_record WHERE ticket_id IN (:a,:b)",
            Map.of("a", ticket[1], "b", ticket[2]), Integer.class)).isZero();
        assertThat(rounds.list(ticket[2], salesActor).rounds().get(0).trip().state()).isEqualTo("DELAYED");
        assertThat(trips.home(manager).waiting()).filteredOn(r -> r.roundId() == rid[2]).singleElement().matches(r -> r.delayed());
        refusal(() -> trips.cancel(trip.id(), manager), HttpStatus.CONFLICT);

        trips.delay(trip.id(), rid[1], true, manager);
        assertThat(trips.deliver(trip.id(), manager).trip().status()).isEqualTo("DONE");
        assertThat(trips.home(manager).waiting()).as("a delivered order leaves the queue")
            .extracting(r -> r.roundId()).doesNotContain(rid[0]);
        refusal(() -> trips.deliver(trip.id(), manager), HttpStatus.CONFLICT);
    }

    private void invoice(long rid, String number, String total, UserPrincipal issuer) throws Exception {
        rounds.attachTaxInvoice(rid, new MockMultipartFile("file", number + ".pdf", "application/pdf",
            TaxInvoicePdfReaderTest.pdf("No. " + number, "Grand Total " + total)), issuer);
        rounds.confirmInvoice(rid, new DeliveryRequestDtos.ConfirmInvoiceRequest(number, java.time.LocalDate.now(),
            new BigDecimal(total)), issuer);
    }

    private List<String> notices(long employeeId, String type) {
        return jdbc.query("SELECT message FROM hr.notification WHERE employee_id = :e AND type = :t",
            Map.of("e", employeeId, "t", type), (rs, n) -> rs.getString(1));
    }

    @Test
    void salesManagerWithTheAllDocumentsGrant_mayWriteRounds_andOneWithoutStillMayNot() {
        long ticketId = confirmedDeal();
        refusal(() -> rounds.createDraft(ticketId, null, salesManagerActor), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE hr.employee SET can_manage_all_sales_documents = TRUE WHERE employee_id = :id",
            Map.of("id", salesManagerActor.id()));
        assertThat(rounds.createDraft(ticketId, null, salesManagerActor).status()).isEqualTo("DRAFT");
    }

    @Test
    void files_unknownTypeAndBlankOrOctetStreamTypeAreDecidedByTheExtension_andTheCountIsCapped() {
        long ticketId = confirmedDeal();
        RoundDto draft = rounds.createDraft(ticketId, null, salesActor);
        var xlsx = new MockMultipartFile("file", "sheet.xlsx", "application/octet-stream", new byte[] {1});
        assertThat(rounds.addFile(draft.id(), xlsx, salesActor).files()).singleElement().satisfies(f ->
            assertThat(f.mimeType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        var blank = new MockMultipartFile("file", "photo.heic", null, new byte[] {1});
        assertThat(rounds.addFile(draft.id(), blank, salesActor).files()).hasSize(2);
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "a.pdf", "text/html", new byte[] {1}), salesActor),
            HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "noext", null, new byte[] {1}), salesActor),
            HttpStatus.BAD_REQUEST);

        for (int i = 2; i < 30; i++) {
            jdbc.update("INSERT INTO sales.attachment (ticket_id, file_name, file_path, attach_type, uploaded_by, delivery_request_id,"
                + " uploader_side) VALUES (:t, 'f.pdf', '/x/f.pdf', 'OTHER', :u, :r, 'SALES')",
                Map.of("t", ticketId, "u", salesActor.id(), "r", draft.id()));
        }
        refusal(() -> rounds.addFile(draft.id(), new MockMultipartFile("file", "last.pdf", "application/pdf", new byte[] {1}),
            salesActor), HttpStatus.BAD_REQUEST);
    }

    @Test
    void send_notifiesWarehouseStaffByEffectiveRole_notByDivisionAlone_andNotTheSender() {
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long actingAsSales = createEmployee(employees, "พนักงานคลัง ขาย", "wh-sales-dr@glr.co.th", "WH", "คลังสินค้า", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", actingAsSales));
        long ticketId = confirmedDeal();
        RoundDto draft = rounds.createDraft(ticketId, null, salesActor);
        rounds.send(draft.id(), null, salesActor);
        List<String> links = jdbc.query("SELECT link FROM hr.notification WHERE employee_id = :e AND type = 'DELIVERY_REQUEST_SENT'",
            Map.of("e", warehouseEmployeeId), (rs, n) -> rs.getString("link"));
        assertThat(links).containsExactly("/tickets/" + ticketId + "?tab=ship");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e AND type = 'DELIVERY_REQUEST_SENT'",
            Map.of("e", actingAsSales), Integer.class)).as("a wh person acting as sales is not warehouse staff").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e AND type = 'DELIVERY_REQUEST_SENT'",
            Map.of("e", salesActor.id()), Integer.class)).isZero();
    }

    @Test
    void send_storesACopyOfEachSystemDocOnTheRound_andADocumentOfAnotherDealIs400WithNothingStored() {
        long ticketId = confirmedDeal();
        long quotationId = rounds.list(ticketId, salesActor).approvedQuotations().get(0).id();
        long noticeId = issuedNotice(ticketId, "DN-T1");
        when(depositNotices.getPdf(anyLong(), any())).thenReturn(new byte[] {1, 2, 3});
        long otherNotice = issuedNotice(deal(salesActor), "DN-OTHER");

        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        refusal(() -> rounds.send(rid, List.of(new SystemDocRef("QUOTATION", quotationId),
            new SystemDocRef("DEPOSIT_NOTICE", otherNotice)), salesActor), HttpStatus.BAD_REQUEST);
        assertThat(rounds.list(ticketId, salesActor).rounds().get(0).files()).isEmpty();

        RoundDto sent = rounds.send(rid, List.of(new SystemDocRef("QUOTATION", quotationId),
            new SystemDocRef("DEPOSIT_NOTICE", noticeId)), salesActor);
        assertThat(sent.files()).hasSize(2).allSatisfy(f -> {
            assertThat(f.kind()).isEqualTo("SYSTEM_DOC");
            assertThat(f.fileName()).endsWith(".pdf");
        });
        assertThat(sent.files().stream().map(f -> f.fileName()).toList()).contains("DN-T1.pdf");
    }

    @Test
    void aSentQuotationCannotSourceADepositNotice_systemCopiesAreNotExtraFiles_andUntickedChoicesStayOnTheDraft() {
        long ticketId = confirmedDeal();
        assertThat(rounds.list(ticketId, salesActor).approvedQuotations().get(0).depositBlockedReason()).isNull();
        jdbc.update("UPDATE sales.quotation SET doc_status = 'SENT' WHERE ticket_id = :t", Map.of("t", ticketId));
        assertThat(rounds.list(ticketId, salesActor).approvedQuotations().get(0).depositBlockedReason()).isNotBlank();

        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO sales.attachment (ticket_id, file_name, file_path, attach_type, uploaded_by, delivery_request_id,"
                + " uploader_side) VALUES (:t, 'f.pdf', '/x/f.pdf', :k, :u, :r, 'SALES')",
                Map.of("t", ticketId, "k", i == 0 ? "SYSTEM_DOC" : "OTHER", "u", salesActor.id(), "r", rid));
        }
        assertThat(rounds.addFile(rid, new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1}), salesActor)
            .files()).hasSize(31);
        refusal(() -> rounds.addFile(rid, new MockMultipartFile("file", "b.pdf", "application/pdf", new byte[] {1}), salesActor),
            HttpStatus.BAD_REQUEST);

        long noticeId = issuedNotice(ticketId, "DN-U1");
        String key = "DEPOSIT_NOTICE:" + noticeId;
        assertThat(rounds.update(rid, new UpdateRequest(null, "x", List.of(key)), salesActor).untickedDocs())
            .containsExactly(key);
        assertThat(rounds.update(rid, new UpdateRequest(null, "y", null), salesActor).untickedDocs())
            .as("null keeps").containsExactly(key);

        // A well-formed key that is not (or no longer) a candidate is dropped, not refused; malformed, comma, too many are 400.
        long strangerNotice = issuedNotice(deal(salesActor), "DN-STRANGER");
        assertThat(rounds.update(rid, new UpdateRequest(null, "s", List.of(key, "DEPOSIT_NOTICE:" + strangerNotice)), salesActor)
            .untickedDocs()).containsExactly(key);
        for (List<String> bad : List.of(List.of("junk"),
            List.of(key + ",QUOTATION:1"), List.of(key, key, key, key, key, key))) {
            refusal(() -> rounds.update(rid, new UpdateRequest(null, "z", bad), salesActor), HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void send_refusesANoticeOfAnotherQuotation_andABlankOrPoReferenceWhenTheOrderHasTwoQuotations() {
        long ticketId = confirmedDeal();
        long poNotice = issuedNotice(ticketId, "DN-PO"); // reference 'x': not a quotation number
        when(depositNotices.getPdf(anyLong(), any())).thenReturn(new byte[] {1});
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        assertThat(rounds.send(rid, List.of(new SystemDocRef("DEPOSIT_NOTICE", poNotice)), salesActor).files())
            .as("one quotation: a PO reference may be attached").hasSize(1);

        long ticket2 = confirmedDeal();
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, quotation_version)
            VALUES (:t, 'QT-SECOND-1', :u, 'DEAL_DIRECT', 'APPROVED', 1)
            """, Map.of("t", ticket2, "u", salesActor.id())); // a second approved quotation on the same order
        var two = rounds.list(ticket2, salesActor).approvedQuotations();
        assertThat(two).hasSize(2);
        long n2 = issuedNotice(ticket2, "DN-Q2A");
        jdbc.update("UPDATE sales.deposit_notice SET reference = :n WHERE deposit_notice_id = :id", Map.of("n", two.get(0).number(), "id", n2));
        long po2 = issuedNotice(ticket2, "DN-PO2");
        long onSecond = rounds.createDraft(ticket2, two.get(1).id(), salesActor).id();
        refusal(() -> rounds.send(onSecond, List.of(new SystemDocRef("DEPOSIT_NOTICE", n2)), salesActor), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.send(onSecond, List.of(new SystemDocRef("DEPOSIT_NOTICE", po2)), salesActor), HttpStatus.BAD_REQUEST);
        assertThat(rounds.send(onSecond, List.of(new SystemDocRef("QUOTATION", two.get(1).id())), salesActor).files()).hasSize(1);
    }

    @Test
    void theRoundNamesTheBuyerOfItsQuotation_notTheDealsHeadlineCustomer() {
        long ticketId = confirmedDeal();
        rounds.createDraft(ticketId, null, salesActor);
        String headline = rounds.list(ticketId, salesActor).rounds().get(0).customerName();
        assertThat(headline).isNotBlank();

        var buyer = new th.co.glr.hr.customer.CustomerRepository(jdbc).create("บริษัท ผู้ซื้อจริง จำกัด", "0100000000999", "999 ถนนทดสอบ", "สาขา 2", "02-000-0999");
        long partyId = jdbc.queryForObject("INSERT INTO sales.deal_party (ticket_id, customer_id, role, status)"
            + " VALUES (:t, :c, 'BUYER', 'AWARDED') RETURNING party_id", Map.of("t", ticketId, "c", buyer.id()), Long.class);
        jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :q", Map.of("p", partyId,
            "q", rounds.list(ticketId, salesActor).approvedQuotations().get(0).id()));

        RoundDto round = rounds.list(ticketId, salesActor).rounds().get(0);
        assertThat(round.customerFromDeal()).isFalse();
        assertThat(round.customerName()).isEqualTo("บริษัท ผู้ซื้อจริง จำกัด").isNotEqualTo(headline);
        assertThat(round.taxId()).isEqualTo("0100000000999");
        assertThat(round.branch()).isEqualTo("สาขา 2");
    }

    private long issuedNotice(long ticketId, String number) {
        return jdbc.queryForObject("""
            INSERT INTO sales.deposit_notice (ticket_id, doc_type, version, doc_number, issue_date, status, customer_name,
                customer_address, project_name, reference, currency, deposit_percent, subtotal, deposit_amount,
                vat_percent, vat_amount, total_payable)
            VALUES (:t, 'DEPOSIT_NOTICE', (SELECT COALESCE(MAX(version), 0) + 1 FROM sales.deposit_notice WHERE ticket_id = :t), :n, CURRENT_DATE, 'ISSUED', 'x', 'x', 'x', 'x', 'THB', 0.3, 100, 30, 0.07, 2, 32)
            RETURNING deposit_notice_id
            """, Map.of("t", ticketId, "n", number), Long.class);
    }

    private UserPrincipal issuer(String email) {
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long id = createEmployee(employees, "ผู้ออกใบกำกับ ทดสอบ", email, "QC", "คิวซี", null);
        jdbc.update("UPDATE hr.employee SET can_issue_tax_invoice = TRUE WHERE employee_id = :id", Map.of("id", id));
        return actor(id, "employee");
    }

    private String paymentStatus(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketId), String.class);
    }

    /** An OPEN expected payment, due the Monday {@code weeksAheadDays} days after this week's. */
    private long expectedRow(long ticketId, String kind, String amount, int daysAhead) {
        return jdbc.queryForObject("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, created_by)
            VALUES (:t, :k, :a, date_trunc('week', now() AT TIME ZONE 'Asia/Bangkok')::date + :d, :by) RETURNING expected_payment_id
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "d", daysAhead, "by", salesActor.id()), Long.class);
    }

    private Map<String, Object> expected(long id) {
        return jdbc.queryForMap("SELECT status, received_amount, receipt_id FROM sales.expected_payment WHERE expected_payment_id = :id",
            Map.of("id", id));
    }

    private long procurementStart(BigDecimal deposit) {
        Map<String, Object> base = jdbc.queryForMap("SELECT customer_id, project_id FROM sales.ticket WHERE ticket_id = :t",
            Map.of("t", deal(salesActor)));
        return ticketService.create(new th.co.glr.hr.ticket.CreateTicketRequest("งานเก่า", "NORMAL", null,
            ((Number) base.get("customer_id")).longValue(), ((Number) base.get("project_id")).longValue(), null, null, "OWNER_DIRECT", List.of(), null, null, null, null, null, "PROCUREMENT", deposit, TODAY),
            salesActor).summary().id();
    }

    private String one(String sql, long ticketId) {
        return jdbc.queryForObject(sql, Map.of("t", ticketId), String.class);
    }

    @Test
    void preportal_firstSendOnAnOldDeal_confirmsFromTheQuotation_recordsTheDeposit_andBellsFourAudiences() {
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long accountId = createEmployee(employees, "บัญชี ทดสอบก่อนใช้ระบบ", "acct-pp@glr.co.th", "AC", "ฝ่ายบัญชี", null);
        UserPrincipal issuerUser = issuer("qc-pp@glr.co.th");
        long ticketId = deal(salesActor);
        approved(ticketId, draft(), salesActor);
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        var order = rounds.list(ticketId, salesActor).order();
        assertThat(order.unconfirmed()).as("C1: the sheet knows the lines come at send").isTrue();
        assertThat(order.prePortal()).isFalse();

        // The deposit is required (0 allowed) and cannot exceed the quotation total: nothing is written on a refusal.
        refusal(() -> rounds.send(rid, null, null, null, salesActor), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.send(rid, null, new BigDecimal("-1"), TODAY, salesActor), HttpStatus.BAD_REQUEST);
        // The date is required with an amount above 0, and is not in the future.
        refusal(() -> rounds.send(rid, null, new BigDecimal("1000.00"), null, salesActor), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.send(rid, null, new BigDecimal("1000.00"), TODAY.plusDays(1), salesActor), HttpStatus.BAD_REQUEST);
        refusal(() -> rounds.send(rid, null, new BigDecimal("99999999"), TODAY, salesActor), HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT pre_portal FROM sales.ticket WHERE ticket_id = :t", Map.of("t", ticketId), Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE ticket_id = :t", Map.of("t", ticketId), Long.class)).isZero();

        RoundDto sent = rounds.send(rid, null, new BigDecimal("1000.00"), TODAY.minusDays(3), salesActor);
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.lines()).isNotEmpty();
        assertThat(rounds.list(ticketId, salesActor).order().prePortal()).isTrue();
        assertThat(one("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :t", ticketId)).isEqualTo("DELIVERY_SCHEDULING");
        assertThat(eventKinds(ticketId)).contains("DEAL_PROMOTED_FROM_QUOTATION");
        // ONE DEPOSIT receipt, source PRE_PORTAL, by the rep; the policy is NOT_REQUIRED so no gate waits for a deposit.
        assertThat(jdbc.queryForMap("SELECT kind, amount, source, recorded_by FROM sales.payment_receipt WHERE ticket_id = :t",
            Map.of("t", ticketId))).containsEntry("kind", "DEPOSIT").containsEntry("source", "PRE_PORTAL")
            .containsEntry("recorded_by", salesActor.id()).extracting(m -> m.get("amount")).isEqualTo(new BigDecimal("1000.00"));
        assertThat(jdbc.queryForObject("SELECT (received_at AT TIME ZONE 'Asia/Bangkok')::date FROM sales.payment_receipt WHERE ticket_id = :t",
            Map.of("t", ticketId), java.time.LocalDate.class)).as("received on the date the rep gave").isEqualTo(TODAY.minusDays(3));
        assertThat(one("SELECT deposit_policy FROM sales.ticket WHERE ticket_id = :t", ticketId)).isEqualTo("NOT_REQUIRED");
        // C2: account, sales manager, the warehouse (no warehouse manager exists, so every warehouse person) and the issuer.
        assertThat(notices(accountId, "DEAL_PRE_PORTAL")).hasSize(1);
        assertThat(notices(salesManagerActor.id(), "DEAL_PRE_PORTAL")).hasSize(1);
        assertThat(notices(warehouseEmployeeId, "DEAL_PRE_PORTAL")).hasSize(1);
        assertThat(notices(issuerUser.id(), "DEAL_PRE_PORTAL")).hasSize(1);
        assertThat(notices(salesActor.id(), "DEAL_PRE_PORTAL")).isEmpty();
    }

    @Test
    void preportal_bellGoesToTheWarehouseManagerOnly_notTheAssistantManagerNorStaff() {
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        long manager = createEmployee(employees, "หัวหน้าคลัง ทดสอบ", "wh-mgr-dr@glr.co.th", "WH", "คลังสินค้า", "ผู้จัดการ");
        long assistant = createEmployee(employees, "ผู้ช่วยคลัง ทดสอบ", "wh-asst-dr@glr.co.th", "WH", "คลังสินค้า", "ผู้ช่วยผู้จัดการ");
        long ticketId = deal(salesActor);
        approved(ticketId, draft(), salesActor);
        rounds.send(rounds.createDraft(ticketId, null, salesActor).id(), null, BigDecimal.ZERO, null, salesActor);
        assertThat(notices(manager, "DEAL_PRE_PORTAL")).hasSize(1);
        assertThat(notices(assistant, "DEAL_PRE_PORTAL")).isEmpty();
        assertThat(notices(warehouseEmployeeId, "DEAL_PRE_PORTAL")).isEmpty();
    }

    @Test
    void preportal_anOldDealWithPreliminaryLines_stillAsksTheDepositAndSends() {
        long ticketId = deal(salesActor);
        jdbc.update("INSERT INTO sales.ticket_item (ticket_id, brand, qty, qty_from_stock, sort_order) VALUES (:t, 'SCG', 5, 0, 0)",
            Map.of("t", ticketId));
        approved(ticketId, draft(), salesActor);
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        assertThat(rounds.list(ticketId, salesActor).order().unconfirmed()).as("N-I1: the sheet asks for the deposit send requires").isTrue();
        assertThat(rounds.send(rid, null, BigDecimal.ZERO, null, salesActor).status()).isEqualTo("SENT");
        assertThat(rounds.list(ticketId, salesActor).order().prePortal()).isTrue();
    }

    @Test
    void preportal_anOldDealWhoseTicketIsPastDraft_sendsItsFirstRequest_keepingItsStatus() {
        long ticketId = deal(salesActor);
        approved(ticketId, draft(), salesActor);
        jdbc.update("UPDATE sales.ticket SET status = 'submitted' WHERE ticket_id = :t", Map.of("t", ticketId));
        long rid = rounds.createDraft(ticketId, null, salesActor).id();
        assertThat(rounds.send(rid, null, BigDecimal.ZERO, null, salesActor).status()).isEqualTo("SENT");
        assertThat(one("SELECT status FROM sales.ticket WHERE ticket_id = :t", ticketId)).isEqualTo("submitted");
        assertThat(rounds.list(ticketId, salesActor).order().prePortal()).isTrue();
    }

    @Test
    void preportal_startAtProcurement_needsTheDeposit_andTheImplicitConfirmDoesNotRefuseTheCeo() {
        refusal(() -> procurementStart(null), HttpStatus.BAD_REQUEST);
        long made = procurementStart(new BigDecimal("0"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE ticket_id = :t", Map.of("t", made), Long.class))
            .as("0 writes nothing").isZero();
        long withDeposit = procurementStart(new BigDecimal("500"));
        assertThat(jdbc.queryForMap("SELECT pre_portal, sales_stage FROM sales.ticket WHERE ticket_id = :t", Map.of("t", withDeposit)))
            .containsEntry("pre_portal", true).containsEntry("sales_stage", "PROCUREMENT");
        assertThat(one("SELECT source FROM sales.payment_receipt WHERE ticket_id = :t", withDeposit)).isEqualTo("PRE_PORTAL");

        // The first IR / deposit notice / remaining invoice / request all confirm through this bean, authorised on the outer
        // action: the CEO (not an edit-role holder of the quotation) is not refused, the stage stays, the policy follows.
        EmployeeRepository employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc));
        UserPrincipal ceo = actor(createEmployee(employees, "ผู้บริหาร ก่อนใช้ระบบ", "ceo-pp@glr.co.th", "MD", "ผู้บริหาร", null), "ceo");
        approved(withDeposit, draft(), salesActor);
        var bean = new th.co.glr.hr.deliveryrequest.PrePortal(new TicketRepository(jdbc), new DeliveryRequestRepository(jdbc),
            quotationService, ticketService);
        bean.ensureOrderConfirmed(withDeposit, null, ceo);
        assertThat(eventKinds(withDeposit)).contains("DEAL_PROMOTED_FROM_QUOTATION");
        assertThat(one("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :t", withDeposit)).isEqualTo("PROCUREMENT");
        assertThat(one("SELECT deposit_policy FROM sales.ticket WHERE ticket_id = :t", withDeposit)).isEqualTo("NOT_REQUIRED");
        // The IR gate now passes by the ordinary rule, not by a pre-portal skip.
        ticketService.requireImportRequestIssuable(new TicketRepository(jdbc).findSummaryById(withDeposit).orElseThrow(), true);

        // A deposit above the quotation total is refused at the confirm (account corrects with an ADJUSTMENT).
        long tooMuch = procurementStart(new BigDecimal("99999999"));
        approved(tooMuch, draft(), salesActor);
        refusal(() -> bean.ensureOrderConfirmed(tooMuch, null, ceo), HttpStatus.CONFLICT);
        // N-I2: the cap is on the net money received, so account's ADJUSTMENT is the way out (and nothing else was written).
        new TicketRepository(jdbc).insertPaymentReceipt(tooMuch, "ADJUSTMENT", new BigDecimal("99999998"), salesActor.id(), null,
            "ปรับยอด", null, null);
        bean.ensureOrderConfirmed(tooMuch, null, ceo);
        assertThat(eventKinds(tooMuch)).contains("DEAL_PROMOTED_FROM_QUOTATION");
        long normal = deal(salesActor);
        refusal(() -> ticketService.requireImportRequestIssuable(new TicketRepository(jdbc).findSummaryById(normal).orElseThrow(), true),
            HttpStatus.CONFLICT);
    }

    private long confirmedDeal() {
        long ticketId = deal(salesActor);
        DealQuotationDtos.DealQuotationDto quote = approved(ticketId, draft(), salesActor);
        quotationService.confirmOrderFromDirectQuotation(quote.id(), salesActor, Set.of());
        return ticketId;
    }

    private List<String> eventKinds(long ticketId) {
        return jdbc.query("SELECT kind FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", ticketId), (rs, n) -> rs.getString("kind"));
    }
}
