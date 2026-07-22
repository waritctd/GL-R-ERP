package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Unit-tests the DECISION in {@link FactoryQuoteService#deleteAttachment} — which branch is
 * chosen for a given (deletedAt, pricing-request status, quote status, submitted-costing
 * reference) combination — with every collaborator mocked, no database involved.
 *
 * <p>This is deliberately the "resolveScope-style" half of the two-layer evidence CLAUDE.md
 * requires for an authorization change: it proves the branch, not that the branch's SQL actually
 * reaches Postgres correctly. {@code PricingFactoryQuoteCostingIntegrationTest}'s
 * {@code deleteAttachment*} tests are the other half — real Postgres, real repository, real
 * service — and are what a mocked repository here cannot substitute for.
 */
class FactoryQuoteServiceAttachmentTest {

    private final FactoryQuoteRepository quotes = mock(FactoryQuoteRepository.class);
    private final PricingRequestRepository pricingRequests = mock(PricingRequestRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final FactoryConfigRepository factoryConfigs = mock(FactoryConfigRepository.class);
    private final FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final FactoryQuoteService service = new FactoryQuoteService(
        quotes, pricingRequests, tickets, factoryConfigs, factoryEmail, notifications, fileStorage, new AppProperties());

    private final UserPrincipal importActor = actor(1L, "import");
    private final UserPrincipal salesActor = actor(2L, "sales");
    private final UserPrincipal ceoActor = actor(3L, "ceo");

    @Test
    void rejectsNonImportRoles() {
        assertForbidden(() -> service.deleteAttachment(10L, "reason", salesActor));
        assertForbidden(() -> service.deleteAttachment(10L, "reason", ceoActor));
        verify(quotes, never()).findAttachment(anyLong());
    }

    @Test
    void rejectsAnAlreadyDeletedAttachment() {
        stubAttachment(10L, 20L, Instant.now());

        assertConflict(() -> service.deleteAttachment(10L, "reason", importActor));
        verify(quotes, never()).tombstoneAttachment(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsWhenPricingRequestHasReachedReadyForCeoReview() {
        stubAttachment(10L, 20L, null);
        stubQuote(20L, 30L, FactoryQuoteStatus.RESPONSE_RECEIVED);
        stubPricingRequest(30L, PricingRequestStatus.READY_FOR_CEO_REVIEW);

        assertConflict(() -> service.deleteAttachment(10L, "reason", importActor));
        verify(quotes, never()).tombstoneAttachment(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsWhenTheQuoteItselfIsReadyForCosting() {
        stubAttachment(10L, 20L, null);
        stubQuote(20L, 30L, FactoryQuoteStatus.READY_FOR_COSTING);
        stubPricingRequest(30L, PricingRequestStatus.COSTING_IN_PROGRESS);

        assertConflict(() -> service.deleteAttachment(10L, "reason", importActor));
        verify(quotes, never()).tombstoneAttachment(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectsWhenReferencedByASubmittedCosting() {
        stubAttachment(10L, 20L, null);
        stubQuote(20L, 30L, FactoryQuoteStatus.RESPONSE_RECEIVED);
        stubPricingRequest(30L, PricingRequestStatus.COSTING_IN_PROGRESS);
        when(quotes.existsSubmittedCostingReferencingQuote(20L)).thenReturn(true);

        assertConflict(() -> service.deleteAttachment(10L, "reason", importActor));
        verify(quotes, never()).tombstoneAttachment(anyLong(), anyLong(), anyString());
    }

    @Test
    void tombstonesWhenEveryGuardPasses() {
        stubAttachment(10L, 20L, null);
        stubQuote(20L, 30L, FactoryQuoteStatus.RESPONSE_RECEIVED);
        stubPricingRequest(30L, PricingRequestStatus.IMPORT_REVIEWING);
        when(quotes.existsSubmittedCostingReferencingQuote(20L)).thenReturn(false);
        when(quotes.tombstoneAttachment(10L, importActor.id(), "no longer needed")).thenReturn(1);

        service.deleteAttachment(10L, "no longer needed", importActor);

        verify(quotes, times(1)).tombstoneAttachment(10L, importActor.id(), "no longer needed");
    }

    // --- helpers ---

    private void stubAttachment(long attachmentId, long quoteId, Instant deletedAt) {
        when(quotes.findAttachment(attachmentId)).thenReturn(Optional.of(new FactoryQuoteAttachmentDto(
            attachmentId, quoteId, "quote.pdf", "application/pdf", 100L, importActor.id(), Instant.now(),
            deletedAt, deletedAt == null ? null : importActor.id(), deletedAt == null ? null : "prior reason")));
    }

    private void stubQuote(long quoteId, long pricingRequestId, String status) {
        when(quotes.find(quoteId)).thenReturn(Optional.of(new FactoryQuoteDto(
            quoteId, "FQ-2026-0001", pricingRequestId, 40L, "Factory A", status,
            "factory@example.com", "subject", "body", null, null, null, "THB", null, null, null, null,
            null, null, quoteId, null, 1, null, true, Instant.now(), Instant.now(),
            List.of(), List.of(), null, 0, null, null)));
    }

    private void stubPricingRequest(long pricingRequestId, String status) {
        when(pricingRequests.findSummary(pricingRequestId)).thenReturn(Optional.of(new PricingRequestSummaryDto(
            pricingRequestId, "PCR-2026-0001", 50L, "T-1", "Project", "Customer", 2L,
            PricingRequestRecipient.BUYER, null, null, status, 2L, "Sales User", importActor.id(), "Import User",
            LocalDate.now().plusDays(7), new BigDecimal("100.00"), "THB", null, 1, 1, null, null, null, null,
            Instant.now(), Instant.now(), null)));
    }

    private static UserPrincipal actor(long id, String role) {
        return new UserPrincipal(id, role + "@glr.co.th", role, role, id, true, LocalDate.now(), false, null, false);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
