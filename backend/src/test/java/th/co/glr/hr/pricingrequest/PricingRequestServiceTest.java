package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.EntryChannel;
import th.co.glr.hr.ticket.TenderRequirement;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

class PricingRequestServiceTest {

    private final PricingRequestRepository requestRepo = mock(PricingRequestRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final NotificationRepository notifRepo = mock(NotificationRepository.class);
    private final PricingRequestService service =
        new PricingRequestService(requestRepo, ticketRepo, notifRepo, new ObjectMapper());

    private final UserPrincipal salesActor        = actor(1L, "sales");
    private final UserPrincipal otherSales        = actor(2L, "sales");
    private final UserPrincipal importActor       = actor(3L, "import");
    private final UserPrincipal ceoActor          = actor(4L, "ceo");
    private final UserPrincipal accountActor      = actor(5L, "account");
    private final UserPrincipal employeeActor     = actor(6L, "employee");
    private final UserPrincipal salesManagerActor = actor(7L, "sales_manager");

    // ── role gates ───────────────────────────────────────────────────────

    @Test
    void createDraft_rejectsNonSalesRoles() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), importActor));
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), ceoActor));
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), accountActor));
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), salesManagerActor));
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), employeeActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void updateDraft_rejectsNonSalesRoles() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.updateDraft(20L, sampleUpdateRequest(), importActor));
        assertForbidden(() -> service.updateDraft(20L, sampleUpdateRequest(), ceoActor));
        assertForbidden(() -> service.updateDraft(20L, sampleUpdateRequest(), accountActor));
        assertForbidden(() -> service.updateDraft(20L, sampleUpdateRequest(), salesManagerActor));
        verify(requestRepo, never()).updateDraft(anyLong(), any());
    }

    @Test
    void submit_rejectsNonSalesRoles() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.submit(20L, importActor));
        assertForbidden(() -> service.submit(20L, ceoActor));
        assertForbidden(() -> service.submit(20L, accountActor));
        assertForbidden(() -> service.submit(20L, salesManagerActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void get_rejectsEmployeeRole() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.get(20L, employeeActor));
    }

    // ── ownership ──────────────────────────────────────────────────────────

    @Test
    void createDraft_rejectsNonOwnerSales() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        assertForbidden(() -> service.createDraft(10L, sampleCreateRequest(), otherSales));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    // ── deal lifecycle ─────────────────────────────────────────────────────

    @Test
    void createDraft_rejectsNonActiveDeal() {
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        assertConflict(() -> service.createDraft(10L, sampleCreateRequest(), salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    // ── validation before persistence ───────────────────────────────────────

    @Test
    void createDraft_rejectsInvalidRecipientType() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = createRequest("BOGUS", 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_rejectsInvalidQuantityType() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, "BOGUS")));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_rejectsInvalidCurrency() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, 1L, null, null, null, "DOLLARS", null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_rejectsMissingRecipient() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, null, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_rejectsSourceItemFromAnotherTicket() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of(501L, 502L));
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(999L, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_acceptsSourceItemBelongingToTicket() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of(501L, 502L));
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(501L, QuantityType.REFERENCE)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
        verify(requestRepo).addEvent(eq(20L), eq(10L), eq(1L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CREATED), eq(null), eq(PricingRequestStatus.DRAFT),
            eq(null), eq(null));
    }

    @Test
    void createDraft_notifiesNobody() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(notifRepo, never()).notifyByRole(any(), anyLong(), any(), any());
        verify(notifRepo, never()).notifyEmployee(anyLong(), anyLong(), any(), any());
    }

    // ── submit ───────────────────────────────────────────────────────────

    @Test
    void submit_rejectsNonDraftStatus() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        assertConflict(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsWhenTransitionRowsZero() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null))
            .thenReturn(0);

        assertConflict(() -> service.submit(20L, salesActor));

        verify(notifRepo, never()).notifyByRole(any(), anyLong(), any(), any());
    }

    @Test
    void submit_notifiesImportOnceAndNeverCeo() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null))
            .thenReturn(1);

        service.submit(20L, salesActor);

        verify(notifRepo, times(1)).notifyByRole(eq("import"), eq(10L), any(), any());
        verify(notifRepo, never()).notifyByRole(eq("ceo"), anyLong(), any(), any());
    }

    @Test
    void submit_rejectsWhenNoItems() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsDuplicateSourceTicketItemIds() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(501L), sampleItem(501L)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsUnidentifiableRecipient() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, null, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsPastRequiredDate() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, LocalDate.now().minusDays(1));
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsNonOwnerSales() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        assertForbidden(() -> service.submit(20L, otherSales));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void submit_rejectsNonActiveDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        assertConflict(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    // ── cancel ───────────────────────────────────────────────────────────

    @Test
    void cancel_rejectsAlreadyCancelled() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.CANCELLED);
        assertConflict(() -> service.cancel(20L, cancelRequest(), salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cancel_allowsOwnerFromDraft() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 1L))
            .thenReturn(1);

        service.cancel(20L, cancelRequest(), salesActor);

        verify(requestRepo).transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 1L);
    }

    @Test
    void cancel_allowsCeoOnAnyDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        when(requestRepo.transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.CANCELLED, null, 4L))
            .thenReturn(1);

        service.cancel(20L, cancelRequest(), ceoActor);

        verify(requestRepo).transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.CANCELLED, null, 4L);
    }

    @Test
    void cancel_rejectsNonOwnerSales() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.cancel(20L, cancelRequest(), otherSales));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void cancel_rejectsWhenTransitionRowsZero() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 1L))
            .thenReturn(0);
        assertConflict(() -> service.cancel(20L, cancelRequest(), salesActor));
    }

    // ── read scoping ─────────────────────────────────────────────────────

    @Test
    void get_salesCannotViewOthersDeal() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.get(20L, salesActor));
    }

    @Test
    void get_importCanViewAnyDeal() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, importActor).summary().id()).isEqualTo(20L);
    }

    @Test
    void listForTicket_salesSeesOnlyOwnDeal() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        service.listForTicket(10L, salesActor);
        verify(requestRepo).findByTicket(10L);
    }

    @Test
    void listForTicket_salesCannotSeeOthersDeal() {
        stubTicket(10L, 99L, DealLifecycle.ACTIVE);
        assertForbidden(() -> service.listForTicket(10L, salesActor));
    }

    @Test
    void list_salesFiltersToOwnDeals() {
        service.list(null, null, true, salesActor);
        verify(requestRepo).findSummaries(null, null, 1L, true);
    }

    @Test
    void list_nonSalesSeesAllDeals() {
        service.list(null, null, true, importActor);
        verify(requestRepo).findSummaries(null, null, null, true);
    }

    @Test
    void list_rejectsInvalidStatus() {
        assertBadRequest(() -> service.list("BOGUS", null, true, salesActor));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String lifecycle) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.APPROVED, "NORMAL",
            createdById, "Sales User", null, null, "Test Customer", null, null, "Test Project",
            null, null, null, Instant.now(), Instant.now(), null, 1, false, null, null,
            "QUOTE_DESIGN_SIDE", null, null, Instant.now(),
            lifecycle, TenderRequirement.UNKNOWN, DepositPolicy.REQUIRED, null, EntryChannel.DESIGNER_LED);
        TicketDto ticket = new TicketDto(summary, List.of(), List.of(), null, List.of());
        when(ticketRepo.findById(ticketId)).thenReturn(java.util.Optional.of(ticket));
        return ticket;
    }

    private PricingRequestSummaryDto stubPricingRequest(long id, long ticketId, long ticketCreatedById, String status) {
        return stubPricingRequest(id, ticketId, ticketCreatedById, status, 1L, null, null);
    }

    private PricingRequestSummaryDto stubPricingRequest(long id, long ticketId, long ticketCreatedById, String status,
                                                         Long recipientContactId, String recipientLabel,
                                                         LocalDate requiredDate) {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            id, "PCR-2026-0001", ticketId, "PR-2026-0001", "Test Project", "Test Customer",
            ticketCreatedById, PricingRequestRecipient.BUYER, recipientContactId, recipientLabel,
            status, ticketCreatedById, "Sales User", null, null, requiredDate, null, null, null,
            1, 1, null, null, null, null, Instant.now(), Instant.now());
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    private static PricingRequestItemDto sampleItem(Long sourceTicketItemId) {
        return new PricingRequestItemDto(1L, 20L, sourceTicketItemId, null, null,
            "Brand", "Model", null, null, null, null,
            new BigDecimal("1"), null, "PIECE", QuantityType.REFERENCE,
            null, null, null, 0);
    }

    private static PricingRequestItemRequest sampleItemRequest(Long sourceTicketItemId, String quantityType) {
        return new PricingRequestItemRequest(sourceTicketItemId, null, null, "Brand", "Model", null, null, null, null,
            new BigDecimal("1"), null, "PIECE", quantityType, null, null, null);
    }

    private static CreatePricingRequestRequest createRequest(String recipientType, Long recipientContactId,
                                                             String recipientLabel,
                                                             List<PricingRequestItemRequest> items) {
        return new CreatePricingRequestRequest(recipientType, recipientContactId, recipientLabel, null, null, null,
            null, items);
    }

    private static CreatePricingRequestRequest sampleCreateRequest() {
        return createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
    }

    private static UpdatePricingRequestRequest sampleUpdateRequest() {
        return new UpdatePricingRequestRequest(null, null, null, LocalDate.now().plusDays(1), null, null, null, null);
    }

    private static CancelPricingRequestRequest cancelRequest() {
        return new CancelPricingRequestRequest("ลูกค้ายกเลิกโครงการ");
    }

    private static void assertForbidden(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertBadRequest(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void assertConflict(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
