package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

class DepositNoticeServiceTest {

    private final DepositNoticeRepository docs = mock(DepositNoticeRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final DepositNoticePdfRenderer renderer = mock(DepositNoticePdfRenderer.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final CustomerQuotationRepository quotationRepo = mock(CustomerQuotationRepository.class);
    private final DocumentBundle bundle = mock(DocumentBundle.class);
    private final DepositNoticeService service = new DepositNoticeService(
        docs, ticketRepo, notifications, renderer, customerRepo, quotationRepo, bundle, org.mockito.Mockito.mock(th.co.glr.hr.deposit.RemainingInvoiceRepository.class));
    {
        when(bundle.bundle(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(i -> new DocumentBundle.Result(i.getArgument(0), java.util.List.of()));
        // Default stub so every payment-track write in this file (advancePaymentStatus's compare-and-set) reads as
        // "succeeded" unless a specific test overrides it — Mockito's default answer for an unstubbed int-returning
        // method is 0, which the service would read as a lost race and turn into a 409.
        when(ticketRepo.advancePaymentStatus(anyLong(), anyString(), any(), anyString())).thenReturn(1);
    }

    private final UserPrincipal owner = new UserPrincipal(
        1L, "sales@glr.co.th", "Sales", "sales", 1L, true, LocalDate.of(2026, 1, 1), false, 1L, false);
    // sales_manager: read-only oversight of deposit notices. It can create (and so own) a deal
    // since deal entry was widened, so every deposit-notice write is role-gated to sales.
    private final UserPrincipal salesManagerActor = new UserPrincipal(
        8L, "sales_manager@glr.co.th", "Sales Manager", "sales_manager", 8L, true,
        LocalDate.of(2026, 1, 1), false, null, false);

    // ── issue(): the document IS the payment-track step ─────────────────────

    @Test
    void issue_requiresQuotationIssuedStatus() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.APPROVED, null);
        assertConflict(() -> service.issue(99L, owner));
    }

    @Test
    void issue_requiresCustomerConfirmedOrDepositNoticeIssuedPayment() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, null);
        assertConflict(() -> service.issue(99L, owner));
        verify(docs, never()).issue(anyLong(), anyLong(), anyString());

        // A payment status genuinely past the deposit-notice step (already paid) is also refused — only
        // CUSTOMER_CONFIRMED (first issue) or DEPOSIT_NOTICE_ISSUED (a revision —
        // PaymentTrack's one legal self-loop) are legal here.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID");
        assertConflict(() -> service.issue(99L, owner));
        verify(docs, never()).issue(anyLong(), anyLong(), anyString());
    }

    // ── sales_manager oversight (read only, zero write actions) ─────────────
    // sales_manager is a read+comment-only follow-up role for the sales team on tickets; the
    // same rule extends to deposit notices (money-adjacent customer documents). It is in
    // VIEWER_ROLES only.

