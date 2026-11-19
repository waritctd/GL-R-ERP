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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.factoryquote.FactoryQuoteCarryForward;
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
    private final ContactRepository contactRepo = mock(ContactRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    /** Mocked here, and only here: this class's whole point is that every collaborator is a mock, and its default
     * false from carryForwardOnSubmit is the ordinary not-a-revision answer, so submit() behaves exactly as it
     * did. */
    private final FactoryQuoteCarryForward carryForward = mock(FactoryQuoteCarryForward.class);
    private final PricingRequestService service =
        new PricingRequestService(requestRepo, ticketRepo, notifRepo, new ObjectMapper(), contactRepo, fileStorage,
            carryForward);

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

    // ── item identity (Part 1: an unidentified product must be rejected) ───────

    @Test
    void createDraft_idempotentReplayReturnsExistingWithoutDuplicateInsertOrEvent() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        PricingRequestSummaryDto existing = stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findByClientRequestId(1L, clientRequestId())).thenReturn(Optional.of(existing));
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        var replay = service.createDraft(10L, sampleCreateRequest(), salesActor);

        assertThat(replay.summary().id()).isEqualTo(20L);
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
        verify(requestRepo, never()).addEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    // ── updateDraft: full-replacement re-validation ────────
    // The full-replacement PUT re-runs EVERY validation against the
    // replacement payload, not just the contact-ownership check above; these
    // service-level tests cover that re-validation, the ownership/status guards
    // ahead of it, and the happy path.

    @Test
    void updateDraft_rejectsNonDraftStatus() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        assertConflict(() -> service.updateDraft(20L, sampleUpdateRequest(), salesActor));
        verify(requestRepo, never()).updateDraft(anyLong(), any());
    }

    // ── updateDraft (recipient guard on the OTHER route) ────────────
    //
    // createCustomerChangeRevision refuses a revision child whose recipientType differs from its
    // parent's, but that guard alone was reachable sideways: nothing stopped a plain PUT
    // (updateDraft) on an already-created child from repointing recipient_type afterward, and
    // PricingRequestRepository.updateDraft writes it unconditionally on any DRAFT row. Submit ->
    // CEO approve -> issue would then take the recipient from the mismatched value, and
    // CustomerQuotationService's supersedeSupersededChainQuotations — keyed only on
    // root_pricing_request_id, never recipient_type — would retire the OTHER recipient's already-
    // issued, already-delivered quotation. Same bug as the revision route, reached through the other door.

    @Test
    void updateDraft_rejectsDifferentRecipientTypeOnRevisionChildAndMutatesNothing() {
        stubPricingRequestRevisionChild(21L, 10L, 1L, PricingRequestStatus.DRAFT,
            PricingRequestRecipient.DESIGNER, 20L);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        UpdatePricingRequestRequest request = new UpdatePricingRequestRequest(
            PricingRequestRecipient.OWNER, null, null, null, null, null, null, null);

        assertConflict(() -> service.updateDraft(21L, request, salesActor));

        // The guard must fire BEFORE either mutating repository call in this method — enumerated from
        // PricingRequestService#updateDraft itself, not guessed: the row write and its event.
        verify(requestRepo, never()).updateDraft(anyLong(), any());
        verify(requestRepo, never()).addEvent(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    // ── submit ───────────────────────────────────────────────────────────

    @Test
    void submit_notifiesImportAndCeoWithPricingRequestLinks() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.SUBMITTED, null, null))
            .thenReturn(1);

        service.submit(20L, salesActor);

        verify(notifRepo, times(1)).notifyByRoleForPricingRequest(
            eq("import"), eq(20L), eq("PRICING_REQUEST_SUBMITTED"), any());
        verify(notifRepo, times(1)).notifyByRoleForPricingRequest(
            eq("ceo"), eq(20L), eq(PricingRequestEventKind.PRICING_REQUEST_SUBMITTED), any());
    }

    @Test
    void submit_rejectsPastRequiredDate() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, LocalDate.now().minusDays(1));
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    // ── cancel ───────────────────────────────────────────────────────────

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

    // ── read scoping ─────────────────────────────────────────────────────

    @Test
    void get_otherSalesCannotReadAnotherRepsSubmittedRequest_returns403() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.SUBMITTED);
        assertForbidden(() -> service.get(20L, salesActor));
    }

    // This class's own Javadoc says "a draft is the rep's private
    // scratchpad until submit()", so import must not be able to read ANY rep's DRAFT
    // by id — see requireViewable/canSeeDraft. Policy: DRAFT is visible only to its
    // owning sales rep plus ceo/sales_manager oversight; NOT import, NOT account.
    // A non-owner/non-oversight caller gets 404, not 403 — see requireViewable's
    // comment for why (403 would confirm the row exists and let a non-owner
    // probe ids for other reps' drafts).
    @Test
    void get_importCannotSeeDraft_returns404() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        assertNotFound(() -> service.get(20L, importActor));
    }

    @Test
    void get_importCanViewOnceSubmitted() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.SUBMITTED);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, importActor).summary().id()).isEqualTo(20L);
    }

    @Test
    void get_ownerSalesCanSeeOwnDraft() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, salesActor).summary().id()).isEqualTo(20L);
    }

    // ── pickup ───────────────────────────────────────────────────────────

    @Test
    void pickup_rejectsNonImportRoles() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        assertForbidden(() -> service.pickup(20L, salesActor));
        assertForbidden(() -> service.pickup(20L, ceoActor));
        assertForbidden(() -> service.pickup(20L, accountActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void pickup_succeedsAssignsOnlyTheRequestNeverTheTicketAndNotifiesRequester() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING,
            3L, null)).thenReturn(1);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        service.pickup(20L, importActor);

        verify(requestRepo).transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING, 3L, null);
        verify(requestRepo).addEvent(eq(20L), eq(10L), eq(3L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_PICKED_UP), eq(PricingRequestStatus.SUBMITTED),
            eq(PricingRequestStatus.IMPORT_REVIEWING), eq(null), eq(null));
        verify(notifRepo).notifyEmployeeForPricingRequest(eq(1L), eq(20L), eq("PICKED_UP"), any());
        verify(notifRepo).notifyByRoleForPricingRequest(
            eq("ceo"), eq(20L), eq(PricingRequestEventKind.PRICING_REQUEST_PICKED_UP), any());
        // The landmine guard: this repository call must be the ONLY interaction with TicketRepository — pickup must
        // never write sales.ticket.assigned_to. The real end-to-end assertion (assigned_to IS NULL after a real
        // pickup) lives in PricingRequestRepositoryIntegrationTest.
        verify(ticketRepo, times(1)).findById(10L);
        verifyNoMoreInteractions(ticketRepo);
    }

    // A raced compare-and-set must not be skipped, which would silently leave a
    // CLOSED_LOST/CANCELLED deal with an open IMPORT_REVIEWING pricing request behind.
    // cancelOpenForTicket retries a raced row up to CANCEL_MAX_ATTEMPTS (3) times
    // against a freshly-read status before giving up on it.
    @Test
    void cancelOpenForTicket_retriesARacedTransitionAndEndsCancelled() {
        when(requestRepo.findOpenIdsForTicket(10L)).thenReturn(List.of(21L));
        stubPricingRequest(21L, 10L, 1L, PricingRequestStatus.DRAFT);
        // First attempt races and misses; second attempt (still reading DRAFT, since the stub is a fixed answer)
        // succeeds.
        when(requestRepo.cancelForDeadDeal(21L, PricingRequestStatus.DRAFT, 4L)).thenReturn(0, 1);

        PricingRequestService.CancelOpenForTicketResult result = service.cancelOpenForTicket(10L, "OTHER", PricingRequestService.DeadDealCause.LOST, ceoActor);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.abandonedIds()).isEmpty();
        verify(requestRepo, times(2)).findSummary(21L);
        verify(requestRepo, times(2)).cancelForDeadDeal(21L, PricingRequestStatus.DRAFT, 4L);
        verify(requestRepo).addEvent(eq(21L), eq(10L), eq(4L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CANCELLED), eq(PricingRequestStatus.DRAFT),
            eq(PricingRequestStatus.CANCELLED), eq("OTHER"), any());
    }

    @Test
    void cancelOpenForTicket_givesUpAfterMaxAttemptsAndReportsAbandonment() {
        // 21L: every attempt races and misses. The loop must not abort the whole cascade over this — 22L, which comes
        // after it, must still be cancelled — and 21L's failure must be reported back, not silently swallowed into a
        // plain count that looks identical to "nothing was open".
        when(requestRepo.findOpenIdsForTicket(10L)).thenReturn(List.of(21L, 22L));
        stubPricingRequest(21L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.cancelForDeadDeal(21L, PricingRequestStatus.DRAFT, 4L)).thenReturn(0);
        stubPricingRequest(22L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        when(requestRepo.cancelForDeadDeal(22L, PricingRequestStatus.SUBMITTED, 4L)).thenReturn(1);

        PricingRequestService.CancelOpenForTicketResult result = service.cancelOpenForTicket(10L, "OTHER", PricingRequestService.DeadDealCause.LOST, ceoActor);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.abandonedIds()).containsExactly(21L);
        assertThat(result.abandonedCount()).isEqualTo(1);
        assertThat(result.hasAbandoned()).isTrue();
        verify(requestRepo, times(3)).findSummary(21L);
        verify(requestRepo, times(3)).cancelForDeadDeal(21L, PricingRequestStatus.DRAFT, 4L);
        verify(requestRepo, never()).addEvent(eq(21L), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(requestRepo).addEvent(eq(22L), eq(10L), eq(4L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CANCELLED), eq(PricingRequestStatus.SUBMITTED),
            eq(PricingRequestStatus.CANCELLED), eq("OTHER"), any());
    }

    // Pricing Request attachments ───── "Unit-test the decision" half of the two-layer evidence CLAUDE.md requires
    // for an authorization change.

    // ── helpers ──────────────────────────────────────────────────────────

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private TicketDto stubTicket(long ticketId, long createdById, String lifecycle) {
        return stubTicket(ticketId, createdById, lifecycle, null);
    }

    /** Variant that sets the deal's customerId — needed for the recipient-contact-ownership tests. */
    private TicketDto stubTicket(long ticketId, long createdById, String lifecycle, Long customerId) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.APPROVED, "NORMAL",
            createdById, "Sales User", null, null, "Test Customer", customerId, null, "Test Project",
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
            1, 1, null, null, null, null, Instant.now(), Instant.now(), null);
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    /** Variant for pickup: carries assignedImportId. */
    private PricingRequestSummaryDto stubPricingRequest(long id, long ticketId, long ticketCreatedById, String status,
                                                         Long assignedImportId) {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            id, "PCR-2026-0001", ticketId, "PR-2026-0001", "Test Project", "Test Customer",
            ticketCreatedById, PricingRequestRecipient.BUYER, 1L, null,
            status, ticketCreatedById, "Sales User", assignedImportId,
            assignedImportId != null ? "Import User" : null, null, null, null, null,
            1, 1, null, null, null, null, Instant.now(), Instant.now(), null);
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    /**
     * Variant for a revision CHILD — parentPricingRequestId is non-null, exactly
     * the signal PricingRequestService#updateDraft's new guard keys on.
     */
    private PricingRequestSummaryDto stubPricingRequestRevisionChild(long id, long ticketId, long ticketCreatedById,
                                                                      String status, String recipientType,
                                                                      long parentPricingRequestId) {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            id, "PCR-2026-0002", ticketId, "PR-2026-0001", "Test Project", "Test Customer",
            ticketCreatedById, recipientType, 1L, "Designer Co.",
            status, ticketCreatedById, "Sales User", null, null, null, null, null, null,
            1, 2, parentPricingRequestId, null, null, null, Instant.now(), Instant.now(), null);
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    /** Catalog snapshot fields are fully populated here so the Finding A catalog-completeness gate in submit does
     * not interfere with unrelated tests that use this DTO — dedicated catalog-gate tests build their own items
     * with those fields deliberately null/incomplete instead of reusing this helper. */
    private static PricingRequestItemDto sampleItem(Long sourceTicketItemId) {
        return itemDtoWithIdentity(sourceTicketItemId, null, "Brand", "Model", null);
    }

    // V185 (direct-deal-form parity): color/texture/size/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
    // REQUIRED on every item reaching PricingRequestService#resolveItems (every
    // createDraft/updateDraft/createCustomerChangeRevision item, unconditionally) — see that method's own Javadoc.
    private static PricingRequestItemRequest sampleItemRequest(Long sourceTicketItemId, String quantityType) {
        return sampleItemRequest(sourceTicketItemId, quantityType, null);
    }

    private static PricingRequestItemRequest sampleItemRequest(Long sourceTicketItemId, String quantityType,
                                                               String factory) {
        return new PricingRequestItemRequest(sourceTicketItemId, null, null, "Brand", "Model", null,
            "สี", "ผิว", "60x60", factory,
            null, null, null, null, quantityType, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, 1, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            null, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    /** Same as {@link #sampleItem}, but with every identity field controllable for the Part 1 submit-recheck tests. */
    private static PricingRequestItemDto itemDtoWithIdentity(Long sourceTicketItemId, Long productId,
                                                             String brand, String model, String specialRequirement) {
        return itemDtoWithIdentity(sourceTicketItemId, productId, brand, model, specialRequirement, null);
    }

    private static PricingRequestItemDto itemDtoWithIdentity(Long sourceTicketItemId, Long productId,
                                                             String brand, String model, String specialRequirement,
                                                             String productDescription) {
        // Catalog snapshot fields populated so this helper's callers exercise submit's Finding A gate the same way
        // regardless of whether productId itself is null.
        return new PricingRequestItemDto(1L, 20L, sourceTicketItemId, productId, null,
            brand, model, productDescription, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", UnitBasis.PER_PIECE, QuantityType.REFERENCE,
            null, null, specialRequirement, 0, 900L, 900L, new BigDecimal("100.0000"), "THB",
            null, 900L, "Test Factory", null, null, null, null, null);
    }

    private static CreatePricingRequestRequest createRequest(String recipientType, Long recipientContactId,
                                                             String recipientLabel,
                                                             List<PricingRequestItemRequest> items) {
        return new CreatePricingRequestRequest(recipientType, recipientContactId, recipientLabel, null, null, null,
            null, clientRequestId(), items);
    }

    private static String clientRequestId() {
        return "11111111-1111-1111-1111-111111111111";
    }

    private static CreatePricingRequestRequest sampleCreateRequest() {
        return createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
    }

    private static UpdatePricingRequestRequest sampleUpdateRequest() {
        // recipientType is a real value, not null: updateDraft is a
        // full-replacement PUT that validates recipientType
        // unconditionally, so this helper must represent a request that could
        // otherwise legitimately succeed on THAT guard. It still has no
        // recipientContactId/recipientLabel, so it fails
        // validateRecipientIdentifiable — fine for this fixture's actual callers,
        // which short-circuit on an earlier guard (role / deal lifecycle) before
        // ever reaching that far. A test that needs to reach past
        // validateRecipientIdentifiable (e.g. to exercise a later validation, or
        // the happy path) must use {@link #validUpdateRequest()} instead.
        return new UpdatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, null, LocalDate.now().plusDays(1), null, null, null, null);
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

    private static void assertNotFound(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

}
