package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

class DepositNoticeServiceTest {

    private final DepositNoticeRepository docs = mock(DepositNoticeRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final DepositNoticeRenderer renderer = mock(DepositNoticeRenderer.class);
    private final RemainingInvoiceRenderer remainingRenderer = mock(RemainingInvoiceRenderer.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final CustomerQuotationRepository quotationRepo = mock(CustomerQuotationRepository.class);
    private final DepositNoticeService service = new DepositNoticeService(
        docs, ticketRepo, notifications, renderer, remainingRenderer, customerRepo, quotationRepo);

    private final UserPrincipal owner = new UserPrincipal(
        1L, "sales@glr.co.th", "Sales", "sales", 1L, true, LocalDate.of(2026, 1, 1), false, 1L, false);
    // sales_manager: read-only oversight of deposit notices — never owns a ticket
    // (no create access), so every owner-gated write is denied by ownership alone.
    private final UserPrincipal salesManagerActor = new UserPrincipal(
        8L, "sales_manager@glr.co.th", "Sales Manager", "sales_manager", 8L, true,
        LocalDate.of(2026, 1, 1), false, null, false);

    // ── issue(): the document IS the payment-track step ─────────────────────

    @Test
    void issue_advancesPaymentTrackAndKeepsTicketStatus() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.issue(99L, 1L, "Sales")).thenReturn(Optional.of("GLRD69001"));

        service.issue(99L, owner);

        // Payment track advances; the main status is untouched (no document_issued flip
        // — that side effect killed the dual-track UI and let unpaid tickets close).
        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_NOTICE_ISSUED");
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.DEPOSIT_NOTICE_ISSUED),
            eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), anyString());
        verify(ticketRepo, never()).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.DOCUMENT_ISSUED), anyString(), anyString(), anyString());
    }

    @Test
    void issue_requiresQuotationIssuedStatus() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.APPROVED, null);
        assertConflict(() -> service.issue(99L, owner));
    }

    @Test
    void issue_requiresCustomerConfirmedPayment() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, null);
        assertConflict(() -> service.issue(99L, owner));

        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED");
        assertConflict(() -> service.issue(99L, owner));
    }

    // ── sales_manager oversight (read only, zero write actions) ─────────────
    // Product decision (2026-07-16): sales_manager is a read+comment-only follow-up
    // role for the sales team on tickets; the same rule extends to deposit notices
    // (money-adjacent customer documents). Added to VIEWER_ROLES only.

    @Test
    void listByTicket_salesManagerCanViewAnyTicketsNotices() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(docs.findByTicket(10L)).thenReturn(List.of());

        service.listByTicket(10L, salesManagerActor); // must not throw

        verify(docs).findByTicket(10L);
    }

    @Test
    void getById_salesManagerCanViewAnyonesDocument() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");

        DepositNoticeDto doc = service.getById(99L, salesManagerActor); // must not throw
        assertThat(doc.id()).isEqualTo(99L);
    }

    // ── Phase B (role-scoped views): import has no business reading deposit ─
    // notices — a customer financial document, unlike a ticket's other fields
    // which import may still see. Mirrors salesViewScope.js hiding the whole
    // "depositNotice" section from import's TicketDetailPage.

    private final UserPrincipal importActor = new UserPrincipal(
        3L, "import@glr.co.th", "Import", "import", 3L, true, LocalDate.of(2026, 1, 1), false, null, false);

    @Test
    void listByTicket_importDenied() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.listByTicket(10L, importActor));
    }

    @Test
    void getById_importDenied() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.getById(99L, importActor));
    }

    @Test
    void downloadRemainingInvoice_importDenied() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.getRemainingInvoiceXlsx(10L, importActor));
    }

    @Test
    void createDraft_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) as the very first check.
        assertForbidden(() -> service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null),
            salesManagerActor));
    }

    @Test
    void update_rejectsSalesManagerRole() {
        // update() has NO role gate — only requireTicketOwner. sales_manager can
        // never own a ticket, so the ownership check alone denies it.
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.APPROVED, null);

        assertForbidden(() -> service.update(99L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null),
            salesManagerActor));
    }

    @Test
    void issue_rejectsSalesManagerRole() {
        stubDraft(99L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        assertForbidden(() -> service.issue(99L, salesManagerActor));
    }

    @Test
    void requestRevision_rejectsSalesManagerRole() {
        // Role-gated (SALES_ROLES) as the very first check.
        assertForbidden(() -> service.requestRevision(10L,
            new RevisionRequest(RevisionScope.QTY_OR_NOTE, "reason"), salesManagerActor));
    }

    // ── Header autofill + item fallback (branch fix: deposit-notice autofill) ─
    // See DepositNoticeService.createDraft/buildItemsFromRequest for what changed and why —
    // every deal created through the pricing-request chain reached these methods with
    // approved_price NULL on every ticket_item and no header autofill at all.

    @Test
    void createDraft_autofillsHeaderFromCustomerMasterAndTicketProjectName() {
        stubTicketWithCustomer(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", 42L, "Project Zeta");
        when(customerRepo.findById(42L)).thenReturn(Optional.of(
            new CustomerDto(42L, "ACME", "0100000000000", "123 Road", "Showroom", "02-000-0000")));
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of());
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(201L);
        stubDraft(201L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        ArgumentCaptor<DepositNoticeDraftRequest> captor = ArgumentCaptor.forClass(DepositNoticeDraftRequest.class);
        verify(docs).createDraft(eq(10L), captor.capture(), any());
        DepositNoticeDraftRequest effective = captor.getValue();
        assertThat(effective.customerTaxId()).isEqualTo("0100000000000");
        assertThat(effective.customerAddress()).isEqualTo("123 Road Showroom");
        assertThat(effective.projectName()).isEqualTo("Project Zeta");
    }

    @Test
    void createDraft_nullCustomerIdLeavesHeaderFieldsBlankWithoutThrowing() {
        // Base fixture's ticket carries customerId = null — must not NPE, and the customer
        // repository must never even be consulted.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of());
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(202L);
        stubDraft(202L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        ArgumentCaptor<DepositNoticeDraftRequest> captor = ArgumentCaptor.forClass(DepositNoticeDraftRequest.class);
        verify(docs).createDraft(eq(10L), captor.capture(), any());
        assertThat(captor.getValue().customerTaxId()).isNull();
        assertThat(captor.getValue().customerAddress()).isNull();
        verify(customerRepo, never()).findById(anyLong());
    }

    @Test
    void createDraft_fallsBackToAcceptedQuotationWhenNoTicketItemHasApprovedPrice() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED"); // items: List.of() — no approved_price
        CustomerQuotationItemDto item = quotationItem("กระเบื้อง A", new BigDecimal("10"), "PER_SQM",
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"));
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(quotation(1L, 10L, "ACCEPTED", 1, List.of(item))));
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(203L);
        stubDraft(203L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        List<DepositNoticeItemRequest> items = itemsCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).description()).isEqualTo("กระเบื้อง A");
        assertThat(items.get(0).unit()).isEqualTo("ตร.ม.");
        assertThat(items.get(0).unitPrice()).isEqualTo(new BigDecimal("100"));
    }

    @Test
    void createDraft_prefersAcceptedQuotationOverAnIssuedOne() {
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED");
        CustomerQuotationItemDto issuedItem = quotationItem("Issued Item", BigDecimal.ONE, "PER_PIECE",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        CustomerQuotationItemDto acceptedItem = quotationItem("Accepted Item", BigDecimal.ONE, "PER_PIECE",
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN);
        // findByTicket's own contract: ascending by quotation_id ALONE (see its own Javadoc —
        // quotation_revision_no is scoped to a single pricing request, not comparable across
        // the multiple pricing requests one ticket can have) — the accepted revision here has
        // the higher quotation_id, same as a real revise-after-accept flow.
        when(quotationRepo.findByTicket(10L)).thenReturn(List.of(
            quotation(1L, 10L, "ISSUED", 1, List.of(issuedItem)),
            quotation(2L, 10L, "ACCEPTED", 2, List.of(acceptedItem))));
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(204L);
        stubDraft(204L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(1);
        assertThat(itemsCaptor.getValue().get(0).description()).isEqualTo("Accepted Item");
    }

    @Test
    void createDraft_legacyApprovedPriceItemsWinOverQuotationChainWhenBothExist() {
        TicketItemDto approvedItem = new TicketItemDto(1L, 10L, "SCG", "Model", "White", "Matte", "60x60",
            "Factory", new BigDecimal("10"), null, null, null, null, null, new BigDecimal("100"), "THB",
            0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", List.of(approvedItem));
        // Even though an ACCEPTED quotation exists too, the legacy approved_price path must win
        // and the quotation chain must never even be queried (buildLegacyItems short-circuits).
        when(docs.createDraft(eq(10L), any(), any())).thenReturn(205L);
        stubDraft(205L, 10L);

        service.createDraft(10L,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DepositNoticeItemRequest>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(docs).createDraft(eq(10L), any(), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(1);
        assertThat(itemsCaptor.getValue().get(0).unitPrice()).isEqualTo(new BigDecimal("100"));
        verify(quotationRepo, never()).findByTicket(10L);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void stubDraft(long docId, long ticketId) {
        DepositNoticeDto draft = new DepositNoticeDto(
            docId, ticketId, "DEPOSIT_NOTICE", 1, null, null, "DRAFT",
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-1", "THB",
            new BigDecimal("0.50"), new BigDecimal("1000.00"), new BigDecimal("500.00"),
            new BigDecimal("0.07"), new BigDecimal("35.00"), new BigDecimal("535.00"),
            List.of(), false, false, "Sales", "Preparer", null, null, List.of());
        when(docs.findById(docId)).thenReturn(Optional.of(draft));
    }

    @org.junit.jupiter.api.Test
    void createDraft_rejectsPausedDeal() {
        // Phase 1 lifecycle gate: deposit-notice mutations advance the payment track,
        // so an ON_HOLD deal must not create/issue notices.
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", "ON_HOLD");
        assertThatThrownBy(() -> service.createDraft(10L,
                new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), owner))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @org.junit.jupiter.api.Test
    void issue_rejectsPausedDeal() {
        stubDraft(5L, 10L);
        stubTicket(10L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", "ON_HOLD");
        assertThatThrownBy(() -> service.issue(5L, owner))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    private void stubTicket(long ticketId, String status, String paymentStatus) {
        stubTicket(ticketId, status, paymentStatus, "ACTIVE");
    }

    private void stubTicket(long ticketId, String status, String paymentStatus, String lifecycle) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            lifecycle, "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, List.of(), List.of(), null, List.of())));
    }

    // customerId non-null + a projectName — the header-autofill source fields — with no items
    // (so the item-fallback path is exercised at the same time by every caller of this fixture).
    private void stubTicketWithCustomer(long ticketId, String status, String paymentStatus,
                                        Long customerId, String projectName) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", customerId, null, projectName, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, List.of(), List.of(), null, List.of())));
    }

    // Same ticket shape as stubTicket, but with explicit ticket_item rows — for the legacy
    // approved_price fallback path.
    private void stubTicketWithItems(long ticketId, String status, String paymentStatus,
                                     List<TicketItemDto> items) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test", status, "NORMAL",
            1L, "Sales", null, null, "ACME", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, 1, false, paymentStatus, null,
            "LEAD_APPROACH", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        when(ticketRepo.findById(ticketId))
            .thenReturn(Optional.of(new TicketDto(summary, items, List.of(), null, List.of())));
    }

    // Minimal CustomerQuotationDto fixture — only the fields buildItemsFromRequest's pickQuotation
    // and itemsFromQuotation actually read (ticketId, docStatus, quotationRevisionNo, items) carry
    // meaningful values; everything else is a harmless placeholder.
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
}
