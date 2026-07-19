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
import static org.mockito.Mockito.verifyNoInteractions;
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
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CancelPricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RequestMoreInformationRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.RespondMoreInformationRequest;
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
    private final PricingRequestService service =
        new PricingRequestService(requestRepo, ticketRepo, notifRepo, new ObjectMapper(), contactRepo);

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
    void updateDraft_rejectsNonActiveDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        assertConflict(() -> service.updateDraft(20L, sampleUpdateRequest(), salesActor));
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

    // ── item identity (Part 1: an unidentified product must be rejected) ───────

    @Test
    void createDraft_rejectsItemWithNoIdentityField() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(null, null, "Brand only, no model", null, null)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_rejectsItemIdentifiedByBrandAlone() {
        // Brand alone is explicitly NOT sufficient — a brand with no model
        // does not identify a product. Same assertion as the no-fields test
        // above, kept separate so a future change that (wrongly) special-cases
        // "at least one field is non-null" fails this test specifically.
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(null, null, "Some Brand", null, null)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_acceptsItemIdentifiedBySourceTicketItemIdAlone() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of(501L));
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(501L, null, null, null, null)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
    }

    @Test
    void createDraft_acceptsItemIdentifiedByProductIdAlone() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(null, 77L, null, null, null)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
    }

    @Test
    void createDraft_acceptsItemIdentifiedByModelAlone() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(null, null, null, "Model X", null)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
    }

    @Test
    void createDraft_acceptsItemIdentifiedBySpecialRequirementAlone() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(itemRequestWithIdentity(null, null, null, null, "กระเบื้องลายไม้สีเข้ม ผิวด้าน")));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
    }

    @Test
    void submit_rejectsPreExistingDraftWithUnidentifiedItem() {
        // Simulates a draft created BEFORE this rule existed (or otherwise
        // persisted with an unidentified line): submit() must re-check the
        // PERSISTED items, not just rely on createDraft/updateDraft having
        // validated them at write time.
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.findItems(20L)).thenReturn(List.of(itemDtoWithIdentity(null, null, "Brand only", null, null)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    // ── recipient contact ownership (Part 2) ────────────────────────────────

    @Test
    void createDraft_rejectsContactBelongingToAnotherCustomer() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE, 5L);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(contactRepo.findById(1L)).thenReturn(Optional.of(
            new ContactDto(1L, 99L, "Other", "Customer's Contact", null, null, null)));
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_acceptsContactBelongingToTheDealsCustomer() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE, 5L);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(contactRepo.findById(1L)).thenReturn(Optional.of(
            new ContactDto(1L, 5L, "Matching", "Contact", null, null, null)));
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
    }

    @Test
    void createDraft_rejectsUnknownContactId() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE, 5L);
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(contactRepo.findById(1L)).thenReturn(Optional.empty());
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        assertBadRequest(() -> service.createDraft(10L, request, salesActor));
        verify(requestRepo, never()).create(anyLong(), any(), any(), anyLong());
    }

    @Test
    void createDraft_skipsContactOwnershipCheckWhenTicketHasNoCustomer() {
        // Older deals may have customerId == null (no customer link at all).
        // The ownership check has nothing to compare the contact against in
        // that case, so it is skipped rather than treated as a mismatch —
        // this is "nothing to check", NOT "any contact is fine" in general.
        // contactRepo is deliberately never stubbed: if this code path
        // incorrectly called it anyway, Mockito's default Optional.empty()
        // would fail this test for the wrong reason (contact not found)
        // instead of the assertion below, and verifyNoInteractions makes
        // that failure mode explicit rather than accidental.
        stubTicket(10L, 1L, DealLifecycle.ACTIVE); // customerId defaults to null
        when(requestRepo.findItemIdsForTicket(10L)).thenReturn(List.of());
        when(requestRepo.nextRequestCode()).thenReturn("PCR-2026-0001");
        CreatePricingRequestRequest request = createRequest(PricingRequestRecipient.BUYER, 1L, null,
            List.of(sampleItemRequest(null, QuantityType.REFERENCE)));
        when(requestRepo.create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L))).thenReturn(20L);
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);

        service.createDraft(10L, request, salesActor);

        verify(requestRepo).create(eq(10L), eq("PCR-2026-0001"), eq(request), eq(1L));
        verifyNoInteractions(contactRepo);
    }

    @Test
    void updateDraft_rejectsContactBelongingToAnotherCustomer() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE, 5L);
        when(contactRepo.findById(1L)).thenReturn(Optional.of(
            new ContactDto(1L, 99L, "Other", "Customer's Contact", null, null, null)));
        UpdatePricingRequestRequest request = new UpdatePricingRequestRequest(
            null, 1L, null, null, null, null, null, null);
        assertBadRequest(() -> service.updateDraft(20L, request, salesActor));
        verify(requestRepo, never()).updateDraft(anyLong(), any());
    }

    @Test
    void submit_rejectsContactBelongingToAnotherCustomer() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT, 1L, null, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE, 5L);
        when(requestRepo.findItems(20L)).thenReturn(List.of(sampleItem(null)));
        when(contactRepo.findById(1L)).thenReturn(Optional.of(
            new ContactDto(1L, 99L, "Other", "Customer's Contact", null, null, null)));
        assertBadRequest(() -> service.submit(20L, salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
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

    @Test
    void cancel_succeedsOnNonActiveDealWithoutCheckingTicketLifecycle() {
        // cancel() must remain callable no matter the deal's lifecycle — a request
        // left over on a deal that just went ON_HOLD/DORMANT/lost must still be
        // cancellable (see the comment on cancel() itself). Deliberately no
        // stubTicket() call: if cancel() ever gained a requireTicket/requireActive
        // check, ticketRepo.findById would return empty and this would blow up
        // with 404 "Ticket not found" instead of succeeding.
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 1L))
            .thenReturn(1);

        service.cancel(20L, cancelRequest(), salesActor);

        verify(requestRepo).transition(20L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 1L);
        verify(ticketRepo, never()).findById(anyLong());
    }

    // ── read scoping ─────────────────────────────────────────────────────

    @Test
    void get_salesCannotViewOthersDeal() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        assertForbidden(() -> service.get(20L, salesActor));
    }

    // This inverts the old get_importCanViewAnyDeal, which asserted the exact bug
    // this class's own Javadoc disclaims ("a draft is the rep's private
    // scratchpad until submit()"): import could previously read ANY rep's DRAFT
    // by id. That was a real privacy bug, not a rule worth preserving — see
    // requireViewable/canSeeDraft. Approved policy: DRAFT is visible only to its
    // owning sales rep plus ceo/sales_manager oversight; NOT import, NOT account.
    // A non-owner/non-oversight caller gets 404, not 403 — see requireViewable's
    // comment for why (403 would confirm the row exists and let a non-owner
    // probe ids for other reps' drafts).
    @Test
    void get_importCannotViewAnotherRepsDraft() {
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
    void get_accountCannotViewAnotherRepsDraft() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        assertNotFound(() -> service.get(20L, accountActor));
    }

    @Test
    void get_ceoCanViewAnotherRepsDraft() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, ceoActor).summary().id()).isEqualTo(20L);
    }

    @Test
    void get_salesManagerCanViewAnotherRepsDraft() {
        stubPricingRequest(20L, 10L, 99L, PricingRequestStatus.DRAFT);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, salesManagerActor).summary().id()).isEqualTo(20L);
    }

    @Test
    void get_owningSalesRepCanViewOwnDraft() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());
        assertThat(service.get(20L, salesActor).summary().id()).isEqualTo(20L);
    }

    // list()/findSummaries: another rep's DRAFT must never leak into the queue,
    // regardless of the status filter used, unless the caller has oversight.
    @Test
    void list_importDoesNotSeeAnotherRepsDraft_statusFilterNull() {
        when(requestRepo.findSummaries(null, null, null, true, false, 3L)).thenReturn(List.of());
        assertThat(service.list(null, null, true, importActor)).isEmpty();
        verify(requestRepo).findSummaries(null, null, null, true, false, 3L);
    }

    @Test
    void list_importDoesNotSeeAnotherRepsDraft_statusFilterDraft() {
        when(requestRepo.findSummaries(PricingRequestStatus.DRAFT, null, null, true, false, 3L)).thenReturn(List.of());
        assertThat(service.list(PricingRequestStatus.DRAFT, null, true, importActor)).isEmpty();
        verify(requestRepo).findSummaries(PricingRequestStatus.DRAFT, null, null, true, false, 3L);
    }

    @Test
    void list_ceoSeesAnotherRepsDraft() {
        PricingRequestSummaryDto draft = stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findSummaries(null, null, null, true, true, 4L)).thenReturn(List.of(draft));
        assertThat(service.list(null, null, true, ceoActor))
            .extracting(PricingRequestSummaryDto::id).contains(20L);
        verify(requestRepo).findSummaries(null, null, null, true, true, 4L);
    }

    // listForTicket is a separate read path from get()/list() and must honour the
    // same DRAFT-privacy rule.
    @Test
    void listForTicket_importDoesNotSeeDraftRequestsOnTicket() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        PricingRequestSummaryDto draft = stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findByTicket(10L)).thenReturn(List.of(draft));
        assertThat(service.listForTicket(10L, importActor)).isEmpty();
    }

    @Test
    void listForTicket_ceoSeesDraftRequestsOnTicket() {
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        PricingRequestSummaryDto draft = stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.findByTicket(10L)).thenReturn(List.of(draft));
        assertThat(service.listForTicket(10L, ceoActor))
            .extracting(PricingRequestSummaryDto::id).containsExactly(20L);
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
        // draftOversight=false (sales is not ceo/sales_manager), draftOwnerId=1L
        // (salesActor's own id) — see findSummaries's Javadoc/clause comment.
        verify(requestRepo).findSummaries(null, null, 1L, true, false, 1L);
    }

    @Test
    void list_nonSalesSeesAllDeals() {
        service.list(null, null, true, importActor);
        // import is not ceo/sales_manager, so draftOversight=false — import still
        // relies on the draftOwnerId=t.created_by match, which the DRAFT-hiding
        // matrix below (list_import...) proves does NOT let import see another
        // rep's draft.
        verify(requestRepo).findSummaries(null, null, null, true, false, 3L);
    }

    @Test
    void list_rejectsInvalidStatus() {
        assertBadRequest(() -> service.list("BOGUS", null, true, salesActor));
    }

    // ── B1: dead-deal lifecycle filter is wired, but get(id) bypasses it ───────

    @Test
    void list_defaultQueueExcludesOnHoldDealButGetStillReachesIt() {
        // list(..., activeDealsOnly=true) must exclude a request whose deal is not
        // ACTIVE (repo-level behaviour proved by
        // PricingRequestRepositoryIntegrationTest#findSummaries_...); this test
        // proves the SERVICE actually passes activeDealsOnly through unmangled, and
        // that get(id) — which never touches deal lifecycle, only ownership/role —
        // still reaches the same request directly.
        when(requestRepo.findSummaries(null, null, null, true, false, 3L)).thenReturn(List.of());
        PricingRequestSummaryDto onHoldDealRequest = stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        when(requestRepo.findSummaries(null, null, null, false, false, 3L)).thenReturn(List.of(onHoldDealRequest));
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        assertThat(service.list(null, null, true, importActor)).isEmpty();
        assertThat(service.list(null, null, false, importActor)).extracting(PricingRequestSummaryDto::id).contains(20L);
        assertThat(service.get(20L, importActor).summary().id()).isEqualTo(20L);
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
    void pickup_rejectsNonSubmittedStatus() {
        // Side effect of the Part 1 privacy fix: this pricing request is still
        // DRAFT and importActor (id 3) is not its owner (id 1) nor ceo/
        // sales_manager, so requireViewable now hides it from import entirely —
        // 404, not the old 409 "Only a submitted pricing request can be picked
        // up". That 409 message would have leaked the row's existence/status to
        // an import user who should not even know a draft with this id exists;
        // a still-DRAFT request literally cannot reach a submitted-only import
        // yet, so 404 is the correct status once DRAFT is private.
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.DRAFT);
        assertNotFound(() -> service.pickup(20L, importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void pickup_rejectsNonActiveDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        assertConflict(() -> service.pickup(20L, importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void pickup_rejectsWhenTransitionRowsZero() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.IMPORT_REVIEWING,
            3L, null)).thenReturn(0);
        assertConflict(() -> service.pickup(20L, importActor));
        verify(notifRepo, never()).notifyEmployee(anyLong(), anyLong(), any(), any());
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
        verify(notifRepo).notifyEmployee(eq(1L), eq(10L), eq("PICKED_UP"), any());
        // The landmine guard: this repository call must be the ONLY interaction with
        // TicketRepository — pickup must never write sales.ticket.assigned_to. The
        // real end-to-end assertion (assigned_to IS NULL after a real pickup) lives
        // in PricingRequestRepositoryIntegrationTest.
        verify(ticketRepo, times(1)).findById(10L);
        verifyNoMoreInteractions(ticketRepo);
    }

    // ── requestInformation ──────────────────────────────────────────────────

    @Test
    void requestInformation_rejectsNonImportRoles() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, 3L);
        assertForbidden(() -> service.requestInformation(20L, moreInfoRequest(), salesActor));
        assertForbidden(() -> service.requestInformation(20L, moreInfoRequest(), ceoActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void requestInformation_rejectsUnassignedImport() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, null);
        assertForbidden(() -> service.requestInformation(20L, moreInfoRequest(), importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void requestInformation_rejectsDifferentAssignedImport() {
        long anotherImportId = 99L;
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, anotherImportId);
        assertForbidden(() -> service.requestInformation(20L, moreInfoRequest(), importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void requestInformation_rejectsWrongStatus() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED, 3L);
        assertConflict(() -> service.requestInformation(20L, moreInfoRequest(), importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void requestInformation_rejectsWhenTransitionRowsZero() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, 3L);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED,
            null, null)).thenReturn(0);
        assertConflict(() -> service.requestInformation(20L, moreInfoRequest(), importActor));
        verify(notifRepo, never()).notifyEmployee(anyLong(), anyLong(), any(), any());
    }

    @Test
    void requestInformation_rejectsNonActiveDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, 3L);
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        assertConflict(() -> service.requestInformation(20L, moreInfoRequest(), importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void requestInformation_assignedImportSucceedsAndNotifiesRequester() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, 3L);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED,
            null, null)).thenReturn(1);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        service.requestInformation(20L, moreInfoRequest(), importActor);

        verify(requestRepo).transition(20L, PricingRequestStatus.IMPORT_REVIEWING, PricingRequestStatus.MORE_INFO_REQUIRED,
            null, null);
        verify(requestRepo).addEvent(eq(20L), eq(10L), eq(3L), any(),
            eq(PricingRequestEventKind.MORE_INFO_REQUESTED), eq(PricingRequestStatus.IMPORT_REVIEWING),
            eq(PricingRequestStatus.MORE_INFO_REQUIRED), eq("กรุณาระบุขนาดสินค้าเพิ่มเติม"), any());
        verify(notifRepo).notifyEmployee(eq(1L), eq(10L), eq("MORE_INFO_REQUIRED"), any());
    }

    // ── respondInformation ──────────────────────────────────────────────────

    @Test
    void respondInformation_rejectsImportRole() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, 3L);
        assertForbidden(() -> service.respondInformation(20L, respondRequest(), importActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void respondInformation_rejectsNonOwnerSales() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, 3L);
        assertForbidden(() -> service.respondInformation(20L, respondRequest(), otherSales));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void respondInformation_rejectsWrongStatus() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.IMPORT_REVIEWING, 3L);
        assertConflict(() -> service.respondInformation(20L, respondRequest(), salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void respondInformation_rejectsWhenTransitionRowsZero() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, 3L);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING,
            null, null)).thenReturn(0);
        assertConflict(() -> service.respondInformation(20L, respondRequest(), salesActor));
    }

    @Test
    void respondInformation_rejectsNonActiveDeal() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, 3L);
        stubTicket(10L, 1L, DealLifecycle.ON_HOLD);
        assertConflict(() -> service.respondInformation(20L, respondRequest(), salesActor));
        verify(requestRepo, never()).transition(anyLong(), any(), any(), any(), any());
    }

    @Test
    void respondInformation_goesToImportReviewingNotSubmittedAndNotifiesAssignedImport() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, 3L);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING,
            null, null)).thenReturn(1);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        service.respondInformation(20L, respondRequest(), salesActor);

        // The critical assertion: NOT back to SUBMITTED.
        verify(requestRepo).transition(20L, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING,
            null, null);
        verify(requestRepo, never()).transition(eq(20L), any(), eq(PricingRequestStatus.SUBMITTED), any(), any());
        verify(requestRepo).addEvent(eq(20L), eq(10L), eq(1L), any(),
            eq(PricingRequestEventKind.MORE_INFO_RESPONDED), eq(PricingRequestStatus.MORE_INFO_REQUIRED),
            eq(PricingRequestStatus.IMPORT_REVIEWING), any(), eq(null));
        verify(notifRepo).notifyEmployee(eq(3L), eq(10L), eq("MORE_INFO_RESPONDED"), any());
    }

    @Test
    void respondInformation_guardsAgainstNullAssignedImport() {
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED, null);
        stubTicket(10L, 1L, DealLifecycle.ACTIVE);
        when(requestRepo.transition(20L, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.IMPORT_REVIEWING,
            null, null)).thenReturn(1);
        when(requestRepo.findItems(20L)).thenReturn(List.of());
        when(requestRepo.findEvents(20L)).thenReturn(List.of());

        service.respondInformation(20L, respondRequest(), salesActor);

        verify(notifRepo, never()).notifyEmployee(anyLong(), anyLong(), any(), any());
    }

    // ── cancelOpenForTicket (internal dead-deal cascade) ──────────────────────

    @Test
    void cancelOpenForTicket_cancelsAllOpenRequests() {
        when(requestRepo.findOpenIdsForTicket(10L)).thenReturn(List.of(20L, 21L));
        stubPricingRequest(20L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        stubPricingRequest(21L, 10L, 1L, PricingRequestStatus.MORE_INFO_REQUIRED);
        when(requestRepo.transition(20L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.CANCELLED, null, 4L))
            .thenReturn(1);
        when(requestRepo.transition(21L, PricingRequestStatus.MORE_INFO_REQUIRED, PricingRequestStatus.CANCELLED, null, 4L))
            .thenReturn(1);

        int cancelledCount = service.cancelOpenForTicket(10L, "PROJECT_ON_HOLD", ceoActor);

        assertThat(cancelledCount).isEqualTo(2);
        verify(requestRepo).addEvent(eq(20L), eq(10L), eq(4L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CANCELLED), eq(PricingRequestStatus.SUBMITTED),
            eq(PricingRequestStatus.CANCELLED), eq("PROJECT_ON_HOLD"), any());
        verify(requestRepo).addEvent(eq(21L), eq(10L), eq(4L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CANCELLED), eq(PricingRequestStatus.MORE_INFO_REQUIRED),
            eq(PricingRequestStatus.CANCELLED), eq("PROJECT_ON_HOLD"), any());
    }

    @Test
    void cancelOpenForTicket_skipsAlreadyCancelledAndRacedRows() {
        // 20L: already cancelled by the time findSummary runs (findOpenIdsForTicket
        // read is not in the same transaction snapshot as the loop body).
        when(requestRepo.findOpenIdsForTicket(10L)).thenReturn(List.of(20L, 21L, 22L));
        when(requestRepo.findSummary(20L)).thenReturn(java.util.Optional.empty());
        // 21L: still open per findSummary, but the compare-and-set races and misses.
        stubPricingRequest(21L, 10L, 1L, PricingRequestStatus.DRAFT);
        when(requestRepo.transition(21L, PricingRequestStatus.DRAFT, PricingRequestStatus.CANCELLED, null, 4L))
            .thenReturn(0);
        // 22L: succeeds normally.
        stubPricingRequest(22L, 10L, 1L, PricingRequestStatus.SUBMITTED);
        when(requestRepo.transition(22L, PricingRequestStatus.SUBMITTED, PricingRequestStatus.CANCELLED, null, 4L))
            .thenReturn(1);

        int cancelledCount = service.cancelOpenForTicket(10L, "OTHER", ceoActor);

        // A raced 0-rowcount (21L) does not abort the loop — 22L still gets cancelled.
        assertThat(cancelledCount).isEqualTo(1);
        verify(requestRepo, never()).addEvent(eq(21L), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(requestRepo).addEvent(eq(22L), eq(10L), eq(4L), any(),
            eq(PricingRequestEventKind.PRICING_REQUEST_CANCELLED), eq(PricingRequestStatus.SUBMITTED),
            eq(PricingRequestStatus.CANCELLED), eq("OTHER"), any());
    }

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
            1, 1, null, null, null, null, Instant.now(), Instant.now());
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    /** Variant for pickup/requestInformation/respondInformation: carries assignedImportId. */
    private PricingRequestSummaryDto stubPricingRequest(long id, long ticketId, long ticketCreatedById, String status,
                                                         Long assignedImportId) {
        PricingRequestSummaryDto summary = new PricingRequestSummaryDto(
            id, "PCR-2026-0001", ticketId, "PR-2026-0001", "Test Project", "Test Customer",
            ticketCreatedById, PricingRequestRecipient.BUYER, 1L, null,
            status, ticketCreatedById, "Sales User", assignedImportId,
            assignedImportId != null ? "Import User" : null, null, null, null, null,
            1, 1, null, null, null, null, Instant.now(), Instant.now());
        when(requestRepo.findSummary(id)).thenReturn(java.util.Optional.of(summary));
        return summary;
    }

    private static RequestMoreInformationRequest moreInfoRequest() {
        return new RequestMoreInformationRequest("กรุณาระบุขนาดสินค้าเพิ่มเติม", null);
    }

    private static RespondMoreInformationRequest respondRequest() {
        return new RespondMoreInformationRequest("ขนาด 60x60");
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

    /** Lets each Part 1 identity test set exactly one identifying field and leave the rest null. */
    private static PricingRequestItemRequest itemRequestWithIdentity(Long sourceTicketItemId, Long productId,
                                                                     String brand, String model, String specialRequirement) {
        return new PricingRequestItemRequest(sourceTicketItemId, productId, null, brand, model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", QuantityType.REFERENCE, null, null, specialRequirement);
    }

    /** Same as {@link #sampleItem}, but with every identity field controllable for the Part 1 submit-recheck tests. */
    private static PricingRequestItemDto itemDtoWithIdentity(Long sourceTicketItemId, Long productId,
                                                             String brand, String model, String specialRequirement) {
        return new PricingRequestItemDto(1L, 20L, sourceTicketItemId, productId, null,
            brand, model, null, null, null, null,
            new BigDecimal("1"), null, "PIECE", QuantityType.REFERENCE,
            null, null, specialRequirement, 0);
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

    private static void assertNotFound(ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