    @Test
    void listByTicket_salesManagerCanViewAnyTicketsNotices() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.findByTicket(10L)).thenReturn(List.of());

        service.listByTicket(10L, salesManagerActor); // must not throw

        verify(docs).findByTicket(10L);
    }

    // Phase B (role-scoped views): import has no business reading deposit ─ notices — a customer financial
    // document, unlike a ticket's other fields which import may still see. Mirrors salesViewScope.js hiding the
    // whole "depositNotice" section from import's TicketDetailPage.

    private final UserPrincipal importActor = new UserPrincipal(
        3L, "import@glr.co.th", "Import", "import", 3L, true, LocalDate.of(2026, 1, 1), false, null, false);

    @Test
    void listByTicket_importDenied() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.listByTicket(10L, importActor));
    }

    // Remaining invoice item sourcing / capacity / defaults ────────────────── The real production shape: every
    // ticket_item.approved_price is NULL for a deal priced through PricingRequest -> PricingDecision ->
    // CustomerQuotation (stubTicket's items are always List.of.

    @Test
    void remainingInvoiceXlsx_sourcesItemsFromAcceptedQuotationUsingStoredLineSubtotal() throws Exception {
        // Bypass policy (WAIVED): an ACCEPTED quotation qualifies with NO deposit notice needed at
        // all (the exception to quotation selection rule A) — deliberately used here so THIS test can isolate
        // "stored lineSubtotal used verbatim" from the separate deposit-matching rules (B/C).
        stubTicketWithDepositPolicy(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", DepositPolicy.WAIVED);
        // lineSubtotal deliberately NOT equal to finalUnitPrice*qty (299.99 vs 300.00) so this test actually proves
        // the stored subtotal is used verbatim, not recomputed.
        CustomerQuotationItemDto item = new CustomerQuotationItemDto(
            1L, 1, 1L, 1L, "กระเบื้อง A", null, "PER_SQM", new BigDecimal("3"),
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
            null, new BigDecimal("299.99"), BigDecimal.ZERO, new BigDecimal("299.99"));
        when(quotationRepo.findByTicket(10L)).thenReturn(
            List.of(quotation(1L, 10L, "ACCEPTED", 1, List.of(item))));

        // Asserted via the SAME package-private resolveRemainingInvoiceSnapshot
        // RemainingInvoiceService itself freezes off of, so this pins resolveRemainingInvoice's
        // own item-sourcing logic.
        DepositNoticeService.RemainingInvoiceSnapshot snap =
            service.resolveRemainingInvoiceSnapshot(10L, null, owner);
        DepositNoticeService.ResolvedRemainingInvoice resolved = snap.resolved();
        assertThat(resolved.items()).hasSize(1);
        assertThat(resolved.items().get(0).amount()).isEqualByComparingTo("299.99");
        assertThat(resolved.items().get(0).netUnitPrice()).isEqualByComparingTo("100");
        // Bypass policy -> no deduction row, depositAmount is zero.
        assertThat(resolved.depositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void remainingInvoiceOptions_depositGreaterThanItemsIsBlocked() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ACCEPTED", 1, List.of(quotationItem("A", BigDecimal.ONE, "PER_PIECE",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)))));
        // Rule D11: itemsTotal must come from the NOTICE's own items (10 here), not the
        // quotation's — deposit (15) exceeds it, so rule C's negative-net refusal fires.
        DepositNoticeItemDto noticeItem = new DepositNoticeItemDto(
            1L, 1, "From notice", BigDecimal.ONE, "แผ่น", BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN);
        when(docs.findByTicket(10L)).thenReturn(List.of(
            issuedNotice(5L, 10L, 1, "GLRD69001", "QT-2026-1", BigDecimal.TEN, new BigDecimal("15"),
                List.of(noticeItem))));

        RemainingInvoiceOptionsDto options = service.getRemainingInvoiceOptions(10L, owner);

        assertThat(options.blockingReason()).contains("ติดลบ");
    }

    // Legacy-fallback overload: reference "REF-1" never matches any of this file's "QT-2026-N" quotation numbers,
    // and subtotal is a placeholder — correct ONLY for a fixture where no ACCEPTED quotation is in play at all.
    private DepositNoticeDto issuedNotice(long docId, long ticketId, int version, String docNumber,
                                          BigDecimal depositAmount, List<DepositNoticeItemDto> items) {
        return issuedNotice(docId, ticketId, version, docNumber, "REF-1",
            depositAmount.multiply(new BigDecimal("2")), depositAmount, items);
    }

    // reference/subtotal explicit — reference must equal the qualifying quotation's own number (rule A's match
    // key) and subtotal must equal that quotation's own item total (rule B) for the notice to actually qualify a
    // quotation as a remaining-invoice source.
    private DepositNoticeDto issuedNotice(long docId, long ticketId, int version, String docNumber,
                                          String reference, BigDecimal subtotal, BigDecimal depositAmount,
                                          List<DepositNoticeItemDto> items) {
        return new DepositNoticeDto(
            docId, ticketId, "DEPOSIT_NOTICE", version, docNumber, LocalDate.of(2026, 8, 1), "ISSUED",
            "ACME", "0100000000000", "Bangkok", "Showroom", reference, "THB",
            new BigDecimal("0.50"), subtotal, depositAmount,
            new BigDecimal("0.07"), BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(), true, true, "Sales", "Preparer", null, null, null, items, false);
    }

    // ── Deposit percent = what the source quotation's PDF printed ────
    // The governing quotation's percent (DepositNoticeRepository#findGoverningQuotation: legacy
    // 50, new-engine its own, blank 30); a caller-supplied percent is never read on create, and
    // 0.50 applies only when the ticket has no live quotation. update() then lets sales change it
    // on a DRAFT; an ISSUED notice refuses any edit (requireDraft, 409).

    @Test
    void createDraft_sourcesDepositPercentFromQuotation_ignoringCallerSuppliedValue() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.findGoverningQuotation(10L)).thenReturn(Optional.of(
            new DepositNoticeRepository.GoverningQuotation(5L, false, "QT-5", new BigDecimal("0.30"))));
        when(docs.createDraft(eq(10L), any(), any(), any())).thenReturn(301L);
        stubDraft(301L, 10L);

        // Caller (or a stale/forged client) asks for 99% — must be ignored; the quotation's own 30% wins.
        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, new BigDecimal("0.99"), null, null),
            owner);

        ArgumentCaptor<DepositNoticeDraftRequest> captor = ArgumentCaptor.forClass(DepositNoticeDraftRequest.class);
        verify(docs).createDraft(eq(10L), captor.capture(), any(), any());
        assertThat(captor.getValue().depositPercent()).isEqualByComparingTo("0.30");
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void stubDraft(long docId, long ticketId) {
        stubDraft(docId, ticketId, new BigDecimal("0.50"));
    }

    private void stubDraft(long docId, long ticketId, BigDecimal depositPercent) {
        DepositNoticeDto draft = new DepositNoticeDto(
            docId, ticketId, "DEPOSIT_NOTICE", 1, null, null, "DRAFT",
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-1", "THB",
            depositPercent, new BigDecimal("1000.00"), new BigDecimal("500.00"),
            new BigDecimal("0.07"), new BigDecimal("35.00"), new BigDecimal("535.00"),
            List.of(), false, false, "Sales", "Preparer", null, null, null, List.of(), false);
        when(docs.findById(docId)).thenReturn(Optional.of(draft));
    }

    private void stubTicket(long ticketId, String status, String paymentStatus) {
        stubTicket(ticketId, status, paymentStatus, "ACTIVE");
    }

    private void stubTicket(long ticketId, String status, String paymentStatus, String lifecycle) {
        stubTicketWithDepositPolicy(ticketId, status, paymentStatus, DepositPolicy.REQUIRED, lifecycle);
    }

    // Remaining-invoice quotation-selection fixture (rule A): a deposit policy other than
    // REQUIRED changes whether an ACCEPTED quotation qualifies WITHOUT a matching issued deposit
    // notice — see DepositPolicy.bypassesDepositNotice.
    private void stubTicketWithDepositPolicy(long ticketId, String status, String paymentStatus, String depositPolicy) {
        stubTicketWithDepositPolicy(ticketId, status, paymentStatus, depositPolicy, "ACTIVE");
    }

    private void stubTicketWithDepositPolicy(long ticketId, String status, String paymentStatus,
                                             String depositPolicy, String lifecycle) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            lifecycle, "UNKNOWN", depositPolicy, null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, List.of(), List.of(), null, List.of())));
    }

    // Minimal CustomerQuotationDto fixture — only the fields the service actually reads (ticketId,
    // docStatus, quotationRevisionNo, items) carry meaningful values; everything else is a
    // harmless placeholder.
    private CustomerQuotationDto quotation(long id, long ticketId, String docStatus, int revisionNo,
                                           List<CustomerQuotationItemDto> items) {
        return new CustomerQuotationDto(
            id, "QT-2026-" + id, ticketId, 900L, 901L, "CUSTOMER", null, docStatus, 1, revisionNo,
            null, 1L, "Sales", Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "THB",
            null, null, null, null, null, null, null, null, Instant.now(), null, null, items);
    }

    private CustomerQuotationItemDto quotationItem(String description, BigDecimal qty, String unitBasis,
                                                    BigDecimal unitPrice, BigDecimal discount, BigDecimal net) {
        BigDecimal lineSubtotal = net.multiply(qty);
        return new CustomerQuotationItemDto(
            1L, 1, 1L, 1L, description, null, unitBasis, qty, unitPrice, discount, net,
            null, lineSubtotal, BigDecimal.ZERO, lineSubtotal);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // Each tax invoice's VAT is round2(its own net x 7%). Deal 11
    // (PR-2026-0006): net 119,745.00 - 35,923.50 = 83,821.50 -> VAT 5,867.505 -> 5,867.51 -> grand 89,689.01.
    // The 1-satang gap against the deal payable (128,127.15 - 38,438.15 = 89,689.00) is normal.
    @Test
    void remainingInvoiceMoney_deal11_isPerDocumentRounding() {
        DepositNoticeService.RemainingInvoiceMoney m = service.computeRemainingInvoiceMoney(
            new BigDecimal("119745.00"), new BigDecimal("35923.50"));

        assertThat(m.netAmount()).isEqualByComparingTo("83821.50");
        assertThat(m.vatAmount()).isEqualByComparingTo("5867.51");
        assertThat(m.grandTotal()).isEqualByComparingTo("89689.01");
    }

    /** No deposit (bypass): unchanged -- net = items, VAT = round2(items x 7%). */
    @Test
    void remainingInvoiceMoney_withNoDeposit_isTheWholeDealInclVat() {
        DepositNoticeService.RemainingInvoiceMoney m = service.computeRemainingInvoiceMoney(
            new BigDecimal("33333.33"), BigDecimal.ZERO);

        assertThat(m.netAmount()).isEqualByComparingTo("33333.33");
        assertThat(m.vatAmount()).isEqualByComparingTo("2333.33");
        assertThat(m.grandTotal()).isEqualByComparingTo("35666.66");
    }
}
