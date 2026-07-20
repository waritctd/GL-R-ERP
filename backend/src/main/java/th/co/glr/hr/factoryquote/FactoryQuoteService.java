package th.co.glr.hr.factoryquote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository.FactoryQuoteEmailDispatchDto;
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
import th.co.glr.hr.pricingrequest.UnitBasis;
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
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);
    private static final Set<String> MUTABLE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);
    // Review remediation (COMMIT 4): deliberately NARROWER than MUTABLE_STATUSES —
    // READY_FOR_CEO_REVIEW is excluded on purpose. Uploading new supplementary evidence while
    // the request awaits CEO review is still fine (MUTABLE_STATUSES, above); DELETING evidence
    // once the request has reached that far is not, because a submitted costing may already
    // depend on it. See deleteAttachment's own two additional per-quote/per-costing guards below
    // for the remaining cases MUTABLE_STATUSES membership alone cannot catch (READY_FOR_COSTING,
    // and a SUBMITTED costing referencing this exact quote revision).
    private static final Set<String> ATTACHMENT_DELETE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.COSTING_IN_PROGRESS);

    private final FactoryQuoteRepository quotes;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final FactoryConfigRepository factoryConfigs;
    private final FactoryEmailService factoryEmail;
    private final NotificationRepository notifications;
    private final FileStorageService fileStorage;
    private final AppProperties properties;

    public FactoryQuoteService(FactoryQuoteRepository quotes, PricingRequestRepository pricingRequests,
                               TicketRepository tickets, FactoryConfigRepository factoryConfigs,
                               FactoryEmailService factoryEmail, NotificationRepository notifications,
                               FileStorageService fileStorage, AppProperties properties) {
        this.quotes = quotes;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.factoryConfigs = factoryConfigs;
        this.factoryEmail = factoryEmail;
        this.notifications = notifications;
        this.fileStorage = fileStorage;
        this.properties = properties;
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

    /**
     * Enqueue-only: validates and commits a {@code PENDING} dispatch row, then returns
     * immediately. {@link FactoryQuoteEmailDispatchWorker} sends the email and finalizes the
     * quote/pricing-request state out-of-band, in a separate transaction, so an app crash between
     * "email accepted by the provider" and "quote/pricing-request updated" cannot happen inside
     * the HTTP request/response cycle — see {@link #finalizeDispatch} for how a crash mid-flight
     * is recovered instead of stranding the dispatch. This method itself never calls the mail
     * provider.
     */
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
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        dispatchForSend(quoteId, clientRequestId, emailTo, subject, body, actor);
        return requireQuote(quoteId);
    }

    /**
     * One worker-tick's candidate dispatch ids (PENDING, backed-off FAILED due for retry, or
     * stale SENDING past the reclaim timeout — all under the configured attempt cap).
     */
    public List<Long> claimableDispatchIds() {
        AppProperties.FactoryQuoteDispatch config = properties.getFactoryQuoteDispatch();
        return quotes.findClaimableDispatchIds(config.getBatchSize(), config.getReclaimTimeoutSeconds(),
            config.getMaxAttempts());
    }

    /**
     * Atomically claims one dispatch row for this worker. Returns false if another worker (or a
     * concurrent tick of this one) already claimed it, or it is no longer eligible (already
     * SENT, past the attempt cap, or not yet due). Two workers racing the same id: only one
     * UPDATE affects a row, courtesy of Postgres row locking plus the WHERE recheck — see
     * {@link FactoryQuoteRepository#claimDispatch}.
     */
    public boolean claimDispatch(long dispatchId) {
        AppProperties.FactoryQuoteDispatch config = properties.getFactoryQuoteDispatch();
        return quotes.claimDispatch(dispatchId, config.getReclaimTimeoutSeconds(), config.getMaxAttempts()) > 0;
    }

    /**
     * Calls the mail provider for a claimed dispatch, unless a previous attempt already got a
     * provider acknowledgement recorded (a crash between "provider accepted" and finalize would
     * otherwise cause a reclaim to resend the email to the factory). Throws on provider failure;
     * the caller ({@link #processDispatch}) is responsible for marking the dispatch FAILED.
     */
    public void attemptSend(long dispatchId) {
        FactoryQuoteEmailDispatchDto dispatch = quotes.findDispatch(dispatchId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory quote dispatch not found"));
        if (dispatch.providerMessageId() != null) {
            return;
        }
        FactoryQuoteDto quote = requireQuote(dispatch.factoryQuoteId());
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        // Resolved fresh right here, not at send()-time enqueue: Import can keep toggling
        // include_in_factory_email on Pricing Request attachments up until the worker actually
        // calls the mail provider, and this is that moment. See FactoryEmailService's javadoc for
        // why an empty list keeps the original plain-text SimpleMailMessage path unchanged.
        List<FactoryEmailService.EmailAttachment> emailAttachments = pricingRequests
            .findIncludedInFactoryEmailAttachmentFiles(summary.id()).stream()
            .map(file -> new FactoryEmailService.EmailAttachment(file.fileName(), file.filePath(), file.mimeType()))
            .toList();
        String messageId = factoryEmail.send(summary.ticketId(), quote.factoryName(), dispatch.emailTo(),
            dispatch.emailSubject(), dispatch.emailBody(), emailAttachments);
        // "sent-<id>" is a local fallback marker, not a real provider id: FactoryEmailService
        // always returns one today, but a null return must still count as "sent, don't resend" —
        // this is the field's whole purpose, not merely audit metadata.
        quotes.recordProviderMessageId(dispatchId, messageId != null ? messageId : ("sent-" + dispatchId));
    }

    /**
     * Idempotently completes a dispatch whose email has already gone out: quote to REQUESTED,
     * the pricing request's status transition, the audit event, the CEO notification, and the
     * dispatch's own SENT/finalized_at — all in one transaction.
     *
     * <p><b>Must only be entered through the Spring proxy</b> — i.e. called on the injected
     * {@code FactoryQuoteService} bean from outside this class (as {@link
     * FactoryQuoteEmailDispatchWorker} does), never as a same-class self-invocation such as
     * {@code this.finalizeDispatch(...)} from another method here. A self-invocation bypasses the
     * AOP proxy entirely, silently disabling {@code @Transactional}: every write below would then
     * auto-commit individually instead of atomically, reopening the exact crash window this method
     * exists to close. See {@link #processDispatch}'s javadoc — that method's self-invocation of
     * this one is a known, deliberately test-only exception to this rule.
     *
     * <p>Two independent guards protect against duplicating the event/notification pair:
     * <ul>
     *   <li>{@code finalizedAt != null} short-circuits the whole method for a dispatch that is
     *       already fully done — cheap, and correct whenever finalize genuinely runs as one
     *       transaction (a crash before commit rolls back everything, including the otherwise
     *       self-idempotent quote-status/pricing-request writes below, so there is no reachable
     *       in-between state where the event exists but {@code finalizedAt} does not).
     *   <li>{@code existsEventForDispatch} additionally guards the event/notification insert
     *       itself, keyed on this dispatch's id via the event's {@code metadata} column. This is
     *       deliberate defense-in-depth for a scenario the first guard does NOT cover: finalize
     *       being entered non-transactionally after all (self-invocation reintroduced by a future
     *       refactor, or any other path that leaves {@code finalizedAt} null after the event was
     *       already committed). Without it, a retry in that state would re-insert the event and
     *       re-notify the CEO — see {@code
     *       PricingFactoryQuoteCostingIntegrationTest#dispatchFinalizeSkipsDuplicateEventAndNotificationWhenReRunAfterEventAlreadyWritten}.
     * </ul>
     */
    @Transactional
    public FactoryQuoteDto finalizeDispatch(long dispatchId) {
        FactoryQuoteEmailDispatchDto dispatch = quotes.findDispatch(dispatchId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory quote dispatch not found"));
        FactoryQuoteDto quote = requireQuote(dispatch.factoryQuoteId());
        if (dispatch.finalizedAt() != null) {
            return quote;
        }

        quotes.markRequested(quote.id(), dispatch.emailTo(), dispatch.emailSubject(), dispatch.emailBody(),
            dispatch.createdBy() == null ? 0L : dispatch.createdBy());
        FactoryQuoteDto requestedQuote = requireQuote(quote.id());
        if (!FactoryQuoteStatus.REQUESTED.equals(requestedQuote.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Factory quote " + quote.id() + " cannot be finalized from status " + requestedQuote.status());
        }

        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        if (PricingRequestStatus.IMPORT_REVIEWING.equals(summary.status())
                || PricingRequestStatus.COSTING_IN_PROGRESS.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), summary.status(),
                PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
            }
        }
        PricingRequestSummaryDto currentSummary = requirePricingRequest(quote.pricingRequestId());

        if (!pricingRequests.existsEventForDispatch(currentSummary.id(),
                PricingRequestEventKind.FACTORY_EMAIL_SENT, dispatchId)) {
            addEventForDispatch(currentSummary, dispatch.createdBy(), PricingRequestEventKind.FACTORY_EMAIL_SENT,
                summary.status(), currentSummary.status(), "Factory request sent to " + quote.factoryName(),
                dispatchId);
            notifyCeo(currentSummary, PricingRequestEventKind.FACTORY_EMAIL_SENT,
                "ใบขอราคา " + currentSummary.requestCode() + " ส่งคำขอโรงงาน " + quote.factoryName());
        }

        quotes.markDispatchFinalized(dispatchId);
        return requireQuote(quote.id());
    }

    /** Records a failed attempt with exponential-ish backoff; stays claimable under the attempt cap. */
    public void markDispatchFailed(long dispatchId, String message) {
        FactoryQuoteEmailDispatchDto dispatch = quotes.findDispatch(dispatchId).orElse(null);
        int attemptCount = dispatch == null ? 1 : Math.max(1, dispatch.attemptCount());
        AppProperties.FactoryQuoteDispatch config = properties.getFactoryQuoteDispatch();
        int backoffSeconds = config.getBackoffBaseSeconds() * attemptCount;
        quotes.markDispatchFailedWithBackoff(dispatchId, message == null ? "unknown error" : message, backoffSeconds);
    }

    /**
     * TEST-ONLY convenience wrapper — <b>not</b> the production path. It calls {@link
     * #finalizeDispatch} as a same-class self-invocation ({@code this.finalizeDispatch(...)}),
     * which bypasses the Spring AOP proxy and silently disables {@code @Transactional} whenever
     * this object IS a proxy (i.e. in production, where Spring manages this bean). That is
     * harmless only in {@code AbstractPostgresIntegrationTest}, which constructs {@code
     * FactoryQuoteService} directly with {@code new} — there is no proxy to bypass there, so this
     * method's behavior is identical to calling the three steps separately in that harness, and it
     * exists purely to keep test call sites short.
     *
     * <p>The real production path is {@link FactoryQuoteEmailDispatchWorker#pollAndDispatch()},
     * which calls {@code claimDispatch}/{@code attemptSend}/{@code finalizeDispatch} as three
     * separate calls into the injected (proxied) service bean, so {@code finalizeDispatch}'s
     * {@code @Transactional} genuinely applies there. Do not call this method from production code.
     */
    public void processDispatch(long dispatchId) {
        if (!claimDispatch(dispatchId)) {
            return;
        }
        try {
            attemptSend(dispatchId);
            finalizeDispatch(dispatchId);
        } catch (RuntimeException e) {
            markDispatchFailed(dispatchId, e.getMessage());
        }
    }

    @Transactional
    public FactoryQuoteAttachmentDto uploadAttachment(long quoteId, MultipartFile file, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, MUTABLE_STATUSES);
        requireActiveDeal(summary.ticketId());
        FileStorageService.StoredFile stored = fileStorage.store("factory-quotes", quoteId, file, Set.of());
        FactoryQuoteAttachmentDto attachment = quotes.saveAttachment(quoteId, stored.fileName(), stored.filePath(),
            stored.mimeType(), stored.fileSize(), actor.id());
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED, summary.status(), summary.status(),
            "Factory quote attachment uploaded for " + quote.factoryName() + ": " + attachment.fileName());
        return attachment;
    }

    public FactoryQuoteAttachmentDto getAttachment(long attachmentId, UserPrincipal actor) {
        requireRole(actor, RAW_QUOTE_ROLES);
        FactoryQuoteAttachmentDto attachment = quotes.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory quote attachment not found"));
        requireQuote(attachment.factoryQuoteId());
        return attachment;
    }

    public String attachmentFilePath(long attachmentId, UserPrincipal actor) {
        getAttachment(attachmentId, actor);
        String path = quotes.findAttachmentFilePath(attachmentId);
        if (path == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Factory quote attachment file not found");
        }
        return path;
    }

    /**
     * Review remediation (COMMIT 4): the original version permitted deletion whenever the parent
     * pricing request was in {@code MUTABLE_STATUSES} (which included {@code
     * READY_FOR_CEO_REVIEW}) and then physically removed the row and the file — so evidence
     * backing an already-submitted costing could be destroyed outright. Now:
     * <ol>
     *   <li>{@code READY_FOR_CEO_REVIEW} is excluded from the permitted pricing-request statuses
     *       ({@link #ATTACHMENT_DELETE_STATUSES}, deliberately narrower than the upload gate's
     *       {@link #MUTABLE_STATUSES}).</li>
     *   <li>Deletion is refused outright once the quote ITSELF has reached {@code
     *       READY_FOR_COSTING} — a quote can reach that status while its parent pricing request
     *       is still, say, {@code COSTING_IN_PROGRESS} for a DIFFERENT factory, so the
     *       pricing-request-level gate above cannot catch this case alone.</li>
     *   <li>Deletion is refused outright if this exact quote revision is referenced by any {@code
     *       SUBMITTED} costing ({@link FactoryQuoteRepository#existsSubmittedCostingReferencingQuote}) —
     *       covers a quote that was READY_FOR_COSTING, got superseded by a later revision, but
     *       whose OLD revision a costing still points at.</li>
     * </ol>
     * Otherwise, deletion is an audited tombstone ({@link FactoryQuoteRepository#tombstoneAttachment}):
     * the row and the file on disk are both kept, only {@code deleted_at}/{@code deleted_by}/
     * {@code delete_reason} are recorded. Supplemental supplier evidence is append-only.
     */
    @Transactional
    public void deleteAttachment(long attachmentId, String reason, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteAttachmentDto attachment = quotes.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Factory quote attachment not found"));
        if (attachment.deletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Factory quote attachment was already deleted");
        }
        FactoryQuoteDto quote = requireQuote(attachment.factoryQuoteId());
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, ATTACHMENT_DELETE_STATUSES);
        if (FactoryQuoteStatus.READY_FOR_COSTING.equals(quote.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Factory quote attachment cannot be deleted once the quote is ready for costing");
        }
        if (quotes.existsSubmittedCostingReferencingQuote(quote.id())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Factory quote attachment cannot be deleted: referenced by a submitted costing");
        }
        int rows = quotes.tombstoneAttachment(attachmentId, actor.id(), reason);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Factory quote attachment could not be deleted");
        }
    }

    private FactoryQuoteEmailDispatchDto dispatchForSend(long quoteId, String clientRequestId, String emailTo,
                                                         String subject, String body, UserPrincipal actor) {
        FactoryQuoteEmailDispatchDto existingForClient = quotes.findDispatchByClientRequest(actor.id(), clientRequestId)
            .orElse(null);
        if (existingForClient != null) {
            if (existingForClient.factoryQuoteId() != quoteId) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId has already been used for another factory quote");
            }
            return existingForClient;
        }
        FactoryQuoteEmailDispatchDto active = quotes.findActiveDispatch(quoteId).orElse(null);
        if (active != null) {
            return active;
        }
        return quotes.createDispatch(quoteId, clientRequestId, emailTo, subject, body, actor.id());
    }


    @Transactional
    public FactoryQuoteDto receive(long quoteId, ReceiveFactoryQuoteRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        String clientRequestId = validateClientRequestId(request.clientRequestId());
        // Serialize concurrent calls that share the same idempotency key so a racing
        // retry blocks until the first attempt's receipt is committed and visible,
        // rather than both attempts racing into duplicate mutations. Held for the whole
        // (Spring-proxied, @Transactional) method via the transaction-scoped advisory lock.
        quotes.lockResponseIdempotencyKey(actor.id(), clientRequestId);
        FactoryQuoteRepository.FactoryQuoteResponseReceiptDto existingReceipt =
            quotes.findResponseReceipt(actor.id(), clientRequestId).orElse(null);
        FactoryQuoteDto current = requireQuote(quoteId);
        if (existingReceipt != null) {
            FactoryQuoteDto receiptQuote = requireQuote(existingReceipt.factoryQuoteId());
            // Compare the QUOTE CHAIN, not the pricing request: a pricing request has one
            // quote per factory, so on a revision replay the caller retries with the OLD
            // quoteId while the receipt now points at the NEW revision row — those must
            // match (same chain) and return idempotently. A key reused against a DIFFERENT
            // factory's quote in the SAME pricing request must 409, not silently return the
            // wrong factory's quote (which would discard this call's response with a 200).
            if (chainId(receiptQuote) != chainId(current)) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "clientRequestId has already been used for another factory quote");
            }
            return receiptQuote;
        }
        if (!current.current()) {
            throw new ApiException(HttpStatus.CONFLICT, "Only the current factory quote revision can receive a response");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(current.pricingRequestId());
        requireMutablePricingRequest(summary, RESPONSE_STATUSES);
        requireActiveDeal(summary.ticketId());
        List<ReceiveFactoryQuoteItemRequest> normalizedItems = validateAndNormalizeResponseItems(current, request.items());

        FactoryQuoteDto saved;
        long respondedQuoteId;
        if (Set.of(FactoryQuoteStatus.DRAFT, FactoryQuoteStatus.REQUESTED).contains(current.status())) {
            int rows = quotes.updateFirstResponse(quoteId, request.supplierQuoteRef(), request.defaultCurrency(),
                request.paymentTerms(), request.leadTimeText(), request.revisionReason(), request.negotiationNote());
            if (rows == 0) {
                // Someone else already moved this quote out of DRAFT/REQUESTED. If that was a
                // concurrent replay of THIS SAME idempotency key that won the race, return its
                // result rather than surfacing a conflict to a caller that is, from Import's
                // point of view, just retrying a lost response.
                FactoryQuoteRepository.FactoryQuoteResponseReceiptDto raced =
                    quotes.findResponseReceipt(actor.id(), clientRequestId).orElse(null);
                if (raced != null) {
                    FactoryQuoteDto racedQuote = requireQuote(raced.factoryQuoteId());
                    if (chainId(racedQuote) == chainId(current)) {
                        return racedQuote;
                    }
                }
                throw new ApiException(HttpStatus.CONFLICT, "Factory quote was changed by another user");
            }
            quotes.replaceResponseItems(quoteId, normalizedItems);
            String toStatus = summary.status();
            // A first/partial factory response only confirms the request is awaiting
            // (or still awaiting) factory replies. COSTING_IN_PROGRESS is entered only
            // by PricingCostingService.createDraft(), once every request item's factory
            // has a current READY_FOR_COSTING quote — otherwise a multi-factory request
            // would flip to "costing in progress" the moment the first factory answers,
            // while other factories are still pending.
            if (PricingRequestStatus.IMPORT_REVIEWING.equals(summary.status())) {
                int transitioned = pricingRequests.transition(summary.id(), summary.status(),
                    PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
                if (transitioned == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
                }
                toStatus = PricingRequestStatus.AWAITING_FACTORY_RESPONSE;
            }
            respondedQuoteId = quoteId;
            saved = requireQuote(quoteId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED, summary.status(), toStatus,
                "Factory response received from " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED,
                "ใบขอราคา " + summary.requestCode() + " ได้รับราคาจาก " + current.factoryName());
        } else if (Set.of(FactoryQuoteStatus.RESPONSE_RECEIVED, FactoryQuoteStatus.NEGOTIATING,
                FactoryQuoteStatus.READY_FOR_COSTING).contains(current.status())) {
            quotes.supersede(current.id());
            long newId = quotes.createRevision(current, request.supplierQuoteRef(), request.defaultCurrency(),
                request.paymentTerms(), request.leadTimeText(), request.revisionReason(), request.negotiationNote(), actor.id());
            quotes.replaceResponseItems(newId, normalizedItems);
            quotes.markOpenCostingsStale(summary.id(), "Factory quote revision changed");
            String toStatus = summary.status();
            if (PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
                int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.READY_FOR_CEO_REVIEW,
                    PricingRequestStatus.COSTING_IN_PROGRESS, null, null);
                if (transitioned == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "Pricing request was changed by another user");
                }
                toStatus = PricingRequestStatus.COSTING_IN_PROGRESS;
            }
            respondedQuoteId = newId;
            saved = requireQuote(newId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_REVISED, summary.status(), toStatus,
                "Factory response revised for " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_REVISED,
                "ใบขอราคา " + summary.requestCode() + " มีราคาฉบับปรับปรุงจาก " + current.factoryName());
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "Factory quote cannot receive a response in status " + current.status());
        }
        // ON CONFLICT DO NOTHING never aborts the transaction (unlike letting the unique index
        // throw and catching it): a duplicate key here means a concurrent racer already recorded
        // the receipt first, so fetch and return its result instead of ours.
        Optional<FactoryQuoteRepository.FactoryQuoteResponseReceiptDto> insertedReceipt =
            quotes.createResponseReceiptIfAbsent(respondedQuoteId, actor.id(), clientRequestId);
        if (insertedReceipt.isEmpty()) {
            FactoryQuoteRepository.FactoryQuoteResponseReceiptDto raced =
                quotes.findResponseReceipt(actor.id(), clientRequestId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "clientRequestId conflict could not be resolved"));
            return requireQuote(raced.factoryQuoteId());
        }
        return saved;
    }

    private long chainId(FactoryQuoteDto quote) {
        Long root = quote.rootFactoryQuoteId();
        return root != null ? root : quote.id();
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

    private List<ReceiveFactoryQuoteItemRequest> validateAndNormalizeResponseItems(
        FactoryQuoteDto quote,
        List<ReceiveFactoryQuoteItemRequest> responseItems
    ) {
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
        List<ReceiveFactoryQuoteItemRequest> normalized = new ArrayList<>();
        for (ReceiveFactoryQuoteItemRequest responseItem : responseItems) {
            PricingRequestItemDto requestItem = requestItemsById.get(responseItem.pricingRequestItemId());
            if (requestItem == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Factory response item does not belong to this pricing request");
            }
            String itemFactory = firstText(requestItem.resolvedFactoryName(), requestItem.factory());
            if (!quote.factoryName().equals(itemFactory)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Factory response item belongs to a different factory");
            }
            String unitBasis = UnitBasis.canonicalize(responseItem.unitBasis(), "Factory quote unit");
            String quotedUnit = UnitBasis.canonicalize(responseItem.quotedUnit(), "Factory quote unit");
            if (UnitBasis.PER_BOX.equals(unitBasis) && responseItem.piecesPerBox() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PER_BOX factory response items require piecesPerBox");
            }
            if (UnitBasis.PER_SQM.equals(unitBasis) && responseItem.sqmPerUnit() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PER_SQM factory response items require sqmPerUnit");
            }
            if (UnitBasis.PER_LINEAR_M.equals(unitBasis) && responseItem.linearMPerUnit() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PER_LINEAR_M factory response items require linearMPerUnit");
            }
            normalized.add(new ReceiveFactoryQuoteItemRequest(
                responseItem.pricingRequestItemId(),
                responseItem.supplierProductCode(),
                responseItem.supplierProductDescription(),
                responseItem.quotedQuantity(),
                quotedUnit,
                unitBasis,
                responseItem.rawUnitPrice(),
                responseItem.currency(),
                responseItem.minimumOrderQuantity(),
                responseItem.sqmPerUnit(),
                responseItem.piecesPerBox(),
                responseItem.linearMPerUnit(),
                responseItem.leadTimeText(),
                responseItem.availabilityNote(),
                responseItem.lineNote()
            ));
        }
        return normalized;
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
        addEvent(summary, actor.id(), actor.name(), kind, fromStatus, toStatus, message);
    }

    /** Worker-context variant: no UserPrincipal, just the (nullable) employee id that enqueued the dispatch. */
    private void addEvent(PricingRequestSummaryDto summary, Long actorId, String actorName, String kind,
                          String fromStatus, String toStatus, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actorId, actorName, kind, fromStatus, toStatus,
            message, null);
    }

    /**
     * Worker-context event tagged with its dispatch id in {@code metadata}, so {@code
     * PricingRequestRepository.existsEventForDispatch} can detect a re-run and skip inserting a
     * duplicate — see {@link #finalizeDispatch}'s javadoc for why this guard exists alongside the
     * {@code finalizedAt} check.
     */
    private void addEventForDispatch(PricingRequestSummaryDto summary, Long actorId, String kind,
                                     String fromStatus, String toStatus, String message, long dispatchId) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actorId, null, kind, fromStatus, toStatus,
            message, "{\"dispatchId\":" + dispatchId + "}");
    }

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), type, message);
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

    private String validateClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a UUID");
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId must be a UUID");
        }
    }
}
