package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

class DepositNoticeServiceTest {

    private final DepositNoticeRepository docs = mock(DepositNoticeRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final DepositNoticeRenderer renderer = mock(DepositNoticeRenderer.class);
    private final RemainingInvoiceRenderer remainingRenderer = mock(RemainingInvoiceRenderer.class);
    private final DepositNoticeService service = new DepositNoticeService(
        docs, ticketRepo, notifications, renderer, remainingRenderer);

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
        when(docs.issue(99L, 1L, "Sales")).thenReturn("GLRD69001");

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

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
