package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogSqmBasis;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRepository.ContactSnapshot;
import th.co.glr.hr.dealquotation.DealQuotationRepository.CustomerSnapshot;
import th.co.glr.hr.dealquotation.DealQuotationRepository.InsertDraftParams;
import th.co.glr.hr.dealquotation.DealQuotationRepository.NewItem;
import th.co.glr.hr.dealquotation.DealQuotationRepository.PictureImage;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.RejectRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.notification.EmailRecipient;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Quotation v2 (direct deal quotation, V165) — see docs/sales/quotation-v2-plan.md for the full spec. Sales
 * creates a deal, adds items straight onto a quotation with typed unit price + discount (the
 * pricing-request/factory-quote/CEO-costing chain is bypassed for this release — owner ruling
 * 2026-09-09), the server computes every number ({@link WastageCalculator}), and sales_manager/
 * ceo approves. Extends the SAME {@code sales.quotation} aggregate {@code customerquotation/*}
 * already uses (tagged {@code origin = 'DEAL_DIRECT'}) — that package stays untouched.
 *
 * <p>Every authz decision lives here, not in {@link DealQuotationController} (that class only
 * calls {@code sessions.requireUser} and delegates) — this codebase's standing convention.
 *
 * <p><strong>Per-employee grant (owner ruling, Ploy 2026-09-09):</strong> ภิญญดา (employee 144,
 * QC&amp;ISO / role {@code qc}) must be able to create quotations without being given the
 * {@code sales} or {@code sales_manager} role. {@code hr.employee.can_create_quotation}
 * (V165) is a CAPABILITY in the exact shape of the existing admin capability
 * ({@code hr.employee.is_admin} → {@link EmployeeAuthRepository#isAdmin} →
 * {@code AuthResponse.admin}): a plain boolean, read LIVE at decision time via
 * {@link EmployeeAuthRepository#canCreateQuotation}, never inferred from the session principal.
 * A grant lets its holder create/edit/submit/cancel/revise and view/list/download on ANY deal —
 * see {@link #requireEditAccess} / {@link #requireViewAccess} — but never approve/reject.
 */
@Service
public class DealQuotationService {
    private static final Logger log = LoggerFactory.getLogger(DealQuotationService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    // v3b: this class's own copy of the 7% rate is GONE. Every VAT decision here now asks
    // WastageCalculator#vatRateFor(documentLanguage), because the rate is no longer a constant —
    // an EN/F-SM-008 document has none. Keeping a private 0.07 alongside that would be a second
    // answer to the same question, which is exactly how "an EN document has no VAT" ends up true
    // in one place and false in another.
    // v3: a PLAIN row applies its own discount percent the same way WastageCalculator does
    // for a tile (which keeps its own copy of this, being a no-Spring pure class).
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Set<String> EDIT_ROLES = Set.of("sales", "sales_manager");
    private static final Set<String> APPROVE_ROLES = Set.of("sales_manager", "ceo");
    private static final Set<String> VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import", "account");

    private final DealQuotationRepository quotations;
    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final NotificationRepository notifications;
    /** Owner ruling 2026-09-12 ("2) ไม่มีค่อยคำนวนเอง"): {@link #resolveSqmPerPiece} reads a
     * tile's ตร.ม./แผ่น from THIS, never from the free-text size string — see that method's own
     * Javadoc for the resolution order. */
    private final CatalogRepository catalog;
    /** The per-employee "can create quotations" capability gate (owner ruling, Ploy 2026-09-09) —
     * read LIVE at decision time via {@link EmployeeAuthRepository#canCreateQuotation}, never from
     * {@code actor} (a {@link UserPrincipal} is captured at login and would otherwise go stale the
     * moment a grant is added or revoked). Same discipline as {@code isAdmin} elsewhere. */
    private final EmployeeAuthRepository employeeAuth;
    /** "Make the mailer injectable so tests use a no-op": {@link NotificationEmailService} is a
     * plain constructor dependency, and it in turn depends on the {@code Mailer} interface — a
     * test wires a real {@code NotificationEmailService} with a no-op (or capturing) {@code
     * Mailer} rather than needing a second mailer abstraction here. */
    private final NotificationEmailService approvalMailer;
    private final th.co.glr.hr.ticket.QuotationRenderer renderer;
    private final EmployeeSignatureRepository signatures;
    private final String appBaseUrl;

    private final List<String> bankBlockLines;

    public DealQuotationService(DealQuotationRepository quotations, TicketRepository tickets,
                                CustomerRepository customers, ContactRepository contacts,
                                NotificationRepository notifications,
                                NotificationEmailService approvalMailer,
                                th.co.glr.hr.ticket.QuotationRenderer renderer,
                                EmployeeAuthRepository employeeAuth,
                                EmployeeSignatureRepository signatures,
                                CatalogRepository catalog,
                                @Value("${app.mail.app-base-url:}") String appBaseUrl,
                                // app.quotation.bank-block-line1..3 — the unnumbered bank block on an
                                // ENGLISH document, taken verbatim from the owner's own quotations and
                                // kept in application.yml so a moved account is an operations change
                                // rather than a rebuild. Defaults are EMPTY here on purpose: the values
                                // live in one place, and an unset block prints the honest
                                // proforma-invoice line instead of a half-filled set of wire
                                // instructions.
                                @Value("${app.quotation.bank-block-line1:}") String bankBlockLine1,
                                @Value("${app.quotation.bank-block-line2:}") String bankBlockLine2,
                                @Value("${app.quotation.bank-block-line3:}") String bankBlockLine3) {
        this.bankBlockLines = List.of(
            bankBlockLine1 == null ? "" : bankBlockLine1,
            bankBlockLine2 == null ? "" : bankBlockLine2,
            bankBlockLine3 == null ? "" : bankBlockLine3);
        this.quotations = quotations;
        this.tickets = tickets;
        this.customers = customers;
        this.contacts = contacts;
        this.notifications = notifications;
        this.approvalMailer = approvalMailer;
        this.renderer = renderer;
        this.employeeAuth = employeeAuth;
        this.signatures = signatures;
        this.catalog = catalog;
        this.appBaseUrl = appBaseUrl == null ? "" : appBaseUrl.trim();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto create(long ticketId, UpsertDealQuotationRequest request, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        requireEditAccess(actor, ticket);
        quotations.lockTicket(ticketId);
        ContactSnapshot contact = resolveContact(request.contactId(), null, ticket);
        String priceMode = resolvePriceMode(request.priceMode());
        String documentLanguage = resolveDocumentLanguage(request.documentLanguage());
        String currency = resolveCurrency(documentLanguage, request.currency());
        requirePriceModeAvailableInLanguage(priceMode, documentLanguage);
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        CustomerSnapshot customerSnapshot = customerSnapshot(ticket);
        // Owner feedback 2026-09-11 ("มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรกเป็น QT-2026-0014-1"):
        // the FIRST issued document now carries the revision suffix too, so a fresh sequence value
        // ("0014") is minted here and immediately formatted as revision 1 of itself
        // ({@link DealQuotationRepository#revisionNumber}) — "QT-2026-0014-1", not a bare
        // "QT-2026-0014". The sequence allocation itself (nextQuotationCode) is unchanged: this
        // only changes how revision 1's number is FORMATTED, never which "0014" a ticket gets.
        String number = DealQuotationRepository.revisionNumber(quotations.nextQuotationCode(), 1);
        long id = quotations.insertDraft(new InsertDraftParams(
            ticketId, number, actor.id(), ticket.createdById(),
            customerSnapshot.name(), customerSnapshot.address(),
            customerSnapshot.taxId(), customerSnapshot.phone(),
            contact,
            ticket.projectName(), blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), blankToNull(request.remainderMode()),
            request.creditDays(), request.validityDays(), blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency, subtotal, null, 1, items));
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────────────────

    public DealQuotationDto get(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return quotation;
    }

    public List<DealQuotationDto> listForTicket(long ticketId, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        if (!hasQuotationGrant(actor)) {
            requireRole(actor, VIEW_ROLES);
            if ("sales".equals(actor.role()) && ticket.createdById() != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
            }
        }
        return quotations.findByTicket(ticketId);
    }

    /** The approver queue (sales_manager/ceo/import/account see everything; sales sees only their
     * own deals' quotations; a {@code canCreateQuotation}-granted employee sees everything, same
     * as sales_manager — the grant is "any deal", not "own deal only"). */
    public List<DealQuotationDto> search(List<String> statuses, boolean needsRework, UserPrincipal actor) {
        return quotations.search(statuses, listOwnerScope(actor), needsRework);
    }

    /** Per-status counts for the list page's tab labels (owner feedback F5, 2026-09-10) — the
     * SAME scope decision as {@link #search}, so a tab's count is always the size of the list
     * that tab would show. */
    public DealQuotationCountsDto counts(UserPrincipal actor) {
        return quotations.counts(listOwnerScope(actor));
    }

    /**
     * The ONE list-scope decision {@link #search} and {@link #counts} share: a
     * {@code canCreateQuotation}-granted employee sees everything (the grant is "any deal");
     * otherwise the caller must hold a {@link #VIEW_ROLES} role, and {@code sales} is scoped to
     * deals they created (returns their own id as the owner filter); every other view role is
     * unrestricted (null).
     */
    private Long listOwnerScope(UserPrincipal actor) {
        boolean grant = hasQuotationGrant(actor);
        if (!grant) {
            requireRole(actor, VIEW_ROLES);
        }
        return (!grant && "sales".equals(actor.role())) ? actor.id() : null;
    }

    /** Rule: server recomputes every number, always — this is the stateless preview the item
     * editor calls on every keystroke (debounced client-side), performing ZERO writes. */
    public DealQuotationItemDto calculateLine(ItemInput input, UserPrincipal actor) {
        if (!EDIT_ROLES.contains(actor.role()) && !hasQuotationGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        // The preview endpoint carries no quotation, so it has no per-quotation price mode to
        // read. Infer it from the row itself: a ราคาพิเศษ means SPECIAL_SQM, a typed net means
        // DIRECT_NET, and neither means NET. This keeps `POST .../calculate-line`'s request shape
        // unchanged (no new endpoint, no new parameter) and cannot disagree with the saved
        // document, because #buildTileItem reads exactly the same two fields.
        // The preview carries no quotation, so it has no language either — TH, whose 7% is what the
        // stored per-item vat column has always held. The preview never shows that column and the
        // saved row is recomputed by #buildItems with the document's REAL language, so this cannot
        // reach a document.
        return toItemDto(0L, 0, buildItem(input, null, inferPriceMode(input),
            WastageCalculator.DOCUMENT_LANGUAGE_TH, BigDecimal.ZERO));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Update (DRAFT-only; item ids are STABLE across a save — see #update's own comment)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto update(long id, UpsertDealQuotationRequest request, UserPrincipal actor) {
        DealQuotationDto existing = requireQuotation(id);
        TicketSummaryDto ticket = requireTicketSummary(existing.ticketId());
        requireEditAccess(actor, ticket);
        ContactSnapshot contact = resolveContact(request.contactId(), existing.contactId(), ticket);
        // ⚠️ v3, review fix F1: a MISSING priceMode on UPDATE keeps the STORED one. #resolvePriceMode's
        // blank→NET default is correct on CREATE (a brand-new document with no mode chosen IS a NET
        // document) and catastrophic here. Any client that omits the field — a pre-v3 client, or a
        // UI that PUTs only the fields it edited — would otherwise silently REPRICE the document:
        // every SPECIAL_SQM tile row back to its list price, special_price_sqm to NULL, the ราคาพิเศษ
        // sub-line gone, ส่วนลด flipped พิเศษ→Net, and the subtotal several times larger, with no error.
        // DIRECT_NET is worse still — its rep-typed net is deliberately not stored as its own column
        // (it IS final_unit_price), so there is nothing left to fall back to once it is overwritten.
        // An EXPLICIT mode still wins, which is what lets the rep switch modes on a draft.
        String priceMode = isBlank(request.priceMode())
            ? resolvePriceMode(existing.priceMode())
            : resolvePriceMode(request.priceMode());
        // v3b, and the SAME reasoning with the same severity: #resolveDocumentLanguage's blank→TH
        // default is right on create and wrong here. A client that omits the field would flip an
        // EN/USD document back to Thai — which reinstates 7% VAT on an export quotation, re-labels
        // every column and every signature slot, and changes the printed total. Silently, and on a
        // document a customer may already have seen.
        String documentLanguage = isBlank(request.documentLanguage())
            ? resolveDocumentLanguage(existing.documentLanguage())
            : resolveDocumentLanguage(request.documentLanguage());
        // Currency is NOT given the same treatment, deliberately: #resolveCurrency derives it from
        // the resolved language and accepts only the matching one, so the stored value can add
        // nothing. Passing it would merely turn a legitimate language switch into a spurious 400.
        String currency = resolveCurrency(documentLanguage, request.currency());
        requirePriceModeAvailableInLanguage(priceMode, documentLanguage);
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // Compare-and-set FIRST, before touching a single item row — a header update that finds
        // the row no longer DRAFT (a concurrent submit/approve) must leave the items untouched.
        // F7 (2026-09-10): re-snapshot the ลูกค้า columns from the LIVE customer row on every DRAFT
        // save. The deal card now edits เลขที่ผู้เสียภาษี / โทร. in place, and the promise made
        // there is "the values on screen at save time" -- which only holds if this save rewrites
        // them. updateHeader's own WHERE clause keeps it DRAFT-only, so an issued/approved document
        // stays frozen at what it was approved with.
        int rows = quotations.updateHeader(id, contact, customerSnapshot(ticket),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), blankToNull(request.remainderMode()),
            request.creditDays(), request.validityDays(), blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency, subtotal);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
        }
        // Item ids are STABLE across a draft save (fix for the picture-dropping race this
        // replaced — a full delete+insert used to mint new ids on every save, so any client that
        // reused ids from an earlier load, e.g. after its own first autosave, a second tab, or a
        // save racing another save, silently lost every picture; see #orderedInputs' Javadoc for
        // the ordering this relies on). Walk the inputs in STORED order: an input whose id is one
        // of THIS quotation's current item ids, and not already claimed by an earlier input in
        // this same payload, is UPDATED IN PLACE — same quotation_item_id, so its picture (set by
        // the GLA-75 endpoints, never touched by this UPDATE) survives untouched. Everything else
        // (a null id, a foreign id, a stale id, or a duplicate within the payload) is INSERTED as
        // a new row instead. Any of this quotation's rows the payload did not claim is deleted
        // FIRST, and its picture dropped unless something else (a parent revision) still uses it.
        List<ItemInput> ordered = orderedInputs(request.items());
        Set<Long> currentItemIds = new HashSet<>(quotations.findItemIds(id));
        Set<Long> claimedItemIds = new HashSet<>();
        List<DealQuotationRepository.ExistingItem> updates = new ArrayList<>();
        List<DealQuotationRepository.SeqItem> inserts = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            int seq = i + 1;
            Long inputId = ordered.get(i).id();
            NewItem item = items.get(i);
            // Short-circuits before touching claimedItemIds for a null/foreign/stale id, so those
            // never spuriously "claim" anything; claimedItemIds.add returns false for a genuine
            // duplicate of an id already claimed earlier in this payload, which is what routes the
            // SECOND occurrence to the insert branch instead of a second UPDATE of the same row.
            if (inputId != null && currentItemIds.contains(inputId) && claimedItemIds.add(inputId)) {
                updates.add(new DealQuotationRepository.ExistingItem(inputId, seq, item));
            } else {
                inserts.add(new DealQuotationRepository.SeqItem(seq, item));
            }
        }
        List<Long> droppedPictureIds = quotations.deleteUnclaimedItems(id, claimedItemIds);
        quotations.deletePicturesIfUnreferenced(droppedPictureIds);
        quotations.updateItemsInPlace(id, updates);
        quotations.insertItemsAtSeq(id, inserts);
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Status machine: DRAFT -> (submit) -> PENDING_APPROVAL -> (approve) -> APPROVED
    //                                                        -> (reject+reason) -> DRAFT
    //                 DRAFT -> (cancel) -> CANCELLED
    //                 APPROVED -> (revise) -> new DRAFT child; parent -> SUPERSEDED once the
    //                             CHILD reaches APPROVED (not before).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto submit(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        if (quotation.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการก่อนส่งขออนุมัติ");
        }
        // ผู้สั่งซื้อ is mandatory (owner feedback F2): create/update already refuse a draft with no
        // resolvable contact, so this only ever catches a row that predates V167 on a ticket that
        // had no contact -- it stays a draft until the rep saves it with one.
        if (quotation.contactId() == null || isBlank(quotation.contactName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        // Item completeness rule, re-checked over the STORED rows: create/update already enforce
        // this on write (#buildItem), but a row can predate this rule (created before this
        // migration of behaviour) or a stale client could in principle bypass the write-time
        // check -- submit is the last gate before an approver ever sees the document.
        for (DealQuotationItemDto item : quotation.items()) {
            requireStoredItemComplete(item);
        }
        int rows = quotations.submit(id, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงส่งขออนุมัติไม่ได้");
        }
        DealQuotationDto submitted = requireQuotation(id);
        tickets.addEvent(submitted.ticketId(), actor.id(), actor.name(), TicketEventKind.SUBMITTED, null, null,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติ");
        // M5: notify() used to run the role fan-out (2 inserts + 2 emails via SalesNotificationMailer)
        // INSIDE this transaction. Same reasoning as #approve's own afterCommit -- mail (and the
        // in-app row alongside it here, since notifyByRoleAtLink does both in one call) must not be
        // observable before the submit itself is durable; a rollback after this line would otherwise
        // have already notified sales_manager/ceo about a submission that never actually happened.
        afterCommit(() -> notifySubmitted(submitted));
        return submitted;
    }

    private void notifySubmitted(DealQuotationDto submitted) {
        String message = "ใบเสนอราคา " + submitted.number() + " รอการอนุมัติ";
        String link = "/quotations/" + submitted.id();
        notifications.notifyByRoleAtLink("sales_manager", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
        notifications.notifyByRoleAtLink("ceo", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
    }

    @Transactional
    public DealQuotationDto approve(long id, ApproveRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        DealQuotationDto quotation = requireQuotation(id);
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        LocalDate validityDate = quotation.validityDays() != null
            ? approvalDate.plusDays(quotation.validityDays()) : null;
        int rows = quotations.approve(id, actor.id(), blankToNull(request.note()), validityDate);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงอนุมัติไม่ได้");
        }
        DealQuotationDto approved = requireQuotation(id);
        // The customer's last approved document stays valid until a REVISION actually reaches
        // APPROVED — see the class Javadoc's status-machine note. Only fires for a revision (a
        // first-ever quotation has no parent).
        if (approved.parentQuotationId() != null) {
            quotations.supersede(approved.parentQuotationId());
        }
        tickets.addEventWithDocument(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติใบเสนอราคา " + approved.number(), RelatedDocumentType.QUOTATION, id);
        String message = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        notifyRepAndCreator(approved, TicketEventKind.DEAL_QUOTATION_APPROVED, message);
        afterCommit(() -> sendApprovalEmail(approved));
        return approved;
    }

    @Transactional
    public DealQuotationDto reject(long id, RejectRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        // 404 for a genuinely missing id, 409 only once we know the row exists but is in the
        // wrong status -- reject() used to skip straight to the compare-and-set UPDATE, so a
        // missing id and a wrong-status id were indistinguishable (both 409), unlike every other
        // status-machine method here (approve/submit/cancel/update all call requireQuotation
        // first).
        requireQuotation(id);
        int rows = quotations.reject(id, actor.id(), request.reason());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงไม่สามารถตีกลับได้");
        }
        DealQuotationDto rejected = requireQuotation(id);
        tickets.addEvent(rejected.ticketId(), actor.id(), actor.name(), TicketEventKind.REJECTED, null, null,
            "ไม่อนุมัติใบเสนอราคา " + rejected.number() + " — " + request.reason());
        notifyRepAndCreator(rejected, TicketEventKind.DEAL_QUOTATION_REJECTED,
            "ใบเสนอราคา " + rejected.number() + " ไม่ได้รับอนุมัติ: " + request.reason());
        return rejected;
    }

    @Transactional
    public DealQuotationDto cancel(long id, CancelRequest request, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        int rows = quotations.cancel(id);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงยกเลิกไม่ได้");
        }
        DealQuotationDto cancelled = requireQuotation(id);
        String reasonSuffix = request.reason() != null && !request.reason().isBlank()
            ? " — " + request.reason().trim() : "";
        tickets.addEvent(cancelled.ticketId(), actor.id(), actor.name(), TicketEventKind.CANCELLED, null, null,
            "ยกเลิกร่างใบเสนอราคา " + cancelled.number() + reasonSuffix);
        return cancelled;
    }

    @Transactional
    public DealQuotationDto createRevision(long id, UserPrincipal actor) {
        DealQuotationDto source = requireQuotation(id);
        requireEditAccessForQuotation(actor, source);
        if (!QuotationStatus.APPROVED.equals(source.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น (ปัจจุบัน: " + source.docStatus() + ")");
        }
        quotations.lockTicket(source.ticketId());
        // M3: two concurrent "create a revision" calls on the same APPROVED parent both compute
        // the SAME deterministic child number ({base}-{revisionNo+1}, from the SAME source row),
        // and sales.quotation.number is UNIQUE (V6) -- so the second INSERT threw
        // DuplicateKeyException, surfaced as a bare 500 with no Thai message. The advisory lock
        // above already serialises callers for this ticket, so the SECOND caller (once it
        // acquires the lock after the first commits) now observes the first caller's
        // already-inserted child here and 409s with an intelligible reason instead of racing into
        // the unique-index violation.
        if (quotations.hasOpenRevision(source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
        }
        // Works unchanged for BOTH a post-2026-09-11 parent ("QT-2026-0014-1", revisionNo 1 -> base
        // "QT-2026-0014") and a legacy pre-change parent ("QT-2026-0014" bare, revisionNo 1 -> base
        // itself unchanged) -- see DealQuotationRepository#baseNumber's own Javadoc for why one
        // formula covers both eras with no special-casing here.
        String baseNumber = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        // M3 (found while testing the fix above): NOT source.revisionNo() + 1 -- see
        // DealQuotationRepository#nextRevisionNo's own Javadoc for the duplicate-key crash that
        // naive formula still has once a prior revision attempt was cancelled.
        int nextRevisionNo = quotations.nextRevisionNo(source.ticketId(), baseNumber);
        String newNumber = DealQuotationRepository.revisionNumber(baseNumber, nextRevisionNo);
        BigDecimal sourceVatRate = WastageCalculator.vatRateFor(source.documentLanguage());
        List<NewItem> items = source.items().stream()
            .map(item -> toNewItemFromDto(item, sourceVatRate)).toList();
        long newId = quotations.insertDraft(new InsertDraftParams(
            source.ticketId(), newNumber, actor.id(), source.salesRepId(),
            source.customerName(), source.customerAddress(), source.customerTaxId(), source.customerPhone(),
            // The parent's frozen snapshot, verbatim -- the rep re-chooses (or the ticket's
            // contact re-defaults) only on the revision's own next save through #update.
            new ContactSnapshot(source.contactId(), source.contactName(), source.contactPhone(), source.contactEmail()),
            source.projectName(), source.deptCode(), source.unitCode(), source.offerDate(),
            source.depositPercent(), source.remainderMode(), source.creditDays(), source.validityDays(),
            // v3b: a revision inherits the parent's ภาษาเอกสาร and currency verbatim, like every
            // other header field here — revising an English quotation must not silently reissue it
            // in Thai. source.documentLanguage() is never null (the repository normalises a stored
            // NULL to TH), so resolveDocumentLanguage is not needed on this path.
            source.customerNotes(), source.priceMode(), source.documentLanguage(),
            WastageCalculator.defaultCurrencyFor(source.documentLanguage()),
            source.subtotalAmount(), source.id(),
            nextRevisionNo, items));
        // GLA-75: "a revision copies its parent's items verbatim" includes their pictures — the
        // child's rows point at the parent's (immutable, shared) picture rows, matched by seq.
        quotations.copyPictureLinks(source.id(), newId);
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.REVISION_REQUESTED, null, null,
            "สร้างใบเสนอราคาฉบับแก้ไข " + newNumber + " จาก " + source.number());
        return requireQuotation(newId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-75 item pictures (V170) — one picture per item, placement BELOW | BESIDE.
    //
    // AUTHZ — the same rule as editing the quotation, by construction: every write goes through
    // #requireEditablePicture, which calls the SAME #requireEditAccessForQuotation that #update,
    // #submit and #cancel use (sales on their OWN deal; sales_manager or the live
    // can_create_quotation grant on any deal — the identical set DealEntryAccess#canEnterDeal
    // admits, with update's ownership rule on top), and then refuses anything not DRAFT exactly as
    // #update does (409). The repository re-states DRAFT inside every UPDATE's WHERE clause, so a
    // submit racing an upload cannot slip a picture onto a PENDING_APPROVAL document. The read is
    // #requireViewAccess, the rule GET /deal-quotations/{id} uses.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto uploadItemPicture(long id, long itemId, MultipartFile file, String placement,
                                              UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditablePicture(actor, quotation, itemId);
        String resolvedPlacement = QuotationItemPictures.parsePlacement(placement);
        QuotationItemPictures.ValidatedPicture picture = QuotationItemPictures.validate(file);
        Long previousPictureId = quotations.findItemPictureId(id, itemId);
        long pictureId = quotations.insertPicture(picture.mimeType(), picture.bytes(), actor.id());
        if (quotations.setItemPicture(id, itemId, pictureId, resolvedPlacement) == 0) {
            // Lost a race with submit/cancel. Clean up explicitly rather than rely on the rollback:
            // the hand-wired integration suite runs with no transaction proxy at all.
            quotations.deletePicturesIfUnreferenced(List.of(pictureId));
            throw notDraftForPicture();
        }
        if (previousPictureId != null) {
            quotations.deletePicturesIfUnreferenced(List.of(previousPictureId));
        }
        return requireQuotation(id);
    }

    @Transactional
    public DealQuotationDto setItemPicturePlacement(long id, long itemId, String placement, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        DealQuotationItemDto item = requireEditablePicture(actor, quotation, itemId);
        String resolvedPlacement = QuotationItemPictures.parsePlacement(placement);
        if (!item.hasPicture()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "รายการนี้ยังไม่มีรูปภาพ");
        }
        if (quotations.setItemPicturePlacement(id, itemId, resolvedPlacement) == 0) {
            throw notDraftForPicture();
        }
        return requireQuotation(id);
    }

    /** Idempotent: removing the picture of an item that has none answers the quotation, not 404. */
    @Transactional
    public DealQuotationDto removeItemPicture(long id, long itemId, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditablePicture(actor, quotation, itemId);
        Long previousPictureId = quotations.findItemPictureId(id, itemId);
        if (quotations.clearItemPicture(id, itemId) == 0) {
            throw notDraftForPicture();
        }
        if (previousPictureId != null) {
            quotations.deletePicturesIfUnreferenced(List.of(previousPictureId));
        }
        return requireQuotation(id);
    }

    public PictureImage getItemPicture(long id, long itemId, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return quotations.findItemPicture(id, itemId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรูปภาพของรายการนี้"));
    }

    /** 403 (edit access, as #update) → 409 (not DRAFT, as #update) → 404 (item not on THIS
     * quotation). Authz first, so a caller without edit rights learns nothing about item ids. */
    private DealQuotationItemDto requireEditablePicture(UserPrincipal actor, DealQuotationDto quotation, long itemId) {
        requireEditAccessForQuotation(actor, quotation);
        if (!QuotationStatus.DRAFT.equals(quotation.docStatus())) {
            throw notDraftForPicture();
        }
        return quotation.items().stream()
            .filter(item -> item.id() == itemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้ในใบเสนอราคา"));
    }

    private ApiException notDraftForPicture() {
        return new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขรูปภาพไม่ได้");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Rendering — builds th.co.glr.hr.ticket.QuotationRenderModel via DealQuotationRenderAdapter
    // and renders it through the SAME QuotationRenderer the legacy/PCR path uses (see that
    // class's Javadoc — one renderer, one rich model, two builders).
    // ─────────────────────────────────────────────────────────────────────────────────────

    public byte[] renderPdf(long id, UserPrincipal actor) {
        return renderer.toPdf(buildRenderModel(id, actor));
    }

    public byte[] renderXlsx(long id, UserPrincipal actor) {
        return renderer.toXlsx(buildRenderModel(id, actor));
    }

    private th.co.glr.hr.ticket.QuotationRenderModel buildRenderModel(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return toRenderModel(quotation);
    }

    /** Resolves the approver's signature bytes (a live {@code hr.employee_signature} read — never
     * cached) and hands everything to the adapter, which is otherwise a pure function. */
    private th.co.glr.hr.ticket.QuotationRenderModel toRenderModel(DealQuotationDto quotation) {
        byte[] signaturePng = null;
        String signatureMime = null;
        if (quotation.approverHasSignature() && quotation.approvedById() != null) {
            var signature = signatures.find(quotation.approvedById());
            if (signature.isPresent()) {
                signaturePng = signature.get().image();
                signatureMime = signature.get().mimeType();
            }
        }
        // GLA-75: item picture bytes are a separate read, and only when some item has one — the DTO
        // never carries bytes.
        Map<Long, PictureImage> itemPictures = quotation.items().stream().anyMatch(DealQuotationItemDto::hasPicture)
            ? quotations.findPictureImagesForQuotation(quotation.id())
            : Map.of();
        return DealQuotationRenderAdapter.toRenderModel(quotation, signaturePng, signatureMime,
            bankBlockLines, itemPictures);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Approval email — Thai body, PDF attached, sent to the rep AND the creator (deduped),
    // after the approving transaction actually commits (mail cannot be un-sent).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void sendApprovalEmail(DealQuotationDto approved) {
        byte[] pdf;
        try {
            pdf = renderer.toPdf(toRenderModel(approved));
        } catch (Exception e) {
            // H1: was logged WITHOUT the exception itself (only e.getMessage()), so a render
            // failure here -- e.g. the underlineCache-across-workbooks bug this PDF render is a
            // second render THROUGH -- left no stack trace anywhere, and the approval email
            // silently never sent with no way to diagnose why. Passing the exception as the last
            // varargs argument (unmatched by a `{}` placeholder) makes SLF4J log the full stack
            // trace. The in-app notification above already fired unconditionally before this
            // deferred call ever runs, so the rep/creator are still told the quotation was
            // approved even when the PDF (and therefore the email) fails.
            log.error("Deal quotation approval PDF render failed: quotationId={}", approved.id(), e);
            return;
        }
        String subject = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        String link = appBaseUrl + "/quotations/" + approved.id();
        String body = "ใบเสนอราคา " + approved.number() + " ได้รับการอนุมัติแล้วโดย "
            + (approved.approvedByName() != null ? approved.approvedByName() : "ผู้อนุมัติ")
            + "\nยอดรวมทั้งสิ้น " + money(approved.grandTotal()) + " บาท"
            + "\nดูรายละเอียด: " + link;
        String filename = approved.number() + ".pdf";
        Set<String> sentTo = new HashSet<>();
        sendToEmployeeIfPossible(approved.salesRepId(), subject, body, filename, pdf, sentTo);
        sendToEmployeeIfPossible(approved.createdById(), subject, body, filename, pdf, sentTo);
    }

    /** In-app notification to the rep AND the creator, deduped when they are the same employee
     * (a rep who created their own deal). */
    private void notifyRepAndCreator(DealQuotationDto quotation, String kind, String message) {
        String link = "/quotations/" + quotation.id();
        notifications.notifyEmployeeAtLink(quotation.salesRepId(), kind, message, link);
        if (quotation.createdById() != quotation.salesRepId()) {
            notifications.notifyEmployeeAtLink(quotation.createdById(), kind, message, link);
        }
    }

    private void sendToEmployeeIfPossible(long employeeId, String subject, String body, String filename,
                                          byte[] pdf, Set<String> sentTo) {
        java.util.Optional<EmailRecipient> recipient = notifications.findEmployeeRecipient(employeeId);
        if (recipient.isEmpty() || recipient.get().email() == null || recipient.get().email().isBlank()) {
            log.warn("Deal quotation approval email skipped: employee={} has no email on file", employeeId);
            return;
        }
        String email = recipient.get().email();
        if (!sentTo.add(email)) {
            return; // dedupe: rep and creator are the same employee, or share an address
        }
        try {
            approvalMailer.sendWithAttachment(email, subject, body, filename, pdf);
        } catch (Exception e) {
            log.error("Deal quotation approval email failed: to={}", email, e);
        }
    }

    /** Defers a side effect (the approval email) until the surrounding transaction commits — mail
     * cannot be un-sent. Same "no transaction, no deferral" contract as this codebase's existing
     * {@code th.co.glr.hr.notification.AfterCommit} (package-private there; reimplemented here
     * rather than reaching into that package for one static method). */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // ผู้สั่งซื้อ — owner feedback F2 (2026-09-10): mandatory, snapshotted (V167).
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The ลูกค้า snapshot to stamp onto the quotation, read from the LIVE customer row — the one
     * place create and update both build it, so the two can never drift into snapshotting different
     * things. Owner feedback F7 (2026-09-10) made เลขที่ผู้เสียภาษี / โทร. editable on the deal
     * card; this is what makes an edit reach the document, since {@code updateHeader} now rewrites
     * these columns on every DRAFT save.
     *
     * <p>{@code name} deliberately still comes from the ticket (its own join on the customer row,
     * i.e. equally live) rather than from {@code CustomerDto}, so a deal whose customer row has been
     * deleted out from under it still prints the name it was created with instead of a blank.
     */
    private CustomerSnapshot customerSnapshot(TicketSummaryDto ticket) {
        CustomerDto customer = ticket.customerId() != null
            ? customers.findById(ticket.customerId()).orElse(null) : null;
        return new CustomerSnapshot(
            ticket.customerName(),
            customer != null ? customer.address() : null,
            customer != null ? customer.taxId() : null,
            customer != null ? customer.phone() : null);
    }

    /**
     * Resolves the ผู้สั่งซื้อ for a create/update and returns the FROZEN snapshot to store —
     * precedence: the request's own {@code contactId}, else the contact the draft already carries
     * ({@code existingContactId}, update only), else the deal's contact
     * ({@code sales.ticket.contact_id}). None → 400 "กรุณาระบุผู้สั่งซื้อ" (the owner's rule: a
     * quotation cannot be saved without one). The contact must exist and belong to the deal's
     * customer — a contact id from another customer is a client bug or a probe, refused as 400 with
     * the same wording so nothing about other customers' contacts leaks. The name/phone/email are
     * read NOW and written onto the quotation; a later edit of the contact row never changes the
     * document (see V167's header for why that matters for an approved, emailed PDF).
     */
    private ContactSnapshot resolveContact(Long requestedContactId, Long existingContactId, TicketSummaryDto ticket) {
        Long contactId = requestedContactId != null ? requestedContactId
            : existingContactId != null ? existingContactId
            : ticket.contactId();
        if (contactId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        ContactDto contact = contacts.findById(contactId)
            .filter(c -> ticket.customerId() != null && c.customerId() == ticket.customerId())
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ"));
        String name = blankToNull(contact.fullName());
        if (name == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        return new ContactSnapshot(contact.id(), name, blankToNull(contact.phone()), blankToNull(contact.email()));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Item arithmetic — the ONLY place ItemInput -> WastageCalculator.Input is assembled.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds every row, in PRINTED order, and derives each ADJUSTMENT row from the rows it applies
     * to. Quotation v3 (owner feedback pass 3, 2026-09-11).
     *
     * <p><b>Ordering is load-bearing.</b> An adjustment is a percentage of the lines ABOVE it, so
     * every ADJUSTMENT row is moved to the END here — stably, preserving the rep's own relative
     * order among them and among the rows they apply to. The sequence numbers are assigned AFTER
     * that move, so the stored {@code seq}, the printed row order and the arithmetic can never
     * disagree, whatever order the client happened to send.
     *
     * <p><b>Two adjustments do NOT compound.</b> {@code adjustmentBase} accumulates only
     * NON-adjustment line amounts, so a second ส่วนลดพิเศษ is a percentage of the same original
     * subtotal as the first, never of the first-discounted figure. That is the simplest defensible
     * reading — it makes two 3% rows mean 6%, not 5.91% — and it is the one behaviour in this pass
     * the owner has NOT ruled on. ⚠️ Flagged in the PR body for her confirmation; if she wants
     * compounding, the change is to add the adjustment's own (negative) lineAmount to the base.
     */
    private List<NewItem> buildItems(List<ItemInput> inputs, String priceMode, String documentLanguage) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการ");
        }
        List<ItemInput> ordered = orderedInputs(inputs);
        if (ordered.stream().allMatch(input -> WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType(input)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ใบเสนอราคาต้องมีรายการสินค้าอย่างน้อยหนึ่งรายการ ก่อนจะใส่ส่วนลดพิเศษได้");
        }

        List<NewItem> items = new ArrayList<>();
        BigDecimal adjustmentBase = BigDecimal.ZERO;
        int seq = 0;
        for (ItemInput input : ordered) {
            seq++;
            NewItem item = buildItem(input, seq, priceMode, documentLanguage, adjustmentBase);
            if (!WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())) {
                adjustmentBase = adjustmentBase.add(item.lineAmount());
            }
            items.add(item);
        }
        // ⚠️ v3, review fix F3 — a NEW rule the owner has not ruled on; flagged in the PR body.
        // The subtotal can be driven below zero two ways, both reachable from an ordinary payload:
        // two 60% adjustments (neither compounds, so each takes the full base — 120% together),
        // and a FLAT adjustmentAmount, whose only bound is @DecimalMax("99999999") and which bears
        // no relation to the base at all (a flat 1,000,000 on a 1,000-baht document prints a grand
        // total of −1,069,930). A negative grand total is never a real quotation — it is a document
        // that says GL&R owes the customer money — so refuse it at write time rather than print it.
        // Zero is allowed: a 100% discount is at least an intelligible thing to issue.
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        if (subtotal.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ยอดรวมหลังหักส่วนลดพิเศษติดลบ กรุณาตรวจสอบส่วนลดพิเศษ");
        }
        return items;
    }

    /** The order items are STORED in (seq 1..n): every non-adjustment row in the order sent, then
     * every adjustment row. The ONE statement of that rule — {@link #buildItems} numbers rows by it
     * and {@link #update} walks inputs by it too, so {@code items.get(i)} and {@code ordered.get(i)}
     * always agree on which input produced seq {@code i + 1} — which is what lets {@code update}
     * match a sent item id to the SAME row {@code buildItems} priced for it. */
    private List<ItemInput> orderedInputs(List<ItemInput> inputs) {
        List<ItemInput> ordered = new ArrayList<>();
        List<ItemInput> adjustments = new ArrayList<>();
        for (ItemInput input : inputs) {
            if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType(input))) {
                adjustments.add(input);
            } else {
                ordered.add(input);
            }
        }
        ordered.addAll(adjustments);
        return ordered;
    }

    /** {@code null}/blank {@code lineType} reads as TILE — every pre-v3 client keeps working
     * unchanged, and the default is the row type 99% of quotations are made of. */
    private String lineType(ItemInput input) {
        return isBlank(input.lineType()) ? WastageCalculator.LINE_TYPE_TILE : input.lineType().trim();
    }

    /** {@code null}/blank reads as NET — today's behaviour, and what every pre-v3 row stores. */
    private String resolvePriceMode(String priceMode) {
        return isBlank(priceMode) ? WastageCalculator.PRICE_MODE_NET : priceMode.trim();
    }

    /** v3b: {@code null}/blank reads as TH — the Thai F-SM-002, the only document that existed
     * before V169 and what every pre-V169 row stores. */
    private String resolveDocumentLanguage(String documentLanguage) {
        return isBlank(documentLanguage)
            ? WastageCalculator.DOCUMENT_LANGUAGE_TH : documentLanguage.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * v3b: the currency, defaulted FROM the language (TH→THB, EN→USD) so a rep picks one thing.
     *
     * <p>An explicit request that DISAGREES with the language is refused rather than quietly
     * honoured or quietly overridden. The two forms are single-currency documents — F-SM-002
     * prints เป็นเงิน (บาท) and a 7% VAT row, F-SM-008 prints Amount (USD) and no VAT row at all —
     * so a "Thai document in USD" would print baht column headings over dollar figures. The field
     * stays on the wire (rather than being derived and dropped) precisely so that if the owner
     * ever rules that an English document may be billed in THB, the relaxation is these three
     * lines and nothing else.
     */
    private String resolveCurrency(String documentLanguage, String requested) {
        String expected = WastageCalculator.defaultCurrencyFor(documentLanguage);
        if (isBlank(requested)) {
            return expected;
        }
        String currency = requested.trim().toUpperCase(Locale.ROOT);
        if (!expected.equals(currency)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "สกุลเงิน " + currency + " ใช้กับเอกสารภาษา " + documentLanguage + " ไม่ได้ (ต้องเป็น " + expected + ")");
        }
        return currency;
    }

    /**
     * v3b: <b>{@code SPECIAL_SQM} is unavailable on an English document.</b>
     *
     * <p>ราคาพิเศษ is a Thai-market concept end to end: the rep quotes บาท per ตร.ม. <i>including
     * VAT</i>, and {@link WastageCalculator#netPerPieceFromSpecialSqm} recovers the per-piece net
     * by dividing that figure by 1.07. An English/USD document carries no VAT at all, so the same
     * arithmetic would be dividing a USD-per-sqm price by a Thai VAT rate that does not apply to
     * it — a number with no meaning, printed as if it did. The sub-line the mode emits
     * ("ราคารวมภาษีมูลค่าเพิ่ม") says so in Thai on an otherwise English page, which is the visible
     * symptom of the same mistake.
     *
     * <p>Refused at the SERVICE, on both write paths, rather than hidden in the UI: a hidden
     * control is not a rule. {@code NET} and {@code DIRECT_NET} are both available in either
     * language — neither touches VAT.
     */
    private void requirePriceModeAvailableInLanguage(String priceMode, String documentLanguage) {
        if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)
            && WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(documentLanguage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ราคาพิเศษ (บาท/ตร.ม. รวมภาษี) ใช้กับเอกสารภาษาอังกฤษไม่ได้ "
                    + "เนื่องจากเอกสารภาษาอังกฤษไม่มีภาษีมูลค่าเพิ่ม กรุณาเลือกราคาสุทธิต่อแผ่นแทน");
        }
    }

    /** See {@link #calculateLine} — the stateless preview has no quotation to read a mode off. */
    private String inferPriceMode(ItemInput input) {
        if (input.specialPriceSqm() != null) {
            return WastageCalculator.PRICE_MODE_SPECIAL_SQM;
        }
        if (input.directNetPrice() != null) {
            return WastageCalculator.PRICE_MODE_DIRECT_NET;
        }
        return WastageCalculator.PRICE_MODE_NET;
    }

    /**
     * Item completeness rule (owner ruling 2026-09-10, "Item completeness rule" in
     * inline-deal-spec.md): create/update (and {@link #submit}, over the already-stored rows)
     * require every item complete — model/color/texture/sizeText/a resolved
     * {@code sqmPerPiece}/thicknessMm/piecesPerBox/unitPrice all present and positive, plus a
     * quantity for the item's own {@code quantityMode}. {@code brand}/{@code productCode}/
     * {@code locationLabel}/{@code originCountry}/lead time/notes stay optional.
     *
     * <p>{@code rowNumber == null} means "skip the completeness check" — the ONLY caller that
     * passes null is {@link #calculateLine}'s single-item preview (the editor's live calculation
     * line, called on every keystroke; a half-typed row must still preview, not 400). This is a
     * deliberate divergence from the spec's literal "enforce in ItemInput bean validation" line:
     * {@code ItemInput} is the SAME record {@code calculate-line}'s controller method validates
     * with {@code @Valid} (see {@code DealQuotationController#calculateLine}), so any bean-level
     * {@code @NotBlank}/{@code @NotNull} added for these fields would 400 calculate-line's
     * mid-typing preview too — the one behaviour the spec explicitly says must stay lenient.
     * Enforcing here (service-level, row-number-gated) is what actually lets create/update/submit
     * be strict while calculate-line stays lenient; a bean annotation cannot express that
     * distinction without a validation-groups setup this change does not introduce.
     */
    private NewItem buildItem(ItemInput input, Integer rowNumber, String priceMode,
                              String documentLanguage, BigDecimal adjustmentBase) {
        String lineType = lineType(input);
        // ALWAYS, including the lenient preview path — see #requirePriceValidForType's Javadoc for
        // why this is not gated on rowNumber the way the completeness check is.
        requirePriceValidForType(input, lineType, priceMode, rowNumber);
        // v3b: the stored per-item vat/line_total columns follow the DOCUMENT's language, so an EN
        // row stores 0.00 VAT rather than a 7% figure nothing on that document ever charges. These
        // two columns are internal (neither is on DealQuotationItemDto, and the renderer never
        // reads them), but leaving a 7% VAT on the rows of a no-VAT document is a lie any later
        // report would read straight out of the table.
        BigDecimal vatRate = WastageCalculator.vatRateFor(documentLanguage);
        return switch (lineType) {
            case WastageCalculator.LINE_TYPE_PLAIN -> buildPlainItem(input, rowNumber, vatRate);
            case WastageCalculator.LINE_TYPE_ADJUSTMENT ->
                buildAdjustmentItem(input, rowNumber, adjustmentBase, vatRate);
            case WastageCalculator.LINE_TYPE_TILE -> buildTileItem(input, rowNumber, priceMode, vatRate);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                "ประเภทรายการไม่ถูกต้อง: " + lineType);
        };
    }

    private NewItem buildTileItem(ItemInput input, Integer rowNumber, String priceMode,
                                  BigDecimal vatRate) {
        BigDecimal sqmPerPiece = resolveSqmPerPiece(input);
        if (rowNumber != null) {
            requireItemComplete(rowNumber, input, sqmPerPiece);
        }
        WastageCalculator.Result result;
        try {
            result = WastageCalculator.calculate(new WastageCalculator.Input(
                sqmPerPiece, input.quantityMode(), input.areaSqm(), input.piecesInput(),
                input.wastageMode(), input.wastageValue(), input.piecesPerBox(), input.unitPrice(),
                input.discountPct()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            // ArithmeticException alongside IllegalArgumentException: BigDecimal#intValueExact
            // (piecesPerBox/ceiling conversions inside WastageCalculator) throws it for a value
            // that does not fit exactly -- an absurd piecesInput/areaSqm/wastageValue combination
            // otherwise reached the generic 500 handler instead of this 400.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
        }

        // v3: the PIECE arithmetic above is mode-independent — only the money changes. WastageCalculator
        // #calculate still owns netUnitPrice for NET mode (list price × (1 − discount)); the two new
        // modes replace it and recompute the line amount from the SAME piecesFinal, so a mode switch
        // can never silently change a quantity.
        BigDecimal netUnitPrice = result.netUnitPrice();
        BigDecimal discountPct = input.discountPct();
        BigDecimal specialPriceSqm = null;
        if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
            specialPriceSqm = input.specialPriceSqm();
            try {
                netUnitPrice = WastageCalculator.netPerPieceFromSpecialSqm(specialPriceSqm, sqmPerPiece);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
            }
            // The printed ส่วนลด is "พิเศษ", not a percentage — there is no percent to store, and a
            // stale one from a previous NET-mode save must not survive the switch.
            discountPct = null;
        } else if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)) {
            netUnitPrice = money2(input.directNetPrice());
            discountPct = null;
        }
        BigDecimal lineAmount = WastageCalculator.PRICE_MODE_NET.equals(priceMode)
            ? result.lineAmount()
            : money2(netUnitPrice.multiply(BigDecimal.valueOf(result.piecesFinal())));

        BigDecimal vat = money2(lineAmount.multiply(vatRate));
        BigDecimal lineTotal = lineAmount.add(vat);
        String descriptionLine = DealQuotationLines.descriptionLine(input.model(), input.color(), input.texture(),
            input.productCode(), input.sizeText(), input.thicknessMm());
        return new NewItem(
            input.locationLabel(), input.catalogPriceId(), input.productCode(),
            input.brand(), input.model(), input.color(), input.texture(), input.sizeText(),
            input.thicknessMm(), sqmPerPiece,
            input.quantityMode(), input.areaSqm(), input.piecesInput(),
            input.wastageMode(), input.wastageValue(), input.piecesPerBox(),
            result.piecesBeforeWastage(), result.piecesAfterWastage(), result.piecesFinal(), result.boxes(),
            input.unitPrice(), discountPct, netUnitPrice, lineAmount,
            vat, lineTotal,
            input.originCountry(), input.leadTimeMinDays(), input.leadTimeMaxDays(), input.itemNotes(),
            descriptionLine,
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.valueOf(result.piecesFinal()), "แผ่น",
            specialPriceSqm, null, null);
    }

    /**
     * S2 — a PLAIN row: description, quantity, unit, unit price, and NONE of the tile machinery.
     * No wastage, no ตร.ม./แผ่น, no แผ่น/กล่อง, no auto-composed description, no catalog link.
     * Freight (1 JOB), Mapei consumables (Bags/Barrels), the cut service, sanitary-ware ชุด rows.
     *
     * <p>{@code amount = quantity × netPrice}, with the row's own discount applied to the unit
     * price exactly as {@code WastageCalculator#calculate} does for a tile — the discount is
     * normally absent, which prints "Net".
     */
    private NewItem buildPlainItem(ItemInput input, Integer rowNumber, BigDecimal vatRate) {
        if (rowNumber != null) {
            List<String> missing = new ArrayList<>();
            if (isBlank(input.description())) missing.add("รายละเอียด");
            if (input.quantity() == null || input.quantity().signum() <= 0) missing.add("จำนวน");
            if (isBlank(input.unit())) missing.add("หน่วย");
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการที่ " + rowNumber + ": ขาด " + String.join(", ", missing));
            }
        }
        BigDecimal quantity = input.quantity() == null ? BigDecimal.ONE : input.quantity();
        BigDecimal discountPct = input.discountPct() == null ? BigDecimal.ZERO : input.discountPct();
        BigDecimal netUnitPrice = money2(input.unitPrice().multiply(
            BigDecimal.ONE.subtract(discountPct.divide(HUNDRED, 10, RoundingMode.HALF_UP))));
        BigDecimal lineAmount = money2(netUnitPrice.multiply(quantity));
        BigDecimal vat = money2(lineAmount.multiply(vatRate));
        return new NewItem(
            input.locationLabel(), null, null,
            null, null, null, null, null,
            null, null,
            null, null, null,
            null, null, null,
            0, 0, 0, null,
            input.unitPrice(), input.discountPct(), netUnitPrice, lineAmount,
            vat, lineAmount.add(vat),
            input.originCountry(), input.leadTimeMinDays(), input.leadTimeMaxDays(), input.itemNotes(),
            input.description() == null ? null : input.description().trim(),
            WastageCalculator.LINE_TYPE_PLAIN, quantity, blankToNull(input.unit()),
            null, null, null);
    }

    /**
     * S3 — an ADJUSTMENT row (ส่วนลดพิเศษ). The rep types a percent and a date; the system derives
     * the amount AND the description. A flat baht amount is accepted as the alternative.
     *
     * <p>The printed shape is the owner's own QN6900704-2: จำนวน −1, no unit, a POSITIVE ราคา and
     * คงเหลือ, and a NEGATIVE เป็นเงิน. Note that this falls out of the ordinary arithmetic rather
     * than being special-cased at print time — {@code amount = quantity × netPrice} with a
     * quantity of −1 IS the negative figure, which is presumably why she writes it that way.
     */
    private NewItem buildAdjustmentItem(ItemInput input, Integer rowNumber, BigDecimal adjustmentBase,
                                        BigDecimal vatRate) {
        BigDecimal amount;
        String description;
        if (input.adjustmentPct() != null) {
            amount = WastageCalculator.adjustmentAmount(adjustmentBase, input.adjustmentPct());
            description = DealQuotationLines.adjustmentDescription(
                input.adjustmentPct(), input.adjustmentDeadline());
        } else {
            amount = money2(input.adjustmentAmount());
            // A flat adjustment has no percent to compose from, so the rep's own wording wins;
            // failing that, the same phrasing minus the percentage.
            description = isBlank(input.description())
                ? DealQuotationLines.adjustmentDescription(null, input.adjustmentDeadline())
                : input.description().trim();
        }
        if (amount.signum() <= 0 && rowNumber != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + rowNumber + ": ส่วนลดพิเศษต้องมากกว่าศูนย์");
        }
        BigDecimal lineAmount = amount.negate();
        BigDecimal vat = money2(lineAmount.multiply(vatRate));
        return new NewItem(
            input.locationLabel(), null, null,
            null, null, null, null, null,
            null, null,
            null, null, null,
            null, null, null,
            0, 0, 0, null,
            // ราคา and คงเหลือ both print the POSITIVE magnitude; only เป็นเงิน is negative.
            amount, null, amount, lineAmount,
            vat, lineAmount.add(vat),
            null, null, null, input.itemNotes(),
            description,
            WastageCalculator.LINE_TYPE_ADJUSTMENT, BigDecimal.valueOf(-1), null,
            null, input.adjustmentPct(), input.adjustmentDeadline());
    }

    /** v3b: {@code vatRate} is the SOURCE document's, so a revision of an English quotation copies
     * 0.00 VAT rows rather than re-stamping 7% onto them. */
    private NewItem toNewItemFromDto(DealQuotationItemDto item, BigDecimal vatRate) {
        BigDecimal vat = money2(item.lineAmount().multiply(vatRate));
        BigDecimal lineTotal = item.lineAmount().add(vat);
        return new NewItem(
            item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(),
            item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(),
            item.wastageMode(), item.wastageValue(), item.piecesPerBox(),
            item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(), item.boxes(),
            item.unitPrice(), item.discountPct(), item.netUnitPrice(), item.lineAmount(),
            vat, lineTotal,
            item.originCountry(), item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            item.descriptionLine(),
            // v3: a revision copies its parent's rows VERBATIM, adjustments included — it is a copy,
            // not a recalculation, so an already-approved ส่วนลดพิเศษ figure is carried across
            // rather than re-derived (re-deriving would silently move the number if the parent had
            // been saved under an older rule).
            item.lineType(), item.quantity(), item.unit(),
            item.specialPriceSqm(), item.adjustmentPct(), item.adjustmentDeadline());
    }

    // ProductPriceDto's/price_catalog.product_prices' own price_unit for a linear-metre trim
    // (V153: 561 real catalog rows). Its sqm_per_piece is NOT an area for these rows -- it is
    // LINEAR METRES per piece (the source sqm_per_box column is mislabelled the same way, per that
    // migration's own column comment) -- so #sqmPerPieceFromCatalog must never read it as one, nor
    // ever compute a geometric fallback for it (width_mm x height_mm there describes the PROFILE,
    // not a tile face). Mirrors frontend/src/features/quotations/QuotationItemRow.jsx's own
    // PRICE_UNIT_PER_LINEAR_M constant -- kept as its own literal here (not a shared enum) because
    // the two sides have no shared constants module; if this ever drifts from CatalogRepository's
    // own column values, {@code sqmPerPiece_neverGeometricOrCatalogForPerLinearMRows} below breaks.
    private static final String PRICE_UNIT_PER_LINEAR_M = "per_linear_m";
    private static final BigDecimal SQ_MM_PER_SQM = new BigDecimal("1000000");

    /**
     * Owner ruling 2026-09-12 ("2) ไม่มีค่อยคำนวนเอง"): resolve a tile's ตร.ม./แผ่น from the
     * CATALOGUE, never by guessing the unit of a free-text size string — see
     * {@code WastageCalculator}'s deleted {@code parseSqmPerPieceFromSize}/{@code detectUnit} for
     * the heuristic this replaces. It got a REAL catalog product wrong: "200x300" is not &gt; 300
     * on either side, so it read as CENTIMETRES (6.0 sqm/piece) against the owner's own document
     * for that product (0.061 sqm/piece, 16.39 pcs/sqm) — wrong by ~98x.
     *
     * <p>Resolution order, in this priority, and NEVER inferred from {@code input.sizeText()}:
     * <ol>
     *   <li>the item's own {@code sqmPerPiece}, if Sales supplied or overrode one;</li>
     *   <li>else the catalog row's own {@code sqm_per_piece} (by {@code catalogPriceId}) — this is
     *       the AUTHORITATIVE figure; geometry disagrees with it on 8.6% of the real catalog (by
     *       up to 14x on trims), so it is never recomputed even when width/height are available;</li>
     *   <li>else, when the catalogue itself has no {@code sqm_per_piece}, COMPUTE it from that same
     *       row's {@code width_mm x height_mm / 1,000,000} — both columns are unambiguous
     *       millimetres, unlike the free-text size;</li>
     *   <li>else {@code null} — {@link #requireItemComplete} then fails the row with a plain Thai
     *       "ขาด ตร.ม./แผ่น" rather than this method ever inventing a number.</li>
     * </ol>
     *
     * <p>Steps 2 and 3 NEVER apply to a {@code per_linear_m} row — see
     * {@link #PRICE_UNIT_PER_LINEAR_M}'s own comment. Such a row falls straight from step 1 to
     * step 4 unless Sales types a value by hand.
     */
    private BigDecimal resolveSqmPerPiece(ItemInput input) {
        if (input.sqmPerPiece() != null) {
            return input.sqmPerPiece();
        }
        if (input.catalogPriceId() == null) {
            return null;
        }
        return catalog.findSqmBasis(input.catalogPriceId())
            .map(this::sqmPerPieceFromCatalog)
            .orElse(null);
    }

    private BigDecimal sqmPerPieceFromCatalog(CatalogSqmBasis basis) {
        if (PRICE_UNIT_PER_LINEAR_M.equals(basis.priceUnit())) {
            return null;
        }
        if (basis.sqmPerPiece() != null && basis.sqmPerPiece().signum() > 0) {
            return basis.sqmPerPiece();
        }
        if (basis.widthMm() != null && basis.heightMm() != null
            && basis.widthMm().signum() > 0 && basis.heightMm().signum() > 0) {
            return basis.widthMm().multiply(basis.heightMm())
                .divide(SQ_MM_PER_SQM, 6, RoundingMode.HALF_UP);
        }
        return null;
    }

    /**
     * <b>Type-aware price validation</b> — quotation v3, and the replacement for the
     * {@code @NotNull @DecimalMin("0.01")} that {@code ItemInput.unitPrice} used to carry.
     *
     * <p>That bean annotation could not survive S3: an ADJUSTMENT row has NO rep-typed price at
     * all (the system derives it from the rows above), so a blanket annotation would 400 the very
     * row type this pass exists to add. The rule was made TYPE-AWARE, not relaxed:
     *
     * <ul>
     *   <li><b>TILE</b> — still refuses a null, zero or negative {@code unitPrice}, exactly as
     *       before. In SPECIAL_SQM mode the ราคาพิเศษ must ALSO be positive, and in DIRECT_NET the
     *       typed net must; a mode whose own price is missing is a 400, never a silent fallback to
     *       the list price.</li>
     *   <li><b>PLAIN</b> — same positive-price rule. A freight or consumables line always carries
     *       a direct price; there is no such thing as a free one here.</li>
     *   <li><b>ADJUSTMENT</b> — IGNORES a rep-supplied {@code unitPrice} (it is derived from the
     *       rows above; see the inline note on why refusing it broke the GET→PUT round-trip), and
     *       requires EXACTLY one of {@code adjustmentPct} / {@code adjustmentAmount}.</li>
     * </ul>
     *
     * <p><b>Not gated on {@code rowNumber}</b>, unlike {@link #requireItemComplete}. The bean
     * annotation it replaces ran on the lenient {@code calculate-line} preview too, so gating this
     * would be a real loosening — a TILE row with a zero price would start previewing happily
     * where today it 400s. Completeness (รุ่น/สี/ผิว/ขนาด, a description, a quantity) stays
     * row-number-gated, because THAT is what has to tolerate a half-typed row.
     *
     * <p>{@code quantityMode}/{@code wastageMode} are re-required here for TILE rows only; they
     * lost their {@code @NotBlank} for the same S2/S3 reason (a PLAIN row has neither).
     */
    private void requirePriceValidForType(ItemInput input, String lineType, String priceMode,
                                          Integer rowNumber) {
        String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
        if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType)) {
            // ⚠️ v3, review fix F2: a supplied unitPrice on an ADJUSTMENT row is IGNORED, not
            // refused. It used to 400, which made a GET→PUT round-trip of any document containing
            // an adjustment IMPOSSIBLE: #toItemDto/#mapItem return unitPrice = the derived amount on
            // that very row (the ราคา column has to print it), so a UI that loads a draft and hands
            // the items back on save was rejected for echoing a field we ourselves sent. Ignoring is
            // safe because #buildAdjustmentItem never reads input.unitPrice() at all — the amount is
            // derived from adjustmentPct/adjustmentAmount either way, so a supplied price is
            // redundant rather than dangerous, and the caller still cannot dictate the discount.
            boolean hasPct = input.adjustmentPct() != null;
            boolean hasFlat = input.adjustmentAmount() != null;
            if (hasPct == hasFlat) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    where + "ส่วนลดพิเศษต้องระบุเป็นเปอร์เซ็นต์ หรือเป็นจำนวนเงิน อย่างใดอย่างหนึ่ง");
            }
            if (hasPct && input.adjustmentPct().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, where + "ส่วนลดพิเศษต้องมากกว่าศูนย์");
            }
            if (hasFlat && input.adjustmentAmount().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, where + "ส่วนลดพิเศษต้องมากกว่าศูนย์");
            }
            return;
        }
        // TILE and PLAIN both require a positive unit price — the pre-v3 rule, unchanged.
        if (input.unitPrice() == null || input.unitPrice().signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาต่อหน่วยต้องมากกว่าศูนย์");
        }
        if (!WastageCalculator.LINE_TYPE_TILE.equals(lineType)) {
            return;
        }
        if (isBlank(input.quantityMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "กรุณาระบุรูปแบบจำนวน");
        }
        if (isBlank(input.wastageMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "กรุณาระบุรูปแบบเผื่อ");
        }
        if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)
            && (input.specialPriceSqm() == null || input.specialPriceSqm().signum() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาพิเศษต้องมากกว่าศูนย์");
        }
        if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)
            && (input.directNetPrice() == null || input.directNetPrice().signum() <= 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาสุทธิต้องมากกว่าศูนย์");
        }
    }

    /** Item completeness rule — see {@link #buildItem(ItemInput, Integer)}'s own Javadoc. Collects
     * EVERY missing/invalid field before throwing (not just the first) so one 400 tells the rep
     * everything wrong with the row, not a one-at-a-time guessing game. */
    private void requireItemComplete(int rowNumber, ItemInput input, BigDecimal resolvedSqmPerPiece) {
        List<String> missing = new ArrayList<>();
        if (isBlank(input.model())) missing.add("รุ่น");
        if (isBlank(input.color())) missing.add("สี");
        if (isBlank(input.texture())) missing.add("ผิว");
        if (isBlank(input.sizeText())) missing.add("ขนาด");
        if (input.thicknessMm() == null || input.thicknessMm().signum() <= 0) missing.add("ความหนา");
        if (input.piecesPerBox() == null || input.piecesPerBox() < 1) missing.add("จำนวนแผ่นต่อกล่อง");
        if (resolvedSqmPerPiece == null || resolvedSqmPerPiece.signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (input.unitPrice() == null || input.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
        if (!hasQuantity(input.quantityMode(), input.areaSqm(), input.piecesInput())) missing.add("จำนวน");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + rowNumber + ": ขาด " + String.join(", ", missing));
        }
    }

    /** Same rule as {@link #requireItemComplete}, over an already-stored (post-derivation) row —
     * used only by {@link #submit}'s defensive re-check. {@code seq} is the item's own printed
     * row number, so the message names the same row the rep sees on screen. */
    private void requireStoredItemComplete(DealQuotationItemDto item) {
        // v3: the tile completeness rule applies to TILE rows only — a PLAIN row has no
        // รุ่น/สี/ผิว/ขนาด/ความหนา/แผ่นต่อกล่อง to be missing, and an ADJUSTMENT row has neither
        // those nor a rep-typed price. Running the tile checks over them would make every
        // document containing a freight line or a ส่วนลดพิเศษ un-submittable.
        if (!WastageCalculator.LINE_TYPE_TILE.equals(item.lineType())) {
            requireStoredNonTileComplete(item);
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(item.model())) missing.add("รุ่น");
        if (isBlank(item.color())) missing.add("สี");
        if (isBlank(item.texture())) missing.add("ผิว");
        if (isBlank(item.sizeText())) missing.add("ขนาด");
        if (item.thicknessMm() == null || item.thicknessMm().signum() <= 0) missing.add("ความหนา");
        if (item.piecesPerBox() == null || item.piecesPerBox() < 1) missing.add("จำนวนแผ่นต่อกล่อง");
        if (item.sqmPerPiece() == null || item.sqmPerPiece().signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (item.unitPrice() == null || item.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
        if (!hasQuantity(item.quantityMode(), item.areaSqm(), item.piecesInput())) missing.add("จำนวน");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + item.seq() + ": ขาด " + String.join(", ", missing));
        }
    }

    /** {@link #requireStoredItemComplete}'s PLAIN/ADJUSTMENT half — submit's last gate over an
     * already-stored row. Deliberately thin: what a non-tile row must have is a description and,
     * for PLAIN, a quantity and a price. Written over the STORED row (not the input) because that
     * is what the approver is about to see. */
    private void requireStoredNonTileComplete(DealQuotationItemDto item) {
        List<String> missing = new ArrayList<>();
        if (isBlank(item.descriptionLine())) missing.add("รายละเอียด");
        if (WastageCalculator.LINE_TYPE_PLAIN.equals(item.lineType())) {
            if (item.quantity() == null || item.quantity().signum() <= 0) missing.add("จำนวน");
            if (item.unitPrice() == null || item.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
            if (isBlank(item.unit())) missing.add("หน่วย");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + item.seq() + ": ขาด " + String.join(", ", missing));
        }
    }

    private boolean hasQuantity(String quantityMode, BigDecimal areaSqm, Integer piecesInput) {
        if (WastageCalculator.QUANTITY_MODE_PIECES.equals(quantityMode)) {
            return piecesInput != null && piecesInput >= 1;
        }
        // AREA is the default reading of any other/blank value -- matches WastageCalculator's own
        // interpretation (see its Input record's Javadoc).
        return areaSqm != null && areaSqm.signum() > 0;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Stateless preview shape (id=0, seq=0) for {@link #calculateLine} — no DB round trip. Must
     * stay in lockstep with {@code DealQuotationRepository#mapItem}, which builds the same DTO from
     * a stored row; the editor's live preview and the saved document have to agree line-for-line. */
    private DealQuotationItemDto toItemDto(long id, int seq, NewItem item) {
        // v3: via WastageCalculator, not an inline reciprocal — see #piecesPerSqm's Javadoc.
        BigDecimal piecesPerSqm = item.sqmPerPiece() != null && item.sqmPerPiece().signum() > 0
            ? WastageCalculator.piecesPerSqm(item.sqmPerPiece()) : null;
        boolean tile = WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        String sizeLine = tile ? DealQuotationLines.sizeLine(item.sizeText(), item.thicknessMm()) : null;
        String calculationLine = tile
            ? DealQuotationLines.calculationLine(item.quantityMode(), item.areaSqm(), piecesPerSqm,
                item.piecesBeforeWastage(), item.wastageMode(), item.wastageValue(), item.piecesFinal(),
                item.piecesPerBox())
            : null;
        return new DealQuotationItemDto(id, seq, item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            piecesPerSqm, item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(), item.boxes(),
            item.netUnitPrice(), item.lineAmount(), item.descriptionLine(), sizeLine, calculationLine,
            item.lineType(), item.quantity(), item.unit(), item.specialPriceSqm(), item.adjustmentPct(),
            item.adjustmentDeadline(), DealQuotationLines.specialPriceLine(item.specialPriceSqm()),
            DealQuotationLines.flatAdjustmentAmount(item.lineType(), item.adjustmentPct(), item.unitPrice()));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — every decision lives here (see class Javadoc).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** create/edit/submit/cancel/revise: {@code sales} on their OWN deals only (open question for
     * Ploy, per docs/sales/quotation-v2-plan.md: whether plain {@code sales} may act on another rep's deal —
     * implemented as NO, the conservative reading, until answered); {@code sales_manager} on any
     * deal; OR a {@code canCreateQuotation}-granted employee (owner ruling, Ploy 2026-09-09 —
     * ภิญญดา, employee 144, QC&amp;ISO / role {@code qc}) on ANY deal, regardless of role — the
     * grant is deliberately not further role-restricted; it is a per-person capability, not a
     * role. Read LIVE via {@link #hasQuotationGrant}, never from {@code actor}. */
    private void requireEditAccess(UserPrincipal actor, TicketSummaryDto ticket) {
        boolean grant = hasQuotationGrant(actor);
        if (!EDIT_ROLES.contains(actor.role()) && !grant) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales_manager".equals(actor.role()) || grant) {
            return; // any deal
        }
        if ("sales".equals(actor.role()) && ticket.createdById() == actor.id()) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
    }

    private void requireEditAccessForQuotation(UserPrincipal actor, DealQuotationDto quotation) {
        requireEditAccess(actor, requireTicketSummary(quotation.ticketId()));
    }

    /** view/list/download: {@link #VIEW_ROLES} (sales limited to own deals) OR a
     * {@code canCreateQuotation}-granted employee, who may view any deal — the grant already lets
     * them act on any deal, so it lets them view any deal too (a strict subset would mean they
     * could create a quotation they then couldn't look at). */
    private void requireViewAccess(UserPrincipal actor, DealQuotationDto quotation) {
        if (hasQuotationGrant(actor)) {
            return;
        }
        requireRole(actor, VIEW_ROLES);
        if ("sales".equals(actor.role())) {
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            if (ticket.createdById() != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }
    }

    /** Live DB read of the per-employee grant — see {@link EmployeeAuthRepository#canCreateQuotation}
     * for why this is never cached on {@code actor}. */
    private boolean hasQuotationGrant(UserPrincipal actor) {
        return employeeAuth.canCreateQuotation(actor.id());
    }

    /**
     * approve/reject: {@code sales_manager} or {@code ceo}. No self-exclusion (matches this
     * repo's existing convention — flagged as an open question in docs/sales/quotation-v2-plan.md).
     *
     * <p>Amount routing (&lt;1M sales_manager, &gt;1M CEO) is an OUT-OF-SYSTEM rule by owner
     * decision 2026-09-09 — do not add amount logic here.
     */
    private void requireApproveAccess(UserPrincipal actor) {
        requireRole(actor, APPROVE_ROLES);
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }

    private DealQuotationDto requireQuotation(long id) {
        return quotations.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
