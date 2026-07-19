package th.co.glr.hr.factoryquote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkNotAvailableRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.UpdateFactoryQuoteDraftRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

@Service
public class FactoryQuoteService {
    private static final Set<String> RAW_QUOTE_ROLES = Set.of("import", "ceo");
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> DRAFT_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS);
    private static final Set<String> RESPONSE_STATUSES = Set.of(
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);
    private static final Set<String> MUTABLE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);

    private final FactoryQuoteRepository quotes;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final FactoryConfigRepository factoryConfigs;
    private final FactoryEmailService factoryEmail;
    private final NotificationRepository notifications;

    public FactoryQuoteService(FactoryQuoteRepository quotes, PricingRequestRepository pricingRequests,
                               TicketRepository tickets, FactoryConfigRepository factoryConfigs,
                               FactoryEmailService factoryEmail, NotificationRepository notifications) {
        this.quotes = quotes;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.factoryConfigs = factoryConfigs;
        this.factoryEmail = factoryEmail;
        this.notifications = notifications;
    }

    @Transactional
    public List<FactoryQuoteDto> generateDrafts(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!DRAFT_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Pricing request must be under Import review before factory quote drafts can be generated");
        }
        requireActiveDeal(summary.ticketId());
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        Map<String, List<PricingRequestItemDto>> byFactory = groupByFactory(items);
        for (Map.Entry<String, List<PricingRequestItemDto>> entry : byFactory.entrySet()) {
            String factoryName = entry.getKey();
            if (quotes.findCurrentByFactory(pricingRequestId, factoryName).isPresent()) {
                continue;
            }
            FactoryConfigDto config = factoryConfigs.findByName(factoryName).orElse(null);
            String emailTo = config == null ? null : config.email();
            String subject = "Pricing request " + summary.requestCode() + " - " + safe(summary.projectName(), summary.customerName());
            String body = emailBody(summary, factoryName, entry.getValue());
            Long factoryId = entry.getValue().stream()
                .map(PricingRequestItemDto::resolvedFactoryId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
            long quoteId = quotes.createDraft(pricingRequestId, factoryId, factoryName, emailTo, subject, body, actor.id());
            quotes.insertDraftItems(quoteId, entry.getValue().stream().map(PricingRequestItemDto::id).toList());
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_EMAIL_READY, summary.status(), summary.status(),
                "Factory email draft ready for " + factoryName);
            notifyCeo(summary, PricingRequestEventKind.FACTORY_EMAIL_READY,
                "ใบขอราคา " + summary.requestCode() + " สร้างร่างอีเมลโรงงาน " + factoryName);
        }
        return list(pricingRequestId, actor);
    }

    public List<FactoryQuoteDto> list(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, RAW_QUOTE_ROLES);
        requirePricingRequest(pricingRequestId);
        return quotes.findByPricingRequest(pricingRequestId);
    }

    public FactoryQuoteDto get(long quoteId, UserPrincipal actor) {
        requireRole(actor, RAW_QUOTE_ROLES);
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto updateDraft(long quoteId, UpdateFactoryQuoteDraftRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, DRAFT_STATUSES);
        requireActiveDeal(summary.ticketId());
        if (!quotes.updateDraft(quoteId, request.emailTo(), request.emailSubject(), request.emailBody(), request.note())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only draft factory quote emails can be edited");
        }
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto send(long quoteId, SendFactoryQuoteRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, DRAFT_STATUSES);
        requireActiveDeal(summary.ticketId());
        if (FactoryQuoteStatus.REQUESTED.equals(quote.status())) {
            return quote;
        }
        String emailTo = firstText(request.emailTo(), quote.emailTo());
        String subject = firstText(request.emailSubject(), quote.emailSubject());
        String body = firstText(request.emailBody(), quote.emailBody());
        if (emailTo == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Factory email recipient is required");
        }
        int rows = quotes.markRequested(quoteId, emailTo, subject, body, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Only draft factory quote emails can be sent");
        }
        factoryEmail.send(summary.ticketId(), quote.factoryName(), emailTo, subject, body);
        if (PricingRequestStatus.IMPORT_REVIEWING.equals(summary.status())) {
            pricingRequests.transition(summary.id(), PricingRequestStatus.IMPORT_REVIEWING,
                PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
        }
        if (!PricingRequestStatus.AWAITING_FACTORY_RESPONSE.equals(summary.status())) {
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_EMAIL_SENT, summary.status(),
                PricingRequestStatus.AWAITING_FACTORY_RESPONSE, "Factory request sent to " + quote.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_EMAIL_SENT,
                "ใบขอราคา " + summary.requestCode() + " ส่งคำขอโรงงาน " + quote.factoryName());
        }
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto receive(long quoteId, ReceiveFactoryQuoteRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto current = requireQuote(quoteId);
        if (!current.current()) {
            throw new ApiException(HttpStatus.CONFLICT, "Only the current factory quote revision can receive a response");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(current.pricingRequestId());
        requireMutablePricingRequest(summary, RESPONSE_STATUSES);
        requireActiveDeal(summary.ticketId());
        validateResponseItems(current, request.items());

        FactoryQuoteDto saved;
        if (Set.of(FactoryQuoteStatus.DRAFT, FactoryQuoteStatus.REQUESTED).contains(current.status())) {
            int rows = quotes.updateFirstResponse(quoteId, request.supplierQuoteRef(), request.defaultCurrency(),
                request.paymentTerms(), request.leadTimeText(), request.revisionReason(), request.negotiationNote());
            if (rows == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Factory quote was changed by another user");
            }
            quotes.replaceResponseItems(quoteId, request.items());
            saved = requireQuote(quoteId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED, summary.status(), summary.status(),
                "Factory response received from " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED,
                "ใบขอราคา " + summary.requestCode() + " ได้รับราคาจาก " + current.factoryName());
        } else if (Set.of(FactoryQuoteStatus.RESPONSE_RECEIVED, FactoryQuoteStatus.NEGOTIATING,
                FactoryQuoteStatus.READY_FOR_COSTING).contains(current.status())) {
            quotes.supersede(current.id());
            long newId = quotes.createRevision(current, request.supplierQuoteRef(), request.defaultCurrency(),
                request.paymentTerms(), request.leadTimeText(), request.revisionReason(), request.negotiationNote(), actor.id());
            quotes.replaceResponseItems(newId, request.items());
            quotes.markOpenCostingsStale(summary.id(), "Factory quote revision changed");
            if (PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
                int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.READY_FOR_CEO_REVIEW,
                    PricingRequestStatus.COSTING_IN_PROGRESS, null, null);
                if (transitioned == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
                }
            }
            saved = requireQuote(newId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_REVISED, summary.status(), summary.status(),
                "Factory response revised for " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_REVISED,
                "ใบขอราคา " + summary.requestCode() + " มีราคาฉบับปรับปรุงจาก " + current.factoryName());
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "Factory quote cannot receive a response in status " + current.status());
        }
        return saved;
    }

    @Transactional
    public FactoryQuoteDto startNegotiation(long quoteId, StartNegotiationRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, RESPONSE_STATUSES);
        requireActiveDeal(summary.ticketId());
        int rows = quotes.startNegotiation(quoteId, request.note());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a current received response can enter negotiation");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_NEGOTIATION_STARTED, summary.status(), summary.status(),
            request.note());
        notifyCeo(summary, PricingRequestEventKind.FACTORY_NEGOTIATION_STARTED,
            "ใบขอราคา " + summary.requestCode() + " เริ่มเจรจากับ " + quote.factoryName());
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto markReadyForCosting(long quoteId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, RESPONSE_STATUSES);
        requireActiveDeal(summary.ticketId());
        int rows = quotes.markReady(quoteId);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Current response must have raw prices before it can be marked ready");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_READY_FOR_COSTING, summary.status(), summary.status(),
            "Factory response ready for costing: " + quote.factoryName());
        notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_READY_FOR_COSTING,
            "ใบขอราคา " + summary.requestCode() + " พร้อมคำนวณต้นทุนสำหรับ " + quote.factoryName());
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto markNotAvailable(long quoteId, MarkNotAvailableRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, MUTABLE_STATUSES);
        requireActiveDeal(summary.ticketId());
        int rows = quotes.markNotAvailable(quoteId, request.reason(), actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Factory quote cannot be marked unavailable in its current status");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_NOT_AVAILABLE, summary.status(), summary.status(),
            request.reason());
        notifyCeo(summary, PricingRequestEventKind.FACTORY_NOT_AVAILABLE,
            "ใบขอราคา " + summary.requestCode() + " โรงงานไม่สามารถเสนอราคาได้: " + quote.factoryName());
        return requireQuote(quoteId);
    }

    private Map<String, List<PricingRequestItemDto>> groupByFactory(List<PricingRequestItemDto> items) {
        Map<String, List<PricingRequestItemDto>> byFactory = new LinkedHashMap<>();
        for (PricingRequestItemDto item : items.stream().sorted(Comparator.comparingInt(PricingRequestItemDto::sortOrder)).toList()) {
            String factoryName = firstText(item.resolvedFactoryName(), item.factory());
            if (factoryName == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pricing request item " + item.id() + " has no resolved factory");
            }
            byFactory.computeIfAbsent(factoryName, ignored -> new ArrayList<>()).add(item);
        }
        return byFactory;
    }

    private void validateResponseItems(FactoryQuoteDto quote, List<ReceiveFactoryQuoteItemRequest> responseItems) {
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        Map<Long, PricingRequestItemDto> requestItemsById = new HashMap<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(summary.id())) {
            requestItemsById.put(item.id(), item);
        }
        Set<Long> expected = quote.items().stream()
            .map(item -> item.pricingRequestItemId())
            .collect(java.util.stream.Collectors.toSet());
        Set<Long> received = responseItems.stream()
            .map(ReceiveFactoryQuoteItemRequest::pricingRequestItemId)
            .collect(java.util.stream.Collectors.toSet());
        if (!received.equals(expected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Factory response must include exactly the quote's request items");
        }
        for (ReceiveFactoryQuoteItemRequest responseItem : responseItems) {
            PricingRequestItemDto requestItem = requestItemsById.get(responseItem.pricingRequestItemId());
            if (requestItem == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Factory response item does not belong to this pricing request");
            }
            String itemFactory = firstText(requestItem.resolvedFactoryName(), requestItem.factory());
            if (!quote.factoryName().equals(itemFactory)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Factory response item belongs to a different factory");
            }
        }
    }

    private PricingRequestSummaryDto requirePricingRequest(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pricing request not found"));
    }

    private FactoryQuoteDto requireQuote(long quoteId) {
        return quotes.find(quoteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory quote not found"));
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"))
            .summary();
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "Parent deal must be ACTIVE");
        }
    }

    private void requireMutablePricingRequest(PricingRequestSummaryDto summary, Set<String> allowedStatuses) {
        if (!allowedStatuses.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Pricing request status '" + summary.status() + "' cannot be modified by factory quote actions");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void addEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind,
                          String fromStatus, String toStatus, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind, fromStatus, toStatus,
            message, null);
    }

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRole("ceo", summary.ticketId(), type, message);
    }

    private String emailBody(PricingRequestSummaryDto summary, String factoryName, List<PricingRequestItemDto> items) {
        StringBuilder body = new StringBuilder();
        body.append("Pricing request ").append(summary.requestCode()).append("\n");
        body.append("Factory: ").append(factoryName).append("\n\n");
        for (PricingRequestItemDto item : items) {
            body.append("- ")
                .append(safe(item.brand(), ""))
                .append(" ")
                .append(safe(item.model(), item.productDescription()))
                .append(" ")
                .append(safe(item.size(), ""))
                .append(" qty ").append(item.requestedQty()).append(" ").append(item.requestedUnit())
                .append("\n");
        }
        if (summary.note() != null && !summary.note().isBlank()) {
            body.append("\nSales note: ").append(summary.note()).append("\n");
        }
        return body.toString();
    }

    private String safe(String first, String fallback) {
        return first != null && !first.isBlank() ? first : (fallback == null ? "" : fallback);
    }

    private String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }
}
