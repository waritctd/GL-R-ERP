package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.pricing.PriceCalcService;

class TicketServiceTest {

    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PriceCalcService priceCalcService = mock(PriceCalcService.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final QuotationRenderer quotationRenderer = new QuotationRenderer();
    private final TicketService service = new TicketService(
        ticketRepo, notificationService, priceCalcService, new ObjectMapper(), customerRepo, quotationRenderer);

    private final UserPrincipal salesActor  = actor(1L, "sales");
    private final UserPrincipal otherSales  = actor(2L, "sales");
    private final UserPrincipal importActor = actor(3L, "import");
    private final UserPrincipal ceoActor    = actor(4L, "ceo");

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

    // ── submit ────────────────────────────────────────────────────────────

    @Test
    void submit_draftByOwner_transitionsToSubmitted() {
        stubTicket(10L, 1L, TicketStatus.DRAFT);

        service.submit(10L, salesActor);

        verify(ticketRepo).addEvent(eq(10L), eq(1L), anyString(),
            eq(TicketEventKind.SUBMITTED), eq(TicketStatus.DRAFT), eq(TicketStatus.SUBMITTED), isNull());
        verify(notificationService).notifyByRole(eq("import"), anyString(), anyString(), anyString(), eq("/tickets/10"), eq(true));
        verify(notificationService).notifyByRole(eq("ceo"), anyString(), anyString(), anyString(), eq("/tickets/10"), eq(true));
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
        verify(notificationService).notifyByRole(eq("ceo"), anyString(), anyString(), anyString(), eq("/tickets/10"), eq(true));
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
        verify(notificationService).notify(eq(1L), anyString(), anyString(), anyString(), eq("/tickets/10"), eq(true));
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
        verify(notificationService).notifyByRole(eq("import"), anyString(), anyString(), anyString(), eq("/tickets/10"), eq(true));
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
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", status, "NORMAL",
            createdById, "Sales User", null, null, "Test Customer", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, items.size(), false, null, null);
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
