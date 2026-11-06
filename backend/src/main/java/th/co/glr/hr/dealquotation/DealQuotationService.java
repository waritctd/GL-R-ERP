package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import th.co.glr.hr.ticket.DealOwnership;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.catalog.CatalogRepository.CatalogSqmBasis;
import th.co.glr.hr.commission.CommissionRepOptionDto;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
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
import th.co.glr.hr.notification.DealNoticeText;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.QuotationRecipient;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.RelatedDocumentType;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Direct deal quotation (quotation v2, see docs/sales/quotation-v2-plan.md). Sales adds items straight onto a
 * quotation with a typed unit price + discount; the server computes every number ({@link WastageCalculator});
 * sales_manager/ceo approves. Extends the same {@code sales.quotation} aggregate as {@code customerquotation/*}
 * (tagged {@code origin = 'DEAL_DIRECT'}), which stays untouched.
 *
 * <p>Every authz decision lives here, not in {@link DealQuotationController} (which only calls
 * {@code sessions.requireUser} and delegates).
 *
 * <p><strong>Per-employee grant:</strong> {@code hr.employee.can_create_quotation} lets someone outside ฝ่ายขาย
 * (e.g. QC&amp;ISO) create quotations. It is a plain boolean capability read LIVE via
 * {@link EmployeeAuthRepository#canCreateQuotation}, never inferred from the session principal. A grant lets its
 * holder create/edit/submit/cancel/revise and view/list/download on ANY deal ({@link #requireEditAccess} /
 * {@link #requireViewAccess}) but never approve/reject. Employee/qc/warehouse division roles also resolve to
 * {@code sales} ({@code DivisionAccessPolicy#roleFor}).
 */
@Service
public class DealQuotationService {
    private static final Logger log = LoggerFactory.getLogger(DealQuotationService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    // The VAT rate comes from WastageCalculator#vatRateFor(documentLanguage): an EN/F-SM-008 document has none, so a
    // private 0.07 here would contradict it.
    // A PLAIN row applies its own discount percent the same way WastageCalculator does for a tile (which keeps its
    // own copy: it is a no-Spring pure class).
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ผู้ติดต่อ's phone and email, printed on the document when filled. The limits are the widths of
    // sales.quotation.ordered_by_phone / ordered_by_email, so an over-long value is a 400, never a SQL error.
    private static final int ORDERED_BY_PHONE_MAX = 50;
    private static final int ORDERED_BY_EMAIL_MAX = 254;
    // One @, no whitespace, a dot in the domain: "somchai@example" is the ".com" nobody typed. The
    // editor checks the same shape before it saves (quotationMeta.js#orderedByEmailError); keep the
    // two in step, or the rep meets a refusal the form gave no warning of.
    private static final Pattern ORDERED_BY_EMAIL_SHAPE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private static final Set<String> EDIT_ROLES = Set.of("sales", "sales_manager");
    private static final Set<String> APPROVE_ROLES = Set.of("sales_manager", "ceo");
    private static final Set<String> VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import", "account");
    // VIEW_ROLES is the DEAL_DIRECT gate and is intentionally too broad for PRICING_REQUEST-origin quotations. This
    // set drops `import` (unlike CustomerQuotationService.VIEW_ROLES): on that origin the document's own
    // unitPrice/discountPct/netUnitPrice ARE the CEO's approved price and discount, and import must see nothing
    // price- or discount-shaped in this chain. There is no quotation-grant bypass either.
    //
    // `account` is handled outside this set: it may read a PRICING_REQUEST-origin quotation (number, total, net line
    // prices) and its PDF only on a deal inside the account list scope (TicketRepository#isInAccountScope); see
    // #requireViewAccess / #canViewPricingRequestOriginRow / #listForTicket. FinanceDealDto carries no list
    // price/discount.
    /**
     * For {@code account}, a deal quotation is visible only once it has gone to the customer: APPROVED (DEAL_DIRECT),
     * ISSUED (PRICING_REQUEST, see {@link DealQuotationRepository#approve}), SENT and ACCEPTED. Everything else is
     * refused on get / PDF / XLS / picture and dropped from listForTicket and search.
     */
    private static final Set<String> ACCOUNT_VISIBLE_STATUSES = Set.of("APPROVED", "ISSUED", "SENT", "ACCEPTED");

    private static final Set<String> PRICING_REQUEST_VIEW_ROLES = Set.of("sales", "sales_manager", "ceo");

    /** On the GLOBAL LIST ({@link #search}/{@link #counts}) only, sales_manager and ceo see every deal's quotations;
     * everyone else (including grant-holders) sees only quotations on deals they created. {@link #requireViewAccess},
     * {@link #get}, {@link #listForTicket} and {@link #renderPdf} are unaffected, so individual quotations stay
     * readable from the deal page. */
    private static final Set<String> LIST_SEE_ALL_ROLES = Set.of("sales_manager", "ceo");

    private final DealQuotationRepository quotations;
    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final NotificationRepository notifications;
    /** {@link #resolveSqmPerPiece} reads a tile's ตร.ม./แผ่น from this, never from the free-text size string. */
    private final CatalogRepository catalog;
    /** The per-employee "can create quotations" gate, read LIVE via {@link EmployeeAuthRepository#canCreateQuotation},
     * never from {@code actor}: a {@link UserPrincipal} is captured at login and goes stale when a grant changes. */
    private final EmployeeAuthRepository employeeAuth;
    /**
     * Unused since the plain-text approval email was retired (approval sends only the in-app
     * notification + branded HTML email). Kept so the constructor shape the tests build stays
     * stable; do NOT route a second approval email through it -- that is the double-email bug.
     */
    private final NotificationEmailService approvalMailer;
    private final th.co.glr.hr.ticket.QuotationRenderer renderer;
    private final EmployeeSignatureRepository signatures;
    private final String appBaseUrl;
    // Read-only dependencies onto the pricing-request/decision chain, used only by #createFromPricingRequest.
    // Setter-injected so hand-wired `new DealQuotationService(...)` tests keep compiling. Never returned to a caller:
    // only the numbers needed are copied onto the new quotation row, so the CEO-bearing decision DTOs never reach
    // the API.
    private PricingRequestRepository pricingRequests;
    private PricingDecisionRepository pricingDecisions;
    // Mutual exclusivity between the customerquotation/ create path and this class's for the same pricing request.
    // Setter-injected like the fields above.
    private th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotations;
    // Issuing a PRICING_REQUEST-origin quotation advances the deal's sales stage the same way
    // CustomerQuotationService#issue does. Setter-injected like the fields above.
    private th.co.glr.hr.ticket.TicketService ticketService;

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
                                // app.quotation.bank-block-line1..3: the unnumbered bank block on an ENGLISH document, kept in application.yml
                                // so a moved account needs no rebuild. Defaults are EMPTY on purpose: an unset block prints the
                                // proforma-invoice line instead of half-filled wire instructions.
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

    /** Setter-injected; see {@link #pricingRequests}. {@code required = false} so deployments and tests that never
     * call {@link #createFromPricingRequest} are unaffected. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wirePricingRequestDependencies(PricingRequestRepository pricingRequests,
                                        PricingDecisionRepository pricingDecisions,
                                        th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotations) {
        this.pricingRequests = pricingRequests;
        this.pricingDecisions = pricingDecisions;
        this.customerQuotations = customerQuotations;
    }

    /** Setter-injected; see {@link #ticketService}. {@code required = false} so a hand-wired test that never
     * exercises {@link #approveAndIssuePricingRequestOrigin} is unaffected (that path null-guards). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wireTicketService(th.co.glr.hr.ticket.TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto create(long ticketId, UpsertDealQuotationRequest request, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        requireEditAccess(actor, ticket);
        // Precedence (mirrored by mockApi.dealQuotations.create): authz 403 -> recipient 400 -> one-pricing-route 409 ->
        // live-quotation 409, all before anything is resolved or written.
        String recipientType = requireRecipientForCreate(request.recipientType());
        quotations.lockTicket(ticketId);
        // One pricing route per deal: the reverse of DirectQuotationLocks#requireNoLiveDirectQuotation. Only for
        // #create; a revision or reorder is not a new route choice, and #createFromPricingRequest IS the pricing route.
        th.co.glr.hr.ticket.DirectQuotationLocks.requireNoLivePricingRequest(tickets, ticketId);
        // One live direct quotation per deal. The 409 names the live one (liveQuotationId/number/docStatus, top-level in
        // the body) so the client can offer "แก้ไขฉบับนั้น" / "สร้างฉบับแก้ไข". Under #lockTicket so concurrent creates
        // cannot both pass.
        th.co.glr.hr.ticket.DirectQuotationLocks.requireNoLiveDirectQuotationForCreate(tickets, ticketId);
        ContactSnapshot contact = resolveContact(request.contactId(), null, ticket);
        String priceMode = resolvePriceMode(request.priceMode());
        String documentLanguage = resolveDocumentLanguage(request.documentLanguage());
        // #create only makes a DEAL_DIRECT document, so only the language half of the rule can bite.
        requirePriceModeAllowed(priceMode, documentLanguage, false);
        String currency = resolveCurrency(documentLanguage, request.currency());
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // A brand-new document's own date is today (Bangkok), the value #mapQuotation computes from issued_at = now().
        // Must run AFTER #buildItems: "has special pricing" is decided from the rows about to be written.
        String validityMode = resolveValidityMode(request.validityMode());
        boolean specialPricing = DealQuotationRenderAdapter.hasSpecialPricingForNewItems(priceMode, items);
        LocalDate validityUntil = requireValidityUntilForMode(validityMode, request.validityUntil(),
            LocalDate.now(BANGKOK), specialPricing);
        CustomerSnapshot customerSnapshot = customerSnapshot(ticket);
        // Print-only ผู้พิมพ์/พนักงานขาย name override, validated against the same eligible union the options endpoint
        // lists, so create never persists an id no client could have picked.
        Long printedByDisplayId = requireEligibleDisplayEmployeeId(request.printedByDisplayId());
        Long salesRepDisplayId = requireEligibleDisplayEmployeeId(request.salesRepDisplayId());
        // remainderMode/creditDays are irrelevant on a zero-deposit ("ไม่รับมัดจำ") document, whose whole-amount term is
        // fullPaymentTerm; see #noDeposit/#resolveFullPaymentTerm.
        boolean noDeposit = isZeroDeposit(request.depositPercent());
        String remainderMode = noDeposit ? null : blankToNull(request.remainderMode());
        Integer creditDays = noDeposit ? null : request.creditDays();
        requireValidCreditDays(remainderMode, creditDays);
        String fullPaymentTerm = resolveFullPaymentTerm(request.depositPercent(), request.fullPaymentTerm());
        // Refused here, before a quotation number is taken or anything is written.
        String orderedByPhone = resolveOrderedByPhone(request.orderedByPhone());
        String orderedByEmail = resolveOrderedByEmail(request.orderedByEmail());
        // Every document carries the revision suffix, including the first. The base number belongs to the DEAL
        // (.design/quote-stage-gates/VERSIONING.md): its family is continued, and a fresh base is minted only when it has
        // none. Serialised by #lockTicket so concurrent creates cannot race the number's UNIQUE constraint.
        DealQuotationRepository.QuotationNumber quotationNumber = quotations.nextNumberOnDeal(ticketId);
        long id = quotations.insertDraft(new InsertDraftParams(
            ticketId, quotationNumber.number(), actor.id(), ticket.createdById(),
            customerSnapshot.name(), customerSnapshot.address(),
            customerSnapshot.taxId(), customerSnapshot.phone(),
            contact,
            // A request-supplied projectName (even blank, to clear it) wins; null falls back to the deal's own project name.
            request.projectName() != null ? blankToNull(request.projectName()) : ticket.projectName(),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), remainderMode,
            creditDays, request.validityDays(), validityMode, validityUntil,
            blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency,
            printedByDisplayId, salesRepDisplayId,
            resolveOmitContactHonorific(request.omitContactHonorific()), fullPaymentTerm,
            // Neither a revision (parentQuotationId) nor a reorder clone (derivedFromQuotationId): both null. revisionNo is
            // the deal family's next suffix, not a hardcoded 1.
            subtotal, null, quotationNumber.revisionNo(), null, items,
            // The rep's ผู้รับ and its Thai label. Still DEAL_DIRECT, no source pricing request/decision.
            DealQuotationRepository.ORIGIN_DEAL_DIRECT, recipientType, QuotationRecipient.thaiLabel(recipientType),
            null, null,
            // ordered_by_name = the picked contact's name (null when none), written on every DRAFT save; stored only, never
            // printed (the ผู้สั่งซื้อ slot is always dotted). request.orderedByName() is ignored.
            contact.name(),
            orderedByPhone, orderedByEmail));
        advanceDealToRecipientStage(ticketId, recipientType, actor);
        // A brand-new document prints its project unless the request says otherwise, so only an explicit true needs
        // writing (the column DEFAULT is FALSE). A statement of its own, see DealQuotationRepository#updateHideProjectName.
        if (Boolean.TRUE.equals(request.hideProjectName())) {
            quotations.updateHideProjectName(id, true);
        }
        return requireQuotation(id);
    }

    /**
     * A quotation addressed to a recipient moves the deal onto that recipient's stage at creation. Called from
     * {@link #create}, {@link #createFromPricingRequest} and {@link #update}'s recipient change.
     *
     * <p>Pure delegation to {@code TicketService#autoAdvanceStage}, which writes straight to the repository and never
     * calls {@code requireStageMoveAllowed}; that keeps it non-circular with the fact gate and no-throw when the
     * target is unreachable.
     *
     * <p>The null check is for hand-wired tests only ({@link #ticketService} is {@code required = false}); a test
     * asserting on the stage must call {@code wireTicketService}.
     */
    private void advanceDealToRecipientStage(long ticketId, String recipientType, UserPrincipal actor) {
        if (ticketService == null) {
            return;
        }
        ticketService.advanceStageForCustomerQuotationIssue(ticketId, recipientType, actor);
    }

    /** A direct create must name its ผู้รับ: DESIGNER / OWNER / BUYER only. Returns the validated code. Both sentences
     * are mirrored verbatim by {@code mockApi.dealQuotations}. */
    private static String requireRecipientForCreate(String recipientType) {
        if (recipientType == null || recipientType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุผู้รับใบเสนอราคา");
        }
        return requireAddressableRecipient(recipientType);
    }

    private static String requireAddressableRecipient(String recipientType) {
        if (!QuotationRecipient.isAddressable(recipientType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่รองรับผู้รับใบเสนอราคา '" + recipientType + "'");
        }
        return recipientType;
    }

    // Create from an approved คำขอราคา. Reuses THIS engine (buildItem/WastageCalculator/insertDraft) rather than
    // forking it. Gated like CustomerQuotationService#create: sales-only, the owning rep only, deal ACTIVE, pricing
    // request APPROVED_FOR_QUOTATION, an APPROVED decision. Sales MAY edit price after create (see
    // DealQuotationDto#priceModeChangedFromCeo and DealQuotationItemDto#priceChangedFromCeo); there is no server-side
    // lock, only the CEO-required-when-changed gate #requireCeoApprovalIfChanged reads at approval time.

    @Transactional
    public DealQuotationDto createFromPricingRequest(long pricingRequestId, UserPrincipal actor) {
        if (pricingRequests == null || pricingDecisions == null) {
            // Only reachable if a deployment wires this bean without calling #wirePricingRequestDependencies.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        if (!"sales".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        PricingRequestSummaryDto summary = pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // Owner-only, like CustomerQuotationService#requireOwner: a sales_manager is NOT admitted here (unlike
        // DealQuotationService#EDIT_ROLES for editing an EXISTING quotation).
        if (!DealOwnership.isOwnerOrKeyer(summary.ticketCreatedById(), summary.ticketKeyedById(), actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        if (!th.co.glr.hr.ticket.DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
        // Two concurrent callers (a double-click; this endpoint has no clientRequestId dedupe) would otherwise both pass
        // the idempotent-replay check below and both INSERT, the second hitting UNIQUE(number) as a bare 500. Lock
        // BEFORE the idempotent check (same repository method as CustomerQuotationService#create, same device as
        // #requireNoOpenRevision) so the second caller sees the first's DRAFT in #findOpenDraftForPricingRequest and
        // replays it. Needs the customerQuotations wiring, hence the null-guard.
        if (customerQuotations != null) {
            customerQuotations.lockPricingRequest(pricingRequestId);
        }
        // PR lock first, then the deal's advisory lock (see DealQuotationRepository#lockTicket); never reverse.
        quotations.lockTicket(summary.ticketId());
        // QUOTATION_ISSUED is tolerated at the STATUS level only, so an EXPIRED quotation does not dead-end the request:
        // sales may write a FRESH quotation from the same approved decision (the expired one stays as an audit record,
        // never revived). The pricing request never rolls back from QUOTATION_ISSUED, and recordOutcome leaves it there
        // for REJECTED / REVISION_REQUESTED too, so "AfterExpiry" understates what this gates. "No second live
        // quotation" is enforced once, for both branches, by #hasLivePricingRequestQuotation below.
        boolean prReadyForFirstQuotation = PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status());
        boolean prReadyForReplacementAfterExpiry = PricingRequestStatus.QUOTATION_ISSUED.equals(summary.status());
        if (!prReadyForFirstQuotation && !prReadyForReplacementAfterExpiry) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคาต้องอยู่ในสถานะ 'อนุมัติราคาขายแล้ว' ก่อนจึงจะออกใบเสนอราคาลูกค้าได้ (ปัจจุบัน: " + summary.status() + ")");
        }
        // Issuing any quotation retires every other live one on the deal (VERSIONING.md B-4), so this request can sit at
        // QUOTATION_ISSUED with its only document SUPERSEDED by a sibling request's issue; re-quoting would price off the
        // OLDER CEO decision and retire the sibling's NEWER offer. Only the LATEST row decides: an
        // EXPIRED/CANCELLED/REJECTED latest (incl. an expired revision of a superseded parent) keeps re-creation open.
        if (prReadyForReplacementAfterExpiry) {
            quotations.findLatestForPricingRequest(pricingRequestId)
                .map(this::requireQuotation)
                .filter(q -> QuotationStatus.SUPERSEDED.equals(q.docStatus()))
                .ifPresent(q -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "คำขอราคานี้ถูกแทนที่ด้วยใบเสนอราคาฉบับใหม่ของดีลแล้ว — ออกใบเสนอราคาจากคำขอราคาล่าสุด หรือสร้างคำขอราคาใหม่");
                });
        }
        PricingDecisionDto decision = pricingDecisions.findByPricingRequest(pricingRequestId).stream()
            .filter(d -> "APPROVED".equals(d.status()))
            .max(java.util.Comparator.comparingInt(PricingDecisionDto::decisionVersionNo))
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ยังไม่มีราคาขายที่ CEO อนุมัติสำหรับคำขอราคานี้"));
        // The legacy margin/"ปรับราคาเอง" formula path never sets price_mode; refuse it explicitly (409, not a silent
        // fallback) so a legacy request keeps using CustomerQuotationService's own create().
        if (decision.priceMode() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้ยังใช้รูปแบบราคาเดิม (ก่อน CEO เลือกวิธีกรอกราคา) กรุณาสร้างใบเสนอราคาแบบเดิมจากหน้านี้");
        }
        // Mutual exclusivity: a PR must never carry both an OPEN old-flow customer quotation and a new-flow one. This
        // catches "someone already used the OLD create button"; see CustomerQuotationService#create for the other
        // direction.
        if (customerQuotations != null && customerQuotations.hasLiveQuotation(pricingRequestId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้มีใบเสนอราคาลูกค้า (แบบเดิม) อยู่แล้ว ไม่สามารถสร้างใบเสนอราคาแบบใหม่ซ้ำได้");
        }
        // Idempotent replay guard: a double-click (or retry; no clientRequestId, no body) must not mint a second DRAFT
        // for the same pricing request. Returns the existing one rather than 409ing, since either outcome satisfies a
        // re-click.
        Optional<Long> existingDraftId = quotations.findOpenDraftForPricingRequest(pricingRequestId);
        if (existingDraftId.isPresent()) {
            return requireQuotation(existingDraftId.get());
        }
        // The one unconditional "no second LIVE quotation" guardrail. The pricing request stays APPROVED_FOR_QUOTATION
        // for the whole PENDING_APPROVAL window (it only moves to QUOTATION_ISSUED at actual issue), so without this a
        // second call while the first sat PENDING_APPROVAL would mint a second live one past the DRAFT check above.
        // Catches the live-but-non-DRAFT case: PENDING_APPROVAL or (not yet expired) ISSUED.
        if (quotations.hasLivePricingRequestQuotation(pricingRequestId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้มีใบเสนอราคาที่ยังไม่หมดอายุอยู่แล้ว ไม่สามารถสร้างใบเสนอราคาใหม่ซ้ำได้");
        }

        Map<Long, PricingRequestItemDto> prItemsById = new java.util.HashMap<>();
        for (PricingRequestItemDto pri : pricingRequests.findItems(pricingRequestId)) {
            prItemsById.put(pri.id(), pri);
        }
        List<NewItem> items = new ArrayList<>();
        int rowNumber = 1;
        for (PricingDecisionItemDto di : decision.items()) {
            PricingRequestItemDto pri = prItemsById.get(di.pricingRequestItemId());
            if (pri == null) {
                // Defence in depth: every pricing_decision_item is created FROM a pricing_request_item of the same request
                // (PricingDecisionService), so this should be unreachable.
                throw new ApiException(HttpStatus.CONFLICT, "ข้อมูลคำขอราคาไม่ครบถ้วน (รายการที่ผูกไว้หายไป)");
            }
            ItemInput input = buildItemInputFromDecisionItem(pri, di, decision.priceMode());
            NewItem built = buildItem(input, rowNumber, decision.priceMode(),
                WastageCalculator.DOCUMENT_LANGUAGE_TH, BigDecimal.ZERO);
            items.add(withDecisionLink(built, pri.id(), di.id()));
            rowNumber++;
        }
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());

        ContactSnapshot contact = resolveContact(summary.recipientContactId(), null, ticket);
        CustomerSnapshot customerSnap = customerSnapshot(ticket);
        // Header terms carried over from the pricing request; editable afterwards like every other draft header field.
        Integer creditDays = "CREDIT".equals(summary.paymentTermMode()) ? summary.creditDays() : null;

        // A genuinely NEW, independent create: not wired through parentQuotationId/createRevision, so no revision-chain
        // linkage or ancestor-supersede behaviour; only the printed NUMBER continues. The base number belongs to the DEAL
        // (.design/quote-stage-gates/VERSIONING.md) and is continued across pricing requests and origins by the same
        // counter #create and #insertCopyOf use. prReadyForReplacementAfterExpiry only decides whether an expired round
        // may be re-created; it does not affect numbering.
        DealQuotationRepository.QuotationNumber quotationNumber = quotations.nextNumberOnDeal(summary.ticketId());
        long id = quotations.insertDraft(new InsertDraftParams(
            summary.ticketId(), quotationNumber.number(), actor.id(), ticket.createdById(),
            customerSnap.name(), customerSnap.address(), customerSnap.taxId(), customerSnap.phone(),
            contact,
            ticket.projectName(), blankToNull(summary.deptCode()), blankToNull(summary.unitCode()),
            null, // offerDate: not carried from the PR; rep fills in on the draft, same as DEAL_DIRECT.
            null, // depositPercent: engine default (unset); rep chooses it.
            null, // remainderMode: resolved once a deposit percent is chosen.
            creditDays, summary.validityDays(),
            WastageCalculator.VALIDITY_MODE_DAYS, null,
            blankToNull(summary.note()),
            decision.priceMode(), WastageCalculator.DOCUMENT_LANGUAGE_TH, "THB",
            summary.printedByDisplayId(), summary.salesRepDisplayId(),
            summary.omitContactHonorific(), null,
            subtotal, null, quotationNumber.revisionNo(), null, items,
            "PRICING_REQUEST", summary.recipientType(), summary.recipientLabel(),
            summary.id(), decision.id(),
            // ordered_by_name = the recipient contact's name, as #create writes it (never printed); no phone or email.
            contact.name(), null, null));
        // The same creation-time advance #create performs, on the recipient carried over from the source คำขอราคา; this
        // is the call that gets a PCR-route deal to ขั้น 4.
        advanceDealToRecipientStage(summary.ticketId(), summary.recipientType(), actor);

        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_CREATED, summary.status(), summary.status(),
            "สร้างร่างใบเสนอราคาลูกค้า (เครื่องมือใบเสนอราคาแบบ direct)", null);
        tickets.addEvent(summary.ticketId(), actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
            "สร้างร่างใบเสนอราคา " + quotationNumber.number() + " จากคำขอราคา " + summary.requestCode());
        return requireQuotation(id);
    }

    /** Read-only lookup so {@code PricingRequestDetailPage}'s "ใบเสนอราคาลูกค้า" panel can show a NEW-engine
     * quotation's number/status/link without {@code createFromPricingRequest} (which would mint or replay a draft:
     * wrong for a page that must not have create side effects on render). Returns empty when this PR has no
     * PRICING_REQUEST-origin quotation yet.
     *
     * <p>Uses {@link DealQuotationRepository#findLatestForPricingRequest} (the current quotation regardless of
     * status), not the DRAFT-only {@code findOpenDraftForPricingRequest}: a DRAFT-only lookup returns empty for every
     * quotation past DRAFT, which would hide the outcome panel and the expiry re-create UI. Same
     * {@link #requireViewAccess} gate as {@code GET /deal-quotations/{id}}: narrower than DEAL_DIRECT's, no account,
     * no quotation-grant bypass. */
    public Optional<DealQuotationDto> findForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        Optional<Long> id = quotations.findLatestForPricingRequest(pricingRequestId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        DealQuotationDto quotation = requireQuotation(id.get());
        requireViewAccess(actor, quotation);
        return Optional.of(forViewer(quotation, actor));
    }

    /** One decision item's CEO-approved price, expressed as the {@link ItemInput} fields {@link #buildItem} expects
     * for the decision's {@code priceMode}. Mirrors {@code QuotationEditorPage.jsx}'s per-mode input mapping so
     * {@link NewItem#netUnitPrice()} comes out EQUAL to {@code di.netUnitPrice()} (asserted in
     * {@code PricingRequestQuotationIntegrationTest}). Every other field is the physical data Sales typed on the
     * pricing request, see {@link PricingRequestItemDto}. */
    private ItemInput buildItemInputFromDecisionItem(PricingRequestItemDto pri, PricingDecisionItemDto di,
                                                      String priceMode) {
        // unitPrice ("ราคา") is set for EVERY mode: WastageCalculator#requirePriceValidForType needs a positive unitPrice
        // on every non-perSqm TILE row, and buildTileItem only READS it for NET's math.
        // Under SPECIAL_SQM / DIRECT_NET the printed ราคา is the net (the CEO's figure), so the cost x margin list price
        // never reaches the quotation. NET keeps list_unit_price (always auto-calculated) unless the CEO overrode it
        // (manualSellingPricePerRequestedUnit, NET-only).
        BigDecimal unitPrice = !WastageCalculator.PRICE_MODE_NET.equals(priceMode) ? di.netUnitPrice()
            : di.manualSellingPricePerRequestedUnit() != null
            ? di.manualSellingPricePerRequestedUnit() : di.listUnitPrice();
        BigDecimal discountPct = null;
        BigDecimal specialPriceSqm = null;
        BigDecimal directNetPrice = null;
        if (WastageCalculator.PRICE_MODE_NET.equals(priceMode)) {
            discountPct = di.discountPct() != null ? di.discountPct() : BigDecimal.ZERO;
        } else if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
            specialPriceSqm = di.specialPriceSqm();
        } else if (WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)) {
            directNetPrice = di.directNetPrice();
        }
        return new ItemInput(
            null, // locationLabel: not on the pricing request; rep fills in on the draft.
            pri.catalogPriceId(), pri.productCode(), pri.brand(), pri.model(), pri.color(), pri.texture(),
            pri.size(), pri.thicknessMm(), pri.sqmPerPiece(), pri.quantityMode(), pri.areaSqm(),
            pri.piecesInput(), pri.wastageMode(), pri.wastageValue(), pri.piecesPerBox(),
            unitPrice, discountPct, pri.originCountry(), pri.leadTimeMinDays(), pri.leadTimeMaxDays(),
            blankToNull(pri.productDescription()),
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            specialPriceSqm, directNetPrice, null, null, null,
            null, pri.sqmPerBox(), pri.roundToFullBox());
    }

    /** {@code item} carrying {@code prItemId}/{@code decItemId} as its {@link NewItem#pricingRequestItemId()}/
     * {@link NewItem#pricingDecisionItemId()}; {@link #buildItem} (shared with DEAL_DIRECT, which never sets a link)
     * has no reason to know about either. */
    private NewItem withDecisionLink(NewItem item, long prItemId, long decItemId) {
        return new NewItem(item.locationLabel(), item.catalogPriceId(), item.productCode(), item.brand(),
            item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(), item.wastageValue(),
            item.piecesPerBox(), item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(),
            item.boxes(), item.unitPrice(), item.discountPct(), item.netUnitPrice(), item.lineAmount(),
            item.vat(), item.lineTotal(), item.originCountry(), item.leadTimeMinDays(), item.leadTimeMaxDays(),
            item.itemNotes(), item.descriptionLine(), item.lineType(), item.quantity(), item.unit(),
            item.specialPriceSqm(), item.adjustmentPct(), item.adjustmentDeadline(), item.catalogWidthMm(),
            item.catalogHeightMm(), item.sqmPerBox(), item.roundToFullBox(), prItemId, decItemId,
            // Whatever the row already carries (a revision's copy of its parent's typed prices; null on a row freshly built
            // from the CEO's decision).
            item.listPriceSqm(), item.directNetPriceSqm(),
            // Always null today (this origin can never be MIXED, see #requirePriceModeAllowed), passed through so that stays
            // a rule rather than an accident of this constructor.
            item.priceMode(),
            // A revision copies the declaration; a PRICING_REQUEST row's INSERT re-reads it from its pricing-request line.
            item.stockSource());
    }

    /** Refuses changing a CEO-linked line's TYPE (must stay TILE, the only type {@link #createFromPricingRequest}
     * produces) or its PRODUCT IDENTITY (catalog link, product code, brand, model, colour, texture, size, thickness,
     * sqm/piece, pcs/box): the fields the CEO actually priced. Quantity and เผื่อ (wastage) stay freely editable;
     * sales may edit price/quantity and the CEO re-approves price changes, so this guards the OFFER identity, not the
     * numbers. {@code stored} is null only if {@code inputId} is linked per
     * {@link DealQuotationRepository#findItemLinks} yet absent from {@code existing.items()}; unreachable (same
     * transaction), guarded to avoid an NPE. */
    private void requireLinkedLineIdentityUnchanged(DealQuotationItemDto stored, NewItem incoming) {
        if (stored == null) {
            return;
        }
        boolean changed = !"TILE".equals(incoming.lineType())
            || !java.util.Objects.equals(stored.catalogPriceId(), incoming.catalogPriceId())
            || !java.util.Objects.equals(stored.productCode(), incoming.productCode())
            || !java.util.Objects.equals(stored.brand(), incoming.brand())
            || !java.util.Objects.equals(stored.model(), incoming.model())
            || !java.util.Objects.equals(stored.color(), incoming.color())
            || !java.util.Objects.equals(stored.texture(), incoming.texture())
            || !java.util.Objects.equals(stored.sizeText(), incoming.sizeText())
            || !bdEquals(stored.thicknessMm(), incoming.thicknessMm())
            || !bdEquals(stored.sqmPerPiece(), incoming.sqmPerPiece())
            || !java.util.Objects.equals(stored.piecesPerBox(), incoming.piecesPerBox());
        if (changed) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "แก้ไขชนิดหรือข้อมูลสินค้าของรายการที่ CEO อนุมัติไม่ได้ — หากต้องเปลี่ยนสินค้า กรุณาแก้ไขที่คำขอราคาต้นทางแล้วให้ CEO อนุมัติใหม่");
        }
    }

    /** Null-safe, scale-insensitive {@link BigDecimal} equality (60 and 60.00 compare equal); {@code equals()} is
     * not, and would false-positive on a value that round-trips through {@link WastageCalculator}'s rounding. */
    private static boolean bdEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────────────────

    public DealQuotationDto get(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireViewAccess(actor, quotation);
        return forViewer(quotation, actor);
    }

    /**
     * {@code account} sees a quotation's number, totals and each line's NET unit price, never the list price,
     * discount or CEO pricing fields, on every origin and every JSON read it can reach. Other roles get the DTO
     * unchanged.
     */
    private static DealQuotationDto forViewer(DealQuotationDto quotation, UserPrincipal actor) {
        return "account".equals(actor.role()) ? quotation.withoutPriceInternals() : quotation;
    }

    /** The quotation, for a caller who may EDIT it: applies {@link #update}'s identity rule
     * ({@link #requireEditAccessForQuotation}), no status rule. Package-private for
     * {@link DealQuotationAttachmentService}: who may attach a file is who may edit the quotation, written once here. */
    DealQuotationDto getForEdit(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        return quotation;
    }

    /** Whether a revision of this quotation is still DRAFT or PENDING_APPROVAL, which is what {@link #cancel} refuses
     * on. Takes the deal's lock first like cancel, so a resubmission minting one right now is waited for and then
     * seen. Package-private, for {@link DealQuotationAttachmentService}. */
    boolean hasOpenRevisionUnderLock(DealQuotationDto quotation) {
        quotations.lockTicket(quotation.ticketId());
        return quotations.hasOpenRevision(quotation.id());
    }

    public List<DealQuotationDto> listForTicket(long ticketId, UserPrincipal actor) {
        TicketSummaryDto ticket = requireTicketSummary(ticketId);
        // account is row-scoped: before the grant bypass, like #requireViewAccess.
        if ("account".equals(actor.role())) {
            requireAccountScope(ticketId);
        } else if (!hasQuotationGrant(actor)) {
            requireRole(actor, VIEW_ROLES);
            if ("sales".equals(actor.role()) && !DealOwnership.isOwnerOrKeyer(ticket, actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
            }
        }
        // The entry gate above is DEAL_DIRECT's broad rule (VIEW_ROLES + a full hasQuotationGrant bypass): fine where
        // price is the rep's own typed number, too broad for a PRICING_REQUEST row, where unitPrice/discountPct/
        // netUnitPrice ARE the CEO's approved price/discount. #findByTicket's result set includes PRICING_REQUEST rows, so
        // each is filtered here (dropped, not field-stripped, like search/counts); otherwise import could read
        // discountPct off one via this endpoint.
        List<DealQuotationDto> rows = quotations.findByTicket(ticketId);
        List<DealQuotationDto> visible = new ArrayList<>(rows.size());
        for (DealQuotationDto row : rows) {
            if ("account".equals(actor.role()) && !ACCOUNT_VISIBLE_STATUSES.contains(row.docStatus())) {
                continue;
            }
            if (!"PRICING_REQUEST".equals(row.origin()) || canViewPricingRequestOriginRow(actor, row)) {
                visible.add(forViewer(row, actor));
            }
        }
        return visible;
    }

    /**
     * Which of {@code visible} (one viewer's {@link #listForTicket} result) the deal's order was confirmed from, or
     * {@code null}. Read from the deal's order-confirmation event, the same fact the payable-quotation rule uses. Only an
     * id already in {@code visible} is returned, so this never names a quotation the viewer cannot see.
     */
    public Long orderConfirmedQuotationId(long ticketId, List<DealQuotationDto> visible) {
        return tickets.findOrderConfirmedQuotationId(ticketId)
            .filter(id -> visible.stream().anyMatch(row -> row.id() == id))
            .orElse(null);
    }

    /** The approver queue: sales_manager/ceo see everything; sales, import, account and any
     * {@code canCreateQuotation}-granted employee see only quotations on deals THEY created (the grant reaches this
     * endpoint but does not widen the LIST scope). */
    public List<DealQuotationDto> search(List<String> statuses, boolean needsRework, UserPrincipal actor) {
        return search(statuses, needsRework, null, actor);
    }

    /**
     * {@code GET /api/deal-quotations}: DEAL_DIRECT and PRICING_REQUEST rows as editable rows, legacy v1 rows
     * ({@code origin IS NULL}) tagged {@code LEGACY} and {@code readOnly}. Every row carries its deal's
     * {@code ticketCode}/{@code dealStage}.
     *
     * <p>{@code origin} (optional) narrows to exactly one of {@code DEAL_DIRECT}, {@code PRICING_REQUEST},
     * {@code LEGACY}; blank reads as absent; anything else is 400. The scope is {@link #listOwnerScope} for every
     * origin; the price-bearing origins keep their narrower read rule (see {@link #canViewPricedOriginsOnList}).
     */
    public List<DealQuotationDto> search(List<String> statuses, boolean needsRework, String origin,
                                         UserPrincipal actor) {
        Long owner = listOwnerScope(actor);
        // account sees only customer-facing statuses, with price internals stripped.
        return quotations.search(statuses, owner, needsRework, parseListOrigin(origin),
                canViewPricedOriginsOnList(actor)).stream()
            .filter(row -> !"account".equals(actor.role()) || ACCOUNT_VISIBLE_STATUSES.contains(row.docStatus()))
            .map(row -> forViewer(row, actor)).toList();
    }

    /** Per-status counts for the list page's tab labels: the SAME scope decision as {@link #search}, so a tab's count
     * is the size of the list it would show. */
    public DealQuotationCountsDto counts(UserPrincipal actor) {
        return counts(null, actor);
    }

    /** {@link #counts(UserPrincipal)} under the same optional {@code origin} filter {@link #search} takes, validated
     * identically so tabs and list agree under a filter. */
    public DealQuotationCountsDto counts(String origin, UserPrincipal actor) {
        Long owner = listOwnerScope(actor);
        return quotations.counts(owner, parseListOrigin(origin), canViewPricedOriginsOnList(actor));
    }

    private static final Set<String> LIST_ORIGIN_FILTERS = Set.of(DealQuotationRepository.ORIGIN_DEAL_DIRECT,
        DealQuotationRepository.ORIGIN_PRICING_REQUEST, DealQuotationRepository.ORIGIN_LEGACY);

    /** null/blank → no filter; exact (case-sensitive) member of {@link #LIST_ORIGIN_FILTERS}; else 400. */
    private static String parseListOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        String value = origin.trim();
        if (!LIST_ORIGIN_FILTERS.contains(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ไม่รองรับประเภทใบเสนอราคา '" + origin + "' (ใช้ได้: DEAL_DIRECT, PRICING_REQUEST, LEGACY)");
        }
        return value;
    }

    /**
     * May this caller see PRICING_REQUEST and LEGACY rows on the widened LIST? Both carry a pricing-chain price, so
     * the list applies the role rule {@link #canViewPricingRequestOriginRow} applies to a single row:
     * {@link #PRICING_REQUEST_VIEW_ROLES} only, no {@link #hasQuotationGrant} bypass. The "sales sees only their own
     * deal" half is already {@link #listOwnerScope}'s. Without this, the list would show import/account/grant-holders
     * a price the detail endpoint refuses (403).
     */
    private boolean canViewPricedOriginsOnList(UserPrincipal actor) {
        return PRICING_REQUEST_VIEW_ROLES.contains(actor.role());
    }

    /**
     * The ONE list-scope decision {@link #search} and {@link #counts} share: the caller must hold a
     * {@link #VIEW_ROLES} role OR a {@code canCreateQuotation} grant to pass the entry gate; then only
     * {@code sales_manager}/{@code ceo} see every deal (returns {@code null}, unrestricted) and everyone else,
     * including a grant-holder, is scoped to deals they created (returns their own id as the owner filter).
     */
    private Long listOwnerScope(UserPrincipal actor) {
        boolean grant = hasQuotationGrant(actor);
        if (!grant) {
            requireRole(actor, VIEW_ROLES);
        }
        return LIST_SEE_ALL_ROLES.contains(actor.role()) ? null : actor.id();
    }

    /** The server recomputes every number: this is the stateless preview the item editor calls on every keystroke
     * (debounced client-side), with ZERO writes. */
    public DealQuotationItemDto calculateLine(ItemInput input, UserPrincipal actor) {
        return calculateLine(input, null, actor);
    }

    /**
     * The preview takes the DOCUMENT's language ({@code ?documentLanguage=EN}), so the editor's live line reads
     * exactly what the saved document will print (English lines, and on English the per-sqm quantity). Null/blank
     * is TH.
     */
    public DealQuotationItemDto calculateLine(ItemInput input, String documentLanguage, UserPrincipal actor) {
        if (!EDIT_ROLES.contains(actor.role()) && !hasQuotationGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        // The preview carries no quotation, so no price mode to read. #inferPriceMode takes it from the row: the mode the
        // row STATES when it states one (a mixed document's row always does), otherwise a ราคาพิเศษ means SPECIAL_SQM, a
        // typed net (per แผ่น or per ตร.ม.) means DIRECT_NET, and neither means NET. It cannot disagree with the saved
        // document, because #buildTileItem prices the row from those same fields.
        // It has no language either: TH, whose 7% is what the stored per-item vat column holds. The preview never shows
        // that column and the saved row is recomputed by #buildItems with the document's REAL language.
        String language = resolveDocumentLanguage(documentLanguage);
        if (!WastageCalculator.DOCUMENT_LANGUAGE_TH.equals(language)
            && !WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(language)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ภาษาเอกสารไม่ถูกต้อง: " + language);
        }
        String priceMode = inferPriceMode(input);
        return toItemDto(0L, 0, buildItem(input, null, priceMode, language, BigDecimal.ZERO), language, priceMode);
    }

    // Update (DRAFT-only; item ids are STABLE across a save, see the comment in #update)

    @Transactional
    public DealQuotationDto update(long id, UpsertDealQuotationRequest request, UserPrincipal actor) {
        DealQuotationDto existing = requireQuotation(id);
        TicketSummaryDto ticket = requireTicketSummary(existing.ticketId());
        // #requireEditAccessForQuotation is origin-aware (it uses `existing`, loaded above); the raw
        // #requireEditAccess(actor, ticket) would let a `can_create_quotation` grant holder of ANY role update a
        // PRICING_REQUEST-origin quotation.
        requireEditAccessForQuotation(actor, existing);
        // ผู้รับ on update. Omitted/null keeps the stored value (an old client, or the editor on a PRICING_REQUEST row,
        // which never sends it). A VALUE is refused before anything is computed or persisted, in
        // mockApi.dealQuotations.update's order: not a DRAFT -> 409; a PRICING_REQUEST row (its recipient belongs to its
        // คำขอราคา) -> 409, whatever the value; not DESIGNER/OWNER/BUYER -> 400.
        String requestedRecipient = request.recipientType();
        if (requestedRecipient != null) {
            if (!QuotationStatus.DRAFT.equals(existing.docStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
            }
            // A first-issue PRICING_REQUEST row keeps its request's recipient.
            if ("PRICING_REQUEST".equals(existing.origin()) && existing.parentQuotationId() == null) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "ผู้รับของใบเสนอราคาจากคำขอราคามาจากคำขอราคา — แก้ที่คำขอราคาต้นทาง");
            }
            requireAddressableRecipient(requestedRecipient);
            // A REVISION (parentQuotationId set, either origin) keeps its parent's recipient. One chain = one party, so the
            // register's base+recipient grouping and the parentQuotationId lineage cannot disagree; a different party is a
            // NEW quotation on the deal. Refuse only a CHANGE: the editor re-sends the stored recipient on every DEAL_DIRECT
            // save.
            if (existing.parentQuotationId() != null && !requestedRecipient.equals(existing.recipientType())) {
                throw new ApiException(HttpStatus.CONFLICT, "ฉบับแก้ไขต้องส่งถึงผู้รับเดิม ("
                    + QuotationRecipient.thaiLabel(existing.recipientType())
                    + ") — หากจะเสนอราคาให้ผู้รับรายอื่น ให้สร้างใบเสนอราคาใหม่บนดีลนี้");
            }
        }
        // ล้าง: clearContact stores a blank snapshot whatever contactId says, so updateHeader also writes a null
        // ordered_by_name below.
        ContactSnapshot contact = Boolean.TRUE.equals(request.clearContact())
            ? new ContactSnapshot(null, null, null, null)
            : resolveContact(request.contactId(), existing.contactId(), ticket);
        // A MISSING priceMode on UPDATE keeps the STORED one. #resolvePriceMode's blank->NET default is right on CREATE
        // and catastrophic here: a client that omits the field (an old client, or a UI that PUTs only what it edited)
        // would silently REPRICE the document: every SPECIAL_SQM tile row back to list price, special_price_sqm NULL,
        // the ราคาพิเศษ sub-line gone, no error. DIRECT_NET is worse: its rep-typed net is not stored as its own column
        // (it IS final_unit_price), so nothing is left to fall back to. An EXPLICIT mode still wins, so the rep can
        // switch modes on a draft.
        String priceMode = isBlank(request.priceMode())
            ? resolvePriceMode(existing.priceMode())
            : resolvePriceMode(request.priceMode());
        // Same reasoning for documentLanguage: #resolveDocumentLanguage's blank->TH default is right on create and wrong
        // here. Omitting the field would flip an EN/USD document back to Thai: 7% VAT on an export quotation, relabelled
        // columns and signature slots, a changed total, silently, on a document a customer may have seen.
        String documentLanguage = isBlank(request.documentLanguage())
            ? resolveDocumentLanguage(existing.documentLanguage())
            : resolveDocumentLanguage(request.documentLanguage());
        // Checked on the RESOLVED pair, so it also catches a switch to English on an already-mixed draft and a save that
        // omits the mode on one, not only an explicit MIXED.
        requirePriceModeAllowed(priceMode, documentLanguage, "PRICING_REQUEST".equals(existing.origin()));
        // NOT enforced here: a TH<->EN switch must not carry ANY typed amount into the other currency as the same
        // number (list, net, per-sqm, PLAIN price, flat adjustment are all re-entered by the rep). That rule lives in the
        // EDITOR (QuotationEditorPage#changeLanguage, quotationMeta#rowsWithPricesCleared); this service cannot tell a
        // re-typed price from a stale one, so it prices whatever the PUT carries. Currency gets no "keep stored"
        // treatment: #resolveCurrency derives it from the resolved language and accepts only the matching one, so
        // passing it would only turn a legitimate language switch into a spurious 400.
        String currency = resolveCurrency(documentLanguage, request.currency());
        // Same "missing keeps stored" discipline as priceMode/documentLanguage: a client that omits validityMode on a PUT
        // must not flip a DATE document back to counting days from today.
        String validityMode = isBlank(request.validityMode())
            ? resolveValidityMode(existing.validityMode())
            : resolveValidityMode(request.validityMode());
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // Must run AFTER #buildItems, as in #create: "has special pricing" is decided from the rows about to be written,
        // so a discount cleared on THIS save already refuses DATE mode here, before anything is persisted.
        boolean specialPricing = DealQuotationRenderAdapter.hasSpecialPricingForNewItems(priceMode, items);
        LocalDate validityUntil = requireValidityUntilForMode(validityMode, request.validityUntil(),
            existing.quotationDate(), specialPricing);
        // Same validation as #create. A full PUT always carries the payload's own value (null included), so there is no
        // "missing keeps stored" case: null IS the legitimate "use the real name" value for these two Long fields.
        Long printedByDisplayId = requireEligibleDisplayEmployeeId(request.printedByDisplayId());
        Long salesRepDisplayId = requireEligibleDisplayEmployeeId(request.salesRepDisplayId());
        // Same rule as #create for a zero-deposit ("ไม่รับมัดจำ") document; a full PUT always carries the payload's own
        // depositPercent, so there is nothing stale to preserve.
        boolean noDeposit = isZeroDeposit(request.depositPercent());
        String remainderMode = noDeposit ? null : blankToNull(request.remainderMode());
        Integer creditDays = noDeposit ? null : request.creditDays();
        requireValidCreditDays(remainderMode, creditDays);
        String fullPaymentTerm = resolveFullPaymentTerm(request.depositPercent(), request.fullPaymentTerm());
        // The editor always sends its CURRENT value (the checkbox is always rendered), so there is no "missing keeps
        // stored" case: null on the wire means UNticked, as on create.
        boolean omitContactHonorific = resolveOmitContactHonorific(request.omitContactHonorific());
        // Same check as #create, before the header is written: a refused phone or email leaves the row as it was.
        String orderedByPhone = resolveOrderedByPhone(request.orderedByPhone());
        String orderedByEmail = resolveOrderedByEmail(request.orderedByEmail());
        // Compare-and-set FIRST, before touching an item row: a header update that finds the row no longer DRAFT (a
        // concurrent submit/approve) must leave the items untouched. Re-snapshot the ลูกค้า columns from the LIVE
        // customer row on every DRAFT save, because the deal card edits เลขที่ผู้เสียภาษี / โทร. in place and promises "the
        // values on screen at save time". updateHeader's WHERE keeps it DRAFT-only, so an issued/approved document stays
        // frozen.
        int rows = quotations.updateHeader(id, contact, customerSnapshot(ticket),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), remainderMode,
            creditDays, request.validityDays(), validityMode, validityUntil,
            blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency, subtotal,
            printedByDisplayId, salesRepDisplayId,
            // An editable header field: the editor always sends its CURRENT value, so there is no "missing keeps stored"
            // case; blankToNull(null) clears it, like every other free-text header field on this call.
            blankToNull(request.projectName()),
            omitContactHonorific, fullPaymentTerm,
            // Re-derived from the resolved contact on every DRAFT save, as in #create; request.orderedByName() is ignored.
            contact.name(),
            // Likewise a blank phone and a blank email.
            orderedByPhone, orderedByEmail);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
        }
        // Persist a sent ผู้รับ. The header CAS just proved the row is still a DRAFT in this transaction and
        // updateRecipient's WHERE re-states DRAFT + DEAL_DIRECT, so 0 rows can only mean the row moved under us.
        if (requestedRecipient != null) {
            boolean recipientChanged = !requestedRecipient.equals(existing.recipientType());
            String label = QuotationRecipient.thaiLabel(requestedRecipient);
            if (recipientChanged || !java.util.Objects.equals(label, existing.recipientLabel())) {
                if (quotations.updateRecipient(id, requestedRecipient, label) == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
                }
            }
            if (recipientChanged) {
                // Re-addressing a DRAFT corrects WHO the document is for, so the stage follows. Only on a genuine change (a no-op
                // re-save must not write a stage event). Forward-only, so a correction BACK to an earlier party leaves the deal
                // where the later one put it.
                advanceDealToRecipientStage(existing.ticketId(), requestedRecipient, actor);
            }
        }
        // UNLIKE the header fields above, a MISSING value here means "keep what is stored", not "clear it" (see
        // UpsertDealQuotationRequest#hideProjectName): a stale browser bundle sends none, and reading that as false
        // would start printing a project name the rep had hidden. So write only when the request states a value AND it
        // differs. The header CAS has just proven the row is still DRAFT and updateHideProjectName's WHERE re-states it,
        // so 0 rows can only mean the row moved under us.
        if (request.hideProjectName() != null && request.hideProjectName() != existing.hideProjectName()) {
            if (quotations.updateHideProjectName(id, request.hideProjectName()) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
            }
        }
        // Item ids are STABLE across a draft save: a full delete+insert minted new ids on every save, so a client reusing
        // ids from an earlier load (after its own autosave, a second tab, a racing save) silently lost every picture; see
        // #orderedInputs' Javadoc for the ordering this relies on. Walk the inputs in STORED order: an input whose id is
        // one of THIS quotation's current item ids, and not already claimed earlier in this payload, is UPDATED IN PLACE
        // (same quotation_item_id, so its picture survives). Everything else (null, foreign, stale or duplicate id) is
        // INSERTED as a new row. Rows the payload did not claim are deleted FIRST, and their picture dropped unless a
        // parent revision still uses it.
        List<ItemInput> ordered = orderedInputs(request.items());
        Set<Long> currentItemIds = new HashSet<>(quotations.findItemIds(id));
        Set<Long> claimedItemIds = new HashSet<>();
        List<DealQuotationRepository.ExistingItem> updates = new ArrayList<>();
        List<DealQuotationRepository.SeqItem> inserts = new ArrayList<>();
        // A PRICING_REQUEST-origin quotation's items come ENTIRELY from #createFromPricingRequest: adding an extra row
        // here (TILE, PLAIN or ADJUSTMENT) is refused; extra items are typed and priced on the pricing request. A
        // DEAL_DIRECT document is unaffected. Every linked TILE row's pricing_request_item_id/pricing_decision_item_id
        // is carried forward across this save (#withDecisionLink); ItemInput has no field for either, so a link is set
        // once at create and never changed by the client.
        boolean pricingRequestOrigin = "PRICING_REQUEST".equals(existing.origin());
        Map<Long, DealQuotationRepository.ItemLink> linksByItemId = new java.util.HashMap<>();
        // The STORED shape of every current item, so a linked line's edit is compared against what the CEO actually
        // priced, not the payload's own claim. Only consulted when pricingRequestOrigin.
        Map<Long, DealQuotationItemDto> existingItemsById = new java.util.HashMap<>();
        for (DealQuotationItemDto existingItem : existing.items()) {
            existingItemsById.put(existingItem.id(), existingItem);
        }
        if (pricingRequestOrigin) {
            for (DealQuotationRepository.ItemLink link : quotations.findItemLinks(id)) {
                linksByItemId.put(link.itemId(), link);
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            int seq = i + 1;
            Long inputId = ordered.get(i).id();
            NewItem item = items.get(i);
            // Short-circuits before touching claimedItemIds for a null/foreign/stale id, so those never "claim" anything;
            // claimedItemIds.add returns false for a duplicate id, routing the SECOND occurrence to the insert branch.
            if (inputId != null && currentItemIds.contains(inputId) && claimedItemIds.add(inputId)) {
                DealQuotationRepository.ItemLink link = linksByItemId.get(inputId);
                if (link != null) {
                    // A CEO-linked line may have its quantity/เผื่อ edited freely, but not its LINE TYPE or PRODUCT IDENTITY: those
                    // are what the CEO priced. Dropping the line is allowed (below); silently swapping the product under the same row
                    // id is not.
                    requireLinkedLineIdentityUnchanged(existingItemsById.get(inputId), item);
                }
                NewItem stored = link != null
                    ? withDecisionLink(item, link.pricingRequestItemId(), link.pricingDecisionItemId())
                    : item;
                if (pricingRequestOrigin) {
                    // The pricing-request line is the truth for a PRICING_REQUEST-origin row; the client's tick is ignored.
                    stored = stored.withStockSource(existingItemsById.get(inputId).stockSource());
                }
                updates.add(new DealQuotationRepository.ExistingItem(inputId, seq, stored));
            } else if (pricingRequestOrigin) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ใบเสนอราคาจากคำขอราคานี้เพิ่มรายการใหม่ไม่ได้ — กรุณาเพิ่มรายการที่คำขอราคาต้นทาง แล้วให้ CEO กำหนดราคา");
            } else {
                inserts.add(new DealQuotationRepository.SeqItem(seq, item));
            }
        }
        // Every LINKED item id that existed before this save but was not claimed by this payload is about to be
        // hard-deleted by #deleteUnclaimedItems. Allowed (only swapping a linked line's identity is forbidden) but
        // flagged: +1 on the header counter per line, so the editor can show "ลบรายการที่ CEO อนุมัติ N รายการ".
        if (pricingRequestOrigin) {
            int droppedLinkedCount = 0;
            for (Long linkedItemId : linksByItemId.keySet()) {
                if (!claimedItemIds.contains(linkedItemId)) {
                    droppedLinkedCount++;
                }
            }
            if (droppedLinkedCount > 0) {
                quotations.incrementItemsRemovedFromCeo(id, droppedLinkedCount);
            }
        }
        List<Long> droppedPictureIds = quotations.deleteUnclaimedItems(id, claimedItemIds);
        quotations.deletePicturesIfUnreferenced(droppedPictureIds);
        quotations.updateItemsInPlace(id, updates);
        quotations.insertItemsAtSeq(id, inserts);
        return requireQuotation(id);
    }

    /** "คืนรายการ": re-adds a CEO-linked line a prior save dropped, rebuilding it FRESH from the SAME approved decision
     * item {@link #createFromPricingRequest} built it from, so its product fields and CEO figures match the original
     * rather than anything typed since. Appended as a NEW row (a fresh {@code quotation_item_id}; the dropped row was
     * deleted by {@link DealQuotationRepository#deleteUnclaimedItems}), re-establishing the same
     * pricing_request_item_id/pricing_decision_item_id link. */
    @Transactional
    public DealQuotationDto restoreRemovedItem(long id, long pricingDecisionItemId, UserPrincipal actor) {
        if (pricingRequests == null || pricingDecisions == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        DealQuotationDto existing = requireQuotation(id);
        requireEditAccessForQuotation(actor, existing);
        if (!QuotationStatus.DRAFT.equals(existing.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงแก้ไขไม่ได้");
        }
        if (!"PRICING_REQUEST".equals(existing.origin()) || existing.pricingRequestId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคานี้ไม่ได้มาจากคำขอราคา จึงไม่มีรายการให้คืน");
        }
        // Already live (never dropped, or a double-click on "คืนรายการ"): refuse rather than duplicate the line.
        boolean alreadyPresent = quotations.findItemLinks(id).stream()
            .anyMatch(link -> link.pricingDecisionItemId() == pricingDecisionItemId);
        if (alreadyPresent) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้อยู่ในใบเสนอราคาอยู่แล้ว");
        }
        // Reads through the quotation's OWN frozen pricing_decision_id, not "any APPROVED decision on this PR" (see
        // DealQuotationRepository#findPricingDecisionId).
        Long pricingDecisionId = quotations.findPricingDecisionId(id)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบมติราคาต้นทางของใบเสนอราคานี้"));
        PricingDecisionDto decision = pricingDecisions.find(pricingDecisionId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบมติราคาต้นทางของใบเสนอราคานี้"));
        // A restored line is built for the DOCUMENT's CURRENT price mode, but a decision item's price fields are only
        // populated for the mode the CEO CHOSE: restoring a NET item into a document since switched to DIRECT_NET would
        // insert a row with no net price, 400ing downstream on "ราคาสุทธิต้องมากกว่าศูนย์" with no visible cause. REFUSED
        // instead of building the line under the wrong mode (the renderer prices one mode per DOCUMENT); the rep has a
        // whole-document action for this (the price-mode picker).
        if (!decision.priceMode().equals(existing.priceMode())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คืนรายการไม่ได้ — เอกสารนี้เปลี่ยนวิธีกรอกราคาไปจาก CEO แล้ว (CEO เลือก: " + decision.priceMode()
                    + ", ปัจจุบัน: " + existing.priceMode() + ") กรุณาเปลี่ยนวิธีกรอกราคากลับก่อนจึงจะคืนรายการได้");
        }
        // The CEO's decision price is always THB (pricing_decision has no currency); an EN/USD document has no valid
        // conversion. Like the TH<->EN switch rule (QuotationEditorPage#changeLanguage / quotationMeta#rowsWithPricesCleared),
        // a raw THB figure must not be injected into a USD row: REFUSED for the same reason as the price-mode case; the rep
        // switches the whole document back to TH first.
        if (!WastageCalculator.DOCUMENT_LANGUAGE_TH.equals(existing.documentLanguage())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คืนรายการไม่ได้ — ราคาที่ CEO อนุมัติเป็นเงินบาท กรุณาเปลี่ยนเอกสารกลับเป็นภาษาไทยก่อนจึงจะคืนรายการได้");
        }
        PricingDecisionItemDto decisionItem = decision.items().stream()
            .filter(di -> di.id() == pricingDecisionItemId)
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการนี้ในคำขอราคาต้นทาง"));
        PricingRequestItemDto requestItem = pricingRequests.findItems(existing.pricingRequestId()).stream()
            .filter(pri -> pri.id() == decisionItem.pricingRequestItemId())
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ข้อมูลคำขอราคาไม่ครบถ้วน (รายการที่ผูกไว้หายไป)"));
        ItemInput input = buildItemInputFromDecisionItem(requestItem, decisionItem, existing.priceMode());
        int nextSeq = existing.items().size() + 1;
        NewItem built = buildItem(input, nextSeq, existing.priceMode(), existing.documentLanguage(), BigDecimal.ZERO);
        NewItem linked = withDecisionLink(built, requestItem.id(), decisionItem.id());
        quotations.insertItemsAtSeq(id, List.of(new DealQuotationRepository.SeqItem(nextSeq, linked)));
        quotations.incrementItemsRemovedFromCeo(id, -1);
        // Recompute and persist the subtotal exactly like #update (WastageCalculator.subtotal over every CURRENT line,
        // read back AFTER the insert). Otherwise total_amount (the subtotal #mapQuotation reads, from which VAT/grand
        // total derive) stays understated by the restored line until the next full save.
        DealQuotationDto afterInsert = requireQuotation(id);
        BigDecimal subtotal = WastageCalculator.subtotal(
            afterInsert.items().stream().map(DealQuotationItemDto::lineAmount).toList());
        quotations.updateSubtotal(id, subtotal);
        return requireQuotation(id);
    }

    // Status machine: DRAFT -> (submit) -> PENDING_APPROVAL -> (approve) -> APPROVED
    //                                                        -> (reject+reason) -> DRAFT
    //                 DRAFT -> (cancel) -> CANCELLED
    //                 APPROVED -> (revise) -> new DRAFT child; parent -> SUPERSEDED once the
    //                             CHILD reaches APPROVED (not before).
    //
    // ตีกลับ (reject) itself never renumbers: same row, same number. The renumbering happens the NEXT time #submit
    // runs on that rejected DRAFT (approval_note != null): it mints a new DRAFT revision (parent = the rejected row)
    // and submits it immediately; the parent -> SUPERSEDED once THIS revision reaches APPROVED, the same rule as
    // revising an APPROVED document (see DealQuotationRepository#supersede). In short: ตีกลับ = แก้ใบเดิม เลขไม่เปลี่ยน;
    // resubmitting a ตีกลับ = ออกใบใหม่ เลขเปลี่ยน.
    //
    // A deal may hold only ONE LIVE quotation at a time, any origin (DEAL_DIRECT APPROVED; PRICING_REQUEST
    // ISSUED/REVISION_REQUESTED). #approve therefore ALSO supersedes every OTHER live quotation on the ticket,
    // regardless of lineage, in addition to the ancestor-chain walk. So a "สร้างจากใบเดิม" clone (#createReorder leaves
    // its source APPROVED) replaces its source when the CLONE is approved.

    /**
     * Refuses a สั่งเหมือนเดิม clone ({@link #createReorder}) of a PRICING_REQUEST-origin quotation; it guards nothing
     * else. {@link #submit}, {@link #approve} and {@link #reject} do not call it, and {@link #createRevision} has its
     * own PRICING_REQUEST branch. A DEAL_DIRECT quotation is unaffected (a no-op).
     */
    private void requireReorderAllowedForOrigin(DealQuotationDto quotation) {
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยังไม่เปิดใช้งานการสั่งเหมือนเดิมสำหรับใบเสนอราคาจากคำขอราคา — จะเปิดใช้งานในระยะถัดไป");
        }
    }

    @Transactional
    public DealQuotationDto submit(long id, UserPrincipal actor) {
        DealQuotationDto found = requireQuotation(id);
        requireEditAccessForQuotation(actor, found);
        // The deal's lock, like create/approve/cancel/reorder: TicketService#changeCustomer takes it too, and a submit
        // slipping between its "nothing sent yet" check and its writes would leave a PENDING_APPROVAL document printing the
        // old customer. Re-read under the lock: what was loaded above may be what that change just rewrote.
        quotations.lockTicket(found.ticketId());
        DealQuotationDto quotation = requireQuotation(id);
        requireNoOtherDirectQuotationAfterConfirm(quotation);
        // Submit is live for BOTH origins: every validation below is origin-agnostic; only approve/reject/issue diverge
        // (see #approve/#reject).
        if (quotation.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการก่อนส่งขออนุมัติ");
        }
        // ผู้ติดต่อ is optional: it is picked per quotation (the server stores its name in ordered_by_name, never
        // printed); the deal's own contact is never inherited (see #resolveContact).
        // A zero-deposit ("ไม่รับมัดจำ") document must name ONE of the three payment terms before an approver sees it:
        // create/update let a DRAFT save with no term, so submit is the gate ("create/update permissive, submit strict",
        // like #requireEveryTileItemHasALeadTime and the DATE-mode check below). Not re-checked for a row whose
        // depositPercent is not exactly 0: #resolveFullPaymentTerm already keeps such a row's fullPaymentTerm null.
        if (isZeroDeposit(quotation.depositPercent()) && isBlank(quotation.fullPaymentTerm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกเงื่อนไขการชำระเงินเต็มจำนวน");
        }
        // Submit-time half of the credit-days rule: create/update refuse an explicit invalid VALUE
        // (#requireValidCreditDays) but let a CREDIT-remainder draft save with creditDays BLANK; submit requires it. Not
        // re-checked for a non-null value: one already accepted by create/update is never invalid.
        if ("CREDIT".equals(quotation.remainderMode()) && quotation.creditDays() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
        }
        // A DATE-mode validity deadline that has already passed must not reach an approver: create/update refuse one
        // before the quotation's own date, but time moves on after a draft is saved, so submit re-checks against TODAY
        // (Bangkok).
        if (WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && quotation.validityUntil().isBefore(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ยืนราคาผ่านไปแล้ว");
        }
        // A PRICING_REQUEST-origin quotation MUST carry a validity before submit, or the expiry sweep
        // (DealQuotationRepository#expireOverdueQuotations, scoped to `validity_date IS NOT NULL`) never reaches it once
        // issued and the document lives forever. DEAL_DIRECT never expires, so `validityDays` stays optional for it.
        // Checked at submit (create/update permissive, submit strict).
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            boolean hasValidity = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
                ? quotation.validityUntil() != null
                : quotation.validityDays() != null;
            if (!hasValidity) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุระยะเวลายืนราคาก่อนส่งขออนุมัติ");
            }
        }
        // Item completeness, re-checked over the STORED rows: create/update enforce it on write (#buildItem), but a row can
        // predate the rule or a stale client could bypass it; submit is the last gate before an approver.
        boolean perSqm = WastageCalculator.isEnglishPerSqm(quotation.documentLanguage(), quotation.priceMode());
        for (DealQuotationItemDto item : quotation.items()) {
            requireStoredItemComplete(item, perSqm);
        }
        requireEveryTileItemHasALeadTime(quotation.items());
        // A DRAFT carrying a PRIOR rejection (approval_note IS NOT NULL, the signal
        // DealQuotationRepository#NEEDS_REWORK_PREDICATE keys the "แก้" bucket on) resubmits as a NEW revision of itself,
        // as one atomic step, exactly as if the rep had called #createRevision then #submit. A first-ever submit
        // (approval_note null) keeps its row and number.
        //
        // Excluded for a PRICING_REQUEST-origin row, though it can carry a rejection reason too: its resubmit reuses the
        // SAME row/number, because #insertRevisionCopyOf/#requireNoOpenRevision/#supersede are scoped to
        // {@code origin = 'DEAL_DIRECT'} in the repository.
        if (QuotationStatus.DRAFT.equals(quotation.docStatus()) && quotation.approvalNote() != null
                && !"PRICING_REQUEST".equals(quotation.origin())) {
            return submitAsRevisionOfRejected(quotation, actor);
        }
        return submitDraftRow(id, actor);
    }

    /**
     * The resubmit-after-rejection half of {@link #submit}: mints a revision of {@code rejected} the way
     * {@link #createRevision} does (shared {@link #insertRevisionCopyOf}, same {@link #requireNoOpenRevision}
     * lock/guard), then submits THAT row via {@link #submitDraftRow}. The DRAFT parent becomes SUPERSEDED when this
     * child reaches APPROVED ({@link #approve}'s {@code parentQuotationId() != null -> supersede()}); that is why
     * {@link DealQuotationRepository#supersede} accepts a DRAFT parent.
     */
    private DealQuotationDto submitAsRevisionOfRejected(DealQuotationDto rejected, UserPrincipal actor) {
        requireNoOpenRevision(rejected);
        long revisionId = insertRevisionCopyOf(rejected, actor);
        String revisionNumber = requireQuotation(revisionId).number();
        tickets.addEvent(rejected.ticketId(), actor.id(), actor.name(), TicketEventKind.REVISION_REQUESTED,
            null, null,
            "สร้างใบเสนอราคาฉบับแก้ไข " + revisionNumber + " จาก " + rejected.number()
                + " ที่ถูกตีกลับ — " + rejected.approvalNote());
        return submitDraftRow(revisionId, actor);
    }

    /** The DRAFT -> PENDING_APPROVAL compare-and-set, its ticket event and its notification, shared by a first-time
     * submit and {@link #submitAsRevisionOfRejected} ({@code id} is the freshly-minted revision there). */
    private DealQuotationDto submitDraftRow(long id, UserPrincipal actor) {
        int rows = quotations.submit(id, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงส่งขออนุมัติไม่ได้");
        }
        DealQuotationDto submitted = requireQuotation(id);
        tickets.addEvent(submitted.ticketId(), actor.id(), actor.name(), TicketEventKind.SUBMITTED, null, null,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติ");
        // Called directly, like #approve/#reject call notifyRepAndCreator, NOT wrapped in this class's afterCommit():
        // notifyByRoleAtLink/notifyEmployeeAtLink already defer their own mail to commit (notification.AfterCommit.run() in
        // SalesNotificationMailRouter), and registering a TransactionSynchronization from inside another's afterCommit()
        // is silently dropped by Spring, which swallowed every notifySubmitted() mail. The in-app bell rows are plain
        // INSERTs that join the transaction.
        notifySubmitted(submitted, actor);
        return submitted;
    }

    /**
     /**
      * A submitted revision ({@code parentQuotationId() != null}, from {@link #createRevision} or
      * {@link #submitAsRevisionOfRejected}) must read as a REVISION to sales_manager/ceo, not the "รออนุมัติ" text of a
      * first-time submit. First-time submits fall through to the original branch below.
      */
    private void notifySubmitted(DealQuotationDto submitted, UserPrincipal actor) {
        if (submitted.parentQuotationId() != null) {
            notifyRevisionSubmitted(submitted, submitted.parentQuotationId(), actor);
            return;
        }
        String message = "ใบเสนอราคา " + submitted.number() + " รอการอนุมัติ";
        String link = "/quotations/" + submitted.id();
        notifications.notifyByRoleAtLink("sales_manager", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
        notifications.notifyByRoleAtLink("ceo", TicketEventKind.DEAL_QUOTATION_SUBMITTED, message, link);
        // The rep who submitted (and the creator, if different; same dedupe as #notifyRepAndCreator) gets a confirmation
        // that the submission went through. Same event kind/title as the approver notification (no second
        // TicketEventKind); the message is written from the rep's point of view.
        notifyRepAndCreator(submitted, TicketEventKind.DEAL_QUOTATION_SUBMITTED,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติแล้ว รอผลการพิจารณา");
    }

    /**
     /**
      * The revision half of {@link #notifySubmitted}. Looks the parent up (plain {@link DealQuotationRepository#findById},
      * not {@link #requireQuotation}, and no access re-check: the actor already passed
      * {@link #requireEditAccessForQuotation} earlier in {@link #submit}) to read its {@code number()} and, for the
      * resubmit-after-ตีกลับ path, its rejection reason. A notification must not 404 the submit; the fallback is
      * unreachable today (no quotation DELETE, FK-protected parent) but a side-effect is the wrong place to fail.
      *
      * <p>{@code parent.docStatus()} tells the two {@link #insertRevisionCopyOf} callers apart, NOT
      * {@code approvalNote() != null}: {@link #createRevision} needs an {@code APPROVED} source whose approvalNote is
      * whatever the approver typed, which would be a false "ตีกลับ" reading. {@link #submitAsRevisionOfRejected}
      * needs a {@code DRAFT} source with the rejection reason, and the parent is still DRAFT here (superseded only
      * once THIS revision reaches {@code APPROVED}).
      *
      * <p>{@code actor.name()} is printed verbatim as the FIRST word of this message, so a blank name would read as
      * "null ได้จัดทำ..."; it falls back to a role label like {@link #approve}'s {@code approvedByName() != null ? ... :
      * "ผู้อนุมัติ"}.
      */
    private void notifyRevisionSubmitted(DealQuotationDto submitted, long parentId, UserPrincipal actor) {
        Optional<DealQuotationDto> parent = quotations.findById(parentId);
        String parentNumber = parent.map(DealQuotationDto::number).orElse("-");
        String actorName = isBlank(actor.name()) ? "ผู้เสนอราคา" : actor.name();
        StringBuilder message = new StringBuilder()
            .append(actorName).append(" ได้จัดทำใบเสนอราคาฉบับแก้ไข ").append(submitted.number())
            .append(" (แก้ไขจาก ").append(parentNumber).append(") กรุณาตรวจสอบและพิจารณาอนุมัติ");
        if (parent.isPresent() && QuotationStatus.DRAFT.equals(parent.get().docStatus())
                && !isBlank(parent.get().approvalNote())) {
            message.append(" — แก้ไขตามที่ตีกลับ: ").append(parent.get().approvalNote());
        }
        String link = "/quotations/" + submitted.id();
        notifications.notifyByRoleAtLink(
            "sales_manager", TicketEventKind.DEAL_QUOTATION_REVISION_SUBMITTED, message.toString(), link);
        notifications.notifyByRoleAtLink(
            "ceo", TicketEventKind.DEAL_QUOTATION_REVISION_SUBMITTED, message.toString(), link);
        // Same rep/creator confirmation as the first-time submit, worded for a revision.
        notifyRepAndCreator(submitted, TicketEventKind.DEAL_QUOTATION_SUBMITTED,
            "ส่งใบเสนอราคาฉบับแก้ไข " + submitted.number() + " ขออนุมัติแล้ว รอผลการพิจารณา");
    }

    @Transactional
    public DealQuotationDto approve(long id, ApproveRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        DealQuotationDto quotation = requireQuotation(id);
        // A PRICING_REQUEST-origin quotation still branches out: the compare-and-set is shared (DealQuotationRepository
        // #approve covers both origins), but the extra issue hooks (PR status transition, stage advance, PR event) and
        // the CEO-required-when-changed gate are unique to it. requireReorderAllowedForOrigin is NOT called on this branch
        // (it would always refuse); it covers only createReorder.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            return approveAndIssuePricingRequestOrigin(quotation, request, actor);
        }
        // A deal may hold only ONE LIVE quotation at a time, any origin; approve() also sweeps every other live sibling
        // on this ticket. Locks the ticket BEFORE the compare-and-set (not merely before the sweep), so two concurrent
        // approve() calls on the same ticket, even on two DIFFERENT PENDING_APPROVAL rows, serialise and can never both
        // land APPROVED. Same #lockTicket and "lock the ticket first" ordering as #createRevision/#createReorder, so no
        // deadlock (a transaction never holds a second ticket's lock).
        quotations.lockTicket(quotation.ticketId());
        requireNoOtherDirectQuotationAfterConfirm(quotation); // under the lock: confirm-order takes it too
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        // A DATE-mode document WITH special pricing names its OWN validity_date (the rep's ภายในวันที่, verbatim; approval
        // never recomputes it); every other case (DAYS mode, or a stale DATE mode that lost its special pricing, which
        // create/update now refuse but an already-PENDING_APPROVAL row could carry) falls back to the day-count rule.
        // Mirrors the render adapter's null guard rather than trusting validityUntil is non-null because the mode says
        // DATE: a stray NULL would skip the DAYS fallback and leave validity_date NULL despite validity_days being set.
        LocalDate validityDate = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
                && quotation.validityUntil() != null
                && DealQuotationRenderAdapter.hasSpecialPricing(quotation)
            ? quotation.validityUntil()
            : (quotation.validityDays() != null ? approvalDate.plusDays(quotation.validityDays()) : null);
        int rows = quotations.approve(id, actor.id(), blankToNull(request.note()), validityDate);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงอนุมัติไม่ได้");
        }
        DealQuotationDto approved = requireQuotation(id);
        // One-live-quotation sweep (see #supersedeOtherLiveOnTicket). Runs BEFORE the ancestor walk below so the parent's
        // DEAL_QUOTATION_SUPERSEDED event comes from the sweep.
        supersedeOtherLiveOnTicket(approved, actor);
        // The customer's last approved document stays valid until a REVISION reaches APPROVED (see the status-machine
        // note). Only for a revision. Runs AFTER the sweep and stays SILENT (no ticket event): a DRAFT ancestor from a
        // reject/resubmit chain is invisible to the sweep's live-status predicate, so this walk is the only thing that
        // supersedes one. It walks the WHOLE ancestry chain, not just the immediate parent: a second reject/resubmit cycle
        // (reject A, resubmit mints B, reject B, resubmit mints C, approve C) would otherwise strand A in DRAFT forever,
        // matching NEEDS_REWORK_PREDICATE (the rep's "ฉบับแก้" bucket) and never cleared by hasOpenRevision once B leaves
        // DRAFT/PENDING_APPROVAL.
        Long ancestorId = approved.parentQuotationId();
        while (ancestorId != null) {
            DealQuotationDto ancestor = requireQuotation(ancestorId);
            quotations.supersede(ancestorId);
            ancestorId = ancestor.parentQuotationId();
        }
        tickets.addEventWithDocument(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติใบเสนอราคา " + approved.number(), RelatedDocumentType.QUOTATION, id);
        String message = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        // In-app + branded HTML email only; no second plain-text email with the PDF.
        notifyRepAndCreator(approved, TicketEventKind.DEAL_QUOTATION_APPROVED, message);
        notifyOtherApprover(approved, actor, "อนุมัติใบเสนอราคา " + approved.number() + " แล้ว");
        return approved;
    }

    /**
     * Submit told BOTH approver roles "รออนุมัติ"; once one decides, the OTHER hears who decided (FYI) so their bell is
     * not left waiting. A CEO decision tells the sales manager; anyone else's tells the CEO.
     */
    private void notifyOtherApprover(DealQuotationDto quotation, UserPrincipal actor, String decision) {
        boolean byCeo = "ceo".equals(actor.role());
        TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
        notifications.notifyByRoleAtLink(byCeo ? "sales_manager" : "ceo", TicketEventKind.DEAL_QUOTATION_DECIDED,
            actor.name() + " (" + (byCeo ? "CEO" : "ผู้จัดการฝ่ายขาย") + ") " + decision + "\n"
                + DealNoticeText.dealLabel(ticket.code(), ticket.customerName()) + "\n" + DealNoticeText.FYI,
            "/quotations/" + quotation.id(),
            // The creator and the rep already hear the decision as APPROVED/REJECTED (#notifyRepAndCreator).
            actor.id(), quotation.createdById(), quotation.salesRepId());
    }

    @Transactional
    public DealQuotationDto reject(long id, RejectRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        // 404 for a missing id, 409 only once the row exists but is in the wrong status (like approve/submit/cancel/update,
        // which call requireQuotation first).
        DealQuotationDto quotation = requireQuotation(id);
        // Reject is fully shared across both origins: one approver, one decision, nothing origin-specific to branch on
        // (DealQuotationRepository#reject's WHERE accepts PRICING_REQUEST alongside DEAL_DIRECT).
        int rows = quotations.reject(id, actor.id(), request.reason());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงไม่สามารถตีกลับได้");
        }
        DealQuotationDto rejected = requireQuotation(id);
        tickets.addEvent(rejected.ticketId(), actor.id(), actor.name(), TicketEventKind.REJECTED, null, null,
            "ไม่อนุมัติใบเสนอราคา " + rejected.number() + " — " + request.reason());
        notifyRepAndCreator(rejected, TicketEventKind.DEAL_QUOTATION_REJECTED,
            "ใบเสนอราคา " + rejected.number() + " ไม่ได้รับอนุมัติ: " + request.reason());
        notifyOtherApprover(rejected, actor, "ไม่อนุมัติใบเสนอราคา " + rejected.number() + ": " + request.reason());
        return rejected;
    }

    // PRICING_REQUEST-origin status machine, same as DEAL_DIRECT's except the terminal status's NAME:
    //   DRAFT -> (submit) -> PENDING_APPROVAL -> (approve, by whoever may act) -> ISSUED
    //                                          -> (reject+reason) -> DRAFT
    //          -> (validity_date passes while ISSUED) -> EXPIRED (see #expireOverdueQuotations)
    // ONE approval, by ผู้จัดการฝ่ายขาย OR the CEO, issues the document, and the approver is the signature that prints
    // (DealQuotationRepository#approve's single-shot compare-and-set and approver-snapshot write, shared with
    // DEAL_DIRECT). This origin adds one thing: the CEO's price authority survives sales editing price after create
    // (see #requireCeoApprovalIfChanged).

    /** One live offer per DEAL, whatever origin or recipient. Runs after the approving compare-and-set and BEFORE the
     * ancestor walk, so a revision's parent gets its event from here and the walk no-ops. Caller holds the ticket lock. */
    private void supersedeOtherLiveOnTicket(DealQuotationDto approved, UserPrincipal actor) {
        for (DealQuotationRepository.SupersededSibling sibling
                : quotations.supersedeOtherLiveOnTicket(approved.ticketId(), approved.id())) {
            tickets.addEvent(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.DEAL_QUOTATION_SUPERSEDED,
                null, null, "ใบ " + sibling.number() + " ถูกแทนที่ด้วย " + approved.number());
        }
    }

    /**
     * The approve() branch for a PRICING_REQUEST-origin quotation. {@code actor} is already known to be
     * {@code sales_manager} or {@code ceo} ({@link #requireApproveAccess}).
     */
    private DealQuotationDto approveAndIssuePricingRequestOrigin(DealQuotationDto quotation, ApproveRequest request,
                                                                  UserPrincipal actor) {
        requireCeoApprovalIfChanged(quotation, actor);
        if (pricingRequests == null || customerQuotations == null || ticketService == null) {
            // Same defensive shape as #createFromPricingRequest's guard: only reachable if the bean is wired without the
            // setter-injected dependencies.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        long pricingRequestId = quotation.pricingRequestId();
        // Same lock CustomerQuotationService#issue takes before its compare-and-set. Then the deal's lock, because issuing
        // runs the deal-wide "one live offer" sweep. PR lock first, then the deal's advisory lock (see
        // DealQuotationRepository#lockTicket); never reverse.
        customerQuotations.lockPricingRequest(pricingRequestId);
        quotations.lockTicket(quotation.ticketId());
        PricingRequestSummaryDto summary = pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // The same DATE-mode-with-special-pricing / DAYS-mode validity computation #approve uses for DEAL_DIRECT, shared so
        // the origins cannot compute it differently.
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        LocalDate validityDate = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
                && quotation.validityUntil() != null
                && DealQuotationRenderAdapter.hasSpecialPricing(quotation)
            ? quotation.validityUntil()
            : (quotation.validityDays() != null ? approvalDate.plusDays(quotation.validityDays()) : null);
        // The same repository method DEAL_DIRECT uses: its CASE picks 'ISSUED' over 'APPROVED' for this origin, and its
        // approver-snapshot INSERT freezes THIS actor's identity/signature as the printed approver.
        int rows = quotations.approve(quotation.id(), actor.id(), blankToNull(request.note()), validityDate);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงอนุมัติไม่ได้");
        }
        DealQuotationDto issued = requireQuotation(quotation.id());
        // Only the FIRST issue moves the pricing request. A second, independent quotation (via the expiry re-create in
        // #createFromPricingRequest) reaches here with the PR already at QUOTATION_ISSUED; this guard makes that a no-op
        // transition rather than an IllegalStateException. Mirrors CustomerQuotationService#issue.
        if (PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.APPROVED_FOR_QUOTATION,
                PricingRequestStatus.QUOTATION_ISSUED, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
            }
        }
        // Reuses the same stage transition as CustomerQuotationService#issue (forward-only, ACTIVE-deal-only; see
        // TicketService#advanceStageForCustomerQuotationIssue/#autoAdvanceStage). Uses the QUOTATION's own recipient: a
        // revision cannot be re-addressed so it equals the parent's (a no-op), and a first-issue quotation's equals the
        // request's. The stage has normally arrived at create time (#advanceDealToRecipientStage); this call stays because
        // it is idempotent under forward-only and covers a recipient changed between draft and approval on a path
        // #update's hook does not reach.
        ticketService.advanceStageForCustomerQuotationIssue(summary.ticketId(), issued.recipientType(), actor);
        supersedeOtherLiveOnTicket(issued, actor);
        // Issuing a REVISION replaces its parent chain. Each hop is its own compare-and-set (supersede() only flips an
        // ISSUED/REVISION_REQUESTED row), so already-terminal ancestors are a safe no-op.
        Long ancestorId = issued.parentQuotationId();
        while (ancestorId != null) {
            DealQuotationDto ancestor = requireQuotation(ancestorId);
            quotations.supersede(ancestorId);
            ancestorId = ancestor.parentQuotationId();
        }
        tickets.addEventWithDocument(issued.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติและออกใบเสนอราคา " + issued.number(), RelatedDocumentType.QUOTATION, issued.id());
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED, summary.status(),
            PricingRequestStatus.QUOTATION_ISSUED, "ออกใบเสนอราคา " + issued.number(), null);
        // Parity with CustomerQuotationService#issue's "customer notification" substitute: there is no customer user to
        // notify in-app, so the CEO is told of the issuance. Redundant when the CEO is the approver but kept
        // unconditional, since a sales_manager approval is the common case.
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED, "ใบเสนอราคา " + issued.number() + " ถูกออกแล้ว");
        notifyRepAndCreator(issued, TicketEventKind.DEAL_QUOTATION_APPROVED,
            "ใบเสนอราคา " + issued.number() + " ได้รับอนุมัติและออกใบแล้ว");
        if ("ceo".equals(actor.role())) {
            // Only the CEO's decision needs telling: when the sales manager issues, the CEO already got "ถูกออกแล้ว" above.
            notifyOtherApprover(issued, actor, "อนุมัติใบเสนอราคา " + issued.number() + " แล้ว");
        }
        return issued;
    }

    /**
     * The CEO's price authority: sales may edit price after create, but if ANY line's price/discount or the header
     * price mode now differs from the CEO's original decision, or any CEO-linked line was removed, ONLY the CEO may
     * approve; a sales_manager attempting it is refused before any write.
     *
     * <p>Computed FRESH from {@code quotation} (a {@link #requireQuotation} read, never client input) using the same
     * fields the editor's CEO-comparison UI reads ({@link DealQuotationDto#priceModeChangedFromCeo},
     * {@link DealQuotationItemDto#priceChangedFromCeo}, {@link DealQuotationDto#itemsRemovedFromCeoCount}).
     */
    private void requireCeoApprovalIfChanged(DealQuotationDto quotation, UserPrincipal actor) {
        if ("ceo".equals(actor.role())) {
            return;
        }
        boolean changed = quotation.priceModeChangedFromCeo()
            || quotation.items().stream().anyMatch(DealQuotationItemDto::priceChangedFromCeo)
            || quotation.itemsRemovedFromCeoCount() > 0;
        if (changed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "มีรายการที่เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ");
        }
    }

    // Records what the customer said about an ISSUED PRICING_REQUEST-origin quotation. Narrower than
    // #requireEditAccessForQuotation (which lets sales_manager act on ANY deal): it mirrors
    // CustomerQuotationService#recordOutcome's gate, {@code SALES_ROLES = Set.of("sales")}, so only the ticket's OWNING
    // rep may record an outcome. EDIT_ROLES/APPROVE_ROLES are not reused: recording an outcome is neither editing nor
    // approving. One finalized quotation per deal, across BOTH คำขอราคา origins, is enforced via
    // TicketRepository#hasAcceptedQuotation under TicketRepository#lockTicketForUpdate, the same ticket-row lock
    // CustomerQuotationService#recordOutcome takes, so a legacy accept and a new-origin accept on sibling pricing
    // requests serialise.

    /** The only outcomes a client may record here; mirrors {@code CustomerQuotationService#RECORDABLE_OUTCOMES}
     * (EXPIRED is sweep-only). */
    private static final Set<String> RECORDABLE_OUTCOMES =
        Set.of(QuotationStatus.ACCEPTED, QuotationStatus.REJECTED, QuotationStatus.REVISION_REQUESTED);

    /**
     * Records what the customer said about an ISSUED PRICING_REQUEST-origin quotation; on ACCEPTED, moves the pricing
     * request {@code QUOTATION_ISSUED -> QUOTATION_ACCEPTED} like {@code CustomerQuotationService#recordOutcome} (same
     * event kind, same CEO notification; REJECTED/REVISION_REQUESTED do NOT change the pricing request's status). An
     * ACCEPTED outcome is refused with 409 when {@code sales.quotation} already has an ACCEPTED row for this TICKET
     * under any origin (legacy {@code origin IS NULL} included); see {@link TicketRepository#hasAcceptedQuotation}.
     *
     * <p>A {@code DEAL_DIRECT} row is refused outright (409): the direct quotation has no customer-outcome concept.
     */
    @Transactional
    public DealQuotationDto recordOutcome(long quotationId, DealQuotationRequests.RecordOutcomeRequest request,
                                          UserPrincipal actor) {
        if (QuotationStatus.EXPIRED.equals(request.outcome())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "EXPIRED ไม่สามารถบันทึกผ่าน API นี้ได้ — ระบบตั้งเป็นอัตโนมัติเท่านั้น");
        }
        if (request.outcome() == null || !RECORDABLE_OUTCOMES.contains(request.outcome())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "outcome ไม่ถูกต้อง");
        }
        DealQuotationDto quotation = requireQuotation(quotationId);
        if (!"PRICING_REQUEST".equals(quotation.origin())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบเสนอราคาฉบับนี้ไม่ได้อยู่ในสายงานคำขอราคา จึงบันทึกผลลูกค้าด้วย endpoint นี้ไม่ได้");
        }
        if (pricingRequests == null) {
            // Same defensive shape as #createFromPricingRequest/#approveAndIssuePricingRequestOrigin: only reachable if the
            // bean is wired without the setter-injected dependencies.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        requireOutcomeAccess(actor, quotation);

        // Lock order: the pricing-request advisory lock (borrowed via the setter-injected customerQuotations) FIRST, THEN
        // the ticket-row lock, matching #approveAndIssuePricingRequestOrigin, CustomerQuotationService#issue and
        // CustomerQuotationService#recordOutcome. The reverse order deadlocked on real Postgres. The hasAcceptedQuotation
        // check below runs with BOTH locks held.
        customerQuotations.lockPricingRequest(quotation.pricingRequestId());
        tickets.lockTicketForUpdate(quotation.ticketId());

        String outcomeClientRequestId = validateUuid(request.clientRequestId());
        if (outcomeClientRequestId != null) {
            Optional<Long> replay = quotations.findIdByOutcomeClientRequestId(actor.id(), outcomeClientRequestId);
            if (replay.isPresent()) {
                // As in CustomerQuotationService#issue: a replay resolving to a DIFFERENT quotation than the one named is a
                // conflict, not a silent "success" that records no outcome on the intended one.
                if (replay.get() != quotationId) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น");
                }
                return requireQuotation(replay.get());
            }
        }
        DealQuotationDto fresh = requireQuotation(quotationId);
        if (!QuotationStatus.ISSUED.equals(fresh.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "บันทึกผลได้เฉพาะใบเสนอราคาที่ออกแล้วเท่านั้น (ปัจจุบัน: " + fresh.docStatus() + ")");
        }
        PricingRequestSummaryDto summary = pricingRequests.findSummary(fresh.pricingRequestId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // A customer-change revision supersedes the parent PR but leaves its quotation ISSUED.
        if (PricingRequestStatus.SUPERSEDED.equals(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้ถูกแทนที่ด้วยรอบแก้ไขแล้ว — บันทึกผลลูกค้าที่ใบเสนอราคาของรอบแก้ไขแทน");
        }
        if (QuotationStatus.ACCEPTED.equals(request.outcome()) && tickets.hasAcceptedQuotation(fresh.ticketId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีใบเสนอราคาที่ลูกค้ายอมรับแล้วฉบับหนึ่ง — ยอมรับซ้ำอีกฉบับไม่ได้ (R8)");
        }

        // See CustomerQuotationService#recordOutcome: V75's unique index is table-wide while both origins' replay lookups
        // are origin-scoped, so a cross-engine clientRequestId reuse hits this constraint; turned into the same clean 409
        // as the wrong-quotation replay guard above.
        int rows;
        try {
            rows = quotations.recordOutcome(quotationId, request.outcome(), blankToNull(request.customerNote()),
                actor.id(), outcomeClientRequestId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "clientRequestId ถูกใช้ไปแล้วกับใบเสนอราคาอื่น");
        }
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
        }

        String eventKind = outcomeEventKind(request.outcome());
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            eventKind, summary.status(), summary.status(),
            "บันทึกผลใบเสนอราคาลูกค้า " + fresh.number() + ": " + request.outcome()
                + (request.customerNote() != null && !request.customerNote().isBlank()
                    ? " — " + request.customerNote().trim() : ""), null);
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(), eventKind,
            "ใบเสนอราคาลูกค้า " + fresh.number() + " " + outcomeLabel(request.outcome()));

        if (QuotationStatus.ACCEPTED.equals(request.outcome())
                && PricingRequestStatus.QUOTATION_ISSUED.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.QUOTATION_ISSUED,
                PricingRequestStatus.QUOTATION_ACCEPTED, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
            }
        }
        return requireQuotation(quotationId);
    }

    /** Mirrors {@code CustomerQuotationService#requireEditAccess} for the outcome action: {@link #EDIT_ROLES} /
     * {@link #requireEditAccessForQuotation} (which also admits sales_manager on any deal) are NOT reused here. */
    private void requireOutcomeAccess(UserPrincipal actor, DealQuotationDto quotation) {
        if (!"sales".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
        if (!DealOwnership.isOwnerOrKeyer(ticket, actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private String outcomeEventKind(String outcome) {
        return switch (outcome) {
            case QuotationStatus.ACCEPTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_ACCEPTED;
            case QuotationStatus.REJECTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_REJECTED;
            case QuotationStatus.REVISION_REQUESTED -> PricingRequestEventKind.CUSTOMER_QUOTATION_REVISION_REQUESTED;
            default -> throw new IllegalStateException("Unreachable — validated by RECORDABLE_OUTCOMES");
        };
    }

    private String outcomeLabel(String outcome) {
        return switch (outcome) {
            case QuotationStatus.ACCEPTED -> "ลูกค้ายอมรับแล้ว";
            case QuotationStatus.REJECTED -> "ถูกลูกค้าปฏิเสธ";
            case QuotationStatus.REVISION_REQUESTED -> "ลูกค้าขอแก้ไข";
            default -> "มีการอัปเดตผล";
        };
    }

    // "ยืนยันคำสั่งซื้อ" on a direct quotation: an APPROVED direct quotation's deal moves to S10 ORDER_RECEIVED with the
    // quotation's lines. Direct and pipeline quotations keep separate PRICING routes; there is no recordOutcome for
    // DEAL_DIRECT (see #recordOutcome); this is the rep's own decision that the customer ordered (no signature
    // capture). A quotation-first deal is a real deal (V193 quotation_only is provenance only), and an ordinary deal
    // with a direct quotation confirms the same way. It lives here because every authz decision for this engine does
    // (#requireEditAccess is the audience, reused) and #ticketService is already wired for
    // #approveAndIssuePricingRequestOrigin.

    /** ticket_item column widths: brand 255, model/color/texture/size 80, raw_unit 30. quotation_item's are 255, so a
     * long catalogue text is clipped here rather than failing the confirmation; the quotation keeps the full text. */
    private static final int TICKET_ITEM_BRAND_MAX = 255;
    private static final int TICKET_ITEM_TEXT_MAX = 80;
    private static final int TICKET_ITEM_RAW_UNIT_MAX = 30;

    /**
     * {@code POST /api/deal-quotations/{id}/confirm-order} (and its one-release alias {@code …/promote-to-deal}):
     * confirms the customer's order from an APPROVED direct quotation, moving the deal to S10 ORDER_RECEIVED in ONE
     * transaction, mirroring {@code OrderConfirmationService#confirmOrder}'s bridge sequence:
     *
     * <ol>
     *   <li>lock the ticket row ({@link TicketRepository#lockTicketForUpdate}) and re-read it; every ticket
     *       precondition is checked against the LOCKED read;</li>
     *   <li>{@code status draft -> quotation_issued}, the same bridge write
     *       ({@link TicketRepository#markQuotationIssuedForOrderConfirmation}) confirmOrder uses, which every
     *       downstream ticket-keyed gate (deposit notice, IR, close) keys on;</li>
     *   <li>best-effort {@code quotation_only TRUE -> FALSE} ({@link TicketRepository#markPromotedToPipeline}),
     *       provenance only; its rowcount is ignored;</li>
     *   <li>{@code sales.ticket_item} replaced by the quotation's own goods lines (see
     *       {@link #ticketItemsFromQuotation}), the delivery machinery's quantity source; the quotation is the agreed
     *       order, so it replaces any preliminary lines;</li>
     *   <li>{@code TicketService#confirmCustomerForPromotedQuotation}: {@code payment_status = CUSTOMER_CONFIRMED},
     *       the deposit policy following this quotation's percent ({@code TicketService#followQuotationDepositPolicy}),
     *       stage auto-advanced to {@code ORDER_RECEIVED}.</li>
     * </ol>
     *
     * <p><strong>Order of checks</strong>: quotation exists on this engine (404; a LEGACY {@code origin IS NULL} id is
     * a 404 too, via {@link #requireQuotation}) → authz {@link #requireEditAccess} against the deal (403: the owning
     * {@code sales} rep, any {@code sales_manager}, or a {@code can_create_quotation} grantee; before any 409, so a
     * caller with no access learns nothing about the quotation's state) → {@code origin = DEAL_DIRECT} (409) → [lock]
     * → idempotent replay: a {@link TicketEventKind#DEAL_PROMOTED_FROM_QUOTATION} event already exists → return the
     * current state, write nothing → {@code doc_status = APPROVED} (409) → deal lifecycle {@code ACTIVE} (409) → deal
     * status {@code draft} (409).
     *
     * <p>The event kind keeps its {@code DEAL_PROMOTED_FROM_QUOTATION} name because the {@code chk_event_kind} DB CHECK
     * pins it.
     */
    @Transactional
    public PromoteToDealResultDto confirmOrderFromDirectQuotation(long quotationId, UserPrincipal actor) {
        if (ticketService == null) {
            // Same defensive shape as #approveAndIssuePricingRequestOrigin: only reachable if the bean is wired without
            // #wireTicketService.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        DealQuotationDto quotation = requireQuotation(quotationId);
        long ticketId = quotation.ticketId();
        // Authz first; see this method's Javadoc.
        requireEditAccess(actor, requireTicketSummary(ticketId));
        if (!DealQuotationRepository.ORIGIN_DEAL_DIRECT.equals(quotation.origin())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยืนยันคำสั่งซื้อจากหน้านี้ได้เฉพาะใบเสนอราคาตรง (ไม่ผ่านคำขอราคา) เท่านั้น");
        }

        // Serialize every confirmation (and every other writer taking this lock) on this deal.
        tickets.lockTicketForUpdate(ticketId);
        TicketSummaryDto locked = requireTicketSummary(ticketId);

        if (tickets.hasPromotionFromQuotationEvent(ticketId)) {
            return promotionResult(ticketId, quotationId); // idempotent replay: no writes
        }
        DealQuotationDto fresh = requireQuotation(quotationId);
        if (!QuotationStatus.APPROVED.equals(fresh.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยืนยันคำสั่งซื้อได้เฉพาะใบเสนอราคาที่อนุมัติแล้วเท่านั้น (ปัจจุบัน: " + fresh.docStatus() + ")");
        }
        if (!DealLifecycle.ACTIVE.equals(locked.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลไม่ได้อยู่ในสถานะ ACTIVE (" + locked.lifecycle() + ") จึงยืนยันคำสั่งซื้อไม่ได้");
        }
        if (!TicketStatus.DRAFT.equals(locked.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยืนยันคำสั่งซื้อไม่ได้ — สถานะดีล (" + locked.status() + ") ผ่านขั้นออกใบเสนอราคาไปแล้ว");
        }

        // The status compare-and-set runs under the row lock, so a 0 is unreachable through the API; it is checked, not
        // assumed, and rolls the whole confirmation back.
        if (tickets.markQuotationIssuedForOrderConfirmation(ticketId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                "สถานะดีลถูกเปลี่ยนโดยผู้ใช้อื่น กรุณาโหลดข้อมูลใหม่แล้วลองอีกครั้ง");
        }
        // Provenance only: flips a quotation-first deal's flag; a no-op on an ordinary deal. Nothing reads the flag, so its
        // rowcount is deliberately ignored.
        tickets.markPromotedToPipeline(ticketId);
        tickets.addEventWithDocument(ticketId, actor.id(), actor.name(),
            TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION, TicketStatus.DRAFT, TicketStatus.QUOTATION_ISSUED,
            "ยืนยันคำสั่งซื้อจากใบเสนอราคา " + fresh.number(), RelatedDocumentType.QUOTATION, quotationId);
        // The agreed order is this quotation: the deal's other open direct quotations can never be submitted or approved now
        // (see requireNoOtherDirectQuotationAfterConfirm), so cancel them rather than strand them.
        for (DealQuotationRepository.CancelledQuotation cancelled
                : quotations.cancelOtherOpenDirectQuotations(ticketId, quotationId)) {
            tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.CANCELLED, null, null,
                "ยกเลิกใบเสนอราคา " + cancelled.number() + " อัตโนมัติ เพราะยืนยันคำสั่งซื้อจากใบเสนอราคา " + fresh.number());
            if (cancelled.wasPending()) {
                // Only a quotation that was WAITING on approval had told SM and CEO "รออนุมัติ"; a DRAFT never did.
                String message = actor.name() + " ยกเลิกใบเสนอราคา " + cancelled.number()
                    + " ที่รออนุมัติ เพราะยืนยันคำสั่งซื้อจากใบเสนอราคา " + fresh.number() + " แล้ว\n"
                    + DealNoticeText.dealLabel(locked.code(), locked.customerName()) + "\nแจ้งเพื่อทราบ ไม่ต้องอนุมัติแล้ว";
                String link = "/quotations/" + cancelled.id();
                for (String role : List.of("sales_manager", "ceo")) {
                    notifications.notifyByRoleAtLink(role, TicketEventKind.DEAL_QUOTATION_CANCELLED, message, link, actor.id());
                }
            }
        }
        // The quotation is the agreed order, so it REPLACES whatever preliminary lines the deal carried. Safe: the deal is
        // still pre-order (status draft, so no delivery or stock reservation exists; both key on quotation_issued or
        // later), and the only other reference to a ticket_item, pricing_request_item.source_ticket_item_id, is ON DELETE
        // SET NULL.
        tickets.replaceItems(ticketId, ticketItemsFromQuotation(fresh));
        // A จากสต็อก line is covered in full, as the pricing-request route does at order confirmation, so the routing below sees it.
        tickets.coverSourcedFromStockLines(ticketId);
        int depositPercent = DealQuotationRenderAdapter.effectiveDepositPercent(fresh.depositPercent());
        ticketService.confirmCustomerForPromotedQuotation(ticketId, fresh.number(), depositPercent, actor);
        // FYI to the CEO (the pricing-request path keeps its own notice in OrderConfirmationService).
        notifications.notifyByRoleAtLink("ceo", PricingRequestEventKind.ORDER_CONFIRMED,
            actor.name() + " ยืนยันคำสั่งซื้อจากใบเสนอราคา " + fresh.number() + " แล้ว\n"
                + DealNoticeText.dealLabel(locked.code(), locked.customerName())
                + " · ยอดรวม " + ThaiText.money(fresh.grandTotal()) + " บาท · "
                + (depositPercent == 0 ? "ไม่เก็บมัดจำ" : "มัดจำ " + depositPercent + "%")
                + "\nแจ้งเพื่อทราบ ไม่ต้องดำเนินการ",
            "/tickets/" + ticketId);
        return promotionResult(ticketId, quotationId);
    }

    private PromoteToDealResultDto promotionResult(long ticketId, long quotationId) {
        return new PromoteToDealResultDto(ticketId, requireTicketSummary(ticketId), requireQuotation(quotationId));
    }

    /**
     * The quotation's lines as {@code sales.ticket_item} rows: the same field set
     * {@link TicketRepository#findPricingChainFallbackItems} maps and {@code OrderConfirmationService#reconcileTicketItems}
     * writes (brand/model/color/texture/size, qty, qty_sqm, unit_basis, raw_unit). NO pricing field is copied: those
     * columns carry the legacy ticket-native approval history, and a direct quotation's typed price was never
     * "approved" on this ticket in that sense; money documents read the quotation itself
     * ({@code DepositNoticeRepository#findDealQuotationItems}).
     *
     * <p>Only goods lines: an {@code ADJUSTMENT} row (a negative discount/surcharge, quantity −1) is money, not
     * something to deliver, and a non-positive quantity would violate {@code chk_ticket_item_qty_delivered}. A TILE
     * line's quantity is its final piece count, and its area is derived from that same count via
     * {@link WastageCalculator#sqmQuantityFromPieces}, as reconcileTicketItems does, so qty and qty_sqm cannot
     * disagree. {@code unit_basis} is PIECE for every row: ticket_item's CHECK allows only PIECE/SQM, and qty here is
     * always a count (pieces, or the PLAIN row's own unit kept verbatim in raw_unit).
     */
    private List<TicketItemRequest> ticketItemsFromQuotation(DealQuotationDto quotation) {
        List<TicketItemRequest> rows = new ArrayList<>();
        // Carry the catalogue link and its factory across, as the pricing-request route does, so import can raise the
        // per-factory IR. A free-text line has neither and keeps both null.
        Map<Long, String> factoryByPriceId = catalog.findFactoryNames(quotation.items().stream()
            .map(DealQuotationItemDto::catalogPriceId).filter(java.util.Objects::nonNull).toList());
        for (DealQuotationItemDto item : quotation.items()) {
            if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())) {
                continue;
            }
            BigDecimal qty = item.quantity();
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            boolean tile = !WastageCalculator.LINE_TYPE_PLAIN.equals(item.lineType());
            BigDecimal qtySqm = tile && item.sqmPerPiece() != null && item.sqmPerPiece().signum() > 0
                ? WastageCalculator.sqmQuantityFromPieces(qty.intValue(), item.sqmPerPiece())
                : null;
            rows.add(new TicketItemRequest(
                clip(firstNonBlank(item.brand(), item.model(), item.descriptionLine(), "รายการจากใบเสนอราคา"),
                    TICKET_ITEM_BRAND_MAX),
                clip(item.model(), TICKET_ITEM_TEXT_MAX),
                clip(item.color(), TICKET_ITEM_TEXT_MAX),
                clip(item.texture(), TICKET_ITEM_TEXT_MAX),
                clip(item.sizeText(), TICKET_ITEM_TEXT_MAX),
                clip(item.catalogPriceId() == null ? null : factoryByPriceId.get(item.catalogPriceId()),
                    TICKET_ITEM_TEXT_MAX),
                qty, qtySqm, "PIECE",
                null, null, // rawPrice/rawCurrency: not a costing input, see Javadoc
                clip(item.unit(), TICKET_ITEM_RAW_UNIT_MAX),
                null, // proposedPrice: see Javadoc
                "THB",
                item.catalogPriceId(), null,
                th.co.glr.hr.pricingrequest.PricingRequestService.STOCK_IN_THAILAND.equals(item.stockSource()), null));
        }
        return rows;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Automatic expiry sweep for a PRICING_REQUEST-origin quotation, the counterpart of
     * {@code CustomerQuotationService#expireOverdueQuotations} for the legacy chain. A single guarded UPDATE (via
     * {@link DealQuotationRepository#expireOverdueQuotations}), one PR event + one CEO notification per quotation
     * flipped, NEVER changes the pricing request's own status. A {@code DEAL_DIRECT} quotation never expires; the
     * query is scoped to {@code origin = 'PRICING_REQUEST'}. Runs on the same {@link QuotationExpiryWorker} as the
     * legacy sweep.
     */
    @Transactional
    public int expireOverdueQuotations() {
        if (pricingRequests == null) {
            return 0;
        }
        List<DealQuotationRepository.ExpiredQuotationRow> expired = quotations.expireOverdueQuotations();
        for (DealQuotationRepository.ExpiredQuotationRow row : expired) {
            pricingRequests.findSummary(row.pricingRequestId()).ifPresent(summary -> {
                pricingRequests.addEvent(summary.id(), summary.ticketId(), null, "System (quotation expiry sweep)",
                    PricingRequestEventKind.CUSTOMER_QUOTATION_EXPIRED, summary.status(), summary.status(),
                    "ใบเสนอราคา " + row.number() + " หมดอายุ", null);
                notifications.notifyByRoleForPricingRequest("ceo", summary.id(),
                    PricingRequestEventKind.CUSTOMER_QUOTATION_EXPIRED, "ใบเสนอราคา " + row.number() + " หมดอายุ");
                // The rep who owns the deal chases the customer or re-quotes, unless the deal is lost/cancelled.
                if (!tickets.isDeadDeal(summary.ticketId())) {
                    notifications.notifyEmployeeAtLink(summary.ticketCreatedById(),
                        PricingRequestEventKind.CUSTOMER_QUOTATION_EXPIRED,
                        DealNoticeText.quotationExpired(row.number(), summary.ticketCode(), summary.customerName()),
                        "/tickets/" + summary.ticketId());
                }
            });
        }
        return expired.size();
    }

    @Transactional
    public DealQuotationDto cancel(long id, CancelRequest request, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        // The ancestry of a rejected-and-resubmitted row is a TREE: cancelling a DRAFT row that itself has an OPEN child
        // would let that child's grandparent look "free" (hasOpenRevision checks DIRECT children only, and CANCELLED is not
        // open), so the grandparent could mint a sibling branch while the first was alive. Approval only walks UPWARD from
        // a leaf, so both branches could end APPROVED: two approved quotations on one ticket. Requiring every cancel to be
        // a LEAF closes this by induction: at most one LIVE path (DRAFT/PENDING_APPROVAL/APPROVED) exists through a chain,
        // and approve()'s upward walk walks the only one.
        //
        // Locks the ticket FIRST, like #requireNoOpenRevision: otherwise a concurrent cancel(id) and submit(id) (minting a
        // child of id) could each read hasOpenRevision against pre-commit state and both proceed.
        quotations.lockTicket(quotation.ticketId());
        if (quotations.hasOpenRevision(id)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยกเลิกไม่ได้ เนื่องจากมีฉบับแก้ไขของใบเสนอราคานี้อยู่ กรุณาจัดการฉบับแก้ไขนั้นก่อน");
        }
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
        // A PRICING_REQUEST-origin quotation (ISSUED / REVISION_REQUESTED) may be revised by its OWNING sales rep only (the
        // #recordOutcome gate, #requireOutcomeAccess), narrower than #requireEditAccessForQuotation: the manager
        // APPROVES the revision, not authors it. DEAL_DIRECT: edit access + APPROVED.
        if (isPricingRequestOrigin(source)) {
            requireOutcomeAccess(actor, source);
        } else {
            requireEditAccessForQuotation(actor, source);
        }
        requireRevisableStatus(source);
        requireNoOpenRevision(source); // locks the ticket
        // RE-CHECK the status AFTER the lock: a concurrent approve() (which supersedes siblings via the
        // one-approved-per-deal sweep) could have superseded THIS source between the first check and the lock, and the
        // lock only guarantees this call sees what committed before it. Same message as the check above.
        source = requireRevisableStatus(requireQuotation(id));
        // A SUPERSEDED (customer-change revision) or CANCELLED request is dead, and so is revising its quotation.
        if (isPricingRequestOrigin(source)) {
            String requestStatus = pricingRequests.findSummary(source.pricingRequestId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้")).status();
            if (PricingRequestStatus.SUPERSEDED.equals(requestStatus) || PricingRequestStatus.CANCELLED.equals(requestStatus)) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "คำขอราคานี้ถูกแทนที่ด้วยรอบแก้ไขหรือถูกยกเลิกแล้ว — สร้างฉบับแก้ไขของใบเสนอราคานี้ไม่ได้");
            }
        }
        long newId = insertRevisionCopyOf(source, actor);
        DealQuotationDto revision = requireQuotation(newId);
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.REVISION_REQUESTED, null, null,
            "สร้างใบเสนอราคาฉบับแก้ไข " + revision.number() + " จาก " + source.number());
        return revision;
    }

    /**
     * "สร้างจากใบเดิม" / สั่งเหมือนเดิม: clone an APPROVED direct-deal quotation into a new, INDEPENDENT DRAFT on the
     * same deal, at the next number in the SAME shared numbering family a revision uses (see {@link #insertCopyOf}),
     * everything copied verbatim.
     *
     * <p>A deal may hold only ONE APPROVED {@code DEAL_DIRECT} quotation. Cloning leaves {@code source} APPROVED while
     * the clone is DRAFT/PENDING_APPROVAL, and never links through {@code parentQuotationId} (only the audit-only
     * {@code derivedFromQuotationId}, see {@link DealQuotationDtos.DealQuotationDto#derivedFromQuotationId}), so the
     * ancestor-supersede walk, {@link DealQuotationRepository#hasOpenRevision} and the "แก้" bucket predicate never
     * reach {@code source} through it. When the CLONE is later approved, {@link #approve}'s same-ticket sweep
     * supersedes {@code source}, as it would for a revision.
     *
     * <p>Multiple clones of one source are allowed (no {@link #requireNoOpenRevision}-style guard); only the
     * ticket-wide advisory lock is taken, so concurrent clones/revisions of a family serialise on the shared
     * {base}-{n} counter and cannot collide on the UNIQUE {@code number}. A SUPERSEDED source is refused (409) like a
     * non-APPROVED one, so a clone is only ever made off the deal's one live APPROVED document.
     */
    @Transactional
    public DealQuotationDto createReorder(long id, UserPrincipal actor) {
        DealQuotationDto source = requireQuotation(id);
        requireEditAccessForQuotation(actor, source);
        // Defence in depth; see #createRevision's identical comment.
        requireReorderAllowedForOrigin(source);
        requireApprovedForClone(source, "สร้างจากใบเดิมได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        if (tickets.isOrderConfirmedFromQuotation(source.ticketId(), source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคานี้ยืนยันคำสั่งซื้อแล้ว จึงสร้างจากใบเดิมไม่ได้");
        }
        // No requireNoOpenRevision here on purpose: multiple clones of one APPROVED source are allowed. Still locks the
        // ticket (same advisory lock as #requireNoOpenRevision/#createRevision) so it serialises against a CONCURRENT
        // revision or reorder of the family before #insertCopyOf computes the next {base}-{n} suffix; otherwise two callers
        // could read the same MAX(quotation_revision_no) and race into the number's UNIQUE constraint.
        quotations.lockTicket(source.ticketId());
        // RE-CHECK the status AFTER the lock (see #createRevision): a concurrent approve() could have superseded this
        // source between the check and the lock, and without re-fetching this would clone a SUPERSEDED source.
        source = requireApprovedForClone(requireQuotation(id),
            "สร้างจากใบเดิมได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        long newId = insertReorderCopyOf(source, actor);
        DealQuotationDto reorder = requireQuotation(newId);
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.DEAL_QUOTATION_REORDERED,
            null, null,
            "สร้างใบเสนอราคา " + reorder.number() + " จากใบเดิม " + source.number() + " (สั่งเหมือนเดิม)");
        return reorder;
    }

    /** {@link #createRevision}'s copy step: a new DRAFT LINKED to {@code source} ({@code parentQuotationId}), so
     * {@code source} is SUPERSEDED once this child reaches APPROVED. Thin wrapper over {@link #insertCopyOf}. */
    private long insertRevisionCopyOf(DealQuotationDto source, UserPrincipal actor) {
        return insertCopyOf(source, actor, source.id(), null);
    }

    /** {@link #createReorder}'s copy step: a new DRAFT with NO {@code parentQuotationId} (clone creation never
     * supersedes {@code source}; the clone's own approval does, via {@link #approve}'s same-ticket sweep), only the
     * audit-only {@code derivedFromQuotationId}. Thin wrapper over {@link #insertCopyOf}. */
    private long insertReorderCopyOf(DealQuotationDto source, UserPrincipal actor) {
        return insertCopyOf(source, actor, null, source.id());
    }

    /**
     * The copy step {@link #createRevision}, {@link #submit}'s resubmit-after-ตีกลับ path AND {@link #createReorder}
     * all need: a new DRAFT row, every header field copied VERBATIM, its items and item pictures copied, at the next
     * number in the shared {base}-{n} family. Callers own their precondition (the three paths gate on different source
     * statuses) and must lock the ticket and check any open-revision guard BEFORE calling.
     *
     * <p>{@code parentQuotationId}/{@code derivedFromQuotationId} are the one thing distinguishing a revision from a
     * reorder clone: exactly one is non-null, decided by the caller.
     */
    private long insertCopyOf(DealQuotationDto source, UserPrincipal actor,
                              Long parentQuotationId, Long derivedFromQuotationId) {
        // Works for both a current parent ("QT-2026-0014-1", revisionNo 1 -> base "QT-2026-0014") and a legacy bare one
        // ("QT-2026-0014"); see DealQuotationRepository#baseNumber.
        String baseNumber = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        // NOT source.revisionNo() + 1: see DealQuotationRepository#nextRevisionNo for the duplicate-key crash that formula
        // has once a prior revision attempt was cancelled. Shared by revisions AND reorder clones (the same ticket-scoped
        // counter), so a clone and a revision of one family cannot mint the same suffix.
        int nextRevisionNo = quotations.nextRevisionNo(source.ticketId(), baseNumber);
        String newNumber = DealQuotationRepository.revisionNumber(baseNumber, nextRevisionNo);
        BigDecimal sourceVatRate = WastageCalculator.vatRateFor(source.documentLanguage());
        // A PRICING_REQUEST-origin revision keeps the source's pricing-request/decision ITEM links (matched by item id);
        // dropping them would silently switch off the CEO-price comparison (priceChangedFromCeo) on the copy.
        boolean prSource = isPricingRequestOrigin(source);
        Map<Long, DealQuotationRepository.ItemLink> sourceLinks = new java.util.HashMap<>();
        if (prSource) {
            for (DealQuotationRepository.ItemLink link : quotations.findItemLinks(source.id())) {
                sourceLinks.put(link.itemId(), link);
            }
        }
        List<NewItem> items = source.items().stream().map(item -> {
            NewItem copy = toNewItemFromDto(item, sourceVatRate, source.documentLanguage());
            DealQuotationRepository.ItemLink link = sourceLinks.get(item.id());
            return link == null ? copy
                : withDecisionLink(copy, link.pricingRequestItemId(), link.pricingDecisionItemId());
        }).toList();
        Long sourceDecisionId = prSource ? quotations.findPricingDecisionId(source.id()).orElse(null) : null;
        long newId = quotations.insertDraft(new InsertDraftParams(
            source.ticketId(), newNumber, actor.id(), source.salesRepId(),
            source.customerName(), source.customerAddress(), source.customerTaxId(), source.customerPhone(),
            // The source's frozen snapshot, verbatim: the rep re-picks or clears it on the copy's next save through #update;
            // nothing re-defaults from the deal's contact.
            new ContactSnapshot(source.contactId(), source.contactName(), source.contactPhone(), source.contactEmail()),
            source.projectName(), source.deptCode(), source.unitCode(), source.offerDate(),
            source.depositPercent(), source.remainderMode(), source.creditDays(), source.validityDays(),
            // The copy inherits the source's validity MODE AND DATE verbatim: a DATE-mode source's "ภายในวันที่" deadline is a
            // fact about the customer's order and must not reset to a day count. source.validityMode() is never null (the
            // repository normalises NULL to DAYS), so resolveValidityMode is not needed here.
            source.validityMode(), source.validityUntil(),
            // The copy inherits the source's ภาษาเอกสาร and currency verbatim: copying an English quotation must not reissue it
            // in Thai. source.documentLanguage() is never null (the repository normalises NULL to TH).
            source.customerNotes(), source.priceMode(), source.documentLanguage(),
            WastageCalculator.defaultCurrencyFor(source.documentLanguage()),
            // The copy carries the source's print-name override VERBATIM, with no re-validation (an id valid at the source's
            // last save stays as it was, as the contact/rep snapshot is copied without re-checking).
            source.printedByDisplayId(), source.salesRepDisplayId(),
            // The copy inherits the honorific flag and full-payment term VERBATIM: copying must not re-add "คุณ" or drop a
            // chosen zero-deposit term.
            source.omitContactHonorific(), source.fullPaymentTerm(),
            source.subtotalAmount(), parentQuotationId,
            nextRevisionNo, derivedFromQuotationId, items,
            // The copy inherits the source's ผู้รับ VERBATIM, so a DESIGNER quotation's revision stays DESIGNER
            // (TicketSummaryDto.liveDirectQuotation serves the revision's).
            //
            // The origin is COPIED, not stated. #createRevision has its own PRICING_REQUEST branch; only #createReorder
            // refuses the origin (#requireReorderAllowedForOrigin), and #submit's resubmit-as-revision path excludes it, so
            // that caller is DEAL_DIRECT-only.
            prSource ? "PRICING_REQUEST" : DealQuotationRepository.ORIGIN_DEAL_DIRECT,
            source.recipientType(), source.recipientLabel(),
            prSource ? source.pricingRequestId() : null, sourceDecisionId,
            // The copy inherits the source's manual buyer name VERBATIM, and the phone and email recorded for that person
            // (stored values, already checked when saved).
            source.orderedByName(), source.orderedByPhone(), source.orderedByEmail()));
        // The copy's items point at the source's (immutable, shared) picture rows, matched by seq. Shared by revisions and
        // reorder clones.
        quotations.copyPictureLinks(source.id(), newId);
        // A CEO-linked line already removed on the source stays "removed from the CEO's decision" on the copy; resetting the
        // counter to 0 would let a revision dodge #requireCeoApprovalIfChanged.
        if (prSource && source.itemsRemovedFromCeoCount() > 0) {
            quotations.incrementItemsRemovedFromCeo(newId, source.itemsRemovedFromCeoCount());
        }
        // The copy inherits "ไม่แสดงชื่อโครงการในใบเสนอราคา" VERBATIM: re-issuing a document that hid its project must not
        // start printing it. The new row is a DRAFT on the column's DEFAULT FALSE, so only a true needs writing.
        if (source.hideProjectName()) {
            quotations.updateHideProjectName(newId, true);
        }
        return newId;
    }

    /**
     * The APPROVED-only precondition {@link #createRevision} and {@link #createReorder} check BEFORE the ticket lock
     * (a cheap early rejection) AND AGAIN right after it (the check that matters, since a lock does not re-validate a
     * fact read before it was acquired). One method so the message cannot drift between call sites.
     */
    private DealQuotationDto requireApprovedForClone(DealQuotationDto quotation, String actionMessage) {
        if (!QuotationStatus.APPROVED.equals(quotation.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                actionMessage + " (ปัจจุบัน: " + quotation.docStatus() + ")");
        }
        return quotation;
    }

    /**
     * Once an order is confirmed from a direct quotation, that quotation is the agreed order: no OTHER direct
     * quotation on the deal may be submitted or approved (approval would supersede the confirmed one, move the payable
     * and flip the deposit policy), and the confirmed one may not be cloned.
     */
    private void requireNoOtherDirectQuotationAfterConfirm(DealQuotationDto quotation) {
        if (isPricingRequestOrigin(quotation)
                || !tickets.hasPromotionFromQuotationEvent(quotation.ticketId())
                || tickets.isOrderConfirmedFromQuotation(quotation.ticketId(), quotation.id())) {
            return;
        }
        throw new ApiException(HttpStatus.CONFLICT,
            "ดีลนี้ยืนยันคำสั่งซื้อจากใบเสนอราคาอื่นแล้ว จึงส่ง/อนุมัติใบเสนอราคานี้ไม่ได้");
    }

    private static boolean isPricingRequestOrigin(DealQuotationDto quotation) {
        return "PRICING_REQUEST".equals(quotation.origin());
    }

    /** What {@link #createRevision} may revise: a DEAL_DIRECT row only when APPROVED; a PRICING_REQUEST row only when
     * ISSUED or REVISION_REQUESTED. ACCEPTED is the deal's finalized quotation and REJECTED/EXPIRED have their own
     * recovery ({@link #createFromPricingRequest}), so none of those may be revised. */
    private DealQuotationDto requireRevisableStatus(DealQuotationDto quotation) {
        if (isPricingRequestOrigin(quotation)) {
            if (!QuotationStatus.ISSUED.equals(quotation.docStatus())
                    && !QuotationStatus.REVISION_REQUESTED.equals(quotation.docStatus())) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ออกแล้วหรือลูกค้าขอแก้ไขเท่านั้น (ปัจจุบัน: "
                        + quotation.docStatus() + ")");
            }
            return quotation;
        }
        // The quotation an order was confirmed from is the agreed order, so it is not revisable.
        if (tickets.isOrderConfirmedFromQuotation(quotation.ticketId(), quotation.id())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ใบเสนอราคานี้ยืนยันคำสั่งซื้อแล้ว จึงสร้างฉบับแก้ไขไม่ได้");
        }
        return requireApprovedForClone(quotation, "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
    }

    /**
     * Two concurrent callers of {@link #insertRevisionCopyOf} on the SAME source compute the same child number
     * ({base}-{revisionNo+1}) and {@code sales.quotation.number} is UNIQUE, so the second INSERT would surface as a bare
     * 500. Locking the ticket first serialises them: the second caller then sees the first's child in
     * {@link DealQuotationRepository#hasOpenRevision} and 409s with a clear reason. Shared by {@link #createRevision}
     * and {@link #submit}'s resubmit-after-rejection path.
     */
    private void requireNoOpenRevision(DealQuotationDto source) {
        quotations.lockTicket(source.ticketId());
        if (quotations.hasOpenRevision(source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
        }
    }

    // Item pictures: one picture per item, placement BELOW | BESIDE.
    //
    // AUTHZ is the same rule as editing the quotation: every write goes through #requireEditablePicture, which calls
    // the same #requireEditAccessForQuotation as #update/#submit/#cancel (sales on their OWN deal; sales_manager or the
    // live can_create_quotation grant on any deal, the set DealEntryAccess#canEnterDeal admits), then refuses anything
    // not DRAFT as #update does (409). The repository re-states DRAFT in every UPDATE's WHERE, so a submit racing an
    // upload cannot put a picture on a PENDING_APPROVAL document. Reads use #requireViewAccess, as
    // GET /deal-quotations/{id} does.

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
            // Lost a race with submit/cancel. Clean up explicitly rather than rely on rollback: the hand-wired integration
            // suite runs with no transaction proxy.
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

    /** 403 (edit access, as #update) → 409 (not DRAFT, as #update) → 404 (item not on THIS quotation). Authz first, so
     * a caller without edit rights learns nothing about item ids. */
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

    // Rendering: builds th.co.glr.hr.ticket.QuotationRenderModel via DealQuotationRenderAdapter and renders it through
    // the same QuotationRenderer the legacy/PCR path uses (one renderer, one rich model, two builders).

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

    /** Resolves the approver's signature bytes and hands everything to the adapter, which is otherwise a pure function.
     *
     * <p>"Already-sent quotations never change afterwards": the bytes come from the snapshot frozen at approval
     * ({@link DealQuotationRepository#approve} writes it in the same statement as the status change) and NEVER from the
     * live {@code hr.employee_signature} row when a snapshot exists, even one with no image (the approver had none on
     * file then, so the document stays unsigned). The live read is only a fallback for an approved row with no snapshot
     * (V175's backfill gives every earlier approval one); a DRAFT/PENDING row has {@code approvedById == null} and gets no
     * signature. Both origins share this read: whoever approves freezes into the same single snapshot row.
     *
     * <p>ผู้พิมพ์ (slot 0) and พนักงานขาย (slot 1) also get a signature image, resolved LIVE via {@code signatures.find(id)}
     * on every render and NEVER frozen (no approval event defines when to freeze them). The id is whichever the rep
     * selected: the display override when set ({@code printedByDisplayId}/{@code salesRepDisplayId}), else the real
     * deal-ownership id ({@code createdById}/{@code salesRepId}). A missing signature, or an id equal to the approver's,
     * just makes {@code signatures.find} return empty and the slot print its text-only name; each slot resolves
     * independently. */
    private th.co.glr.hr.ticket.QuotationRenderModel toRenderModel(DealQuotationDto quotation) {
        byte[] signaturePng = null;
        String signatureMime = null;
        var snapshot = quotations.findApproverSignatureSnapshot(quotation.id());
        if (snapshot.isPresent()) {
            signaturePng = snapshot.get().image();
            signatureMime = snapshot.get().mimeType();
        } else if (quotation.approverHasSignature() && quotation.approvedById() != null) {
            var signature = signatures.find(quotation.approvedById());
            if (signature.isPresent()) {
                signaturePng = signature.get().image();
                signatureMime = signature.get().mimeType();
            }
        }
        // Item picture bytes are a separate read, and only when some item has one: the DTO never carries bytes.
        Map<Long, PictureImage> itemPictures = quotation.items().stream().anyMatch(DealQuotationItemDto::hasPicture)
            ? quotations.findPictureImagesForQuotation(quotation.id())
            : Map.of();

        long printedById = quotation.printedByDisplayId() != null
            ? quotation.printedByDisplayId() : quotation.createdById();
        byte[] printedBySignaturePng = null;
        String printedBySignatureMime = null;
        var printedBySignature = signatures.find(printedById);
        if (printedBySignature.isPresent()) {
            printedBySignaturePng = printedBySignature.get().image();
            printedBySignatureMime = printedBySignature.get().mimeType();
        }

        long salesRepId = quotation.salesRepDisplayId() != null
            ? quotation.salesRepDisplayId() : quotation.salesRepId();
        byte[] salesRepSignaturePng = null;
        String salesRepSignatureMime = null;
        var salesRepSignature = signatures.find(salesRepId);
        if (salesRepSignature.isPresent()) {
            salesRepSignaturePng = salesRepSignature.get().image();
            salesRepSignatureMime = salesRepSignature.get().mimeType();
        }

        return DealQuotationRenderAdapter.toRenderModel(quotation, signaturePng, signatureMime,
            bankBlockLines, itemPictures,
            printedBySignaturePng, printedBySignatureMime,
            salesRepSignaturePng, salesRepSignatureMime);
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

    // ผู้สั่งซื้อ: snapshotted (V167).

    /**
     * The ลูกค้า snapshot to stamp onto the quotation, read from the LIVE customer row. The one place create and
     * update both build it, so they cannot snapshot different things; {@code updateHeader} rewrites these columns on
     * every DRAFT save, so an edit to เลขที่ผู้เสียภาษี / โทร. on the deal card reaches the document.
     *
     * <p>{@code name} takes the LIVE {@code customer.name()} whenever the customer row exists, so a typo correction
     * saved to {@code customers.customer.name} reaches the document (the ticket's own frozen {@code customer_name}
     * never gets a cascade, by design: see V167 on why an issued document stays frozen). It falls back to the ticket's
     * frozen name only when the row is gone, like address/taxId/phone below.
     */
    private CustomerSnapshot customerSnapshot(TicketSummaryDto ticket) {
        CustomerDto customer = ticket.customerId() != null
            ? customers.findById(ticket.customerId()).orElse(null) : null;
        return new CustomerSnapshot(
            customer != null ? customer.name() : ticket.customerName(),
            customer != null ? customer.address() : null,
            customer != null ? customer.taxId() : null,
            customer != null ? customer.phone() : null);
    }

    /**
     * Resolves the ผู้ติดต่อ for a create/update and returns the FROZEN snapshot to store. Precedence: the request's
     * own {@code contactId}, else the contact the draft already carries ({@code existingContactId}, update only), else
     * none (a blank snapshot).
     *
     * <p>The contact is picked per quotation and is optional: the deal's {@code sales.ticket.contact_id} is NOT a
     * fallback and nothing is pre-filled from it or another quotation. No contact anywhere in the chain yields a blank
     * snapshot, not a refusal. A contact id that IS given (or kept from the draft) must exist and belong to the deal's
     * customer; another customer's id is a client bug or a probe, refused as 400 with the same wording so nothing about
     * other customers' contacts leaks. The name/phone/email are read NOW and written onto the quotation; a later edit
     * of the contact row never changes the document (V167: an approved, emailed PDF stays frozen).
     *
     * <p>#create, #update and #createFromPricingRequest also write the snapshot's name to {@code ordered_by_name}
     * (stored only, never printed: the ผู้สั่งซื้อ slot is always dotted); the free-text name is not taken from the request.
     */
    private ContactSnapshot resolveContact(Long requestedContactId, Long existingContactId, TicketSummaryDto ticket) {
        Long contactId = requestedContactId != null ? requestedContactId : existingContactId;
        if (contactId == null) {
            return new ContactSnapshot(null, null, null, null);
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
     * Builds every row, in PRINTED order, and derives each ADJUSTMENT row from the rows it applies to.
     *
     * <p><b>Ordering is load-bearing.</b> An adjustment is a percentage of the lines ABOVE it, so every ADJUSTMENT row
     * is moved to the END, stably. Sequence numbers are assigned AFTER that move, so the stored {@code seq}, the
     * printed order and the arithmetic cannot disagree whatever order the client sent.
     *
     * <p><b>Two adjustments do NOT compound.</b> {@code adjustmentBase} accumulates only NON-adjustment line amounts,
     * so a second ส่วนลดพิเศษ is a percentage of the same original subtotal as the first (two 3% rows mean 6%, not
     * 5.91%). The owner has not ruled on this; to compound, add the adjustment's own (negative) lineAmount to the base.
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
        boolean mixed = WastageCalculator.PRICE_MODE_MIXED.equals(priceMode);
        for (ItemInput input : ordered) {
            seq++;
            // The mode THIS row is priced in: the document's, unless the document is MIXED, in which case the one the row
            // states. Everything below #buildItem takes it as "the price mode", so a mixed document's row runs the same code as
            // a single-mode document's row.
            String rowPriceMode = rowPriceMode(input, priceMode, seq);
            NewItem item = buildItem(input, seq, rowPriceMode, documentLanguage, adjustmentBase);
            // …and it is STORED on the row only where it is the row's to decide. On a single-mode
            // document the column stays NULL, so nothing a client left on a row can sit there
            // unseen and decide a price the day the document is switched to MIXED.
            if (mixed && WastageCalculator.LINE_TYPE_TILE.equals(item.lineType())) {
                item = item.withPriceMode(rowPriceMode);
            }
            if (!WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())) {
                adjustmentBase = adjustmentBase.add(item.lineAmount());
            }
            items.add(item);
        }
        // The subtotal can go below zero two ways from an ordinary payload: two 60% adjustments (neither compounds, so
        // 120% together), and a FLAT adjustmentAmount (bounded only by @DecimalMax("99999999"), unrelated to the base). A
        // negative grand total says GL&R owes the customer money, so refuse it at write time. Zero is allowed: a 100%
        // discount is intelligible.
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

    /**
     * Where a MIXED document (each tile row in its own price mode) is allowed at all:
     *
     * <ul>
     *   <li><b>Thai documents only.</b> On the English form SPECIAL_SQM changes the row's UNIT and quantity (USD per
     *       sqm, quantity in square metres; {@link WastageCalculator#isEnglishPerSqm}), and submit checks stored rows
     *       on that basis for the whole document, so mixing there is a different document nobody asked for.</li>
     *   <li><b>Never a quotation that came from a pricing request.</b> Its mode is the CEO's decision
     *       ({@code pricing_decision.price_mode}, one per decision) and every "changed from the CEO" comparison is made
     *       against it; a mixed document would leave those nothing sound to compare to, so it is refused.</li>
     * </ul>
     *
     * Any other mode passes untouched: no rule is added for a single-mode document.
     */
    private void requirePriceModeAllowed(String priceMode, String documentLanguage, boolean pricingRequestOrigin) {
        if (!WastageCalculator.PRICE_MODE_MIXED.equals(priceMode)) {
            return;
        }
        if (pricingRequestOrigin) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ใบเสนอราคาที่สร้างจากคำขอราคาใช้วิธีคิดราคาตามที่ CEO อนุมัติ จึงกรอกราคาแบบผสมหลายวิธีไม่ได้");
        }
        if (!WastageCalculator.DOCUMENT_LANGUAGE_TH.equals(documentLanguage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "การกรอกราคาแบบผสมหลายวิธีในใบเดียว ใช้ได้กับใบเสนอราคาภาษาไทยเท่านั้น");
        }
    }

    /**
     * The price mode ONE row of a document being written is priced in — the write-path face of
     * {@link WastageCalculator#rowPriceMode}, which states the rule. It differs from that method in
     * the one way a WRITE must: on a MIXED document a tile row that states no mode is REFUSED,
     * naming the row, where a read falls back to NET. Guessing a mode from whichever price fields
     * a row happens to carry is exactly the silent repricing the rest of this class is built to
     * prevent (see #update's own comment on a missing document mode).
     *
     * <p>A PLAIN or ADJUSTMENT row has no price mode on any document; on a MIXED one it is handed
     * NET, the mode such a row has always been built under — never MIXED itself, which nothing
     * below this point knows how to price.
     */
    private String rowPriceMode(ItemInput input, String documentPriceMode, int rowNumber) {
        if (!WastageCalculator.PRICE_MODE_MIXED.equals(documentPriceMode)) {
            return documentPriceMode;
        }
        if (!WastageCalculator.LINE_TYPE_TILE.equals(lineType(input))) {
            return WastageCalculator.PRICE_MODE_NET;
        }
        if (isBlank(input.priceMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่ " + rowNumber
                + ": กรุณาเลือกวิธีคิดราคาของรายการนี้ (ใบเสนอราคานี้กรอกราคาแบบผสมหลายวิธี)");
        }
        String rowMode = input.priceMode().trim();
        // Bean validation already bounds this on the HTTP path; the service is also called
        // directly (tests, other services), so the bound is restated where it is relied on.
        if (!WastageCalculator.isRowPriceMode(rowMode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + rowNumber + ": วิธีคิดราคาไม่ถูกต้อง: " + rowMode);
        }
        return rowMode;
    }

    /** {@code null}/blank reads as TH, the Thai F-SM-002 that every row before V169 stores. */
    private String resolveDocumentLanguage(String documentLanguage) {
        return isBlank(documentLanguage)
            ? WastageCalculator.DOCUMENT_LANGUAGE_TH : documentLanguage.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The currency, defaulted FROM the language (TH→THB, EN→USD) so a rep picks one thing.
     *
     * <p>An explicit request that DISAGREES with the language is refused. The two forms are single-currency documents
     * (F-SM-002 prints เป็นเงิน (บาท) and a 7% VAT row, F-SM-008 prints Amount (USD) and no VAT row), so a "Thai
     * document in USD" would print baht headings over dollar figures. The field stays on the wire so that, if an English
     * document may ever be billed in THB, the relaxation is these three lines.
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
     * The nullable {@code Boolean} on the wire resolves to {@code false} (UNticked) when absent; shared by
     * {@link #create}/{@link #update} so "missing means unticked" cannot drift between them.
     */
    private boolean resolveOmitContactHonorific(Boolean omitContactHonorific) {
        return Boolean.TRUE.equals(omitContactHonorific);
    }

    /**
     * ผู้ติดต่อ's phone as stored: trimmed, a blank read as none. Free text on purpose (an extension or several numbers
     * is normal), so only its length is checked, on the TRIMMED value, which is why this is not a bean-validation
     * annotation. Shared by {@link #create}/{@link #update}; both call it before writing anything.
     */
    private String resolveOrderedByPhone(String orderedByPhone) {
        String phone = blankToNull(orderedByPhone);
        if (phone != null && phone.length() > ORDERED_BY_PHONE_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Phone ของลูกค้ายาวเกิน " + ORDERED_BY_PHONE_MAX + " ตัวอักษร");
        }
        return phone;
    }

    /**
     * ผู้ติดต่อ's email as stored: trimmed, a blank read as none, otherwise kept exactly as typed (it records what the
     * rep was given). Refused when too long for its column or not address-shaped ({@link #ORDERED_BY_EMAIL_SHAPE}).
     */
    private String resolveOrderedByEmail(String orderedByEmail) {
        String email = blankToNull(orderedByEmail);
        if (email == null) {
            return null;
        }
        if (email.length() > ORDERED_BY_EMAIL_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Email ของลูกค้ายาวเกิน " + ORDERED_BY_EMAIL_MAX + " ตัวอักษร");
        }
        if (!ORDERED_BY_EMAIL_SHAPE.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email ของลูกค้า: รูปแบบอีเมลไม่ถูกต้อง");
        }
        return email;
    }

    /**
     * {@code true} only for an EXPLICIT {@code depositPercent == 0}. A {@code null} depositPercent is NOT "no deposit":
     * it defaults to 30% like {@code DealQuotationRenderAdapter#depositLine}, so a quotation that never set a percent
     * must not have its remainderMode/creditDays cleared or accept a fullPaymentTerm. Shared by {@link #create}/
     * {@link #update} so "0% is the ONE signal for no-deposit" cannot drift.
     */
    private boolean isZeroDeposit(Integer depositPercent) {
        return depositPercent != null && depositPercent == 0;
    }

    /**
     * A {@code fullPaymentTerm} only applies to a zero-deposit document; on any other (including a null depositPercent,
     * which defaults to 30%, see {@link #isZeroDeposit}) it is forced to null, so unticking "ไม่รับมัดจำ" never leaves a
     * stale term. On a zero-deposit document null/blank passes through: a DRAFT may be saved before the rep picks one;
     * only {@link #submit} refuses to advance it. An unrecognised code never reaches here ({@code @Pattern} 400s it).
     */
    private String resolveFullPaymentTerm(Integer depositPercent, String fullPaymentTerm) {
        return isZeroDeposit(depositPercent) ? blankToNull(fullPaymentTerm) : null;
    }

    /**
     * The save-time half of the credit-days rule; the submit-time half (a BLANK value once {@code remainderMode} is
     * CREDIT may not advance past DRAFT) is inline in {@link #submit}, the "create/update permissive, submit strict"
     * split of {@link #resolveFullPaymentTerm}. Unlike a blank value (which may sit in a draft), an explicit INVALID
     * value (0 or negative) is refused HERE on every save. Create/update never call this for a blank value (the
     * {@code creditDays <= 0} guard below short-circuits on null).
     */
    private void requireValidCreditDays(String remainderMode, Integer creditDays) {
        if ("CREDIT".equals(remainderMode) && creditDays != null && creditDays <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
        }
    }

    /** {@code null}/blank reads as DAYS, which every row before V178 (and the legacy customer-quotation path) stores. */
    private String resolveValidityMode(String validityMode) {
        return isBlank(validityMode)
            ? WastageCalculator.VALIDITY_MODE_DAYS : validityMode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * DAYS mode always stores {@code validity_until = NULL}; only the day count in {@code validity_days} matters.
     *
     * <p>DATE mode is refused on a document with NO special pricing (a price-validity DEADLINE only makes sense when
     * there is a special price or discount to protect; see {@link DealQuotationRenderAdapter#hasSpecialPricing}).
     * Otherwise it requires a date and refuses one that predates the quotation's own document date
     * ({@code quotationDate}: today on create, the frozen creation date on update).
     */
    private LocalDate requireValidityUntilForMode(String validityMode, LocalDate validityUntil,
                                                   LocalDate quotationDate, boolean hasSpecialPricing) {
        if (!WastageCalculator.VALIDITY_MODE_DATE.equals(validityMode)) {
            return null;
        }
        if (!hasSpecialPricing) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ระบุวันที่ยืนราคาได้เฉพาะใบเสนอราคาที่มีราคาพิเศษหรือส่วนลด");
        }
        if (validityUntil == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุวันที่ยืนราคา");
        }
        if (validityUntil.isBefore(quotationDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ยืนราคาต้องไม่ก่อนวันที่ใบเสนอราคา");
        }
        return validityUntil;
    }

    /**
     * Validates a {@code printedByDisplayId}/{@code salesRepDisplayId} candidate before it is written. Null passes
     * (it means "use the real name"). A non-null id must be in the SAME eligible union
     * {@link #findQuotationDisplayNameOptions} lists (active AND a sales-division member OR a
     * {@code can_create_quotation} grant holder), checked by {@link DealQuotationRepository#isEligibleQuotationDisplayName},
     * which shares its predicate with the options query. Used by both {@link #create} and {@link #update}.
     */
    private Long requireEligibleDisplayEmployeeId(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        if (!quotations.isEligibleQuotationDisplayName(employeeId, DivisionAccessPolicy.SALES_DIVISION_CODE)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "พนักงานที่เลือกไม่สามารถแสดงเป็นผู้พิมพ์หรือพนักงานขายในใบเสนอราคาได้");
        }
        return employeeId;
    }

    /**
     * The option list for the ผู้พิมพ์/พนักงานขาย print-name selectors. Gated like every other quotation WRITE action
     * ({@link #EDIT_ROLES} or the live {@code can_create_quotation} grant), not {@link #requireEditAccess}'s per-deal
     * ownership rule: the list has no single deal's context (the dropdown can open before a quotation is loaded), and
     * anyone who may set the field on SOME deal may see who they can choose from.
     */
    public List<CommissionRepOptionDto> findQuotationDisplayNameOptions(UserPrincipal actor) {
        if (!EDIT_ROLES.contains(actor.role()) && !hasQuotationGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return quotations.findEligibleQuotationDisplayNameOptions(DivisionAccessPolicy.SALES_DIVISION_CODE);
    }

    // SPECIAL_SQM means "the rep states one price per sqm", and what that means follows the document's language
    // (WastageCalculator#isEnglishPerSqm): VAT-inclusive baht turned into a per-piece net on Thai, USD per sqm with the
    // quantity printed in sqm on English.

    /** See {@link #calculateLine}: the stateless preview has no quotation to read a mode off.
     *
     * <p>A row that STATES its mode is previewed in it; on a mixed document the editor always does, and it must win over
     * the guess below (a row just switched from ราคาพิเศษ to ราคาตั้ง − ส่วนลด % can still carry the ราคาพิเศษ in its hidden
     * box, and guessing from the fields would keep previewing the price the rep turned away from). */
    private String inferPriceMode(ItemInput input) {
        if (!isBlank(input.priceMode())) {
            String stated = input.priceMode().trim();
            if (!WastageCalculator.isRowPriceMode(stated)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "วิธีคิดราคาไม่ถูกต้อง: " + stated);
            }
            return stated;
        }
        if (input.specialPriceSqm() != null) {
            return WastageCalculator.PRICE_MODE_SPECIAL_SQM;
        }
        // A net typed per ตร.ม. says "ราคาสุทธิ" as one typed per แผ่น does; without this the row would preview as NET, off
        // its list price.
        if (input.directNetPrice() != null || input.directNetPriceSqm() != null) {
            return WastageCalculator.PRICE_MODE_DIRECT_NET;
        }
        return WastageCalculator.PRICE_MODE_NET;
    }

    /**
     * Item completeness rule: create/update (and {@link #submit}, over the stored rows) require every item complete:
     * model/color/texture/sizeText/a resolved {@code sqmPerPiece}/thicknessMm/piecesPerBox/unitPrice all present and
     * positive, plus a quantity for the item's own {@code quantityMode}. {@code brand}/{@code productCode}/
     * {@code locationLabel}/{@code originCountry}/lead time/notes stay optional.
     *
     * <p>{@code rowNumber == null} skips the check; the only caller passing null is {@link #calculateLine}'s
     * single-item preview (called on every keystroke; a half-typed row must preview, not 400). This is enforced here
     * rather than as bean validation on {@code ItemInput} because that is the SAME record
     * {@code DealQuotationController#calculateLine} validates with {@code @Valid}, so a bean-level
     * {@code @NotBlank}/{@code @NotNull} would 400 the mid-typing preview too; expressing the distinction needs
     * validation groups.
     */
    private NewItem buildItem(ItemInput input, Integer rowNumber, String priceMode,
                              String documentLanguage, BigDecimal adjustmentBase) {
        String lineType = lineType(input);
        // A Thai TILE row may state its prices per ตร.ม. including VAT. Converted HERE, first, so everything below (price
        // rules, completeness check, WastageCalculator, the stored row) keeps seeing per-แผ่น figures.
        if (WastageCalculator.LINE_TYPE_TILE.equals(lineType)) {
            input = withPerPiecePricesFromPerSqm(input, priceMode, documentLanguage, rowNumber);
        }
        // See #requirePriceValidForType's Javadoc for
        // why this is not gated on rowNumber the way the completeness check is.
        requirePriceValidForType(input, lineType, priceMode, documentLanguage, rowNumber);
        // An invalid lead-time VALUE (min < 1, or max < min) is refused unconditionally, even on the lenient preview: these
        // are wrong values, not an incomplete row, so there is no honest number to preview. Applies to TILE and PLAIN rows
        // (an ADJUSTMENT row has no lead time, see WastageCalculator.LINE_TYPE_ADJUSTMENT).
        if (!WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType)) {
            requireValidLeadTime(input.leadTimeMinDays(), input.leadTimeMaxDays(), rowNumber);
        }
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
            case WastageCalculator.LINE_TYPE_TILE -> buildTileItem(input, rowNumber, priceMode, documentLanguage, vatRate);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                "ประเภทรายการไม่ถูกต้อง: " + lineType);
        };
    }

    /**
     * "แก้เป็น ตร.ม. รวม VAT ทุกแบบ". Returns {@code input} with its per-แผ่น prices filled in from the per-ตร.ม. ones,
     * when it has any:
     *
     * <ul>
     *   <li>{@code listPriceSqm} → {@code unitPrice} (ราคาตั้ง), in every price mode;</li>
     *   <li>{@code directNetPriceSqm} → {@code directNetPrice} (ราคาสุทธิ), in DIRECT_NET only; under another mode it
     *       prices nothing, as {@code specialPriceSqm} is only read in its own.</li>
     * </ul>
     *
     * <p>Each is independent: a row may state one price per ตร.ม. and the other per แผ่น. A per-ตร.ม. price REPLACES its
     * per-แผ่น counterpart (one sent beside it is not consulted), so the stored typed and derived figures cannot
     * disagree. A row with neither is returned untouched: every English document, every row saved before this existed,
     * and every row created from a pricing request.
     *
     * <p>Refused rather than guessed at:
     * <ul>
     *   <li>on an English document (USD with no VAT row, where "รวม VAT" means nothing);</li>
     *   <li>a zero or negative price;</li>
     *   <li>a row with no area per piece. Never a fallback to reading 1,800 per ตร.ม. as 1,800 a piece, even on the
     *       lenient preview: there is no honest number to preview.</li>
     * </ul>
     */
    private ItemInput withPerPiecePricesFromPerSqm(ItemInput input, String priceMode, String documentLanguage,
                                                   Integer rowNumber) {
        BigDecimal listPriceSqm = input.listPriceSqm();
        BigDecimal directNetPriceSqm = WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode)
            ? input.directNetPriceSqm() : null;
        if (listPriceSqm == null && directNetPriceSqm == null) {
            return input;
        }
        String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
        if (WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(documentLanguage)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                where + "ราคาต่อ ตร.ม. รวม VAT ใช้ได้กับใบเสนอราคาภาษาไทยเท่านั้น");
        }
        if (listPriceSqm != null && listPriceSqm.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาตั้งต่อ ตร.ม. ต้องมากกว่าศูนย์");
        }
        if (directNetPriceSqm != null && directNetPriceSqm.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาสุทธิต่อ ตร.ม. ต้องมากกว่าศูนย์");
        }
        // #resolveSqmPerPiece's own order — the row's figure, else the catalogue's. The catalogue
        // is only asked when the row states no area of its own, so the usual row costs no lookup
        // here (#buildTileItem fetches the basis once itself, for more than the area).
        BigDecimal sqmPerPiece = resolveSqmPerPiece(input,
            input.sqmPerPiece() != null ? null : resolveCatalogBasis(input));
        if (sqmPerPiece == null || sqmPerPiece.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                where + "ขาด แผ่น/ตร.ม. จึงแปลงราคาต่อ ตร.ม. เป็นราคาต่อแผ่นไม่ได้");
        }
        try {
            return input.withPerPiecePrices(
                listPriceSqm != null
                    ? WastageCalculator.perPieceExVatFromSqmIncVat(listPriceSqm, sqmPerPiece) : input.unitPrice(),
                directNetPriceSqm != null
                    ? WastageCalculator.perPieceExVatFromSqmIncVat(directNetPriceSqm, sqmPerPiece)
                    : input.directNetPrice());
        } catch (IllegalArgumentException e) {
            // An area per piece outside WastageCalculator's sanity bounds — the same wrap every
            // other WastageCalculator refusal in this class gets.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
        }
    }

    private NewItem buildTileItem(ItemInput input, Integer rowNumber, String priceMode,
                                  String documentLanguage, BigDecimal vatRate) {
        // SPECIAL_SQM on an ENGLISH document: the rep's USD/sqm IS the printed Unit price and Net price, the quantity is
        // boxes × sqm/box, and there is no VAT to take out. Every Thai path below is untouched when this is false.
        boolean perSqm = WastageCalculator.isEnglishPerSqm(documentLanguage, priceMode);
        // Fetched ONCE and threaded through both #resolveSqmPerPiece AND the NewItem below; the catalogue's
        // width_mm/height_mm ride along so DealQuotationLines#sizeLine can print the face size in centimetres from
        // unambiguous millimetres. See NewItem#catalogWidthMm.
        CatalogSqmBasis catalogBasis = resolveCatalogBasis(input);
        BigDecimal sqmPerPiece = resolveSqmPerPiece(input, catalogBasis);
        if (rowNumber != null) {
            requireItemComplete(rowNumber, input, sqmPerPiece, perSqm);
        }
        // "Sell loose pieces": null reads as true. Resolved ONCE here: WastageCalculator.Input, the stored NewItem and the
        // printed line all read this boolean, never input.roundToFullBox() again.
        boolean roundToFullBox = input.roundToFullBox() == null || input.roundToFullBox();
        // ตร.ม./กล่อง is OPTIONAL for English per-sqm. WITH a box area, both box figures are required and the quantity is a
        // whole number of boxes, so loose pieces is refused. WITHOUT one, the printed sqm quantity derives from
        // piecesFinal × sqmPerPiece (DealQuotationLines#tilePrint), which has no box-count restriction: piecesPerBox
        // becomes optional and loose pieces is allowed like any other tile row.
        boolean hasBoxArea = input.sqmPerBox() != null && input.sqmPerBox().signum() > 0;
        if (perSqm && hasBoxArea) {
            // Never a silent pieces fallback: with a box area present there is no sqm quantity
            // without BOTH box figures. Checked on the lenient preview path too — there is no
            // honest number to preview.
            requireBoxDataForPerSqm(input, rowNumber);
            // A box-area quantity IS boxes × sqm/box (WastageCalculator#sqmQuantityFromBoxes),
            // which has no "remainder pieces" term at all. Refused on the lenient preview path too,
            // same as the box-data check just above.
            if (!roundToFullBox) {
                String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
                throw new ApiException(HttpStatus.BAD_REQUEST, where
                    + "ราคาต่อ ตร.ม. (เอกสารภาษาอังกฤษ) ที่ระบุ ตร.ม./กล่อง ต้องปัดขึ้นเต็มกล่องเสมอ ไม่รองรับการขายแผ่นไม่เต็มกล่อง");
            }
        }
        // PIECES wastage must be a whole number of แผ่น: WastageCalculator#applyWastage's PIECES branch silently ROUNDS a
        // fraction UP for the arithmetic while the STORED/PRINTED wastageValue stays the fraction, so the printed "+ เผื่อ
        // 0.5 แผ่น" would disagree with the piece count actually added. Refused before it reaches WastageCalculator.
        // PERCENT wastage may be fractional (2.5%).
        if (WastageCalculator.WASTAGE_MODE_PIECES.equals(input.wastageMode()) && input.wastageValue() != null
            && input.wastageValue().stripTrailingZeros().scale() > 0) {
            String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "จำนวนแผ่นที่เผื่อต้องเป็นจำนวนเต็ม");
        }
        WastageCalculator.Result result;
        try {
            result = WastageCalculator.calculate(new WastageCalculator.Input(
                sqmPerPiece, input.quantityMode(), input.areaSqm(), input.piecesInput(),
                input.wastageMode(), input.wastageValue(), input.piecesPerBox(),
                // English per-sqm: the rep's USD/sqm stands in for the per-piece list price — the
                // money below is recomputed from the sqm quantity, so only the PIECE results of
                // this call are used.
                perSqm ? input.specialPriceSqm() : input.unitPrice(),
                perSqm ? null : input.discountPct(),
                roundToFullBox));
        } catch (IllegalArgumentException | ArithmeticException e) {
            // ArithmeticException alongside IllegalArgumentException: BigDecimal#intValueExact
            // (piecesPerBox/ceiling conversions inside WastageCalculator) throws it for a value
            // that does not fit exactly -- an absurd piecesInput/areaSqm/wastageValue combination
            // otherwise reached the generic 500 handler instead of this 400.
            throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
        }
        // An AREA-mode row whose typed area rounds to ZERO pieces before wastage would print a line selling nothing
        // ("(พื้นที่ 0.01 ตร.ม.ๆละ 2.78 แผ่น รวม 0 แผ่น ...)"). Checked on every path here, the preview included, like
        // #requirePriceValidForType.
        if (WastageCalculator.QUANTITY_MODE_AREA.equals(input.quantityMode()) && result.piecesBeforeWastage() == 0) {
            String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
            throw new ApiException(HttpStatus.BAD_REQUEST, where + "พื้นที่น้อยเกินไป คำนวณได้ 0 แผ่น");
        }

        // v3: the PIECE arithmetic above is mode-independent — only the money changes. WastageCalculator
        // #calculate still owns netUnitPrice for NET mode (list price × (1 − discount)); the two new
        // modes replace it and recompute the line amount from the SAME piecesFinal, so a mode switch
        // can never silently change a quantity.
        BigDecimal netUnitPrice = result.netUnitPrice();
        BigDecimal discountPct = input.discountPct();
        BigDecimal specialPriceSqm = null;
        // Persist the same 2dp list price WastageCalculator#calculate computed netUnitPrice/lineAmount from (its listPrice =
        // round2(unitPrice)): sales.quotation_item.unit_price is NUMERIC(14,2), so storing the raw input would let the stored
        // row disagree with its own printed amount on a 3dp-or-finer typed price. Null-safe because perSqm rows omit
        // unitPrice (specialPriceSqm stands in below).
        BigDecimal unitPrice = input.unitPrice() == null ? null : money2(input.unitPrice());
        BigDecimal perSqmLineAmount = null;
        if (perSqm) {
            specialPriceSqm = money2(input.specialPriceSqm());
            unitPrice = specialPriceSqm;
            netUnitPrice = specialPriceSqm;
            discountPct = null;
            BigDecimal qtySqm;
            try {
                // Same "amount = price/sqm × printed sqm quantity" formula either way; only the quantity's derivation differs by
                // hasBoxArea.
                qtySqm = hasBoxArea
                    ? WastageCalculator.sqmQuantityFromBoxes(result.boxes(), input.sqmPerBox())
                    : WastageCalculator.sqmQuantityFromPieces(result.piecesFinal(), sqmPerPiece);
            } catch (IllegalArgumentException e) {
                if (!hasBoxArea) {
                    // Without a box area, sqmQuantityFromPieces only throws here on a missing/non-positive ตร.ม./แผ่น, and this branch
                    // is reachable only from the LENIENT preview (rowNumber == null; requireItemComplete names the field on
                    // create/update). The raw exception message ("sqmPerPiece is required for a per-sqm quantity, got: null") must not
                    // reach a rep typing, so reuse the Thai field-name wording of the save path.
                    String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
                    throw new ApiException(HttpStatus.BAD_REQUEST, where + "ขาด ตร.ม./แผ่น");
                }
                // hasBoxArea: requireBoxDataForPerSqm above already validated both box figures are
                // present, so this is not expected to be reachable -- kept as the original generic
                // wrap, defence in depth only.
                throw new ApiException(HttpStatus.BAD_REQUEST, "ข้อมูลรายการไม่ถูกต้อง: " + e.getMessage());
            }
            perSqmLineAmount = money2(qtySqm.multiply(specialPriceSqm));
        } else if (WastageCalculator.PRICE_MODE_SPECIAL_SQM.equals(priceMode)) {
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
        BigDecimal lineAmount = perSqm
            ? perSqmLineAmount
            : WastageCalculator.PRICE_MODE_NET.equals(priceMode)
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
            unitPrice, discountPct, netUnitPrice, lineAmount,
            vat, lineTotal,
            input.originCountry(), input.leadTimeMinDays(), input.leadTimeMaxDays(), input.itemNotes(),
            descriptionLine,
            // qty stays the PIECE count on every tile row (the read path's piecesFinal); the sqm
            // quantity is derived from boxes × sqm_per_box (box area present) or piecesFinal ×
            // sqm_per_piece (Option B, box area blank) when printed (DealQuotationLines#tilePrint).
            WastageCalculator.LINE_TYPE_TILE, BigDecimal.valueOf(result.piecesFinal()),
            perSqm ? DealQuotationLines.TILE_UNIT_SQM : "แผ่น",
            specialPriceSqm, null, null,
            catalogBasis == null ? null : catalogBasis.widthMm(),
            catalogBasis == null ? null : catalogBasis.heightMm(),
            input.sqmPerBox(), roundToFullBox,
            // No pricing-request link on a row built from a request — #withDecisionLink adds one.
            null, null,
            // The per-ตร.ม. prices as typed, kept beside the per-แผ่น unitPrice/netUnitPrice #withPerPiecePricesFromPerSqm
            // derived (this `input` is already its result). ราคาสุทธิ/ตร.ม. is kept only in the mode that read it. An English row
            // never reaches here with either: that method refuses them.
            input.listPriceSqm() == null ? null : money2(input.listPriceSqm()),
            WastageCalculator.PRICE_MODE_DIRECT_NET.equals(priceMode) && input.directNetPriceSqm() != null
                ? money2(input.directNetPriceSqm()) : null,
            null, input.stockSource());
    }

    /**
     * A PLAIN row: description, quantity, unit, unit price, and NONE of the tile machinery (no wastage, no ตร.ม./แผ่น,
     * no แผ่น/กล่อง, no auto-composed description, no catalog link). Freight (1 JOB), Mapei consumables
     * (Bags/Barrels), the cut service, sanitary-ware ชุด rows.
     *
     * <p>{@code amount = round2(list × quantity × (1 − pct/100))}: a single rounding of the unrounded product, not
     * {@code round2(netUnitPrice × quantity)}, which double-rounds through the 2dp net unit price and drifts a satang
     * on some rows (round2(netUnitPrice × qty) gave 35,799.64 against the printed 35,799.63). {@code list} is the input
     * price PRE-ROUNDED to 2dp, since {@code sales.quotation_item.unit_price} is NUMERIC(14,2) and the stored row must
     * reproduce its own printed amount. netUnitPrice stays the rounded DISPLAY figure printed in the ราคา/คงเหลือ
     * column; the row's discount applies to the unit price as in {@code WastageCalculator#calculate} for a tile (normally
     * absent, which prints "Net").
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
        BigDecimal discountFactor = BigDecimal.ONE.subtract(discountPct.divide(HUNDRED, 10, RoundingMode.HALF_UP));
        // Pre-round the LIST price to 2dp before any multiplication AND persist that same value (see
        // WastageCalculator#calculate): unit_price is NUMERIC(14,2), so computing from the unrounded input while storing
        // the rounded one would let the stored row and computed amount disagree on a 3dp-or-finer typed price.
        BigDecimal listPrice = money2(input.unitPrice());
        BigDecimal netUnitPrice = money2(listPrice.multiply(discountFactor));
        BigDecimal lineAmount = money2(listPrice.multiply(quantity).multiply(discountFactor));
        BigDecimal vat = money2(lineAmount.multiply(vatRate));
        return new NewItem(
            input.locationLabel(), null, null,
            null, null, null, null, null,
            null, null,
            null, null, null,
            null, null, null,
            0, 0, 0, null,
            listPrice, input.discountPct(), netUnitPrice, lineAmount,
            vat, lineAmount.add(vat),
            input.originCountry(), input.leadTimeMinDays(), input.leadTimeMaxDays(), input.itemNotes(),
            input.description() == null ? null : input.description().trim(),
            WastageCalculator.LINE_TYPE_PLAIN, quantity, blankToNull(input.unit()),
            null, null, null,
            null, null);
    }

    /**
     * An ADJUSTMENT row (ส่วนลดพิเศษ). The rep types a percent and a date; the system derives the amount AND the
     * description. A flat baht amount is the alternative.
     *
     * <p>The printed shape: จำนวน −1, no unit, a POSITIVE ราคา and คงเหลือ, and a NEGATIVE เป็นเงิน. That falls out of
     * the ordinary arithmetic ({@code amount = quantity × netPrice} with quantity −1), not print-time special-casing.
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
            null, input.adjustmentPct(), input.adjustmentDeadline(),
            null, null);
    }

    /** v3b: {@code vatRate} is the SOURCE document's, so a revision of an English quotation copies
     * 0.00 VAT rows rather than re-stamping 7% onto them. */
    private NewItem toNewItemFromDto(DealQuotationItemDto item, BigDecimal vatRate, String documentLanguage) {
        // An English TILE row's DTO prints a TRANSLATED unit ("PCS"/"SQM") and, in per-sqm, a
        // quantity in SQUARE METRES — neither is what the qty/raw_unit columns store (the piece
        // count and the write path's own unit). Copying them verbatim would store an area in the
        // piece-count column. Thai rows copy exactly what they always copied.
        boolean englishTile = WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(documentLanguage)
            && WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
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
            item.lineType(),
            englishTile ? BigDecimal.valueOf(item.piecesFinal()) : item.quantity(),
            englishTile ? (DealQuotationLines.TILE_UNIT_SQM.equals(item.unit()) ? DealQuotationLines.TILE_UNIT_SQM : "แผ่น")
                : item.unit(),
            item.specialPriceSqm(), item.adjustmentPct(), item.adjustmentDeadline(),
            // Transient render-only fields (see NewItem#catalogWidthMm) -- this NewItem is bound
            // for insertDraft, never for #toItemDto, so there is nothing here to carry through.
            null, null,
            item.sqmPerBox(), item.roundToFullBox(),
            // No link here — a PRICING_REQUEST copy re-attaches its own via #withDecisionLink.
            null, null,
            // A copy carries its parent's typed per-ตร.ม. prices VERBATIM like every other figure on the row: a copy, not a
            // recalculation.
            item.listPriceSqm(), item.directNetPriceSqm(),
            // And the mode the row was priced in, on a mixed document (null otherwise): the copy's header says MIXED too
            // (#insertCopyOf copies source.priceMode()), and a mixed document whose rows lost their modes would be re-read with
            // every row as NET.
            item.priceMode(),
            // A revision copies the declaration; a PRICING_REQUEST row's INSERT re-reads it from its pricing-request line.
            item.stockSource());
    }

    // price_catalog.product_prices' price_unit for a linear-metre trim (V153). Its sqm_per_piece is LINEAR METRES per
    // piece, not an area (the source sqm_per_box column is mislabelled the same way), so #sqmPerPieceFromCatalog must
    // never read it as one nor compute a geometric fallback (width_mm x height_mm describes the PROFILE, not a tile
    // face). Mirrors frontend QuotationItemRow.jsx's PRICE_UNIT_PER_LINEAR_M, kept as its own literal because the
    // sides share no constants module; if it drifts from CatalogRepository's column values,
    // {@code sqmPerPiece_neverGeometricOrCatalogForPerLinearMRows} breaks.
    private static final String PRICE_UNIT_PER_LINEAR_M = "per_linear_m";
    private static final BigDecimal SQ_MM_PER_SQM = new BigDecimal("1000000");

    /**
     * Resolves a tile's ตร.ม./แผ่น from the CATALOGUE, never by guessing the unit of a free-text size string (that
     * heuristic read "200x300" as centimetres, 6.0 sqm/piece, against 0.061 for the real product: wrong by ~98x).
     *
     * <p>Resolution order, and NEVER inferred from {@code input.sizeText()}:
     * <ol>
     *   <li>the item's own {@code sqmPerPiece}, if Sales supplied or overrode one;</li>
     *   <li>else the catalog row's own {@code sqm_per_piece} (by {@code catalogPriceId}): the AUTHORITATIVE figure;
     *       geometry disagrees with it on 8.6% of the real catalog (up to 14x on trims), so it is never recomputed
     *       when width/height are available;</li>
     *   <li>else, when the catalogue has no {@code sqm_per_piece}, COMPUTE it from the same row's
     *       {@code width_mm x height_mm / 1,000,000} (both unambiguous millimetres);</li>
     *   <li>else {@code null}: {@link #requireItemComplete} then fails the row with "ขาด ตร.ม./แผ่น" rather than this
     *       method inventing a number.</li>
     * </ol>
     *
     * <p>Steps 2 and 3 NEVER apply to a {@code per_linear_m} row (see {@link #PRICE_UNIT_PER_LINEAR_M}); it goes from
     * step 1 to step 4 unless Sales types a value by hand.
     *
     * <p>Takes the {@link CatalogSqmBasis} as a PARAMETER (see {@link #resolveCatalogBasis}) so {@link #buildTileItem}
     * fetches it ONCE and also threads {@code width_mm}/{@code height_mm} to {@code NewItem} for
     * {@code DealQuotationLines#sizeLine}.
     */
    private BigDecimal resolveSqmPerPiece(ItemInput input, CatalogSqmBasis basis) {
        if (input.sqmPerPiece() != null) {
            return input.sqmPerPiece();
        }
        return basis == null ? null : sqmPerPieceFromCatalog(basis);
    }

    /** The catalogue basis lookup, so {@link #buildTileItem} fetches it once for both ตร.ม./แผ่น and
     * {@code DealQuotationLines#sizeLine}'s face size in centimetres. {@code null} when the item has no catalog link or
     * the linked row does not exist. */
    private CatalogSqmBasis resolveCatalogBasis(ItemInput input) {
        return input.catalogPriceId() == null ? null : catalog.findSqmBasis(input.catalogPriceId()).orElse(null);
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

    /** An English per-sqm row WITH a box area (ตร.ม./กล่อง filled) needs BOTH box figures: its quantity is boxes ×
     * sqm/box, with deliberately no pieces fallback. Only called by {@link #buildTileItem} when {@code hasBoxArea} is
     * true; a row with NO box area skips it (piecesPerBox is optional too, see {@link #requireItemComplete}). A
     * rep-facing Thai 400. */
    private void requireBoxDataForPerSqm(ItemInput input, Integer rowNumber) {
        List<String> missing = new ArrayList<>();
        if (input.piecesPerBox() == null || input.piecesPerBox() < 1) missing.add("แผ่น/กล่อง");
        if (input.sqmPerBox() == null || input.sqmPerBox().signum() <= 0) missing.add("ตร.ม./กล่อง");
        if (!missing.isEmpty()) {
            String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
            throw new ApiException(HttpStatus.BAD_REQUEST, where
                + "ราคาต่อ ตร.ม. (เอกสารภาษาอังกฤษ) คิดจำนวนจากกล่อง จึงต้องระบุ " + String.join(" และ ", missing));
        }
    }

    /**
     * <b>Type-aware price validation.</b> An ADJUSTMENT row has NO rep-typed price (the system derives it from the rows
     * above), so a blanket {@code @NotNull @DecimalMin("0.01")} on {@code ItemInput.unitPrice} would 400 that row type;
     * the rule is TYPE-AWARE instead:
     *
     * <ul>
     *   <li><b>TILE</b>: refuses a null, zero or negative {@code unitPrice}. In SPECIAL_SQM mode the ราคาพิเศษ must ALSO
     *       be positive, and in DIRECT_NET the typed net must; a mode whose own price is missing is a 400, never a
     *       silent fallback to the list price.</li>
     *   <li><b>PLAIN</b>: the same positive-price rule; a freight or consumables line always carries a direct price.</li>
     *   <li><b>ADJUSTMENT</b>: IGNORES a rep-supplied {@code unitPrice} (derived from the rows above) and requires
     *       EXACTLY one of {@code adjustmentPct} / {@code adjustmentAmount}.</li>
     * </ul>
     *
     * <p><b>Not gated on {@code rowNumber}</b>, unlike {@link #requireItemComplete}: it runs on the lenient
     * {@code calculate-line} preview too, so gating it would let a TILE row with a zero price preview happily.
     * Completeness (รุ่น/สี/ผิว/ขนาด, a description, a quantity) stays row-number-gated because THAT must tolerate a
     * half-typed row.
     *
     * <p>{@code quantityMode}/{@code wastageMode} are re-required here for TILE rows only (a PLAIN row has neither).
     */
    private void requirePriceValidForType(ItemInput input, String lineType, String priceMode,
                                          String documentLanguage, Integer rowNumber) {
        String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
        if (WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(lineType)) {
            // A supplied unitPrice on an ADJUSTMENT row is IGNORED, not refused: #toItemDto/#mapItem return unitPrice = the
            // derived amount on that row (the ราคา column prints it), so refusing it would make a GET→PUT round-trip of any
            // document with an adjustment impossible. Ignoring is safe: #buildAdjustmentItem never reads input.unitPrice(); the
            // amount derives from adjustmentPct/adjustmentAmount, so the caller still cannot dictate the discount.
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
        boolean perSqm = WastageCalculator.LINE_TYPE_TILE.equals(lineType)
            && WastageCalculator.isEnglishPerSqm(documentLanguage, priceMode);
        if (perSqm) {
            // English per-sqm: the USD/sqm IS the unit price — no per-piece list price is asked for.
            if (input.specialPriceSqm() == null || input.specialPriceSqm().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, where + "ราคาต่อ ตร.ม. (USD) ต้องมากกว่าศูนย์");
            }
            if (isBlank(input.quantityMode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, where + "กรุณาระบุรูปแบบจำนวน");
            }
            if (isBlank(input.wastageMode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, where + "กรุณาระบุรูปแบบเผื่อ");
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

    /**
     * A lead-time range is only validated once ENTERED (both {@code min}/{@code max} present): a lone value, or
     * neither, is an incompleteness question for {@link #requireItemComplete}/{@link #requireEveryTileItemHasALeadTime}
     * (submit-only). Once both are present, {@code min} must be at least 1 day and {@code max} at least {@code min}
     * (so "90-75" cannot be saved). Refused on save with the exact message the frontend validator mirrors.
     *
     * <p>It only runs on a freshly-submitted {@code ItemInput}, never re-validates a persisted
     * {@link DealQuotationItemDto}, so an old row with such values still RENDERS (see
     * {@code DealQuotationRenderAdapter#flushLeadTimeGroup}, which prints what it is given).
     */
    private void requireValidLeadTime(Integer leadTimeMinDays, Integer leadTimeMaxDays, Integer rowNumber) {
        if (leadTimeMinDays == null || leadTimeMaxDays == null) {
            return;
        }
        if (leadTimeMinDays < 1 || leadTimeMaxDays < leadTimeMinDays) {
            String where = rowNumber == null ? "" : "รายการที่ " + rowNumber + ": ";
            throw new ApiException(HttpStatus.BAD_REQUEST,
                where + "ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด");
        }
    }

    /** Item completeness rule — see {@link #buildItem(ItemInput, Integer)}'s own Javadoc. Collects
     * EVERY missing/invalid field before throwing (not just the first) so one 400 tells the rep
     * everything wrong with the row, not a one-at-a-time guessing game. */
    private void requireItemComplete(int rowNumber, ItemInput input, BigDecimal resolvedSqmPerPiece,
                                     boolean perSqm) {
        List<String> missing = new ArrayList<>();
        if (isBlank(input.model())) missing.add("รุ่น");
        if (isBlank(input.color())) missing.add("สี");
        if (isBlank(input.texture())) missing.add("ผิว");
        if (isBlank(input.sizeText())) missing.add("ขนาด");
        if (input.thicknessMm() == null || input.thicknessMm().signum() <= 0) missing.add("ความหนา");
        // แผ่น/กล่อง stays required for every row EXCEPT an English per-sqm row with no box area, which needs no box
        // multiple (its quantity derives from piecesFinal × sqmPerPiece, see DealQuotationLines#tilePrint).
        boolean hasBoxArea = input.sqmPerBox() != null && input.sqmPerBox().signum() > 0;
        boolean piecesPerBoxOptional = perSqm && !hasBoxArea;
        if (!piecesPerBoxOptional && (input.piecesPerBox() == null || input.piecesPerBox() < 1)) {
            missing.add("จำนวนแผ่นต่อกล่อง");
        }
        if (resolvedSqmPerPiece == null || resolvedSqmPerPiece.signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (perSqm) {
            // ตร.ม./กล่อง is OPTIONAL: no separate check here. WITH one, piecesPerBox is still required (above), and
            // #buildTileItem's hasBoxArea branch separately requires it, refusing a partially-filled pair.
            if (input.specialPriceSqm() == null || input.specialPriceSqm().signum() <= 0) missing.add("ราคาต่อ ตร.ม.");
        } else if (input.unitPrice() == null || input.unitPrice().signum() <= 0) {
            missing.add("ราคาต่อหน่วย");
        }
        if (!hasQuantity(input.quantityMode(), input.areaSqm(), input.piecesInput())) missing.add("จำนวน");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "รายการที่ " + rowNumber + ": ขาด " + String.join(", ", missing));
        }
    }

    /**
     * Submit-only: every TILE row ({@code lineType} null or {@link WastageCalculator#LINE_TYPE_TILE}) must carry both
     * {@code leadTimeMinDays} and {@code leadTimeMaxDays} before the document can be submitted. A rep must enter a lead
     * time rather than let the document print a plausible but false one, or drop the whole remark line (see
     * {@code DealQuotationRenderAdapter#dropLeadTimeLineAndRenumber}). ADJUSTMENT rows have no lead time; a PLAIN row
     * (สินค้า/บริการอื่น, e.g. sanitaryware) CAN carry one but it is never REQUIRED. A DRAFT may be saved with no lead
     * times on any row type.
     */
    private void requireEveryTileItemHasALeadTime(List<DealQuotationItemDto> items) {
        List<String> missingSeqs = new ArrayList<>();
        for (DealQuotationItemDto item : items) {
            boolean tile = item.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
            // In-Thailand stock lines skip import; IN_TRANSIT still needs a lead time.
            if (tile && !th.co.glr.hr.pricingrequest.PricingRequestService.STOCK_IN_THAILAND.equals(item.stockSource()) && (item.leadTimeMinDays() == null || item.leadTimeMaxDays() == null)) {
                missingSeqs.add(String.valueOf(item.seq()));
            }
        }
        if (!missingSeqs.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ " + String.join(", ", missingSeqs));
        }
    }

    /** Same rule as {@link #requireItemComplete}, over an already-stored (post-derivation) row —
     * used only by {@link #submit}'s defensive re-check. {@code seq} is the item's own printed
     * row number, so the message names the same row the rep sees on screen. */
    private void requireStoredItemComplete(DealQuotationItemDto item, boolean perSqm) {
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
        // Same hasBoxArea exception as #requireItemComplete — see its own comment.
        boolean hasBoxArea = item.sqmPerBox() != null && item.sqmPerBox().signum() > 0;
        boolean piecesPerBoxOptional = perSqm && !hasBoxArea;
        if (!piecesPerBoxOptional && (item.piecesPerBox() == null || item.piecesPerBox() < 1)) {
            missing.add("จำนวนแผ่นต่อกล่อง");
        }
        if (item.sqmPerPiece() == null || item.sqmPerPiece().signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (item.unitPrice() == null || item.unitPrice().signum() <= 0) missing.add("ราคาต่อหน่วย");
        // ตร.ม./กล่อง is OPTIONAL: a partially-filled pair (sqmPerBox set, piecesPerBox blank) is caught by the
        // piecesPerBoxOptional branch above, since hasBoxArea alone does not exempt piecesPerBox.
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
    private DealQuotationItemDto toItemDto(long id, int seq, NewItem item, String documentLanguage, String priceMode) {
        // v3: via WastageCalculator, not an inline reciprocal — see #piecesPerSqm's Javadoc.
        BigDecimal piecesPerSqm = item.sqmPerPiece() != null && item.sqmPerPiece().signum() > 0
            ? WastageCalculator.piecesPerSqm(item.sqmPerPiece()) : null;
        boolean tile = WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
        String sizeLine = tile ? DealQuotationLines.sizeLine(documentLanguage, item.sizeText(), item.thicknessMm(),
            item.catalogWidthMm(), item.catalogHeightMm()) : null;
        // The SAME decision DealQuotationRepository#mapItemColumns makes for a stored row.
        DealQuotationLines.TilePrint print = tile
            ? DealQuotationLines.tilePrint(documentLanguage, priceMode, item.quantityMode(), item.areaSqm(),
                item.sqmPerPiece(), piecesPerSqm, item.piecesBeforeWastage(), item.wastageMode(), item.wastageValue(),
                item.piecesAfterWastage(), item.piecesFinal(), item.piecesPerBox(), item.boxes(), item.sqmPerBox(),
                item.quantity(), item.unit(), item.specialPriceSqm(), item.roundToFullBox())
            : null;
        // A tile's description is recomposed in the document's language, exactly as the read path
        // does; a PLAIN/ADJUSTMENT row's is the one the build step composed or the rep typed.
        String descriptionLine = tile
            ? DealQuotationLines.descriptionLine(documentLanguage, item.model(), item.color(), item.texture(),
                item.productCode(), item.sizeText(), item.thicknessMm())
            : WastageCalculator.LINE_TYPE_ADJUSTMENT.equals(item.lineType())
                ? DealQuotationLines.printedAdjustmentDescription(documentLanguage, item.descriptionLine(),
                    item.adjustmentPct(), item.adjustmentDeadline())
                : item.descriptionLine();
        return new DealQuotationItemDto(id, seq, item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            piecesPerSqm, item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(), item.boxes(),
            item.netUnitPrice(), item.lineAmount(), descriptionLine, sizeLine,
            print == null ? null : print.calculationLine(),
            item.lineType(), print == null ? item.quantity() : print.quantity(),
            print == null ? item.unit() : print.unit(), item.specialPriceSqm(), item.adjustmentPct(),
            item.adjustmentDeadline(), print == null ? null : print.subLine(),
            DealQuotationLines.flatAdjustmentAmount(item.lineType(), item.adjustmentPct(), item.unitPrice()))
            .withSqmPerBox(item.sqmPerBox()).withRoundToFullBox(item.roundToFullBox())
            // Echoed so the preview says which per-ตร.ม. figures its per-แผ่น unitPrice/netUnitPrice were derived from.
            .withPerSqmPrices(item.listPriceSqm(), item.directNetPriceSqm());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Authz — every decision lives here (see class Javadoc).
    // ─────────────────────────────────────────────────────────────────────────────────────

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** create/edit/submit/cancel/revise: {@code sales} on their OWN deals only (whether plain {@code sales} may act on
     * another rep's deal is an open question, docs/sales/quotation-v2-plan.md; implemented as NO); {@code sales_manager}
     * on any deal; OR a {@code canCreateQuotation}-granted employee on ANY deal regardless of role (a per-person
     * capability, not a role). Read LIVE via {@link #hasQuotationGrant}, never from {@code actor}. */
    private void requireEditAccess(UserPrincipal actor, TicketSummaryDto ticket) {
        boolean grant = hasQuotationGrant(actor);
        if (!EDIT_ROLES.contains(actor.role()) && !grant) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales_manager".equals(actor.role()) || grant) {
            return; // any deal
        }
        if ("sales".equals(actor.role()) && DealOwnership.isOwnerOrKeyer(ticket, actor.id())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงดีลนี้");
    }

    private void requireEditAccessForQuotation(UserPrincipal actor, DealQuotationDto quotation) {
        // Origin-aware, mirroring requireViewAccess's branch below. The grant bypasses EDIT_ROLES in #requireEditAccess, so an
        // `account` holder of `can_create_quotation` could update/cancel/restore a PRICING_REQUEST row while GET 403s for
        // them: a write-without-read hole exposing every ceo* field in the write response (#update/#restoreRemovedItem
        // return #requireQuotation(id) with no view check). EDIT_ROLES (sales, sales_manager) is unchanged; only the grant
        // bypass and its "any deal regardless of role" shortcut are removed for PRICING_REQUEST rows. Covers every write path
        // reaching this method: update, submit/cancel/createRevision/createReorder, the picture endpoints (via
        // #requireEditablePicture) and restoreRemovedItem.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            if (!EDIT_ROLES.contains(actor.role())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            if ("sales_manager".equals(actor.role())) {
                return; // any deal — same DEAL_DIRECT rule this role already gets, untouched.
            }
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            if (!DealOwnership.isOwnerOrKeyer(ticket, actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            return;
        }
        requireEditAccess(actor, requireTicketSummary(quotation.ticketId()));
    }

    /** view/list/download: {@link #VIEW_ROLES} (sales limited to own deals) OR a
     * {@code canCreateQuotation}-granted employee, who may view any deal — the grant already lets
     * them act on any deal, so it lets them view any deal too (a strict subset would mean they
     * could create a quotation they then couldn't look at). */
    private void requireViewAccess(UserPrincipal actor, DealQuotationDto quotation) {
        // Origin-aware: a PRICING_REQUEST row uses the NARROWER CustomerQuotationService-shaped gate (no account, no
        // quotation-grant bypass), checked FIRST with an early return so the broader DEAL_DIRECT rules never apply. Covers
        // get/render/download/pictures. account is ROW-scoped on BOTH origins: refused on a deal outside its list scope,
        // allowed on a PRICING_REQUEST-origin row inside it. Checked before the quotation-grant bypass so a grant cannot
        // widen an account caller past its scope.
        if ("account".equals(actor.role())) {
            requireAccountScope(quotation.ticketId());
            if (!ACCOUNT_VISIBLE_STATUSES.contains(quotation.docStatus())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            return;
        }
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            if (!canViewPricingRequestOriginRow(actor, quotation)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            return;
        }
        if (hasQuotationGrant(actor)) {
            return;
        }
        requireRole(actor, VIEW_ROLES);
        if ("sales".equals(actor.role())) {
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            if (!DealOwnership.isOwnerOrKeyer(ticket, actor.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }
    }

    /**
     * The boolean form of {@link #requireViewAccess}'s PRICING_REQUEST branch, factored out so {@link #listForTicket}
     * can FILTER a mixed-origin list with the same rule instead of a second, broader gate (which leaked the CEO's
     * price/discount to import/account/grant-holders).
     *
     * <p>On this origin {@link DealQuotationItemDto#unitPrice}/{@code discountPct}/{@code netUnitPrice} ARE the CEO's
     * approved price and discount, so reading them is restricted to {@link #PRICING_REQUEST_VIEW_ROLES} (sales-owner,
     * sales_manager, ceo) with NO {@link #hasQuotationGrant} bypass, unlike every other gate on this class.
     */
    private boolean canViewPricingRequestOriginRow(UserPrincipal actor, DealQuotationDto quotation) {
        // account is the exception; see PRICING_REQUEST_VIEW_ROLES's comment.
        if ("account".equals(actor.role())) {
            return tickets.isInAccountScope(quotation.ticketId());
        }
        if (!PRICING_REQUEST_VIEW_ROLES.contains(actor.role())) {
            return false;
        }
        if ("sales".equals(actor.role())) {
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            return DealOwnership.isOwnerOrKeyer(ticket, actor.id());
        }
        return true;
    }

    /** Live DB read of the per-employee grant — see {@link EmployeeAuthRepository#canCreateQuotation}
     * for why this is never cached on {@code actor}. */
    private boolean hasQuotationGrant(UserPrincipal actor) {
        return employeeAuth.canCreateQuotation(actor.id());
    }

    /**
     * approve/reject: {@code sales_manager} or {@code ceo}. No self-exclusion (an open question in
     * docs/sales/quotation-v2-plan.md).
     *
     * <p>Amount routing (&lt;1M sales_manager, &gt;1M CEO) is an OUT-OF-SYSTEM rule: do not add amount logic here.
     */
    private void requireApproveAccess(UserPrincipal actor) {
        requireRole(actor, APPROVE_ROLES);
    }

    /** The account role's row scope ({@code TicketRepository#isInAccountScope}) -- 403 outside it. */
    private void requireAccountScope(long ticketId) {
        if (!tickets.isInAccountScope(ticketId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSummaryDto requireTicketSummary(long ticketId) {
        return tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"))
            .summary();
    }

    private DealQuotationDto requireQuotation(long id) {
        DealQuotationDto quotation = quotations.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));
        // The single-row read path every other method funnels through (get/create/update/restore/cancel/...), so the one place
        // that populates #removedCeoItems; see that field's Javadoc for why it is a separate query rather than in #findById's SELECT.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            quotation = quotation.withRemovedCeoItems(quotations.findRemovedLinkedItems(id));
        }
        return quotation;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Mirrors {@code CustomerQuotationService#validateUuid}, for {@link #recordOutcome}'s {@code clientRequestId}
     * replay guard (no other method here validates one). */
    private String validateUuid(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(clientRequestId.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientRequestId ต้องเป็น UUID ที่ถูกต้อง");
        }
    }

    private BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
