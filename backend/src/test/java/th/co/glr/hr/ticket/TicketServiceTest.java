package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.PriceCalcService;

class TicketServiceTest {

    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifRepo = mock(NotificationRepository.class);
    private final PriceCalcService priceCalcService = mock(PriceCalcService.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final QuotationRenderer quotationRenderer = new QuotationRenderer();
    private final TicketService service = new TicketService(
        ticketRepo, notifRepo, priceCalcService, new ObjectMapper(), customerRepo, quotationRenderer);

    private final UserPrincipal salesActor   = actor(1L, "sales");
    private final UserPrincipal otherSales   = actor(2L, "sales");
    private final UserPrincipal importActor  = actor(3L, "import");
    private final UserPrincipal ceoActor     = actor(4L, "ceo");
    private final UserPrincipal accountActor = actor(5L, "account");
    private final UserPrincipal hrActor      = actor(6L, "hr");
    private final UserPrincipal employeeActor = actor(7L, "employee");

    // ── list ──────────────────────────────────────────────────────────────

    @Test
    void list_salesActorFiltersToOwnTickets() {
        service.list(null, salesActor);
        verify(ticketRepo).findSummaries(null, 1L);
    }

    @Test
    void list_nonSalesActorSeesAllTickets() {
        service.list(null, importActor);
        verify(ticketRepo).findSummaries(null, null);
    }

    // ── get ───────────────────────────────────────────────────────────────

    @Test
    void get_salesCanViewOwnTicket() {
        TicketDto ticket = stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertThat(service.get(10L, salesActor)).isEqualTo(ticket);
    }

    @Test
    void get_salesCannotViewOthersTicket() {
        stubTicket(10L, 99L, TicketStatus.DRAFT);
        assertForbidden(() -> service.get(10L, salesActor));
    }

    @Test
    void get_importCanViewAnyTicket() {
        TicketDto ticket = stubTicket(10L, 99L, TicketStatus.SUBMITTED);
        assertThat(service.get(10L, importActor)).isEqualTo(ticket);
    }

    // ── read authz (viewer roles) ─────────────────────────────────────────

    @Test
    void list_rejectsHrAndEmployeeRoles() {
        // Tickets carry customer pricing — only sales/import/ceo/account may read.
        assertForbidden(() -> service.list(null, hrActor));
        assertForbidden(() -> service.list(null, employeeActor));
    }

    @Test
    void get_rejectsHrAndEmployeeRoles() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.get(10L, hrActor));
        assertForbidden(() -> service.get(10L, employeeActor));
    }

    @Test
    void get_accountRoleCanViewAnyTicket() {
        TicketDto ticket = stubTicket(10L, 1L, TicketStatus.QUOTATION_ISSUED);
        assertThat(service.get(10L, accountActor)).isEqualTo(ticket);
    }

    @Test
    void comment_rejectsRolesAndNonOwnersWithoutReadAccess() {
        // comment() returns the full TicketDto — it must not be a side door around
        // get()'s scoping (previously any authenticated user could pull any ticket).
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), hrActor));
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), employeeActor));
        assertForbidden(() -> service.comment(10L, new CommentRequest("hi"), otherSales));
    }

    @Test
    void quotationFile_rejectsRolesWithoutReadAccess() {
        stubTicket(10L, 1L, TicketStatus.QUOTATION_ISSUED);
        assertForbidden(() -> service.getQuotationXlsx(10L, 1L, hrActor));
        assertForbidden(() -> service.getQuotationPdf(10L, 1L, employeeActor));
    }

    // ── factory email gate ────────────────────────────────────────────────

    @Test
    void factoryEmail_allowsImportOnExistingTicket() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        service.assertFactoryEmailAllowed(10L, importActor); // must not throw
    }

    @Test
    void factoryEmail_rejectsNonImportRoles() {
        // Previously session-only — an authenticated open mail relay.
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, salesActor));
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, hrActor));
        assertForbidden(() -> service.assertFactoryEmailAllowed(10L, employeeActor));
    }

    @Test
    void factoryEmail_rejectsNonExistentTicket() {
        assertThatThrownBy(() -> service.assertFactoryEmailAllowed(99L, importActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── submit ────────────────────────────────────────────────────────────

    @Test
    void submit_draftByOwner_transitionsToSubmitted() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.submit(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.SUBMITTED), eq(TicketStatus.DRAFT), eq(TicketStatus.SUBMITTED), isNull());
        verify(notifRepo).notifyByRole(eq("import"), eq(10L), anyString(), anyString());
        verify(notifRepo).notifyByRole(eq("ceo"),    eq(10L), anyString(), anyString());
    }

    @Test
    void submit_rejectsImportRole() {
        assertForbidden(() -> service.submit(10L, importActor));
    }

    @Test
    void submit_rejectsNonOwner() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertForbidden(() -> service.submit(10L, otherSales));
    }

    @Test
    void submit_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertConflict(() -> service.submit(10L, salesActor));
    }

    // ── pickup ────────────────────────────────────────────────────────────

    @Test
    void pickup_submittedByImport_transitionsToInReview() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);

        service.pickup(10L, importActor);

        verify(ticketRepo).addEvent(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.PICKED_UP), eq(TicketStatus.SUBMITTED), eq(TicketStatus.IN_REVIEW), isNull());
    }

    @Test
    void pickup_rejectsSalesRole() {
        assertForbidden(() -> service.pickup(10L, salesActor));
    }

    @Test
    void pickup_rejectsCeoRole() {
        assertForbidden(() -> service.pickup(10L, ceoActor));
    }

    @Test
    void pickup_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        assertConflict(() -> service.pickup(10L, importActor));
    }

    // ── proposePrice ──────────────────────────────────────────────────────

    @Test
    void proposePrice_inReviewByImport_transitionsToPriceProposed() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        ProposePriceRequest req = new ProposePriceRequest(List.of(), "ราคาจาก supplier");

        service.proposePrice(10L, req, importActor);

        verify(ticketRepo).addEventWithSnapshot(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.PRICE_PROPOSED), eq(TicketStatus.IN_REVIEW), eq(TicketStatus.PRICE_PROPOSED),
            eq("ราคาจาก supplier"), anyString());
        verify(notifRepo).notifyByRole(eq("ceo"), eq(10L), anyString(), anyString());
    }

    @Test
    void proposePrice_rejectsCeoRole() {
        assertForbidden(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), ceoActor));
    }

    @Test
    void proposePrice_rejectsSalesRole() {
        assertForbidden(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), salesActor));
    }

    @Test
    void proposePrice_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertConflict(() -> service.proposePrice(10L, new ProposePriceRequest(List.of(), null), importActor));
    }

    // ── approve ───────────────────────────────────────────────────────────

    @Test
    void approve_priceProposedByCeo_transitionsToApproved() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);

        service.approve(10L, ceoActor);

        verify(ticketRepo).approveItemPrices(10L);
        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.APPROVED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.APPROVED), isNull());
        verify(notifRepo).notifyEmployee(eq(1L), eq(10L), anyString(), anyString());
    }

    @Test
    void approve_rejectsSalesRole() {
        assertForbidden(() -> service.approve(10L, salesActor));
    }

    @Test
    void approve_rejectsImportRole() {
        assertForbidden(() -> service.approve(10L, importActor));
    }

    @Test
    void approve_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        assertConflict(() -> service.approve(10L, ceoActor));
    }

    // ── reject (reject loop) ──────────────────────────────────────────────

    @Test
    void reject_priceProposedByCeo_returnsToInReview() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);

        service.reject(10L, new RejectRequest("ราคาสูงเกิน"), ceoActor);

        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.REJECTED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.IN_REVIEW), eq("ราคาสูงเกิน"));
        verify(notifRepo).notifyByRole(eq("import"), eq(10L), anyString(), anyString());
    }

    @Test
    void reject_rejectsSalesRole() {
        assertForbidden(() -> service.reject(10L, new RejectRequest("reason"), salesActor));
    }

    @Test
    void reject_rejectsImportRole() {
        assertForbidden(() -> service.reject(10L, new RejectRequest("reason"), importActor));
    }

    @Test
    void reject_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.APPROVED);
        assertConflict(() -> service.reject(10L, new RejectRequest("reason"), ceoActor));
    }

    // ── generateQuotation ─────────────────────────────────────────────────

    @Test
    void generateQuotation_approvedByOwner_createsQuotationAndTransitions() {
        TicketItemDto item = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(item));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0001");

        service.generateQuotation(10L, salesActor);

        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0001"), eq(1L), eq(new BigDecimal("200.00")));
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.QUOTATION_ISSUED), eq(TicketStatus.APPROVED), eq(TicketStatus.QUOTATION_ISSUED), isNull());
    }

    @Test
    void generateQuotation_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, TicketStatus.APPROVED);
        assertForbidden(() -> service.generateQuotation(10L, otherSales));
    }

    @Test
    void generateQuotation_rejectsCeoRole() {
        assertForbidden(() -> service.generateQuotation(10L, ceoActor));
    }

    @Test
    void generateQuotation_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        assertConflict(() -> service.generateQuotation(10L, salesActor));
    }

    @Test
    void generateQuotation_allowsReissueFromQuotationIssued() {
        stubTicketWithItems(10L, 1L, TicketStatus.QUOTATION_ISSUED, List.of());
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0003");

        service.generateQuotation(10L, salesActor);

        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0003"), eq(1L), any(BigDecimal.class));
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.QUOTATION_ISSUED), isNull());
    }

    @Test
    void generateQuotation_sumsMultipleItemsIncludingFractionalQuantities() {
        TicketItemDto item1 = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto item2 = new TicketItemDto(2L, 10L, "PC002", "Product B", null, null,
            "pcs", null, new BigDecimal("3"), null, null, null, null, null,
            new BigDecimal("50.00"), "THB", 1, null, null, null, "PIECE", null, null);
        TicketItemDto item3 = new TicketItemDto(3L, 10L, "PC003", "Product C", null, null,
            "sqm", null, new BigDecimal("1.5"), null, null, null, null, null,
            new BigDecimal("10.00"), "THB", 2, null, null, null, "SQM", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(item1, item2, item3));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0004");

        service.generateQuotation(10L, salesActor);

        // (2 x 100.00) + (3 x 50.00) + (1.5 x 10.00) = 200.00 + 150.00 + 15.00 = 365.00
        // (BigDecimal.equals() is scale-sensitive, so use isEqualByComparingTo rather than eq().)
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0004"), eq(1L), total.capture());
        assertThat(total.getValue()).isEqualByComparingTo(new BigDecimal("365.00"));
    }

    @Test
    void generateQuotation_treatsUnpricedItemAsZeroNotError() {
        TicketItemDto priced = new TicketItemDto(1L, 10L, "PC001", "Product A", null, null,
            "pcs", null, new BigDecimal("2"), null, null, null, null, null,
            new BigDecimal("100.00"), "THB", 0, null, null, null, "PIECE", null, null);
        TicketItemDto unpriced = new TicketItemDto(2L, 10L, "PC002", "Product B", null, null,
            "pcs", null, new BigDecimal("5"), null, null, null, null, null,
            null, "THB", 1, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.APPROVED, List.of(priced, unpriced));
        when(ticketRepo.nextQuotationCode()).thenReturn("QT-2026-0005");

        service.generateQuotation(10L, salesActor);

        // The unpriced item (approvedPrice = null) contributes 0 regardless of its quantity, rather
        // than throwing or being skipped from the total silently in a way that hides its qty.
        ArgumentCaptor<BigDecimal> total = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ticketRepo).createQuotation(eq(10L), eq("QT-2026-0005"), eq(1L), total.capture());
        assertThat(total.getValue()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    // ── close ─────────────────────────────────────────────────────────────

    @Test
    void close_documentIssuedByOwner_transitionsToClosed() {
        stubTicket(10L, 1L, TicketStatus.DOCUMENT_ISSUED);

        service.close(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CLOSED), eq(TicketStatus.DOCUMENT_ISSUED), eq(TicketStatus.CLOSED), isNull());
    }

    @Test
    void close_rejectsNonOwner() {
        stubTicket(10L, 1L, TicketStatus.DOCUMENT_ISSUED);
        assertForbidden(() -> service.close(10L, otherSales));
    }

    @Test
    void close_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.APPROVED);
        assertConflict(() -> service.close(10L, salesActor));
    }

    // ── dual-track lifecycle (payment + fulfillment) ──────────────────────

    @Test
    void confirmCustomer_quotationIssuedBySales_setsCustomerConfirmed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, null, null);

        service.confirmCustomer(10L, salesActor);

        verify(ticketRepo).updatePaymentStatus(10L, "CUSTOMER_CONFIRMED");
    }

    @Test
    void confirmCustomer_refusesDowngradeWhenPaymentTrackAdvanced() {
        // Re-confirming after the deposit was paid must not reset the payment track —
        // a reset re-arms the deadlock orderings.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);
        assertConflict(() -> service.confirmCustomer(10L, salesActor));
    }

    @Test
    void confirmCustomer_rejectsNonOwnerSales() {
        // Dual-track transitions had role checks but no ownership checks — any sales
        // rep could advance a colleague's payment track.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, null, null);
        assertForbidden(() -> service.confirmCustomer(10L, otherSales));
    }

    // (issueDepositNotice endpoint removed — DepositNoticeService.issue() is now the
    //  single action that advances the payment track to DEPOSIT_NOTICE_ISSUED.)

    @Test
    void close_legacyDocumentIssuedWithNullPayment_stillCloses() {
        // Pre-dual-track tickets (paymentStatus never set) keep the legacy close path.
        stubTicketWithTracks(10L, 1L, TicketStatus.DOCUMENT_ISSUED, null, null);

        service.close(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CLOSED), eq(TicketStatus.DOCUMENT_ISSUED), eq(TicketStatus.CLOSED), isNull());
    }

    @Test
    void close_legacyDocumentIssuedMidPaymentTrack_isRefused() {
        // The bypass from the audit: a mid-track ticket flipped to document_issued
        // must not close unpaid.
        stubTicketWithTracks(10L, 1L, TicketStatus.DOCUMENT_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);
        assertConflict(() -> service.close(10L, salesActor));

        stubTicketWithTracks(10L, 1L, TicketStatus.DOCUMENT_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        assertConflict(() -> service.close(10L, salesActor));
    }

    @Test
    void close_legacyDocumentIssuedFullyPaid_closes() {
        stubTicketWithTracks(10L, 1L, TicketStatus.DOCUMENT_ISSUED, "FULLY_PAID", "GOODS_RECEIVED");

        service.close(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CLOSED), eq(TicketStatus.DOCUMENT_ISSUED), eq(TicketStatus.CLOSED), isNull());
    }

    @Test
    void confirmDepositPaid_byAccount_advancesToDepositPaid() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);

        service.confirmDepositPaid(10L, accountActor);

        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
        // Fulfillment hasn't reached GOODS_RECEIVED — no early advance.
        verify(ticketRepo, never()).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
    }

    @Test
    void confirmDepositPaid_byCeoFallback_isAllowed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);
        service.confirmDepositPaid(10L, ceoActor);
        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
    }

    @Test
    void confirmDepositPaid_rejectsSalesRole() {
        // Money-receipt confirmations moved from sales to ฝ่ายบัญชี (account role).
        assertForbidden(() -> service.confirmDepositPaid(10L, salesActor));
    }

    @Test
    void confirmDepositPaid_rejectsImportRole() {
        assertForbidden(() -> service.confirmDepositPaid(10L, importActor));
    }

    @Test
    void confirmDepositPaid_afterGoodsReceived_advancesToAwaitingFinalPayment() {
        // Goods-first ordering (deadlock B): goods arrived while the deposit was
        // unconfirmed; confirming the deposit must carry payment forward, otherwise
        // AWAITING_FINAL_PAYMENT is unreachable and the ticket can never close.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED,
            "DEPOSIT_NOTICE_ISSUED", "GOODS_RECEIVED");

        service.confirmDepositPaid(10L, accountActor);

        verify(ticketRepo).updatePaymentStatus(10L, "DEPOSIT_PAID");
        verify(ticketRepo).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
        verify(ticketRepo).addEvent(eq(10L), eq(5L), anyString(),
            eq(TicketEventKind.AWAITING_FINAL_PAYMENT), anyString(), anyString(), isNull());
    }

    @Test
    void confirmDepositPaid_rejectsWrongPaymentStatus() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", null);
        assertConflict(() -> service.confirmDepositPaid(10L, accountActor));
    }

    @Test
    void issueImportRequest_fromDepositNoticeIssued_startsFulfillment() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", null);

        service.issueImportRequest(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_ISSUED");
    }

    @Test
    void issueImportRequest_fromDepositPaid_isAlsoAllowed() {
        // Deposit-first ordering (deadlock A): the customer often pays before import
        // gets to the IR — that must not lock the fulfillment track out forever.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", null);

        service.issueImportRequest(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_ISSUED");
    }

    @Test
    void issueImportRequest_rejectsReissueOnceFulfillmentStarted() {
        // Re-issuing would downgrade an in-flight fulfillment track back to IR_ISSUED.
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        assertConflict(() -> service.issueImportRequest(10L, importActor));
    }

    @Test
    void issueImportRequest_rejectsWhenNoDepositNoticeYet() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "CUSTOMER_CONFIRMED", null);
        assertConflict(() -> service.issueImportRequest(10L, importActor));
    }

    @Test
    void issueImportRequest_rejectsSalesRole() {
        assertForbidden(() -> service.issueImportRequest(10L, salesActor));
    }

    @Test
    void markIrSent_thenShipping_walksFulfillmentForward() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "IR_ISSUED");
        service.markIrSent(10L, importActor);
        verify(ticketRepo).updateFulfillmentStatus(10L, "IR_SENT");

        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "IR_SENT");
        service.markShipping(10L, importActor);
        verify(ticketRepo).updateFulfillmentStatus(10L, "SHIPPING");
    }

    @Test
    void markGoodsReceived_withDepositPaid_advancesBothTracks() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");

        service.markGoodsReceived(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "GOODS_RECEIVED");
        verify(ticketRepo).updatePaymentStatus(10L, "AWAITING_FINAL_PAYMENT");
    }

    @Test
    void markGoodsReceived_withDepositUnconfirmed_advancesFulfillmentOnly() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_NOTICE_ISSUED", "SHIPPING");

        service.markGoodsReceived(10L, importActor);

        verify(ticketRepo).updateFulfillmentStatus(10L, "GOODS_RECEIVED");
        verify(ticketRepo, never()).updatePaymentStatus(eq(10L), anyString());
    }

    @Test
    void confirmFinalPayment_byAccount_completesPaymentTrack() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");

        service.confirmFinalPayment(10L, accountActor);

        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void confirmFinalPayment_byCeoFallback_isAllowed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        service.confirmFinalPayment(10L, ceoActor);
        verify(ticketRepo).updatePaymentStatus(10L, "FULLY_PAID");
    }

    @Test
    void confirmFinalPayment_rejectsSalesRole() {
        assertForbidden(() -> service.confirmFinalPayment(10L, salesActor));
    }

    @Test
    void confirmFinalPayment_rejectsBeforeAwaitingFinalPayment() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "DEPOSIT_PAID", "SHIPPING");
        assertConflict(() -> service.confirmFinalPayment(10L, accountActor));
    }

    @Test
    void close_dualTrackComplete_transitionsToClosed() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID", "GOODS_RECEIVED");

        service.close(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CLOSED), eq(TicketStatus.QUOTATION_ISSUED), eq(TicketStatus.CLOSED), isNull());
    }

    @Test
    void close_dualTrackRejectsWhenPaymentIncomplete() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "AWAITING_FINAL_PAYMENT", "GOODS_RECEIVED");
        assertConflict(() -> service.close(10L, salesActor));
    }

    @Test
    void close_dualTrackRejectsWhenFulfillmentIncomplete() {
        stubTicketWithTracks(10L, 1L, TicketStatus.QUOTATION_ISSUED, "FULLY_PAID", "SHIPPING");
        assertConflict(() -> service.close(10L, salesActor));
    }

    // ── cancel ────────────────────────────────────────────────────────────

    @Test
    void cancel_ownerCanCancelFromDraft() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);
        service.cancel(10L, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.DRAFT), eq(TicketStatus.CANCELLED), isNull());
    }

    @Test
    void cancel_ownerCanCancelFromInReview() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);
        service.cancel(10L, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.IN_REVIEW), eq(TicketStatus.CANCELLED), isNull());
    }

    @Test
    void cancel_ownerCanCancelFromPriceProposed() {
        stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        service.cancel(10L, salesActor);
        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.CANCELLED), eq(TicketStatus.PRICE_PROPOSED), eq(TicketStatus.CANCELLED), isNull());
    }

    @Test
    void cancel_rejectsAlreadyClosed() {
        stubTicket(10L, 1L, TicketStatus.CLOSED);
        assertConflict(() -> service.cancel(10L, salesActor));
    }

    @Test
    void cancel_rejectsAlreadyCancelled() {
        stubTicket(10L, 1L, TicketStatus.CANCELLED);
        assertConflict(() -> service.cancel(10L, salesActor));
    }

    @Test
    void cancel_rejectsNonOwner() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        assertForbidden(() -> service.cancel(10L, otherSales));
    }

    // ── editItems ─────────────────────────────────────────────────────────
    // 2026-07-16 pricing-integrity audit, finding #4: sales editing descriptive fields
    // must never silently discard import's proposed price or CEO's approved/manual price.

    @Test
    void editItems_preservesExistingPricingAndIgnoresRequestSuppliedPrices() {
        TicketItemDto existing = new TicketItemDto(
            101L, 10L, "OldBrand", "OldModel", "White", "Matte", "60x60", "Cotto",
            new BigDecimal("5"), new BigDecimal("10"),
            new BigDecimal("50"), "USD", "piece",
            new BigDecimal("777.00"), new BigDecimal("888.00"), "THB", 0,
            new BigDecimal("600.0000"), new BigDecimal("777.00"), 3, "PIECE",
            new BigDecimal("999.00"), "CEO special discount");
        stubTicketWithItems(10L, 1L, TicketStatus.PRICE_PROPOSED, List.of(existing));

        TicketItemRequest edited = new TicketItemRequest(
            "NewBrand", "NewModel", "Grey", "Glossy", "80x80", "Cotto",
            new BigDecimal("7"), new BigDecimal("14"), "PIECE",
            new BigDecimal("50"), "USD", "piece",
            new BigDecimal("1"),   // attacker/sales-supplied proposedPrice — must be ignored
            "THB");

        service.editItems(10L, new EditItemsRequest(List.of(edited), "แก้ไข spec"), salesActor);

        ArgumentCaptor<List<TicketItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).replaceItemsPreservingPricing(eq(10L), captor.capture());
        TicketItemDto merged = captor.getValue().get(0);

        assertThat(merged.brand()).isEqualTo("NewBrand");
        assertThat(merged.model()).isEqualTo("NewModel");
        assertThat(merged.qty()).isEqualByComparingTo("7");
        // Pricing fields carried over from the existing item, NOT the request.
        assertThat(merged.proposedPrice()).isEqualByComparingTo("777.00");
        assertThat(merged.approvedPrice()).isEqualByComparingTo("888.00");
        assertThat(merged.calcedCost()).isEqualByComparingTo("600.0000");
        assertThat(merged.calcedPrice()).isEqualByComparingTo("777.00");
        assertThat(merged.calcConfigVersion()).isEqualTo(3);
        assertThat(merged.manualPrice()).isEqualByComparingTo("999.00");
        assertThat(merged.manualOverrideReason()).isEqualTo("CEO special discount");
    }

    @Test
    void editItems_newItemBeyondCurrentCountGetsNullPricingFields() {
        TicketItemDto existing = new TicketItemDto(
            101L, 10L, "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("5"), new BigDecimal("10"),
            new BigDecimal("50"), "THB", "piece",
            new BigDecimal("100"), new BigDecimal("100"), "THB", 0,
            null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.SUBMITTED, List.of(existing));

        TicketItemRequest first = new TicketItemRequest(
            "Brand", "Model", null, null, null, "Cotto",
            new BigDecimal("5"), new BigDecimal("10"), "PIECE",
            new BigDecimal("50"), "THB", "piece", null, "THB");
        TicketItemRequest brandNew = new TicketItemRequest(
            "AnotherBrand", "AnotherModel", null, null, null, "Cotto",
            new BigDecimal("2"), null, "PIECE",
            new BigDecimal("20"), "THB", "piece", new BigDecimal("999"), "THB");

        service.editItems(10L, new EditItemsRequest(List.of(first, brandNew), null), salesActor);

        ArgumentCaptor<List<TicketItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(ticketRepo).replaceItemsPreservingPricing(eq(10L), captor.capture());
        TicketItemDto newItem = captor.getValue().get(1);

        assertThat(newItem.brand()).isEqualTo("AnotherBrand");
        assertThat(newItem.proposedPrice()).isNull();
        assertThat(newItem.approvedPrice()).isNull();
        assertThat(newItem.manualPrice()).isNull();
    }

    @Test
    void editItems_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, TicketStatus.SUBMITTED);
        TicketItemRequest req = new TicketItemRequest(
            "Brand", "Model", "Color", "Texture", "Size", "Factory",
            BigDecimal.ONE, null, "PIECE", null, null, null, null, "THB");

        assertForbidden(() -> service.editItems(10L, new EditItemsRequest(List.of(req), null), otherSales));
    }

    // ── calculatePrices ──────────────────────────────────────────────────

    @Test
    void calculatePrices_priceProposedByCeoDelegatesToPricingEngine() {
        TicketDto calculated = stubTicket(10L, 1L, TicketStatus.PRICE_PROPOSED);
        when(priceCalcService.calculateForTicket(10L)).thenReturn(calculated);
        when(priceCalcService.calculateBreakdown(10L)).thenReturn(List.of());

        TicketService.CalculatePricesResult result = service.calculatePrices(10L, ceoActor);

        assertThat(result.ticket()).isEqualTo(calculated);
        verify(priceCalcService).calculateForTicket(10L);
    }

    @Test
    void calculatePrices_rejectsSalesRole() {
        assertForbidden(() -> service.calculatePrices(10L, salesActor));
    }

    @Test
    void calculatePrices_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        assertConflict(() -> service.calculatePrices(10L, ceoActor));
    }

    // ── overrideItemPrice ────────────────────────────────────────────────
    // 2026-07-16 pricing-integrity audit, finding #3: overriding a price previously left
    // no audit trail at all.

    @Test
    void overrideItemPrice_logsPriceOverriddenEventWithItemIdManualPriceAndReason() {
        TicketItemDto item = new TicketItemDto(
            101L, 10L, "Brand", "Model", null, null, null, "Cotto",
            BigDecimal.ONE, null, new BigDecimal("50"), "THB", "piece",
            new BigDecimal("100"), null, "THB", 0, null, null, null, "PIECE", null, null);
        stubTicketWithItems(10L, 1L, TicketStatus.PRICE_PROPOSED, List.of(item));

        service.overrideItemPrice(10L, 101L,
            new OverridePriceRequest(new BigDecimal("777.00"), "ราคาพิเศษลูกค้า VIP"), ceoActor);

        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketRepo).addEvent(eq(10L), eq(4L), anyString(),
            eq(TicketEventKind.PRICE_OVERRIDDEN), eq(TicketStatus.PRICE_PROPOSED),
            eq(TicketStatus.PRICE_PROPOSED), noteCaptor.capture());
        assertThat(noteCaptor.getValue()).contains("101").contains("777.00").contains("ราคาพิเศษลูกค้า VIP");
        verify(ticketRepo).updateItemManualPrice(101L, new BigDecimal("777.00"), "ราคาพิเศษลูกค้า VIP");
    }

    @Test
    void overrideItemPrice_rejectsWrongStatus() {
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        assertConflict(() -> service.overrideItemPrice(10L, 101L,
            new OverridePriceRequest(new BigDecimal("777.00"), null), ceoActor));
    }

    // ── comment ───────────────────────────────────────────────────────────

    @Test
    void comment_addsCommentEventWithoutStatusChange() {
        when(ticketRepo.existsById(10L)).thenReturn(true);
        stubTicket(10L, 1L, TicketStatus.IN_REVIEW);

        service.comment(10L, new CommentRequest("ช่วยตรวจราคาใหม่ด้วย"), importActor);

        verify(ticketRepo).addEvent(eq(10L), eq(3L), anyString(),
            eq(TicketEventKind.COMMENTED), isNull(), isNull(), eq("ช่วยตรวจราคาใหม่ด้วย"));
    }

    @Test
    void comment_rejectsNonExistentTicket() {
        when(ticketRepo.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.comment(99L, new CommentRequest("hi"), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String status) {
        return stubTicketWithItems(ticketId, createdById, status, List.of());
    }

    private TicketDto stubTicketWithItems(long ticketId, long createdById, String status, List<TicketItemDto> items) {
        return stubTicket(ticketId, createdById, status, items, null, null);
    }

    private TicketDto stubTicketWithTracks(long ticketId, long createdById, String status,
                                           String paymentStatus, String fulfillmentStatus) {
        return stubTicket(ticketId, createdById, status, List.of(), paymentStatus, fulfillmentStatus);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String status, List<TicketItemDto> items,
                                 String paymentStatus, String fulfillmentStatus) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", status, "NORMAL",
            createdById, "Sales User", null, null, "Test Customer", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, items.size(), false, paymentStatus, fulfillmentStatus);
        TicketDto ticket = new TicketDto(summary, items, List.of(), null, List.of());
        when(ticketRepo.findById(ticketId)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private static void assertForbidden(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertConflict(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
