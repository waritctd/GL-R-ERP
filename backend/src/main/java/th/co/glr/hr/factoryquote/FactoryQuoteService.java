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
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteAttachmentDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkNotAvailableRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.UpdateFactoryQuoteDraftRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
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
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
    private static final Set<String> RESPONSE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
        PricingRequestStatus.READY_FOR_CEO_REVIEW);
    private static final Set<String> MUTABLE_STATUSES = Set.of(
        PricingRequestStatus.IMPORT_REVIEWING,
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
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
        PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

    private final FactoryQuoteRepository quotes;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final FactoryConfigRepository factoryConfigs;
    private final NotificationRepository notifications;
    private final FileStorageService fileStorage;
    private final LandedCostCalculator landedCosts;

    public FactoryQuoteService(FactoryQuoteRepository quotes, PricingRequestRepository pricingRequests,
                               TicketRepository tickets, FactoryConfigRepository factoryConfigs,
                               NotificationRepository notifications, FileStorageService fileStorage,
                               LandedCostCalculator landedCosts) {
        this.quotes = quotes;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.factoryConfigs = factoryConfigs;
        this.notifications = notifications;
        this.fileStorage = fileStorage;
        this.landedCosts = landedCosts;
    }

    /**
     * Generates drafts for every factory that RESOLVES, instead of blocking the whole batch on
     * lines that do not (owner decision, manual-RFQ redesign). Only when NO line resolves to a
     * factory does this throw — generating nothing while still returning 200 would be a silent
     * no-op, which is worse than the 422 it replaces. Partially-unresolved requests are common
     * (Import routes factories to lines one at a time), and used to force a second, otherwise
     * pointless round trip once the last line was fixed; now a re-run after filling the gap
     * completes the set.
     *
     * <p><b>A factory that already has a current quote is not simply skipped (BLOCKER 1 fix).</b>
     * This Javadoc used to claim the only silent-no-op this method could produce was the
     * all-unresolved case above, thrown as a 422 — that was wrong. "Every resolved factory
     * already has a quote" was a SECOND silent no-op: the old code skipped such a factory
     * unconditionally, which permanently stranded any line resolved to it AFTER its draft was
     * first created — this method would skip that factory on every future re-run, {@code
     * PricingRequestService#setItemFactory} would 409 on any attempt to move the line elsewhere,
     * and {@code receive}'s item-set check meant the existing quote could never absorb it either.
     * Now: while the current quote is still {@code DRAFT}, newly-resolved items are ADDED to it
     * (see {@link #addNewlyResolvedItemsToExistingDraft}); only once the quote has advanced past
     * DRAFT — REQUESTED and beyond, meaning a human has already sent it or the factory has
     * already answered — is it left alone, because its item set is then exactly what was
     * sent/received and must not silently change under the request's owner. {@code
     * PricingRequestService#setItemFactory} independently refuses to route a NEW line onto a
     * factory in that state, so the two guards protect the same invariant from both directions.
     */
    @Transactional
    public List<FactoryQuoteDto> generateDrafts(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        if (!DRAFT_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคาต้องอยู่ระหว่างการตรวจสอบของฝ่ายนำเข้าก่อนจึงจะสร้างร่างอีเมลราคาโรงงานได้");
        }
        requireActiveDeal(summary.ticketId());
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        Map<String, List<PricingRequestItemDto>> byFactory = groupByFactory(items);
        if (byFactory.isEmpty()) {
            List<String> missing = unresolvedLineDescriptions(items);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ยังไม่ได้ระบุโรงงานสำหรับ " + String.join(", ", missing)
                    + " — กรุณาระบุโรงงานในรายการสินค้าก่อนสร้างร่างอีเมล");
        }
        for (Map.Entry<String, List<PricingRequestItemDto>> entry : byFactory.entrySet()) {
            String factoryName = entry.getKey();
            Optional<FactoryQuoteDto> currentQuote = quotes.findCurrentByFactory(pricingRequestId, factoryName);
            if (currentQuote.isPresent()) {
                addNewlyResolvedItemsToExistingDraft(summary, actor, factoryName, currentQuote.get(), entry.getValue());
                continue;
            }
            FactoryConfigDto config = factoryConfigs.findByName(factoryName).orElse(null);
            String emailTo = config == null ? null : config.email();
            String subject = "Pricing request " + summary.requestCode() + " - " + safe(summary.projectName(), summary.customerName());
            String body = emailBody(summary, factoryName, entry.getValue(), actor);
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
                "คำขอราคา " + summary.requestCode() + " สร้างร่างอีเมลโรงงาน " + factoryName);
        }
        return list(pricingRequestId, actor);
    }

    /**
     * BLOCKER 1a (review remediation): the other half of {@link #generateDrafts}' fix for a
     * factory whose current quote already exists. Adds whichever of {@code resolvedItems} are not
     * already on {@code currentQuote} — and ONLY while it is still {@code DRAFT} — instead of the
     * old unconditional skip that stranded them. {@code resolvedItems} is the FULL set of items
     * currently resolved to this factory (old and new alike; see {@link #groupByFactory}), so
     * filtering against the quote's existing item ids is what isolates the genuinely new ones.
     *
     * <p>A quote already past DRAFT (REQUESTED and beyond) is left untouched — its item set is
     * exactly what was sent to or received from the factory, and {@code
     * PricingRequestService#setItemFactory} independently refuses to route a new line onto a
     * factory in that state, so this method should rarely even see one; it stays defensive here
     * regardless (e.g. a line resolved via the catalog snapshot rather than setItemFactory).
     */
    private void addNewlyResolvedItemsToExistingDraft(PricingRequestSummaryDto summary, UserPrincipal actor,
                                                       String factoryName, FactoryQuoteDto currentQuote,
                                                       List<PricingRequestItemDto> resolvedItems) {
        if (!FactoryQuoteStatus.DRAFT.equals(currentQuote.status())) {
            return;
        }
        Set<Long> alreadyOnQuote = currentQuote.items().stream()
            .map(FactoryQuoteItemDto::pricingRequestItemId)
            .collect(java.util.stream.Collectors.toSet());
        List<Long> newItemIds = resolvedItems.stream()
            .map(PricingRequestItemDto::id)
            .filter(itemId -> !alreadyOnQuote.contains(itemId))
            .toList();
        if (newItemIds.isEmpty()) {
            return;
        }
        quotes.addItemsToDraft(currentQuote.id(), newItemIds);
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_EMAIL_READY, summary.status(), summary.status(),
            "Factory email draft for " + factoryName + " updated with " + newItemIds.size()
                + " newly-routed item(s)");
        notifyCeo(summary, PricingRequestEventKind.FACTORY_EMAIL_READY,
            "คำขอราคา " + summary.requestCode() + " เพิ่มรายการในร่างอีเมลโรงงาน " + factoryName);
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
            throw new ApiException(HttpStatus.CONFLICT, "แก้ไขได้เฉพาะอีเมลราคาโรงงานที่ยังเป็นฉบับร่างเท่านั้น");
        }
        return requireQuote(quoteId);
    }

    /**
     * Manual-only RFQ send (owner decision — the automatic dispatch/outbox path is deleted). This
     * records that a HUMAN has already sent the RFQ email from their own mail client: DRAFT →
     * REQUESTED, persisting emailTo/subject/body/{@code email_sent_at}/{@code sent_by}, advancing
     * the pricing request's status, and raising the same audit event and CEO notification the old
     * dispatch-finalize step used to — all synchronously, in one transaction. Nothing here calls a
     * mail provider, and there is no dispatch row: {@code sales.factory_quote_email_dispatch} is
     * left in place (V163) but no longer written to.
     *
     * <p>The recipient is OPTIONAL — a blank {@code emailTo} no longer 400s, because a human may
     * mark an RFQ sent even when no factory contact email is on file.
     *
     * <p><b>Idempotent by current STATE, not a client-generated key.</b> Calling this again once
     * the quote is already {@code REQUESTED} is a no-op that returns the quote unchanged. The old
     * {@code clientRequestId} idempotency key existed only to protect the enqueue call from being
     * replayed before the out-of-band worker had run; there is no worker to race any more.
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
        int rows = quotes.markRequested(quoteId, emailTo, subject, body, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบเสนอราคาโรงงาน " + quote.id() + " ไม่สามารถส่งได้จากสถานะปัจจุบัน");
        }

        // V140 merged COSTING_IN_PROGRESS into AWAITING_FACTORY_RESPONSE, so the only status
        // still needing promotion here is IMPORT_REVIEWING — mirrors the deleted dispatch
        // finalize step's own transition.
        if (PricingRequestStatus.IMPORT_REVIEWING.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), summary.status(),
                PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
            }
        }
        PricingRequestSummaryDto currentSummary = requirePricingRequest(summary.id());
        addEvent(currentSummary, actor, PricingRequestEventKind.FACTORY_EMAIL_SENT, summary.status(),
            currentSummary.status(), "Factory request sent to " + quote.factoryName());
        notifyCeo(currentSummary, PricingRequestEventKind.FACTORY_EMAIL_SENT,
            "คำขอราคา " + currentSummary.requestCode() + " ส่งคำขอโรงงาน " + quote.factoryName());
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteAttachmentDto uploadAttachment(long quoteId, MultipartFile file, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, MUTABLE_STATUSES);
        requireActiveDeal(summary.ticketId());
        // V134 storage-durability fix: this evidence file goes straight to the database now -- see
        // FileStorageService#storeInDatabase's javadoc.
        FileStorageService.StoredContent stored = fileStorage.storeInDatabase("factory-quotes", quoteId, file, Set.of());
        FactoryQuoteAttachmentDto attachment = quotes.saveAttachmentWithContent(quoteId, stored.fileName(),
            stored.storageKey(), stored.mimeType(), stored.fileSize(), actor.id(), stored.content());
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED, summary.status(), summary.status(),
            "Factory quote attachment uploaded for " + quote.factoryName() + ": " + attachment.fileName());
        return attachment;
    }

    public FactoryQuoteAttachmentDto getAttachment(long attachmentId, UserPrincipal actor) {
        requireRole(actor, RAW_QUOTE_ROLES);
        FactoryQuoteAttachmentDto attachment = quotes.findAttachment(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบราคาโรงงานนี้"));
        requireQuote(attachment.factoryQuoteId());
        return attachment;
    }

    /**
     * V134 storage-durability fix: resolves the attachment's storage location AND confirms its
     * bytes are actually available (DATABASE, or DISK_LEGACY with a file that still resolves),
     * throwing 410 GONE otherwise -- checked only AFTER {@link #getAttachment}'s own 404/role
     * checks above, mirroring {@code LeaveService#resolveAttachmentForDownload}'s ordering (see
     * that method's javadoc for why the order itself is a security property).
     */
    public FactoryQuoteRepository.AttachmentFileLocation attachmentFileLocation(long attachmentId, UserPrincipal actor) {
        getAttachment(attachmentId, actor);
        FactoryQuoteRepository.AttachmentFileLocation location = quotes.findAttachmentFileLocation(attachmentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบราคาโรงงานนี้"));
        if (!bytesAvailable(location)) {
            throw new ApiException(HttpStatus.GONE, "ไฟล์เอกสารนี้สูญหายจากระบบจัดเก็บ กรุณาติดต่อฝ่ายบุคคล");
        }
        return location;
    }

    /** Mirrors {@code LeaveService#bytesAvailable} -- see that method's javadoc. */
    private boolean bytesAvailable(FactoryQuoteRepository.AttachmentFileLocation location) {
        return switch (location.storageState()) {
            case "DATABASE" -> true;
            case "DISK_LEGACY" -> fileStorage.existsOnDisk(location.filePath());
            default -> false;
        };
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์แนบราคาโรงงานนี้"));
        if (attachment.deletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ไฟล์แนบนี้ถูกลบไปแล้ว");
        }
        FactoryQuoteDto quote = requireQuote(attachment.factoryQuoteId());
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, ATTACHMENT_DELETE_STATUSES);
        if (FactoryQuoteStatus.READY_FOR_COSTING.equals(quote.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถลบไฟล์แนบได้ เนื่องจากใบเสนอราคาโรงงานพร้อมสำหรับการคำนวณต้นทุนแล้ว");
        }
        if (quotes.existsSubmittedCostingReferencingQuote(quote.id())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ไม่สามารถลบไฟล์แนบนี้ได้ เนื่องจากถูกอ้างอิงโดยการคำนวณต้นทุนที่ส่งไปแล้ว");
        }
        int rows = quotes.tombstoneAttachment(attachmentId, actor.id(), reason);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถลบไฟล์แนบนี้ได้");
        }
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
                    "clientRequestId นี้ถูกใช้ไปแล้วกับใบเสนอราคาโรงงานอื่น");
            }
            return receiptQuote;
        }
        if (!current.current()) {
            throw new ApiException(HttpStatus.CONFLICT, "รับคำตอบได้เฉพาะ revision ล่าสุดของใบเสนอราคาโรงงานเท่านั้น");
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
                throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาโรงงานถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
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
                    throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกแก้ไขโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
                }
                toStatus = PricingRequestStatus.AWAITING_FACTORY_RESPONSE;
            }
            respondedQuoteId = quoteId;
            saved = requireQuote(quoteId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED, summary.status(), toStatus,
                "Factory response received from " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_RECEIVED,
                "คำขอราคา " + summary.requestCode() + " ได้รับราคาจาก " + current.factoryName());
        } else if (Set.of(FactoryQuoteStatus.RESPONSE_RECEIVED, FactoryQuoteStatus.NEGOTIATING,
                FactoryQuoteStatus.READY_FOR_COSTING).contains(current.status())) {
            quotes.supersede(current.id());
            long newId = quotes.createRevision(current, request.supplierQuoteRef(), request.defaultCurrency(),
                request.paymentTerms(), request.leadTimeText(), request.revisionReason(), request.negotiationNote(), actor.id());
            quotes.replaceResponseItems(newId, normalizedItems);
            // CEO-owns-costing (plan 2.3): there is no submitted costing row sitting around any
            // more for a revision to mark "stale" (markOpenCostingsStale, deleted) — cost is
            // computed fresh, once, at CEO-review time, from whichever quote is CURRENT then. So
            // what a revision arriving here can invalidate instead is READINESS: if the request
            // already sits at READY_FOR_CEO_REVIEW, that status asserted every item's factory
            // quote was ready — this revision just un-readied one of them (the new revision is
            // RESPONSE_RECEIVED, not READY_FOR_COSTING, until Import re-marks it). Pull the
            // request back so the CEO cannot open a review whose price is about to change under
            // them; Import must re-mark this revision ready to re-advance (FactoryQuoteService.
            // markReadyForCosting). See PricingRequestStatus's READY_FOR_CEO_REVIEW ->
            // AWAITING_FACTORY_RESPONSE back-edge for the state-machine side of this.
            String toStatus = summary.status();
            if (PricingRequestStatus.READY_FOR_CEO_REVIEW.equals(summary.status())) {
                int pulledBack = pricingRequests.transition(summary.id(), PricingRequestStatus.READY_FOR_CEO_REVIEW,
                    PricingRequestStatus.AWAITING_FACTORY_RESPONSE, null, null);
                if (pulledBack == 1) {
                    toStatus = PricingRequestStatus.AWAITING_FACTORY_RESPONSE;
                }
            }
            respondedQuoteId = newId;
            saved = requireQuote(newId);
            addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_REVISED, summary.status(), toStatus,
                "Factory response revised for " + current.factoryName());
            notifyCeo(summary, PricingRequestEventKind.FACTORY_RESPONSE_REVISED,
                "คำขอราคา " + summary.requestCode() + " มีราคาฉบับปรับปรุงจาก " + current.factoryName());
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาโรงงานนี้ไม่สามารถรับคำตอบได้ในสถานะ " + current.status());
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
                        "ไม่สามารถแก้ไขข้อขัดแย้งของ clientRequestId ได้"));
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
            throw new ApiException(HttpStatus.CONFLICT, "เข้าสู่ขั้นตอนต่อรองได้เฉพาะคำตอบล่าสุดที่ได้รับเท่านั้น");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_NEGOTIATION_STARTED, summary.status(), summary.status(),
            request.note());
        notifyCeo(summary, PricingRequestEventKind.FACTORY_NEGOTIATION_STARTED,
            "คำขอราคา " + summary.requestCode() + " เริ่มเจรจากับ " + quote.factoryName());
        return requireQuote(quoteId);
    }

    @Transactional
    public FactoryQuoteDto markReadyForCosting(long quoteId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = requireQuote(quoteId);
        // Serialize concurrent mark-ready calls on the SAME pricing request, so the
        // "is every quote ready now?" check below cannot be run by two callers who each see only
        // their own uncommitted markReady. Without this, two Import users marking the LAST two
        // factories ready simultaneously would BOTH evaluate isFullyResolvable to false under
        // READ COMMITTED, neither would advance, and the request would sit at
        // AWAITING_FACTORY_RESPONSE with every quote ready — recoverable only by re-negotiating a
        // quote to get it back out of READY_FOR_COSTING (markReady 409s on an already-ready one).
        // Same pg_advisory_xact_lock key every other step in this chain uses.
        pricingRequests.lockPricingRequest(quote.pricingRequestId());
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        requireMutablePricingRequest(summary, RESPONSE_STATUSES);
        requireActiveDeal(summary.ticketId());
        int rows = quotes.markReady(quoteId);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คำตอบล่าสุดต้องมีราคาต้นทางก่อนจึงจะทำเครื่องหมายว่าพร้อมได้");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_RESPONSE_READY_FOR_COSTING, summary.status(), summary.status(),
            "Factory response ready for costing: " + quote.factoryName());
        // CEO-owns-costing (plan 2.2): push the request forward the moment EVERY item's factory
        // quote is ready — LandedCostCalculator.isFullyResolvable is the SAME predicate
        // FactoryQuoteCarryForward shares, so those two call sites cannot drift apart from each
        // other. A multi-factory request does not advance until the LAST factory's quote is marked
        // ready; re-reading summary is unnecessary since quotes.markReady above did not touch the
        // pricing_request row itself. No advisory lock guards this check (unlike
        // PricingDecisionService.startReview) — the worst case of two quotes being marked ready in
        // the same instant is a missed auto-advance, not an illegal state, and the plan does not
        // call for locking here.
        //
        // V164 (2026-09) briefly ALSO required every item's thickness to resolve here, with an
        // else-branch recording a "blocked on thickness" event for Import when it did not. An
        // owner-ruled scope change on 2026-09-06 moved the thickness obligation to Sales
        // (PricingRequestService#submit gates it before a request is ever visible here) and
        // removed Import's ability to supply one at all — so a request reaching this method can no
        // longer be blocked by an unresolved thickness, and that else-branch is gone with it. See
        // LandedCostCalculator#isFullyResolvable's own Javadoc for the fuller history.
        if (PricingRequestStatus.AWAITING_FACTORY_RESPONSE.equals(summary.status())
                && landedCosts.isFullyResolvable(summary)) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.AWAITING_FACTORY_RESPONSE,
                PricingRequestStatus.READY_FOR_CEO_REVIEW, null, null);
            if (transitioned == 1) {
                // Reuses PRICING_COSTING_SUBMITTED: historically the event marking exactly this
                // transition (AWAITING_FACTORY_RESPONSE -> READY_FOR_CEO_REVIEW), back when
                // PricingCostingService.submit() drove it instead of this auto-advance. Keeping
                // the same kind preserves one consistent "became ready for CEO review" trail
                // across both eras of the workflow.
                addEvent(summary, actor, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
                    PricingRequestStatus.AWAITING_FACTORY_RESPONSE, PricingRequestStatus.READY_FOR_CEO_REVIEW,
                    "All factory quotes ready for costing — request advanced for CEO review");
                notifyCeo(summary, PricingRequestEventKind.PRICING_COSTING_SUBMITTED,
                    "คำขอราคา " + summary.requestCode() + " พร้อมให้ CEO พิจารณาราคาแล้ว");
            }
        }
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
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถทำเครื่องหมายว่าโรงงานนี้ไม่พร้อมเสนอราคาได้ในสถานะปัจจุบัน");
        }
        addEvent(summary, actor, PricingRequestEventKind.FACTORY_NOT_AVAILABLE, summary.status(), summary.status(),
            request.reason());
        notifyCeo(summary, PricingRequestEventKind.FACTORY_NOT_AVAILABLE,
            "คำขอราคา " + summary.requestCode() + " โรงงานไม่สามารถเสนอราคาได้: " + quote.factoryName());
        return requireQuote(quoteId);
    }

    /**
     * Groups a pricing request's lines by the factory each one is routed to. A line with no
     * resolved factory is simply SKIPPED, not fatal (owner decision, manual-RFQ redesign) — {@link
     * #generateDrafts} decides whether an entirely-empty result is an error; this method's only
     * job is the grouping.
     */
    private Map<String, List<PricingRequestItemDto>> groupByFactory(List<PricingRequestItemDto> items) {
        List<PricingRequestItemDto> ordered = items.stream()
            .sorted(Comparator.comparingInt(PricingRequestItemDto::sortOrder))
            .toList();
        Map<String, List<PricingRequestItemDto>> byFactory = new LinkedHashMap<>();
        for (PricingRequestItemDto item : ordered) {
            String factoryName = item.resolvedFactory();
            if (factoryName == null) {
                continue;
            }
            byFactory.computeIfAbsent(factoryName, ignored -> new ArrayList<>()).add(item);
        }
        return byFactory;
    }

    /**
     * Human-readable "รายการที่ N (ชื่อสินค้า)" descriptions for every line with no resolved
     * factory, in on-screen row order — used by {@link #generateDrafts} to build its 422 message
     * when NO line resolves at all.
     *
     * <p><b>The message names the ROW, not the primary key.</b> It used to read
     * {@code "รายการที่ " + item.id()} — the {@code sales.pricing_request_item} PK. The identical
     * Thai phrase means the 1-based row position everywhere else in this workflow (see
     * {@code PricingRequestService#identityErrorMessage}), so Import read "รายการที่ 35" on a
     * three-line request and had no way to tell which line was meant: the PK appears nowhere on
     * screen. The position below matches what the detail page renders because {@code findItems}
     * already returns {@code ORDER BY sort_order, pricing_request_item_id} and
     * {@link java.util.List#sort} is stable, so re-sorting on {@code sortOrder} alone preserves
     * that order. The product name is included as well, so the line is identifiable even if the
     * reader is looking at a stale render.
     *
     * <p>It also reports EVERY offending line rather than just the first. Fixing them one 422 at a
     * time was the difference between one trip through
     * {@code PricingRequestService#setItemFactory} and N of them.
     */
    private List<String> unresolvedLineDescriptions(List<PricingRequestItemDto> items) {
        List<PricingRequestItemDto> ordered = items.stream()
            .sorted(Comparator.comparingInt(PricingRequestItemDto::sortOrder))
            .toList();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PricingRequestItemDto item = ordered.get(i);
            if (item.resolvedFactory() == null) {
                missing.add("รายการที่ " + (i + 1) + " (" + item.displayName() + ")");
            }
        }
        return missing;
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "การตอบกลับของโรงงานต้องครบตามรายการที่ขอไปทุกรายการเท่านั้น");
        }
        List<ReceiveFactoryQuoteItemRequest> normalized = new ArrayList<>();
        for (ReceiveFactoryQuoteItemRequest responseItem : responseItems) {
            PricingRequestItemDto requestItem = requestItemsById.get(responseItem.pricingRequestItemId());
            if (requestItem == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการตอบกลับนี้ไม่ได้เป็นของคำขอราคานี้");
            }
            String itemFactory = requestItem.resolvedFactory();
            if (!quote.factoryName().equals(itemFactory)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการตอบกลับนี้เป็นของโรงงานอื่น");
            }
            String unitBasis = UnitBasis.canonicalize(responseItem.unitBasis(), "Factory quote unit");
            // quotedUnit is the DISPLAY unit (ตร.ม., PCS, ...) — LandedCostCalculator never reads
            // it for arithmetic (only unitBasis feeds pricePerPiece/quantityToPieces; quotedUnit
            // rides through as PricingCostingWriteItem.rawUnit, display-only). Routing it through
            // UnitBasis.canonicalize alongside unitBasis was itself a defect, not just the frontend
            // seed that fed it: canonicalize() forces its input to one of the four PER_ codes,
            // rejecting anything else with a 422 — so a real unit like "ตร.ม." could never be
            // stored here, only a basis code (or an English synonym that happens to canonicalize
            // to one). requireUnitText only requires non-blank text, same as every other free-text
            // field on this request.
            String quotedUnit = requireUnitText(responseItem.quotedUnit(), "Factory quote unit");
            if (UnitBasis.PER_BOX.equals(unitBasis) && responseItem.piecesPerBox() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการตอบกลับแบบ PER_BOX ต้องระบุ piecesPerBox");
            }
            // Widened from PER_SQM-only (owner-ruled change, 2026-09): sqmPerUnit is now required
            // on EVERY line, regardless of unit basis — matching what
            // LandedCostCalculator#resolveSqmPerPiece has always needed (freight/insurance are
            // priced per sqm unconditionally; see that method's own javadoc). Before this, a
            // PER_PIECE/PER_BOX/PER_LINEAR_M line with no sqmPerUnit sailed through receive() and
            // only 422'd much later, at CEO costing time — a late, CEO-discovered failure for a gap
            // Import could have been told about immediately. This turns it into an early,
            // actionable import-stage 422 instead. Known, accepted narrowing: resolveSqmPerPiece
            // also accepts requestItem.requestedQtySqm()/requestedQty() as a fallback when the quote
            // itself carries no sqmPerUnit — this check does not look at the request side, so a line
            // that would have costed fine via that fallback now needs sqmPerUnit supplied here too.
            // Real data rarely populates requestedQtySqm (0 of prod's 7 rows, 12 of UAT's 32 do), so
            // the practical impact is small, but it is a real narrowing, not merely a relocation.
            if (responseItem.sqmPerUnit() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการตอบกลับทุกแบบต้องระบุ sqmPerUnit (พื้นที่ต่อหน่วย ตร.ม.) "
                        + "เนื่องจากใช้คำนวณค่าขนส่งและประกันภัยเสมอ ไม่ว่าหน่วยนับจะเป็นแบบใด");
            }
            if (UnitBasis.PER_LINEAR_M.equals(unitBasis) && responseItem.linearMPerUnit() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "รายการตอบกลับแบบ PER_LINEAR_M ต้องระบุ linearMPerUnit");
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private FactoryQuoteDto requireQuote(long quoteId) {
        return quotes.find(quoteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคาโรงงานนี้"));
    }

    private void requireActiveDeal(long ticketId) {
        TicketSummaryDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
        if (!DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
    }

    private void requireMutablePricingRequest(PricingRequestSummaryDto summary, Set<String> allowedStatuses) {
        if (!allowedStatuses.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคาที่อยู่ในสถานะ '" + summary.status() + "' ไม่สามารถแก้ไขผ่านขั้นตอนราคาโรงงานได้");
        }
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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

    private void notifyCeo(PricingRequestSummaryDto summary, String type, String message) {
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), type, message);
    }

    /**
     * English RFQ draft body (manual-RFQ redesign, P2). Contains what the owner approved for this
     * template: a greeting identifying GL&R as the requester, the item table (brand/model/size/
     * quantity/unit), the sales note if any, and a sign-off naming the requesting user and their
     * email. Deliberately NO commercial-terms block and NO reply-by-date block — the owner
     * declined both explicitly. This is a human-reviewed DRAFT the requester edits and sends from
     * their own mail client (factory RFQ email is manual-only — see {@link #send}), not a
     * machine-sent message, so it reads as ordinary business correspondence rather than a system
     * dump of field names.
     *
     * <p><b>Attachment list (review remediation, HIGH 4) — a DEVIATION from the owner's approved
     * template above, flagged rather than assumed.</b> {@code include_in_factory_email} has had no
     * caller since {@code attemptSend} (the old dispatch worker) was deleted: Import could still
     * tick "include this attachment in the factory email" on a pricing-request attachment, and
     * nothing would ever attach it, because nothing sends anything any more. Rather than leave
     * that toggle live and silently pointless, this appends a short plain-text list of the marked
     * attachments' file names — ONLY when at least one is marked — so the human copying this draft
     * into their own mail client knows what to physically attach. It is deliberately a bare file
     * list, not a table, specifically so it is trivial to delete outright if the owner says no to
     * it on review.
     */
    private String emailBody(PricingRequestSummaryDto summary, String factoryName, List<PricingRequestItemDto> items,
                             UserPrincipal actor) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(factoryName).append(" team,\n\n");
        body.append("We are GL&R, a tile and ceramics importer based in Bangkok, Thailand. ")
            .append("We would like to request your best pricing and lead time for the following item(s), ")
            .append("referencing our internal pricing request ").append(summary.requestCode()).append(":\n\n");
        body.append(String.format("%-20s %-25s %-15s %10s  %s%n", "Brand", "Model", "Size", "Quantity", "Unit"));
        for (PricingRequestItemDto item : items) {
            body.append(String.format("%-20s %-25s %-15s %10s  %s%n",
                safe(item.brand(), "-"),
                safe(item.model(), item.productDescription()),
                safe(item.size(), "-"),
                String.valueOf(item.requestedQty()),
                item.requestedUnit()));
        }
        if (summary.note() != null && !summary.note().isBlank()) {
            body.append("\nAdditional note from our sales team: ").append(summary.note()).append("\n");
        }
        List<PricingRequestRepository.PricingRequestEmailAttachmentFile> attachmentsToInclude =
            pricingRequests.findIncludedInFactoryEmailAttachmentFiles(summary.id());
        if (!attachmentsToInclude.isEmpty()) {
            body.append("\nPlease also see the following attachment(s) with this request:\n");
            for (PricingRequestRepository.PricingRequestEmailAttachmentFile attachment : attachmentsToInclude) {
                body.append("- ").append(attachment.fileName()).append("\n");
            }
        }
        body.append("\nThank you very much, and we look forward to your quotation.\n\n");
        body.append("Best regards,\n");
        body.append(safe(actor.name(), "GL&R"));
        if (actor.email() != null && !actor.email().isBlank()) {
            body.append("\n").append(actor.email());
        }
        body.append("\nGL&R\n");
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

    /**
     * Requires non-blank free text and trims it — the same blank check {@link UnitBasis#canonicalize}
     * applies, without forcing the value onto one of its four canonical codes. Used for
     * {@code quotedUnit}, which is a display unit (ตร.ม., PCS, กล่อง, ...), not a basis.
     */
    private static String requireUnitText(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldLabel + " ต้องไม่เว้นว่าง");
        }
        return value.trim();
    }

    private String validateClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID");
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID");
        }
    }
}
