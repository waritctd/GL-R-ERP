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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
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
    // GLA-123 slice S1 fix (Opus review M3, 2026-09-20): a PRICING_REQUEST-origin quotation
    // carries the CEO's discount/list price (design correction 2's whole point on the pricing
    // DECISION side — never leak cost/margin to sales — extends here to "never leak the CEO's
    // discount to import/account either", since GET /deal-quotations/{id} prints it verbatim).
    // VIEW_ROLES above is the DEAL_DIRECT gate (account included, quotation-grant bypasses
    // everything) and is INTENTIONALLY too broad for this origin. Mirrors
    // CustomerQuotationService.VIEW_ROLES exactly — see that class's own comment: "account is
    // deliberately excluded... there is no positive grant for account to read a customer
    // quotation either". No quotation-grant bypass either: that mechanism does not exist on the
    // CustomerQuotationService side this origin's authz otherwise mirrors.
    //
    // MAJOR-4/MINOR-1 fix (owner ruling, confirmed 2026-09-20, second re-review): `import` REMOVED.
    // The M3 comment above (and CustomerQuotationService.VIEW_ROLES, which still includes import)
    // predates this ruling — the two are no longer the same set, and that is deliberate, not a
    // drift to reconcile. On THIS origin the document's OWN unitPrice/discountPct/netUnitPrice
    // fields ARE the CEO's approved list price and discount (createFromPricingRequest prefills
    // them verbatim from pricing_decision_item — see that method's own Javadoc), so viewing the
    // document at all is viewing them; there is no narrower "hide just the ceo* comparison
    // columns" cut the way there was for account. The owner's standing rule is that import sees
    // nothing price- or discount-shaped anywhere in this chain (mirrors PricingDecisionService's
    // own RAW_DECISION_ROLES exclusion of sales, the same rule pointed the other way). import
    // keeps its OWN, unrelated visibility into the pricing request itself, its items, factory
    // quotes and the import request document — none of that is this class's concern and none of
    // it is touched by this set.
    private static final Set<String> PRICING_REQUEST_VIEW_ROLES = Set.of("sales", "sales_manager", "ceo");

    /** Owner ruling 2026-09-24: on the GLOBAL LIST ({@link #search}/{@link #counts}) ONLY,
     * sales_manager and ceo see every deal's quotations; everyone else (sales, import, account,
     * and any {@code canCreateQuotation} grant-holder) sees only quotations on deals they created.
     * This narrows import/account/grant-holders, who previously saw the whole list here. Scope
     * boundary: this affects ONLY {@link #listOwnerScope} (and therefore search/counts) — {@link
     * #requireViewAccess}, {@link #get}, {@link #listForTicket} and {@link #renderPdf} are
     * unchanged, so import/account/grant-holders still read individual quotations from the deal
     * page. A grant no longer widens the LIST scope; it still widens create/edit/detail access via
     * {@link #requireViewAccess}. */
    private static final Set<String> LIST_SEE_ALL_ROLES = Set.of("sales_manager", "ceo");

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
    // GLA-123 slice S1 — read-only dependencies onto the pricing-request/decision chain, used
    // ONLY by #createFromPricingRequest. SETTER-injected (not a constructor parameter) so every
    // existing hand-wired `new DealQuotationService(...)` test call site (13 of them across 6
    // files, none of which exercise this feature) keeps compiling unchanged; Spring wires both
    // automatically in production via the @Autowired setter below, and a test that DOES exercise
    // #createFromPricingRequest calls that setter itself. Never returned to a caller: the
    // CEO-bearing PricingDecisionDto/PricingDecisionItemDto never reach the API response — this
    // class copies only the numbers it needs onto the new sales.quotation(_item) row, so exposing
    // them here does not weaken PricingDecisionDtos' "design correction 2" (no cost/margin leak
    // to sales — see that class's own Javadoc).
    private PricingRequestRepository pricingRequests;
    private PricingDecisionRepository pricingDecisions;
    // M2 fix (Opus review, 2026-09-20) — mutual exclusivity between the old (customerquotation/)
    // and new (this class's own) create paths for the same pricing request. Same setter-injection
    // device as the two fields above, for the same reason.
    private th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotations;
    // GLA-123 slice S2 — issuing a PRICING_REQUEST-origin quotation must advance the deal's sales
    // stage the SAME way CustomerQuotationService#issue already does (Rule 7: reuse the existing
    // transition, not a second path). Same setter-injection device as the three fields above, for
    // the identical reason (13 existing hand-wired test constructors must keep compiling).
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

    /** GLA-123 slice S1 — see {@link #pricingRequests}/{@link #pricingDecisions}'s own Javadoc for
     * why this is setter- rather than constructor-injected. {@code required = false} so a
     * deployment (or a hand-wired test) that never calls {@link #createFromPricingRequest} is
     * unaffected either way. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void wirePricingRequestDependencies(PricingRequestRepository pricingRequests,
                                        PricingDecisionRepository pricingDecisions,
                                        th.co.glr.hr.customerquotation.CustomerQuotationRepository customerQuotations) {
        this.pricingRequests = pricingRequests;
        this.pricingDecisions = pricingDecisions;
        this.customerQuotations = customerQuotations;
    }

    /** GLA-123 slice S2 — see {@link #ticketService}'s own Javadoc for why this is setter- rather
     * than constructor-injected. {@code required = false} so a hand-wired test that never
     * exercises {@link #approveAndIssuePricingRequestOrigin} is unaffected either way (that path
     * already null-guards, mirroring {@link #createFromPricingRequest}'s own guard on
     * {@link #pricingRequests}). */
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
        quotations.lockTicket(ticketId);
        ContactSnapshot contact = resolveContact(request.contactId(), null, ticket);
        String priceMode = resolvePriceMode(request.priceMode());
        String documentLanguage = resolveDocumentLanguage(request.documentLanguage());
        String currency = resolveCurrency(documentLanguage, request.currency());
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // Owner feedback 2026-09-14 (V178): a brand-new document's "own date" is today (Bangkok) —
        // the same value #mapQuotation will compute for quotationDate from issued_at = now(). Must
        // run AFTER #buildItems: "has special pricing" (owner ruling, same date) is decided from
        // the rows about to be written, not from the request's raw input.
        String validityMode = resolveValidityMode(request.validityMode());
        boolean specialPricing = DealQuotationRenderAdapter.hasSpecialPricingForNewItems(priceMode, items);
        LocalDate validityUntil = requireValidityUntilForMode(validityMode, request.validityUntil(),
            LocalDate.now(BANGKOK), specialPricing);
        CustomerSnapshot customerSnapshot = customerSnapshot(ticket);
        // V179 (owner feedback #4, 2026-09-14) — print-only ผู้พิมพ์/พนักงานขาย name override.
        // Validated against the SAME eligible union the options endpoint lists, so create can
        // never persist an id no client could ever have legitimately picked.
        Long printedByDisplayId = requireEligibleDisplayEmployeeId(request.printedByDisplayId());
        Long salesRepDisplayId = requireEligibleDisplayEmployeeId(request.salesRepDisplayId());
        // Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16): remainderMode/creditDays are
        // irrelevant on a zero-deposit document (its whole-amount term is fullPaymentTerm instead)
        // and fullPaymentTerm is irrelevant on any other -- see #noDeposit/#resolveFullPaymentTerm.
        boolean noDeposit = isZeroDeposit(request.depositPercent());
        String remainderMode = noDeposit ? null : blankToNull(request.remainderMode());
        Integer creditDays = noDeposit ? null : request.creditDays();
        requireValidCreditDays(remainderMode, creditDays);
        String fullPaymentTerm = resolveFullPaymentTerm(request.depositPercent(), request.fullPaymentTerm());
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
            // Owner feedback 2026-09-14: a request-supplied projectName (even blank, to clear it)
            // wins; null (a caller that never sends the field at all) falls back to the deal's own
            // project name, exactly as this line unconditionally did before projectName existed.
            request.projectName() != null ? blankToNull(request.projectName()) : ticket.projectName(),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), remainderMode,
            creditDays, request.validityDays(), validityMode, validityUntil,
            blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency,
            printedByDisplayId, salesRepDisplayId,
            resolveOmitContactHonorific(request.omitContactHonorific()), fullPaymentTerm,
            // A brand-new document is neither a revision (parentQuotationId) nor a reorder clone
            // (derivedFromQuotationId) — both null.
            subtotal, null, 1, null, items,
            // Owner-directed reversal of F2 (2026-09-26) — manual, optional buyer name; blank
            // clears it to null, same discipline as every other free-text header field here.
            blankToNull(request.orderedByName())));
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-123 slice S1 — create from an approved คำขอราคา (Phase 3 of the sales pricing redesign).
    // Reuses THIS SAME engine (buildItem/WastageCalculator/insertDraft) rather than forking it —
    // the one design point R5/D1 of GLA-123 exists to enforce. Gated EXACTLY like
    // CustomerQuotationService#create (read 2026-09-19): sales-only, the owning rep only, deal
    // ACTIVE, pricing request APPROVED_FOR_QUOTATION, an APPROVED decision. Owner ruling revised
    // 2026-09-19: sales MAY edit price after create (see DealQuotationDto#priceModeChangedFromCeo
    // and DealQuotationItemDto#priceChangedFromCeo) — there is no server-side lock, only the
    // CEO-required-when-changed gate #requireCeoApprovalIfChanged reads at approval time.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto createFromPricingRequest(long pricingRequestId, UserPrincipal actor) {
        if (pricingRequests == null || pricingDecisions == null) {
            // Only reachable if a deployment wires this bean without calling
            // #wirePricingRequestDependencies — see that method's own Javadoc.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        if (!"sales".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        PricingRequestSummaryDto summary = pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // Owner-only, mirroring CustomerQuotationService#requireOwner exactly (a sales_manager is
        // NOT admitted here either — CustomerQuotationService.SALES_ROLES is sales-only, and this
        // create path mirrors it, not DealQuotationService#EDIT_ROLES' own broader sales_manager
        // allowance for editing an EXISTING quotation).
        if (summary.ticketCreatedById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        TicketSummaryDto ticket = requireTicketSummary(summary.ticketId());
        if (!th.co.glr.hr.ticket.DealLifecycle.ACTIVE.equals(ticket.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
        // MINOR fix (Opus review, 2026-09-20) — two concurrent callers (a genuine double-click; no
        // clientRequestId dedupe exists on this endpoint) would otherwise both pass the
        // idempotent-replay check below (neither sees the other's not-yet-committed row) and both
        // INSERT, the second one hitting sales.quotation's UNIQUE(number) constraint as a bare 500
        // with no Thai message. Same fix, same reasoning, and the SAME repository method
        // (CustomerQuotationRepository#lockPricingRequest — already wired via customerQuotations
        // for the M2 check above) CustomerQuotationService#create already takes at this exact
        // point in its own gate, and the SAME device DealQuotationService#requireNoOpenRevision
        // uses for an analogous race: lock BEFORE the idempotent check, so the second caller
        // (once it acquires the lock after the first commits) observes the first caller's
        // already-inserted DRAFT in #findOpenDraftForPricingRequest below and returns it (a clean
        // idempotent replay) instead of racing into the unique-index violation. Requires
        // customerQuotations to have compiled the M2 wiring, hence the same null-guard.
        if (customerQuotations != null) {
            customerQuotations.lockPricingRequest(pricingRequestId);
        }
        // MAJOR-3 fix (owner ruling via coordinator, 2026-09-20 — "the expiry escape hatch"): a
        // PRICING_REQUEST-origin quotation that EXPIRED (D5) used to be a permanent dead end — the
        // pricing request stays QUOTATION_ISSUED forever (nothing rolls it back), so this original
        // status check refused every re-create attempt with no way forward. Owner ruling: sales
        // may write a FRESH quotation from the SAME approved decision once the existing one has
        // expired — the expired document stays as an audit record, never revived/revised.
        // Widened to tolerate QUOTATION_ISSUED here at the STATUS level only — this branch does
        // NOT itself guarantee "no live quotation exists" (a live ISSUED-but-not-yet-expired
        // document also leaves the PR at QUOTATION_ISSUED); that is enforced once, unconditionally,
        // by #hasLivePricingRequestQuotation just below the idempotent-DRAFT check, which is the
        // ONE place "no second live quotation" is actually decided for BOTH branches.
        //
        // GLA-123 slice S3 BLOCKER-B fix note: this same QUOTATION_ISSUED check ALSO now correctly
        // covers the REVISION_REQUESTED recovery path with no further change here — recordOutcome
        // deliberately never moves the PR's own status off QUOTATION_ISSUED for that outcome (same
        // as REJECTED), so the variable name below ("AfterExpiry") undersells what it now gates;
        // the actual invariant is just "PR status == QUOTATION_ISSUED", which is what BOTH the
        // expiry and the revision-requested recovery paths leave it at.
        boolean prReadyForFirstQuotation = PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status());
        boolean prReadyForReplacementAfterExpiry = PricingRequestStatus.QUOTATION_ISSUED.equals(summary.status());
        if (!prReadyForFirstQuotation && !prReadyForReplacementAfterExpiry) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคาต้องอยู่ในสถานะ 'อนุมัติราคาขายแล้ว' ก่อนจึงจะออกใบเสนอราคาลูกค้าได้ (ปัจจุบัน: " + summary.status() + ")");
        }
        PricingDecisionDto decision = pricingDecisions.findByPricingRequest(pricingRequestId).stream()
            .filter(d -> "APPROVED".equals(d.status()))
            .max(java.util.Comparator.comparingInt(PricingDecisionDto::decisionVersionNo))
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ยังไม่มีราคาขายที่ CEO อนุมัติสำหรับคำขอราคานี้"));
        // The legacy margin/"ปรับราคาเอง" formula path (predates V187, or a pricing-request item
        // shape older than V185) never sets price_mode — refuse it explicitly (409, not a silent
        // fallback) so a legacy request keeps using CustomerQuotationService's own create() path,
        // exactly as the brief requires.
        if (decision.priceMode() == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้ยังใช้รูปแบบราคาเดิม (ก่อน CEO เลือกวิธีกรอกราคา) กรุณาสร้างใบเสนอราคาแบบเดิมจากหน้านี้");
        }
        // M2 fix (Opus review, 2026-09-20): mutual exclusivity — a PR must never carry both an
        // OPEN old-flow customer quotation and a new-flow one. Checked AFTER the idempotent-DRAFT
        // check below would also cover "I already created one myself", so this specifically
        // catches "someone already used the OLD create button on this PR" — see
        // CustomerQuotationService#create's mirror-image check for the other direction.
        if (customerQuotations != null && customerQuotations.hasLiveQuotation(pricingRequestId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คำขอราคานี้มีใบเสนอราคาลูกค้า (แบบเดิม) อยู่แล้ว ไม่สามารถสร้างใบเสนอราคาแบบใหม่ซ้ำได้");
        }
        // Idempotent replay guard: a double-click (or a retried request with no clientRequestId to
        // dedupe on — this endpoint takes no body) must not silently mint a second DRAFT for the
        // same pricing request. Returns the existing one instead of 409ing, since a re-click
        // wanting "the quotation for this request" is satisfied by either outcome and a 409 here
        // would just make the rep click a second, different button to recover.
        Optional<Long> existingDraftId = quotations.findOpenDraftForPricingRequest(pricingRequestId);
        if (existingDraftId.isPresent()) {
            return requireQuotation(existingDraftId.get());
        }
        // MAJOR-3 fix (owner ruling, 2026-09-20) — genuine gap found while implementing the expiry
        // escape hatch, NOT pre-existing: before S2, submit/approve/issue were refused for this
        // origin entirely, so a PRICING_REQUEST-origin row could only ever be DRAFT or CANCELLED —
        // the idempotent-DRAFT check above was therefore the WHOLE of "one live quotation per
        // request" in practice. S2 makes PENDING_APPROVAL/ISSUED reachable, and PR status stays
        // APPROVED_FOR_QUOTATION for the ENTIRE PENDING_APPROVAL window (it only moves to
        // QUOTATION_ISSUED at actual issue) — so, unguarded, a rep could call this a second time
        // while their own first quotation sat PENDING_APPROVAL and mint a SECOND live one on the
        // same request, past the DRAFT check above (which only ever sees DRAFT). This is the ONE
        // explicit guardrail the owner ruling asks to keep unconditionally ("Do not allow a second
        // LIVE quotation: the existing rule stays") — checked HERE, after the DRAFT check (so an
        // open DRAFT still replays idempotently, unchanged), catching the one live-but-non-DRAFT
        // case that check cannot: PENDING_APPROVAL or (not yet expired) ISSUED.
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
                // Defence in depth only — every pricing_decision_item is created FROM a
                // pricing_request_item of the same request (PricingDecisionService), so this
                // should be unreachable.
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
        // Header terms carried over from the pricing request (GLA-123 ruling 3) — stay editable on
        // the quotation afterwards, exactly like every other draft header field.
        Integer creditDays = "CREDIT".equals(summary.paymentTermMode()) ? summary.creditDays() : null;

        // MAJOR-3 fix (owner ruling, 2026-09-20) — re-creating after an expiry continues the SAME
        // {base}-{n} number family (owner's explicit preference: "it is a later version of the
        // same offer"), rather than minting an unrelated fresh base number. Falls back to a fresh
        // number when there is nothing to continue (first-ever creation, or the pricing request's
        // only prior quotation was on the OLD customerquotation engine, which this origin's own
        // #findLatestForPricingRequest cannot see by construction). NOT wired through
        // parentQuotationId/createRevision — this is a genuinely NEW, independent create (D12
        // keeps createRevision/createReorder refused for this origin), so no revision-chain
        // linkage or ancestor-supersede behaviour is implied; only the printed NUMBER continues.
        //
        // Review fix (2026-09-20): uses #nextRevisionNo (MAX across every row that EVER existed
        // under this base, widened to include this origin — see that method's own Javadoc) rather
        // than the naive {@code latest.revisionNo() + 1} — the identical latent duplicate-key risk
        // M3 documented for createRevision applies here too (D9: "the version never restarts"), so
        // reusing the SAME, already-hardened method is more robust than re-deriving the number by
        // hand a second time.
        String number;
        int revisionNo;
        Optional<Long> latestId = prReadyForReplacementAfterExpiry
            ? quotations.findLatestForPricingRequest(pricingRequestId) : Optional.empty();
        if (latestId.isPresent()) {
            DealQuotationDto latest = requireQuotation(latestId.get());
            String base = th.co.glr.hr.ticket.QuotationNumbering.baseNumber(latest.number(), latest.revisionNo());
            revisionNo = quotations.nextRevisionNo(summary.ticketId(), base);
            number = th.co.glr.hr.ticket.QuotationNumbering.revisionNumber(base, revisionNo);
        } else {
            revisionNo = 1;
            number = DealQuotationRepository.revisionNumber(quotations.nextQuotationCode(), 1);
        }
        long id = quotations.insertDraft(new InsertDraftParams(
            summary.ticketId(), number, actor.id(), ticket.createdById(),
            customerSnap.name(), customerSnap.address(), customerSnap.taxId(), customerSnap.phone(),
            contact,
            ticket.projectName(), blankToNull(summary.deptCode()), blankToNull(summary.unitCode()),
            null, // offerDate — not carried from the PR; rep fills in on the draft, same as DEAL_DIRECT.
            null, // depositPercent — engine default (unset); rep chooses it (GLA-123 ruling 2).
            null, // remainderMode — resolved once a deposit percent is chosen.
            creditDays, summary.validityDays(),
            WastageCalculator.VALIDITY_MODE_DAYS, null,
            blankToNull(summary.note()),
            decision.priceMode(), WastageCalculator.DOCUMENT_LANGUAGE_TH, "THB",
            summary.printedByDisplayId(), summary.salesRepDisplayId(),
            summary.omitContactHonorific(), null,
            subtotal, null, revisionNo, null, items,
            "PRICING_REQUEST", summary.recipientType(), summary.recipientLabel(),
            summary.id(), decision.id(),
            // Owner-directed reversal of F2 (2026-09-26) — nothing to inherit on a brand-new
            // PRICING_REQUEST-origin create; the rep sets a manual name later via #update, same
            // as any DEAL_DIRECT document.
            null));

        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_CREATED, summary.status(), summary.status(),
            "สร้างร่างใบเสนอราคาลูกค้า (เครื่องมือใบเสนอราคาแบบ direct)", null);
        tickets.addEvent(summary.ticketId(), actor.id(), actor.name(), TicketEventKind.EDITED, null, null,
            "สร้างร่างใบเสนอราคา " + number + " จากคำขอราคา " + summary.requestCode());
        return requireQuotation(id);
    }

    /** M1 fix (Opus review, 2026-09-20): read-only lookup so
     * {@code PricingRequestDetailPage}'s "ใบเสนอราคาลูกค้า" panel can show a NEW-engine
     * quotation's number/status/link without going through {@code createFromPricingRequest}
     * (which would either mint one or just silently replay the idempotent existing draft — wrong
     * for a page that must not have create side effects on render). Returns empty when this PR
     * has no PRICING_REQUEST-origin quotation yet.
     *
     * <p><b>GLA-123 slice S3 BLOCKER 1 fix (Opus review against real Postgres, 2026-09-23):</b>
     * this used to call {@link DealQuotationRepository#findOpenDraftForPricingRequest}, whose
     * DRAFT-only scope was correct back when S1 shipped (every other status-machine transition
     * WAS refused for this origin at the time) but went stale the moment S2 made
     * PENDING_APPROVAL/ISSUED reachable — and S3 adds ACCEPTED/REJECTED/REVISION_REQUESTED/
     * EXPIRED on top. With the old call, this method returned {@code Optional.empty()} for EVERY
     * quotation past DRAFT, so the frontend's {@code dealQuotationForPr} was null the instant a
     * quotation was submitted — {@code canRecordCustomerQuotationOutcome} (which reads THIS
     * value) could never see a live quotation, so the whole S3 outcome panel was unreachable, and
     * S2's own EXPIRED-escape-hatch UI block (also keyed on this same value) was equally dead.
     * Switched to {@link DealQuotationRepository#findLatestForPricingRequest}, which is already
     * used by {@link #createFromPricingRequest}'s own expiry-escape-hatch numbering for the
     * identical "current quotation regardless of status" need — reusing it here instead of a
     * third status-scoped query. Uses the same {@link #requireViewAccess} gate as
     * {@code GET /deal-quotations/{id}} (M3) — narrower than DEAL_DIRECT's, no account, no
     * quotation-grant bypass. */
    public Optional<DealQuotationDto> findForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        Optional<Long> id = quotations.findLatestForPricingRequest(pricingRequestId);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        DealQuotationDto quotation = requireQuotation(id.get());
        requireViewAccess(actor, quotation);
        return Optional.of(quotation);
    }

    /** One decision item's CEO-approved price, expressed as the {@link ItemInput} fields
     * {@link #buildItem} expects for whichever {@code priceMode} the decision uses — mirrors
     * {@code QuotationEditorPage.jsx}'s own per-mode input mapping so the computed
     * {@link NewItem#netUnitPrice()} comes out EQUAL to {@code di.netUnitPrice()} (asserted in
     * {@code PricingRequestQuotationIntegrationTest}). Every OTHER field is the physical
     * direct-deal-form data Sales already typed on the pricing request (V185) — see
     * {@link PricingRequestItemDto}. */
    private ItemInput buildItemInputFromDecisionItem(PricingRequestItemDto pri, PricingDecisionItemDto di,
                                                      String priceMode) {
        // unitPrice ("ราคาตั้ง") is set for EVERY mode, not just NET: WastageCalculator's own
        // #requirePriceValidForType requires a positive unitPrice on every non-perSqm TILE row
        // regardless of priceMode (the direct-deal editor always shows it, even under
        // SPECIAL_SQM/DIRECT_NET, as the reference figure the chosen mode's own price is compared
        // against) — buildTileItem itself only ever READS it for NET's own math, so this is safe
        // for the other two. list_unit_price (V187) is the right source here because it is
        // ALWAYS auto-calculated regardless of price_mode (see that column's own migration
        // comment), unlike manualSellingPricePerRequestedUnit, which is NET-only ("ruling B").
        BigDecimal unitPrice = WastageCalculator.PRICE_MODE_NET.equals(priceMode)
            && di.manualSellingPricePerRequestedUnit() != null
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
            null, // locationLabel — not on the pricing request; rep fills in on the draft.
            pri.catalogPriceId(), pri.productCode(), pri.brand(), pri.model(), pri.color(), pri.texture(),
            pri.size(), pri.thicknessMm(), pri.sqmPerPiece(), pri.quantityMode(), pri.areaSqm(),
            pri.piecesInput(), pri.wastageMode(), pri.wastageValue(), pri.piecesPerBox(),
            unitPrice, discountPct, pri.originCountry(), pri.leadTimeMinDays(), pri.leadTimeMaxDays(),
            blankToNull(pri.productDescription()),
            WastageCalculator.LINE_TYPE_TILE, null, null, null,
            specialPriceSqm, directNetPrice, null, null, null,
            null, pri.sqmPerBox(), pri.roundToFullBox());
    }

    /** {@code item} carrying {@code prItemId}/{@code decItemId} as its
     * {@link NewItem#pricingRequestItemId()}/{@link NewItem#pricingDecisionItemId()} — same
     * "reconstruct with one field changed" device the repository's own {@code NewItem} compat
     * constructors use, needed because {@link #buildItem} (shared with the DEAL_DIRECT path, which
     * never sets a link) has no reason to know about either field. */
    private NewItem withDecisionLink(NewItem item, long prItemId, long decItemId) {
        return new NewItem(item.locationLabel(), item.catalogPriceId(), item.productCode(), item.brand(),
            item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(), item.sqmPerPiece(),
            item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(), item.wastageValue(),
            item.piecesPerBox(), item.piecesBeforeWastage(), item.piecesAfterWastage(), item.piecesFinal(),
            item.boxes(), item.unitPrice(), item.discountPct(), item.netUnitPrice(), item.lineAmount(),
            item.vat(), item.lineTotal(), item.originCountry(), item.leadTimeMinDays(), item.leadTimeMaxDays(),
            item.itemNotes(), item.descriptionLine(), item.lineType(), item.quantity(), item.unit(),
            item.specialPriceSqm(), item.adjustmentPct(), item.adjustmentDeadline(), item.catalogWidthMm(),
            item.catalogHeightMm(), item.sqmPerBox(), item.roundToFullBox(), prItemId, decItemId);
    }

    /** M4(a)/(b) fix (Opus review, 2026-09-20): refuses changing a CEO-linked line's TYPE (must
     * stay TILE — the only type {@link #createFromPricingRequest} ever produces) or its PRODUCT
     * IDENTITY (catalog link, product code, brand, model, colour, texture, size, thickness,
     * sqm/piece, pcs/box) — the physical/product fields the CEO actually priced. Quantity and
     * เผื่อ (wastage) are DELIBERATELY excluded — those stay freely editable, exactly like every
     * other field on this origin (owner ruling 2026-09-19: sales may edit price/quantity, CEO
     * re-approves price changes; this guard is about the OFFER identity, not the numbers on it).
     * {@code stored} is null only if {@code inputId} claimed an id that is linked per {@link
     * DealQuotationRepository#findItemLinks} but somehow absent from {@code existing.items()} —
     * unreachable in practice (both are read from the same {@code id} moments apart in the same
     * transaction), guarded anyway rather than risking an NPE. */
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

    /** Null-safe, scale-insensitive {@link BigDecimal} equality (60 and 60.00 compare equal) —
     * {@code equals()} on {@link BigDecimal} does not, which would false-positive this guard on a
     * value that round-trips through {@link WastageCalculator}'s own rounding with a different
     * scale but the identical number. */
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
        // GLA-123 slice S3 BLOCKER-A fix (Opus review against real Postgres, 2026-09-23): the
        // entry gate above is DEAL_DIRECT's own broad rule (VIEW_ROLES + a full hasQuotationGrant
        // bypass) — correct for a DEAL_DIRECT row, where price is the rep's own typed number, but
        // WAY too broad for a PRICING_REQUEST row, where unitPrice/discountPct/netUnitPrice ARE
        // the CEO's approved price/discount. #findByTicket was widened (MAJOR 4, this same S3
        // round) to include PRICING_REQUEST rows in its RESULT SET without this method's own
        // per-row visibility check being widened to match — reproduced live: import could read
        // discountPct=10.00 off a PRICING_REQUEST row via this endpoint. Filtered here (not
        // field-stripped) — every OTHER list/count surface on this class (search/counts) already
        // behaves as "rows you may see", not "rows with some fields blanked out", so an
        // unauthorized PRICING_REQUEST row is dropped from the list entirely, exactly as if
        // #findByTicket had never returned it, rather than appearing with nulled-out price fields.
        List<DealQuotationDto> rows = quotations.findByTicket(ticketId);
        List<DealQuotationDto> visible = new ArrayList<>(rows.size());
        for (DealQuotationDto row : rows) {
            if (!"PRICING_REQUEST".equals(row.origin()) || canViewPricingRequestOriginRow(actor, row)) {
                visible.add(row);
            }
        }
        return visible;
    }

    /** The approver queue (owner ruling 2026-09-24: sales_manager/ceo see everything; sales,
     * import, account, and any {@code canCreateQuotation}-granted employee see only quotations on
     * deals THEY created — the grant lets them reach this endpoint but no longer widens the LIST
     * scope, only detail/create/edit access elsewhere). */
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
     * The ONE list-scope decision {@link #search} and {@link #counts} share (owner ruling
     * 2026-09-24, see {@link #LIST_SEE_ALL_ROLES}): the caller must hold a {@link #VIEW_ROLES}
     * role OR a {@code canCreateQuotation} grant to pass the entry gate; having passed it, only
     * {@code sales_manager}/{@code ceo} see every deal (returns {@code null}, unrestricted) —
     * everyone else, including a grant-holder, is scoped to deals they created (returns their own
     * id as the owner filter). This deliberately narrowed import/account/grant-holders, who
     * previously saw the whole list here.
     */
    private Long listOwnerScope(UserPrincipal actor) {
        boolean grant = hasQuotationGrant(actor);
        if (!grant) {
            requireRole(actor, VIEW_ROLES);
        }
        return LIST_SEE_ALL_ROLES.contains(actor.role()) ? null : actor.id();
    }

    /** Rule: server recomputes every number, always — this is the stateless preview the item
     * editor calls on every keystroke (debounced client-side), performing ZERO writes. */
    public DealQuotationItemDto calculateLine(ItemInput input, UserPrincipal actor) {
        return calculateLine(input, null, actor);
    }

    /**
     * Owner decision 2026-09-13 (B): the preview takes the DOCUMENT's language
     * ({@code ?documentLanguage=EN}), so the editor's live line reads exactly what the saved
     * document will print — the English lines, and on English the per-sqm quantity. Null/blank
     * is TH, the pre-existing behaviour.
     */
    public DealQuotationItemDto calculateLine(ItemInput input, String documentLanguage, UserPrincipal actor) {
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
        String language = resolveDocumentLanguage(documentLanguage);
        if (!WastageCalculator.DOCUMENT_LANGUAGE_TH.equals(language)
            && !WastageCalculator.DOCUMENT_LANGUAGE_EN.equals(language)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ภาษาเอกสารไม่ถูกต้อง: " + language);
        }
        String priceMode = inferPriceMode(input);
        return toItemDto(0L, 0, buildItem(input, null, priceMode, language, BigDecimal.ZERO), language, priceMode);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Update (DRAFT-only; item ids are STABLE across a save — see #update's own comment)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DealQuotationDto update(long id, UpsertDealQuotationRequest request, UserPrincipal actor) {
        DealQuotationDto existing = requireQuotation(id);
        TicketSummaryDto ticket = requireTicketSummary(existing.ticketId());
        // MAJOR-3 fix (Opus re-review, 2026-09-20) — this used to call the raw, origin-blind
        // #requireEditAccess(actor, ticket) directly, which is exactly how a PRICING_REQUEST-origin
        // update bypassed the origin-aware gate entirely (a `can_create_quotation` grant holder of
        // ANY role sailed straight through it). #requireEditAccessForQuotation has `existing`
        // (already loaded, right above) and branches on its origin — every other write path
        // already goes through it; this was the one straggler.
        requireEditAccessForQuotation(actor, existing);
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
        // ⚠️ NOT enforced here (owner ruling 2026-09-13, "Clear all prices on switch"): a TH↔EN switch
        // must not carry ANY typed amount into the other currency as the same number — list price,
        // net price, per-sqm price, PLAIN price and flat adjustment are all re-entered by the rep.
        // That rule lives in the EDITOR (QuotationEditorPage#changeLanguage,
        // quotationMeta#rowsWithPricesCleared). This service cannot tell a re-typed price from a
        // stale one, so it prices whatever the PUT carries.
        // Currency is NOT given the same treatment, deliberately: #resolveCurrency derives it from
        // the resolved language and accepts only the matching one, so the stored value can add
        // nothing. Passing it would merely turn a legitimate language switch into a spurious 400.
        String currency = resolveCurrency(documentLanguage, request.currency());
        // V178, the SAME "missing keeps stored" discipline as priceMode/documentLanguage above —
        // a client that omits validityMode on a PUT must not silently flip a DATE document back to
        // counting days from today.
        String validityMode = isBlank(request.validityMode())
            ? resolveValidityMode(existing.validityMode())
            : resolveValidityMode(request.validityMode());
        List<NewItem> items = buildItems(request.items(), priceMode, documentLanguage);
        BigDecimal subtotal = WastageCalculator.subtotal(items.stream().map(NewItem::lineAmount).toList());
        // Must run AFTER #buildItems — same reasoning as #create's own comment: "has special
        // pricing" is decided from the rows about to be written, so a discount cleared on THIS
        // save (or an adjustment row removed) already refuses DATE mode here, before anything is
        // persisted, rather than leaving a stale validity_until on a document that no longer has
        // any special pricing to protect.
        boolean specialPricing = DealQuotationRenderAdapter.hasSpecialPricingForNewItems(priceMode, items);
        LocalDate validityUntil = requireValidityUntilForMode(validityMode, request.validityUntil(),
            existing.quotationDate(), specialPricing);
        // V179 — same validation as #create; a full PUT always carries the payload's own value
        // (null included), so there is no "missing keeps stored" case to handle here, unlike
        // priceMode/documentLanguage/validityMode above (those use blank-string-as-sentinel
        // because a blank string could never be a legitimate value; null IS the legitimate
        // "use the real name" value for these two Long fields, so it is taken at face value).
        Long printedByDisplayId = requireEligibleDisplayEmployeeId(request.printedByDisplayId());
        Long salesRepDisplayId = requireEligibleDisplayEmployeeId(request.salesRepDisplayId());
        // Item 4 ("ไม่รับมัดจำ", V181) — same rule as #create; a full PUT always carries the
        // payload's own depositPercent (there is no "missing keeps stored" case for it), so there is
        // nothing stale to preserve here either.
        boolean noDeposit = isZeroDeposit(request.depositPercent());
        String remainderMode = noDeposit ? null : blankToNull(request.remainderMode());
        Integer creditDays = noDeposit ? null : request.creditDays();
        requireValidCreditDays(remainderMode, creditDays);
        String fullPaymentTerm = resolveFullPaymentTerm(request.depositPercent(), request.fullPaymentTerm());
        // Item 2 ("ไม่เติม “คุณ”", V180) — the editor always sends its CURRENT value (the checkbox
        // is always rendered, never omitted), so, same as printedByDisplayId/projectName, there is
        // no "missing keeps stored" case: a null on the wire means UNticked, exactly like create.
        boolean omitContactHonorific = resolveOmitContactHonorific(request.omitContactHonorific());
        // Compare-and-set FIRST, before touching a single item row — a header update that finds
        // the row no longer DRAFT (a concurrent submit/approve) must leave the items untouched.
        // F7 (2026-09-10): re-snapshot the ลูกค้า columns from the LIVE customer row on every DRAFT
        // save. The deal card now edits เลขที่ผู้เสียภาษี / โทร. in place, and the promise made
        // there is "the values on screen at save time" -- which only holds if this save rewrites
        // them. updateHeader's own WHERE clause keeps it DRAFT-only, so an issued/approved document
        // stays frozen at what it was approved with.
        int rows = quotations.updateHeader(id, contact, customerSnapshot(ticket),
            blankToNull(request.deptCode()), blankToNull(request.unitCode()),
            request.offerDate(), request.depositPercent(), remainderMode,
            creditDays, request.validityDays(), validityMode, validityUntil,
            blankToNull(request.customerNotes()),
            priceMode, documentLanguage, currency, subtotal,
            printedByDisplayId, salesRepDisplayId,
            // Owner feedback 2026-09-14 — a genuinely editable header field now (see this request
            // field's own Javadoc): the editor always sends its CURRENT value, so, same as
            // printedByDisplayId/salesRepDisplayId just above, there is no "missing keeps stored"
            // case — blankToNull(null) clears it, exactly like every other free-text header field
            // on this same call (customerNotes, deptCode, unitCode).
            blankToNull(request.projectName()),
            omitContactHonorific, fullPaymentTerm,
            // Owner-directed reversal of F2 (2026-09-26) — manual, optional buyer name; the
            // editor always sends its current value, so blank genuinely clears it back to the
            // dotted placeholder, same discipline as projectName/customerNotes above.
            blankToNull(request.orderedByName()));
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
        // GLA-123 slice S1 (owner ruling 2026-09-19) — a PRICING_REQUEST-origin quotation's items
        // come ENTIRELY from #createFromPricingRequest; adding an extra row here (TILE, PLAIN or
        // ADJUSTMENT alike) is refused for this origin in S1 — extra items get typed and priced ON
        // THE PRICING REQUEST in a later slice, not on the quotation directly (D7 is superseded by
        // this ruling for THIS origin only; a DEAL_DIRECT document is completely unaffected).
        // Every existing linked TILE row's pricing_request_item_id/pricing_decision_item_id is
        // carried forward across this save (via #withDecisionLink below) — ItemInput has no field
        // for either, so a link can never be set OR changed by the client, only established once,
        // at create.
        boolean pricingRequestOrigin = "PRICING_REQUEST".equals(existing.origin());
        Map<Long, DealQuotationRepository.ItemLink> linksByItemId = new java.util.HashMap<>();
        // M4(a)/(b) fix (Opus review, 2026-09-20) — the STORED shape of every current item, so a
        // linked line's edit can be compared against what the CEO actually priced, not just
        // against the payload's own claim. Built unconditionally (cheap — `existing` is already
        // in hand) but only ever consulted below when pricingRequestOrigin.
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
            // Short-circuits before touching claimedItemIds for a null/foreign/stale id, so those
            // never spuriously "claim" anything; claimedItemIds.add returns false for a genuine
            // duplicate of an id already claimed earlier in this payload, which is what routes the
            // SECOND occurrence to the insert branch instead of a second UPDATE of the same row.
            if (inputId != null && currentItemIds.contains(inputId) && claimedItemIds.add(inputId)) {
                DealQuotationRepository.ItemLink link = linksByItemId.get(inputId);
                if (link != null) {
                    // M4(a)/(b): a CEO-linked line may have its quantity/เผื่อ edited freely (the
                    // ruling this whole slice is built on), but not its LINE TYPE or its PRODUCT
                    // IDENTITY — those are what the CEO actually priced. Dropping the line
                    // entirely is still allowed (M4(c) below), just not silently swapping it for
                    // a different product under the same row id.
                    requireLinkedLineIdentityUnchanged(existingItemsById.get(inputId), item);
                }
                NewItem stored = link != null
                    ? withDecisionLink(item, link.pricingRequestItemId(), link.pricingDecisionItemId())
                    : item;
                updates.add(new DealQuotationRepository.ExistingItem(inputId, seq, stored));
            } else if (pricingRequestOrigin) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ใบเสนอราคาจากคำขอราคานี้เพิ่มรายการใหม่ไม่ได้ — กรุณาเพิ่มรายการที่คำขอราคาต้นทาง แล้วให้ CEO กำหนดราคา");
            } else {
                inserts.add(new DealQuotationRepository.SeqItem(seq, item));
            }
        }
        // M4(c) fix (Opus review, 2026-09-20) — every LINKED item id that existed before this save
        // but was not claimed by anything in this payload is about to be hard-deleted by
        // #deleteUnclaimedItems below. Allowed (the ruling only forbids swapping a linked line's
        // identity, not dropping it), but flagged: +1 to the header counter per line, so the
        // editor can show "ลบรายการที่ CEO อนุมัติ N รายการ" instead of the drop being invisible.
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

    /** M4(d) fix (Opus review, 2026-09-20) — "คืนรายการ": re-adds a CEO-linked line that a prior
     * save dropped (M4(c) above), by rebuilding it FRESH from the SAME approved decision item
     * {@link #createFromPricingRequest} originally built it from — the physical/product fields
     * and the CEO's priced figures are therefore identical to what the line looked like before it
     * was dropped, not whatever the rep might have typed in the meantime for a same-shaped new
     * row (which #update's own extra-row refusal would have blocked anyway). Appended as a NEW
     * row at the end (a fresh {@code quotation_item_id} — the dropped row's own id is gone,
     * deleted by {@link DealQuotationRepository#deleteUnclaimedItems} the save that removed it),
     * re-establishing the SAME pricing_request_item_id/pricing_decision_item_id link. */
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
        // Already live (never dropped, or a double-click on "คืนรายการ") — refuse rather than
        // silently duplicating the line.
        boolean alreadyPresent = quotations.findItemLinks(id).stream()
            .anyMatch(link -> link.pricingDecisionItemId() == pricingDecisionItemId);
        if (alreadyPresent) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการนี้อยู่ในใบเสนอราคาอยู่แล้ว");
        }
        // NIT fix (Opus re-review, 2026-09-20) — see DealQuotationRepository#findPricingDecisionId's
        // own Javadoc for why this reads through the quotation's OWN frozen pricing_decision_id,
        // not "any APPROVED decision on this PR".
        Long pricingDecisionId = quotations.findPricingDecisionId(id)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบมติราคาต้นทางของใบเสนอราคานี้"));
        PricingDecisionDto decision = pricingDecisions.find(pricingDecisionId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบมติราคาต้นทางของใบเสนอราคานี้"));
        // MINOR-2 fix (Opus re-review, 2026-09-20) — a restored line is built for the DOCUMENT's
        // CURRENT price mode (buildItemInputFromDecisionItem's own contract), but a decision
        // item's price fields are only populated for the mode the CEO ACTUALLY chose: a NET
        // decision item has no directNetPrice, so restoring it into a document since switched to
        // DIRECT_NET would insert a row with no net price at all, 400ing downstream on
        // "ราคาสุทธิต้องมากกว่าศูนย์" — a confusing failure with no visible cause. REFUSED here
        // instead of silently building the line under the wrong mode (which the renderer, built
        // on one price mode per DOCUMENT, was never designed to mix): the rep already has one
        // explicit, whole-document action for this (the price-mode picker), and asking them to
        // use it first keeps every line honestly priced under the SAME mode the document claims.
        if (!decision.priceMode().equals(existing.priceMode())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "คืนรายการไม่ได้ — เอกสารนี้เปลี่ยนวิธีกรอกราคาไปจาก CEO แล้ว (CEO เลือก: " + decision.priceMode()
                    + ", ปัจจุบัน: " + existing.priceMode() + ") กรุณาเปลี่ยนวิธีกรอกราคากลับก่อนจึงจะคืนรายการได้");
        }
        // MINOR-3 fix (Opus re-review, 2026-09-20) — the CEO's decision price is always THB (Step
        // 3/pricing_decision has no currency of its own); an EN/USD document has no valid
        // conversion for it. Mirrors this codebase's own TH<->EN switch rule (owner ruling
        // 2026-09-13, QuotationEditorPage#changeLanguage/quotationMeta#rowsWithPricesCleared):
        // a language switch clears every typed price rather than silently carrying one currency's
        // number into the other, because this service cannot tell a re-typed price from a stale
        // one. Restoring must not inject a raw THB figure into a USD row unflagged either — REFUSED
        // for the SAME reason MINOR-2 is: one whole-document action (switch back to TH) keeps
        // every line honestly priced in the SAME currency the document claims, rather than one row
        // silently priced in a currency nothing else on the page agrees with.
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
        // MAJOR-1 fix (Opus re-review, 2026-09-20) — recompute and persist the subtotal exactly
        // like #update does (WastageCalculator.subtotal over every CURRENT line's lineAmount, read
        // back AFTER the insert so the just-restored line is included). Left uncorrected, the
        // restore leaves total_amount (which #mapQuotation reads as the subtotal, and from which
        // VAT/grand total are derived) understated by exactly the restored line's amount until the
        // next full save — silently wrong on any render/download in between.
        DealQuotationDto afterInsert = requireQuotation(id);
        BigDecimal subtotal = WastageCalculator.subtotal(
            afterInsert.items().stream().map(DealQuotationItemDto::lineAmount).toList());
        quotations.updateSubtotal(id, subtotal);
        return requireQuotation(id);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Status machine: DRAFT -> (submit) -> PENDING_APPROVAL -> (approve) -> APPROVED
    //                                                        -> (reject+reason) -> DRAFT
    //                 DRAFT -> (cancel) -> CANCELLED
    //                 APPROVED -> (revise) -> new DRAFT child; parent -> SUPERSEDED once the
    //                             CHILD reaches APPROVED (not before).
    //
    // Owner clarification (2026-09-15): ตีกลับ ITSELF never renumbers -- reject() above is the
    // WHOLE of "DRAFT -> (reject+reason) -> DRAFT", same row, same number, exactly as drawn. The
    // renumbering happens one step later, the NEXT time #submit runs on that now-rejected DRAFT:
    //     DRAFT (with a prior rejection, i.e. approval_note != null) -> (submit) ->
    //         new DRAFT revision (parent = the rejected row) -> PENDING_APPROVAL immediately;
    //         parent -> SUPERSEDED once THIS revision reaches APPROVED (not before) -- the
    //         IDENTICAL rule and mechanism the APPROVED/(revise) row above already uses, just with
    //         a DRAFT parent instead of an APPROVED one (see DealQuotationRepository#supersede).
    // One sentence: ตีกลับ = แก้ใบเดิม เลขไม่เปลี่ยน (until resubmitted); resubmitting ที่ถูกตีกลับ
    // = ออกใบใหม่ เลขเปลี่ยน, the same way a revision of an approved document always has.
    //
    // Owner ruling (2026-09-19): a deal may hold only ONE APPROVED DEAL_DIRECT quotation at a
    // time. #approve therefore ALSO supersedes every OTHER currently-APPROVED DEAL_DIRECT
    // quotation on the same ticket, regardless of lineage -- see its own same-ticket sweep,
    // ADDITIONAL to (not a replacement for) the ancestor-chain walk above. This is what makes
    // GLA-74's "สร้างจากใบเดิม" clone actually replace its source: cloning itself still leaves the
    // source APPROVED (see #createReorder), but the CLONE's own later approval now does supersede
    // it, through this same sweep -- exactly like approving a revision, or approving a second,
    // wholly independent first-issue quotation on the same deal.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * GLA-123 slice S2: submit/approve/reject are now LIVE for a PRICING_REQUEST-origin quotation
     * — {@link #submit} shares its compare-and-set with DEAL_DIRECT outright, and {@link #approve}
     * branches to {@link #approveAndIssuePricingRequestOrigin} only for its OWN extra issue hooks
     * (its own underlying compare-and-set is the SAME shared {@code DealQuotationRepository
     * #approve} DEAL_DIRECT uses); {@link #reject} is now fully shared, no branch at all. None of
     * the three calls this guard. What remains refused for this origin, and what this guard now
     * exists FOR, is a revision of an ISSUED document ({@link #createRevision}) and a
     * สั่งเหมือนเดิม clone ({@link #createReorder}) — both explicitly S3 territory (D12: "a
     * revision of an ACCEPTED quotation is S3's territory"), not yet meaningful here since this
     * origin has no customer-outcome/acceptance step at all until S3 lands (the owner's expiry
     * escape hatch, {@link #createFromPricingRequest}, is a deliberately SEPARATE mechanism — a
     * brand-new, independent create, not a revision of the expired row). A DEAL_DIRECT quotation
     * is completely unaffected; this is a pure no-op for it.
     */
    private void requireStatusMachineEnabled(DealQuotationDto quotation) {
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ยังไม่เปิดใช้งานการทำฉบับแก้ไข/สั่งเหมือนเดิมสำหรับใบเสนอราคาจากคำขอราคา — จะเปิดใช้งานในระยะถัดไป");
        }
    }

    @Transactional
    public DealQuotationDto submit(long id, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        // GLA-123 slice S2: submit is now LIVE for BOTH origins — every validation below (item
        // completeness, ผู้สั่งซื้อ, payment term, validity date) is origin-agnostic and applies
        // unchanged; only approve/reject/issue diverge (see #approve/#reject's own origin branch).
        if (quotation.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ใบเสนอราคาต้องมีอย่างน้อยหนึ่งรายการก่อนส่งขออนุมัติ");
        }
        // ผู้สั่งซื้อ is mandatory (owner feedback F2): create/update already refuse a draft with no
        // resolvable contact, so this only ever catches a row that predates V167 on a ticket that
        // had no contact -- it stays a draft until the rep saves it with one.
        if (quotation.contactId() == null || isBlank(quotation.contactName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุผู้สั่งซื้อ");
        }
        // Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16): a zero-deposit document must name
        // ONE of the three payment terms before an approver ever sees it — create/update allow a
        // DRAFT to be saved with no term chosen yet (isZeroDeposit(...) ? blankToNull(...) : null),
        // so submit is the one gate that actually requires it, the same "create/update permissive,
        // submit strict" split #requireEveryTileItemHasALeadTime and the DATE-mode check just below
        // both already use. Deliberately NOT re-checked for a row whose depositPercent is not
        // exactly 0 (including every pre-V181 row, which stores fullPaymentTerm = NULL regardless
        // of its deposit): #resolveFullPaymentTerm already guarantees such a row's fullPaymentTerm
        // is null, so this condition can only ever fire on a genuinely zero-deposit document.
        if (isZeroDeposit(quotation.depositPercent()) && isBlank(quotation.fullPaymentTerm())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกเงื่อนไขการชำระเงินเต็มจำนวน");
        }
        // Wording-scan fix 6 (2026-09-17) — the submit-time half of the credit-days rule, the exact
        // "create/update permissive, submit strict" split the fullPaymentTerm gate just above uses:
        // create/update already refuse an explicit invalid VALUE (0 or negative — #requireValidCreditDays),
        // but still let a CREDIT-remainder draft save with creditDays left BLANK; submit is what
        // actually requires it be filled in before an approver ever sees the document. Deliberately
        // NOT re-checked for a value that is not exactly null (an explicit invalid one could only
        // reach a stored row from before this fix existed, and re-validating every stored value here
        // would be the same defensive re-check #requireStoredItemComplete already does for items,
        // which this quotation-level field does not need — a value already accepted by create/update
        // going forward is never invalid).
        if ("CREDIT".equals(quotation.remainderMode()) && quotation.creditDays() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
        }
        // V178: a DATE-mode validity deadline that has already passed must not go to an approver —
        // create/update already refuse one before the quotation's OWN date, but time keeps moving
        // after a draft is saved, so submit re-checks against TODAY (Bangkok), the last gate before
        // an approver ever sees the document (same reasoning as the item-completeness re-check
        // below).
        if (WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
            && quotation.validityUntil() != null
            && quotation.validityUntil().isBefore(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ยืนราคาผ่านไปแล้ว");
        }
        // MAJOR-4 fix (Opus re-review, 2026-09-20) — a PRICING_REQUEST-origin quotation MUST carry
        // a validity (whichever the engine's own validityMode uses) before submit, or D5's expiry
        // sweep (DealQuotationRepository#expireOverdueQuotations, scoped to
        // `validity_date IS NOT NULL`) silently never reaches it once issued — an ISSUED document
        // with no validity_date lives forever, defeating the whole rule with no error anywhere.
        // DEAL_DIRECT is DELIBERATELY untouched here (that origin never expires by design, D5, so
        // it has no equivalent requirement — `validityDays` stays optional for it, same as before
        // this fix). Checked at submit, not create/update, matching this method's own "create/
        // update permissive, submit strict" convention used by every other gate in this block.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            boolean hasValidity = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
                ? quotation.validityUntil() != null
                : quotation.validityDays() != null;
            if (!hasValidity) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุระยะเวลายืนราคาก่อนส่งขออนุมัติ");
            }
        }
        // Item completeness rule, re-checked over the STORED rows: create/update already enforce
        // this on write (#buildItem), but a row can predate this rule (created before this
        // migration of behaviour) or a stale client could in principle bypass the write-time
        // check -- submit is the last gate before an approver ever sees the document.
        boolean perSqm = WastageCalculator.isEnglishPerSqm(quotation.documentLanguage(), quotation.priceMode());
        for (DealQuotationItemDto item : quotation.items()) {
            requireStoredItemComplete(item, perSqm);
        }
        requireEveryTileItemHasALeadTime(quotation.items());
        // Owner clarification (2026-09-15): ตีกลับ (reject) itself never renumbers -- reject()
        // sends the SAME row back to DRAFT with a reason (see the class's own status-machine
        // comment above). But once sales fixes it and resubmits, THIS submit is what should mint
        // a new number: "ตีกลับ = แก้ใบเดิม เลขไม่เปลี่ยน (until resubmitted) · then ออกใบใหม่ เลข
        // เปลี่ยน on the next submit." A DRAFT carrying a PRIOR rejection decision --
        // approval_note IS NOT NULL, the EXACT signal DealQuotationRepository#NEEDS_REWORK_PREDICATE
        // already keys the list page's "แก้" bucket on -- resubmits as a NEW revision of itself
        // rather than reusing its own row, done as one atomic step so no caller can observe (or
        // race against) an un-submitted intermediate child, exactly as if the rep had called
        // #createRevision then #submit on the result by hand. A first-ever submit (approval_note
        // is null -- never yet decided on) is untouched: same row, same number, as always.
        //
        // GLA-123 slice S2: deliberately EXCLUDED for a PRICING_REQUEST-origin row, even though it
        // too can carry a prior rejection reason (reject is fully shared with DEAL_DIRECT now —
        // one approver, one decision — and returns to DRAFT with a reason exactly the same way).
        // A resubmit after ตีกลับ on THIS origin reuses the SAME row/number — no revision is minted — because
        // #insertRevisionCopyOf/#requireNoOpenRevision/#supersede below are all scoped to
        // {@code origin = 'DEAL_DIRECT'} in the repository, and createRevision/createReorder stay
        // refused outright for this origin (see #requireStatusMachineEnabled's own Javadoc, D12 —
        // a revision of an ACCEPTED quotation is S3's territory). Forking that machinery to also
        // understand a PRICING_REQUEST lineage is out of S2's scope; "ตีกลับ → sales fixes it →
        // resubmits the SAME document" is exactly what R9 asks for, so this is not a regression,
        // just a narrower rule than DEAL_DIRECT's.
        if (QuotationStatus.DRAFT.equals(quotation.docStatus()) && quotation.approvalNote() != null
                && !"PRICING_REQUEST".equals(quotation.origin())) {
            return submitAsRevisionOfRejected(quotation, actor);
        }
        return submitDraftRow(id, actor);
    }

    /**
     * The resubmit-after-rejection half of {@link #submit}'s branch above: mints a revision of
     * {@code rejected} the SAME way {@link #createRevision} does (shared
     * {@link #insertRevisionCopyOf}, same {@link #requireNoOpenRevision} lock/guard), then submits
     * THAT new row via the ordinary {@link #submitDraftRow} -- so the parent (still whatever
     * status it already was, DRAFT) becomes {@code SUPERSEDED} the SAME way and at the SAME time
     * an ordinary revision's parent does: {@link #approve}'s existing
     * {@code parentQuotationId() != null -> supersede()} call, once THIS child reaches APPROVED,
     * not before. {@link DealQuotationRepository#supersede} accepts a {@code DRAFT} parent for
     * exactly this reason (widened alongside {@code APPROVED}, which is all it needed before this
     * feature).
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

    /** The actual DRAFT -> PENDING_APPROVAL compare-and-set, its ticket event and its
     * notification -- shared by an ordinary first-time submit ({@code id} is the row the caller
     * named) and {@link #submitAsRevisionOfRejected} (where {@code id} is the freshly-minted
     * revision, not the row {@link #submit} was originally called with). */
    private DealQuotationDto submitDraftRow(long id, UserPrincipal actor) {
        int rows = quotations.submit(id, actor.id());
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงส่งขออนุมัติไม่ได้");
        }
        DealQuotationDto submitted = requireQuotation(id);
        tickets.addEvent(submitted.ticketId(), actor.id(), actor.name(), TicketEventKind.SUBMITTED, null, null,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติ");
        // Call directly, exactly like #approve/#reject call notifyRepAndCreator directly -- NOT
        // wrapped in this class's own afterCommit(). That used to wrap this call (M5), on the
        // reasoning that mail/the in-app row must not be observable before the submit is durable.
        // That reasoning is sound but the mechanism was wrong: notifyByRoleAtLink/notifyEmployeeAtLink
        // already defer their OWN mail send until commit, via notification.AfterCommit.run() inside
        // SalesNotificationMailRouter -- the same single-defer mechanism #approve/#reject already
        // rely on below. Wrapping notifySubmitted() in a SECOND, service-local afterCommit() double-
        // registered a TransactionSynchronization from inside another synchronization's own
        // afterCommit() callback, and Spring silently drops that (confirmed against this repo's
        // Spring 7.0.8: the nested synchronization's afterCommit() never runs, even though
        // isSynchronizationActive() still reads true when it is registered) -- so EVERY mail from
        // notifySubmitted() (sales_manager, ceo, and the rep's own submission confirmation) was
        // silently swallowed, though the in-app bell rows still landed since that INSERT runs
        // synchronously and simply joins the ambient transaction like any other write here -- no
        // manual deferral needed for it to roll back together with everything else.
        notifySubmitted(submitted, actor);
        return submitted;
    }

    /**
     * Owner request (2026-09-16): a submitted row that is a revision -- {@code
     * parentQuotationId() != null}, true for BOTH {@link #createRevision}'s own submit (parent
     * {@code APPROVED}) and {@link #submitAsRevisionOfRejected}'s (parent {@code DRAFT}, carrying
     * a rejection reason in its own {@code approvalNote}) -- must read as a REVISION to
     * sales_manager/ceo, not the identical "รออนุมัติ" text an ordinary first-time submit sends; a
     * reviewer skimming the bell/inbox otherwise has no way to tell the two apart. First-time
     * submits ({@code parentQuotationId() == null}) fall straight through to the original
     * unchanged branch below.
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
        // Owner request (2026-09-15): the rep who submitted (and the creator, if different -- same
        // dedupe #notifyRepAndCreator already gives approve()/reject()) gets their own confirmation
        // that the submission actually went through, not just a bell/mail for the two approvers.
        // Same event kind/title as the approver-facing notification above -- reusing it rather than
        // minting a second TicketEventKind keeps this to a one-line addition; the message text is
        // still written from the rep's own point of view.
        notifyRepAndCreator(submitted, TicketEventKind.DEAL_QUOTATION_SUBMITTED,
            "ส่งใบเสนอราคา " + submitted.number() + " ขออนุมัติแล้ว รอผลการพิจารณา");
    }

    /**
     * The revision half of {@link #notifySubmitted} above. Looks the parent up (a plain {@link
     * #requireQuotation}, no access re-check -- the actor already passed {@link
     * #requireEditAccessForQuotation} against the CURRENT row earlier in {@link #submit}, and the
     * parent is the very row that row was copied from) purely to read its {@code number()} and,
     * for the resubmit-after-ตีกลับ path only, its rejection reason.
     *
     * <p>{@code parent.docStatus()} is what actually tells the two {@link #insertRevisionCopyOf}
     * callers apart, NOT {@code approvalNote() != null} alone: {@link #createRevision} requires an
     * {@code APPROVED} source, and an approved row's own {@code approvalNote} is whatever the
     * approver typed (see {@code acceptanceScenario_createUpdateSubmitApprove}'s "อนุมัติแล้ว"),
     * which would be a false "ตีกลับ" reading here. {@link #submitAsRevisionOfRejected} requires a
     * {@code DRAFT} source carrying a PRIOR rejection decision, and ตีกลับ itself never renumbers
     * (see this class's own status-machine comment above {@link #submit}) -- so the parent is
     * STILL {@code DRAFT} at this point, with {@code approvalNote} holding the rejection reason,
     * not yet superseded (that only happens once THIS revision itself reaches {@code APPROVED}).
     *
     * <p>Opus review (2026-09-16): the parent lookup is a plain {@link
     * DealQuotationRepository#findById}, NOT {@link #requireQuotation}, so this notification step
     * can never 404 the surrounding submit transaction just because the parent row is somehow
     * unreadable -- there is no DELETE of quotations anywhere in this codebase and the parent is
     * FK-protected, so the fallback below is not reachable today, but a notification side-effect is
     * still the wrong place to fail a submit outright. Same reasoning for {@code actor.name()}: it
     * is interpolated with no null/blank guard elsewhere in this class, but this is the one message
     * that prints it verbatim as the FIRST word, so a blank name would otherwise read as the
     * literal "null ได้จัดทำ...". Falls back to a role label, matching {@link #approve}'s own
     * {@code approvedByName() != null ? ... : "ผู้อนุมัติ"} pattern just below in this class.
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
        // Same rep/creator confirmation as the first-time-submit branch, worded for a revision.
        notifyRepAndCreator(submitted, TicketEventKind.DEAL_QUOTATION_SUBMITTED,
            "ส่งใบเสนอราคาฉบับแก้ไข " + submitted.number() + " ขออนุมัติแล้ว รอผลการพิจารณา");
    }

    @Transactional
    public DealQuotationDto approve(long id, ApproveRequest request, UserPrincipal actor) {
        requireApproveAccess(actor);
        DealQuotationDto quotation = requireQuotation(id);
        // GLA-123 slice S2 REWORK (owner reversed the dual-approval design, 2026-09-20): a
        // PRICING_REQUEST-origin quotation still branches out — the compare-and-set below IS
        // shared with it now (DealQuotationRepository#approve widened its own origin predicate),
        // but the ADDITIONAL issue hooks (PR status transition, stage advance, PR event) and the
        // CEO-required-when-changed gate are unique to this origin, so the branch stays.
        // requireStatusMachineEnabled is NOT called on this branch (it would always refuse it);
        // that guard now covers only createRevision/createReorder for this origin.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            return approveAndIssuePricingRequestOrigin(quotation, request, actor);
        }
        // Owner ruling (2026-09-19): a deal may hold only ONE APPROVED DEAL_DIRECT quotation at a
        // time -- approve() now ALSO sweeps every other currently-APPROVED sibling on this ticket
        // (see below). Locks the ticket BEFORE the compare-and-set below (not merely before the
        // sweep), so two concurrent approve() calls on the SAME ticket -- even against two
        // DIFFERENT PENDING_APPROVAL rows -- fully serialise: the second caller only proceeds once
        // the first's UPDATE + sweep + commit is visible, so the two can never both land APPROVED
        // together. Same advisory lock, same key (ticketId), and the SAME "lock the ticket first"
        // ordering #createRevision/#createReorder already use (via #requireNoOpenRevision /
        // directly) -- a transaction here never holds a second ticket's lock at the same time, so
        // this cannot deadlock against either of them.
        quotations.lockTicket(quotation.ticketId());
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        // V178: a DATE-mode document WITH special pricing names its OWN validity_date (the rep's
        // ภายในวันที่, verbatim — approval never recomputes it); every other case (DAYS mode, or a
        // stale DATE mode that lost its special pricing — create/update refuse THAT combination
        // going forward, but an already-PENDING_APPROVAL row from before this change could still
        // carry it) falls back to the existing day-count rule.
        // Opus review (2026-09-14): mirror the render adapter's own null guard (DealQuotation
        // RenderAdapter:372/475) rather than trusting validityUntil is non-null just because the
        // mode says DATE — unreachable through create/update/createRevision today, but a stray
        // NULL here would otherwise skip the DAYS fallback entirely and leave validity_date NULL
        // despite validity_days being set.
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
        // Owner ruling (2026-09-19): a deal may hold only ONE APPROVED DEAL_DIRECT quotation at a
        // time. This SWEEP runs BEFORE the ancestor walk below (Opus review, item 4 -- it used to
        // run after) precisely because the walk's own compare-and-set (DealQuotationRepository
        // #supersede) is a no-op on a row this sweep already flipped, so running the sweep FIRST
        // is what lets the MOST COMMON case -- an ordinary revision whose immediate parent is
        // still APPROVED -- get exactly one DEAL_QUOTATION_SUPERSEDED event, from the sweep,
        // instead of being superseded silently by the walk with no event at all (the walk itself
        // stays silent, as it always has, when it touches a DRAFT ancestor from a reject/resubmit
        // chain -- see that loop's own comment below for why that half is deliberately unchanged).
        //
        // UNLIKE the ancestor walk (which only ever climbs THIS row's own parentQuotationId
        // lineage), this sweeps EVERY other currently-APPROVED DEAL_DIRECT quotation on the SAME
        // ticket regardless of lineage -- the case the ancestor walk cannot reach on its own: two
        // wholly independent first-issue quotations, or a GLA-74 reorder clone (which links to its
        // source only via derivedFromQuotationId, never parentQuotationId, and so is invisible to
        // the walk by construction -- see #createReorder). Existing data is NOT rewritten -- this
        // only ever runs going forward, from THIS approval onward; a ticket that already holds
        // two-or-more APPROVED rows from before this ruling stays exactly as it is until its next
        // approval.
        List<DealQuotationRepository.SupersededSibling> supersededSiblings =
            quotations.supersedeOtherApprovedOnTicket(approved.ticketId(), approved.id());
        for (DealQuotationRepository.SupersededSibling sibling : supersededSiblings) {
            tickets.addEvent(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.DEAL_QUOTATION_SUPERSEDED,
                null, null, "ใบ " + sibling.number() + " ถูกแทนที่ด้วย " + approved.number());
        }
        // The customer's last approved document stays valid until a REVISION actually reaches
        // APPROVED — see the class Javadoc's status-machine note. Only fires for a revision (a
        // first-ever quotation has no parent). Runs AFTER the sweep above (see that block's own
        // comment for why) and stays SILENT -- no ticket event -- exactly as it always has: a
        // DRAFT ancestor from a reject/resubmit chain is invisible to the sweep's own
        // {@code doc_status = 'APPROVED'} predicate, so this walk is the ONLY thing that ever
        // supersedes one, and nothing currently asks for that to be logged. (If a DRAFT→SUPERSEDED
        // flip in this specific chain should also produce a ticket event, that is a separate,
        // not-yet-requested change -- left as-is here.)
        //
        // Opus review (2026-09-15): walks the WHOLE ancestry chain, not just the immediate
        // parent. The single-hop version only ever superseded approved.parentQuotationId()
        // itself, so a SECOND (or later) reject/resubmit cycle left every ancestor ABOVE the
        // immediate parent stranded in DRAFT forever once THIS approval landed -- proven by a
        // real-DB probe: reject A -> resubmit mints B -> reject B -> resubmit mints C -> approve
        // C left A in DRAFT permanently (parent_quotation_id chains straight through B, which
        // this approval DOES supersede, but nothing ever walked past B to A). Two consequences
        // that made this a required fix, not a nit: (1) A sits in the rep's "ฉบับแก้" bucket
        // forever on a deal whose quotation is already approved -- NEEDS_REWORK_PREDICATE keeps
        // matching it (still DRAFT, approval_note still set) and hasOpenRevision stops blocking
        // it the moment B leaves DRAFT/PENDING_APPROVAL, so nothing ever clears it; (2) the rep
        // can then resubmit A, and approving THAT mints a second, independently-APPROVED
        // quotation on the same ticket alongside C, with neither superseding the other --
        // contradicting the owner's own constraint that a rejected row's eventual fate is
        // SUPERSEDED. Each hop is its own compare-and-set (supersede()'s WHERE already guards
        // against a non-APPROVED/DRAFT row), so walking past an already-terminal ancestor
        // (already SUPERSEDED from a sibling branch, or CANCELLED, or -- new since the sweep now
        // runs first -- by THIS approval's own sweep) is a safe no-op -- and the loop terminates
        // at the first-ever quotation in the chain, whose parentQuotationId is null by
        // construction.
        Long ancestorId = approved.parentQuotationId();
        while (ancestorId != null) {
            DealQuotationDto ancestor = requireQuotation(ancestorId);
            quotations.supersede(ancestorId);
            ancestorId = ancestor.parentQuotationId();
        }
        tickets.addEventWithDocument(approved.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติใบเสนอราคา " + approved.number(), RelatedDocumentType.QUOTATION, id);
        String message = "ใบเสนอราคา " + approved.number() + " ได้รับอนุมัติแล้ว";
        // Owner request (2026-09-15): this used to ALSO fire #sendApprovalEmail below — a second,
        // plain-text email with the PDF attached, from a completely separate mailer
        // (approvalMailer/Mailer#sendWithAttachment) than the one notifyRepAndCreator uses
        // (salesMailer -> NotificationEmailService#send, the branded HTML template). Two emails
        // landed for one approval. Keep only the HTML one; #sendApprovalEmail/
        // #sendToEmployeeIfPossible are left in place (dead) rather than deleted outright, since
        // several integration tests still construct a mailer fake around them -- removing the
        // call is the actual fix, not a reason to also cascade a constructor-signature change
        // through every test file in the same pass.
        notifyRepAndCreator(approved, TicketEventKind.DEAL_QUOTATION_APPROVED, message);
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
        DealQuotationDto quotation = requireQuotation(id);
        // GLA-123 slice S2 REWORK (owner reversed the dual-approval design, 2026-09-20): reject is
        // fully shared across both origins now — one approver, one decision, so there is nothing
        // origin-specific left to branch on (DealQuotationRepository#reject's own WHERE clause was
        // widened to accept PRICING_REQUEST alongside DEAL_DIRECT for exactly this reason).
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-123 slice S2, REWORKED (owner reversed the dual-approval design, 2026-09-20 — this
    // supersedes R6/D13 and everything built against them). Status machine, unchanged from
    // DEAL_DIRECT's own except the terminal status's NAME:
    //   DRAFT -> (submit) -> PENDING_APPROVAL -> (approve, by whoever may act) -> ISSUED
    //                                          -> (reject+reason) -> DRAFT
    //          -> (validity_date passes while ISSUED) -> EXPIRED (see #expireOverdueQuotations)
    // ONE approval — by ผู้จัดการฝ่ายขาย OR the CEO — issues the document, and whoever approves is
    // the signature that prints (reuses DealQuotationRepository#approve's existing single-shot
    // compare-and-set AND its V175 approver-snapshot write verbatim — the SAME path DEAL_DIRECT
    // uses, already tested there). The ONE thing this origin adds on top of that shared
    // compare-and-set: the CEO's price authority survives sales's freedom to edit price after
    // create (see DealQuotationDto#priceChangedFromCeo's own Javadoc) — see
    // #requireCeoApprovalIfChanged below.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The approve() branch for a PRICING_REQUEST-origin quotation. {@code actor}'s role is
     * already known to be {@code sales_manager} or {@code ceo} ({@link #requireApproveAccess} ran
     * before this was called).
     */
    private DealQuotationDto approveAndIssuePricingRequestOrigin(DealQuotationDto quotation, ApproveRequest request,
                                                                  UserPrincipal actor) {
        requireCeoApprovalIfChanged(quotation, actor);
        if (pricingRequests == null || customerQuotations == null || ticketService == null) {
            // Same defensive shape as #createFromPricingRequest's own guard — only reachable if a
            // deployment wires this bean without the GLA-123 setter-injected dependencies.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        long pricingRequestId = quotation.pricingRequestId();
        // Same lock CustomerQuotationService#issue takes before its own compare-and-set — the PR
        // (not just the ticket) is the shared resource two independent issue attempts on
        // sibling/legacy quotations could otherwise race on. No ticket-level lock is needed here
        // (unlike DEAL_DIRECT's own #approve): this origin has no "one live document per ticket"
        // sweep to serialise — a single compare-and-set on THIS row is the whole invariant.
        customerQuotations.lockPricingRequest(pricingRequestId);
        PricingRequestSummaryDto summary = pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
        // Same DATE-mode-with-special-pricing / DAYS-mode validity computation #approve already
        // uses for DEAL_DIRECT — reused verbatim rather than forked so the two origins can never
        // compute this differently by accident.
        LocalDate approvalDate = LocalDate.now(BANGKOK);
        LocalDate validityDate = WastageCalculator.VALIDITY_MODE_DATE.equals(quotation.validityMode())
                && quotation.validityUntil() != null
                && DealQuotationRenderAdapter.hasSpecialPricing(quotation)
            ? quotation.validityUntil()
            : (quotation.validityDays() != null ? approvalDate.plusDays(quotation.validityDays()) : null);
        // The SAME repository method DEAL_DIRECT uses — its own CASE expression picks 'ISSUED'
        // over 'APPROVED' for this origin, and its V175 snapshot INSERT freezes THIS actor's
        // identity/signature as the printed approver, with no slot/second-approver concept at all.
        int rows = quotations.approve(quotation.id(), actor.id(), blankToNull(request.note()), validityDate);
        if (rows == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบเสนอราคาไม่ได้อยู่ในสถานะรออนุมัติ จึงอนุมัติไม่ได้");
        }
        DealQuotationDto issued = requireQuotation(quotation.id());
        // Only the FIRST issue moves the pricing request — a re-issue (there is none for this
        // origin yet; createRevision/createReorder both stay refused, see
        // #requireStatusMachineEnabled) would be a no-op transition. The owner's expiry escape
        // hatch (#createFromPricingRequest) is what lets a SECOND, independent quotation reach
        // this method again with the PR already at QUOTATION_ISSUED — this guard is what makes
        // that re-issue a no-op transition instead of an IllegalStateException. Mirrors
        // CustomerQuotationService#issue's identical guard.
        if (PricingRequestStatus.APPROVED_FOR_QUOTATION.equals(summary.status())) {
            int transitioned = pricingRequests.transition(summary.id(), PricingRequestStatus.APPROVED_FOR_QUOTATION,
                PricingRequestStatus.QUOTATION_ISSUED, null, null);
            if (transitioned == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "คำขอราคาถูกเปลี่ยนแปลงโดยผู้ใช้อื่น");
            }
        }
        // Rule 7 (shared with CustomerQuotationService#issue): reuse the SAME stage transition,
        // not a second path. Forward-only and ACTIVE-deal-only — see
        // TicketService#advanceStageForCustomerQuotationIssue/#autoAdvanceStage's own guards.
        ticketService.advanceStageForCustomerQuotationIssue(summary.ticketId(), summary.recipientType(), actor);
        tickets.addEventWithDocument(issued.ticketId(), actor.id(), actor.name(), TicketEventKind.QUOTATION_ISSUED,
            null, null, "อนุมัติและออกใบเสนอราคา " + issued.number(), RelatedDocumentType.QUOTATION, issued.id());
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED, summary.status(),
            PricingRequestStatus.QUOTATION_ISSUED, "ออกใบเสนอราคา " + issued.number(), null);
        // Parity with CustomerQuotationService#issue's own "customer notification" substitute:
        // this system has no customer user account to notify in-app, so the closest equivalent is
        // a CEO-visibility notification documenting the issuance. Redundant when the CEO IS the
        // one approving, but kept unconditional for parity and because a sales_manager approval is
        // the more common case, where the CEO genuinely was not just looking at this screen.
        notifications.notifyByRoleForPricingRequest("ceo", summary.id(),
            PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED, "ใบเสนอราคา " + issued.number() + " ถูกออกแล้ว");
        notifyRepAndCreator(issued, TicketEventKind.DEAL_QUOTATION_APPROVED,
            "ใบเสนอราคา " + issued.number() + " ได้รับอนุมัติและออกใบแล้ว");
        return issued;
    }

    /**
     * The CEO's price authority, kept alive after the dual-approval design was reversed (owner
     * ruling, 2026-09-20): sales may still edit price after create (no server-side lock — see
     * {@link DealQuotationDto#priceChangedFromCeo}'s own Javadoc), but if ANY line's price/
     * discount or the header price mode now differs from the CEO's original decision, or any
     * CEO-linked line was removed, then ONLY the CEO may approve — a sales_manager attempting it
     * is refused outright, before any write.
     *
     * <p>Computed FRESH here from {@code quotation} (a {@link #requireQuotation} read, i.e.
     * server truth, never client input) using the EXACT SAME fields the editor's own
     * CEO-comparison UI reads ({@link DealQuotationDto#priceModeChangedFromCeo},
     * {@link DealQuotationItemDto#priceChangedFromCeo}, {@link
     * DealQuotationDto#itemsRemovedFromCeoCount}) — never a flag the client could send instead of
     * a real comparison.
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-123 slice S3 — Step 5 for a PRICING_REQUEST-origin quotation (R9): records what the
    // customer said about an ISSUED document. Deliberately narrower than #requireEditAccessForQuotation
    // (which also lets sales_manager act on ANY deal for edit/submit/cancel): this mirrors {@code
    // CustomerQuotationService#recordOutcome}'s own gate EXACTLY — {@code SALES_ROLES = Set.of("sales")}
    // there, i.e. sales_manager is NOT included, and only the ticket's OWNING rep may record an
    // outcome, full stop. Read from that class directly (not assumed): its #requireEditAccess calls
    // #requireRole(actor, SALES_ROLES) then #requireOwner, with no sales_manager/CEO carve-out
    // anywhere in that path. The new engine's own EDIT_ROLES/APPROVE_ROLES sets are NOT reused here
    // on purpose — recording a customer's outcome is a different action from editing or approving
    // the document, and the legacy behaviour (the ONLY precedent that exists for this action) is
    // sales-owner-only. R8 (one finalized quotation per deal, across BOTH คำขอราคา origins) is
    // enforced via TicketRepository#hasAcceptedQuotation, called under TicketRepository
    // #lockTicketForUpdate — the SAME ticket-row lock CustomerQuotationService#recordOutcome now
    // also takes (see that method's own mirrored comment), so a legacy accept and a new-origin
    // accept on the same ticket's sibling pricing requests fully serialise against each other
    // regardless of which service instance issues the lock.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The only outcomes a client may record here — mirrors {@code
     * CustomerQuotationService#RECORDABLE_OUTCOMES} exactly (EXPIRED is sweep-only). */
    private static final Set<String> RECORDABLE_OUTCOMES =
        Set.of(QuotationStatus.ACCEPTED, QuotationStatus.REJECTED, QuotationStatus.REVISION_REQUESTED);

    /**
     * R9 — records what the customer said about an ISSUED PRICING_REQUEST-origin quotation, and
     * on ACCEPTED, transitions the pricing request {@code QUOTATION_ISSUED -> QUOTATION_ACCEPTED}
     * exactly like {@code CustomerQuotationService#recordOutcome} does for the legacy chain —
     * same event kind, same CEO notification, same "REJECTED/REVISION_REQUESTED do NOT change the
     * pricing request's own status" rule. R8 (one finalized quotation per deal, across BOTH
     * คำขอราคา origins) is the one thing genuinely NEW here: an ACCEPTED outcome is refused with
     * 409 when {@code sales.quotation} already carries an ACCEPTED row for this TICKET under any
     * origin (legacy {@code origin IS NULL} included) — see {@link
     * TicketRepository#hasAcceptedQuotation}'s own Javadoc for why one origin-agnostic query over
     * the shared table is the whole guard.
     *
     * <p>A {@code DEAL_DIRECT} row is refused outright (409) — R10: the direct quotation stays
     * completely separate from the pipeline and has no customer-outcome concept at all.
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
            // Same defensive shape as #createFromPricingRequest/#approveAndIssuePricingRequestOrigin's
            // own guard — only reachable if a deployment wires this bean without the GLA-123
            // setter-injected dependencies.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ฟีเจอร์นี้ยังไม่พร้อมใช้งาน");
        }
        requireOutcomeAccess(actor, quotation);

        // GLA-123 slice S3 MAJOR 3 fix (Opus review against real Postgres, 2026-09-23): this used
        // to lock the ticket row FIRST, then the pricing-request advisory lock — the OPPOSITE
        // order #approveAndIssuePricingRequestOrigin (and CustomerQuotationService#issue) take,
        // and the opposite order this SAME method's own CustomerQuotationService#recordOutcome
        // sibling now takes after its own MAJOR 3 fix. Reproduced as a real Postgres deadlock. The
        // pricing-request advisory lock (borrowed via the existing setter-injected
        // customerQuotations dependency, same as #approveAndIssuePricingRequestOrigin) is taken
        // FIRST, THEN the ticket-row lock — matching every other path. R8 is unaffected: the
        // hasAcceptedQuotation check below still runs with BOTH locks held, which is all it needs.
        customerQuotations.lockPricingRequest(quotation.pricingRequestId());
        tickets.lockTicketForUpdate(quotation.ticketId());

        String outcomeClientRequestId = validateUuid(request.clientRequestId());
        if (outcomeClientRequestId != null) {
            Optional<Long> replay = quotations.findIdByOutcomeClientRequestId(actor.id(), outcomeClientRequestId);
            if (replay.isPresent()) {
                // MINOR-2 fix (Opus review, 2026-09-23) — mirrors CustomerQuotationService#issue's
                // own identical guard: a replay resolving to a DIFFERENT quotation than the one
                // this call actually named is a genuine conflict, not a silent "success" — without
                // this, the caller would get back some OTHER quotation's DTO with no outcome
                // recorded on the one they meant to act on, and no error to notice it by.
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
        if (QuotationStatus.ACCEPTED.equals(request.outcome()) && tickets.hasAcceptedQuotation(fresh.ticketId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ดีลนี้มีใบเสนอราคาที่ลูกค้ายอมรับแล้วฉบับหนึ่ง — ยอมรับซ้ำอีกฉบับไม่ได้ (R8)");
        }

        // MINOR-3 fix (Opus review, 2026-09-23) — see CustomerQuotationService#recordOutcome's
        // identical fix for the full reasoning: V75's own unique index is table-wide, both
        // origins' replay lookups are now origin-scoped, so a cross-engine clientRequestId reuse
        // deterministically hits this constraint instead of replay-matching. Caught and turned
        // into the same clean 409 the wrong-quotation replay guard above already uses.
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

        PricingRequestSummaryDto summary = pricingRequests.findSummary(fresh.pricingRequestId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
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

    /** Mirrors {@code CustomerQuotationService#requireEditAccess} for the outcome action
     * SPECIFICALLY — see this section's own header comment for why {@link #EDIT_ROLES}/{@link
     * #requireEditAccessForQuotation} (which also admits sales_manager on any deal) is NOT reused
     * here. */
    private void requireOutcomeAccess(UserPrincipal actor, DealQuotationDto quotation) {
        if (!"sales".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
        if (ticket.createdById() != actor.id()) {
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

    /**
     * Automatic expiry sweep for a PRICING_REQUEST-origin quotation (D5) — the counterpart
     * {@code CustomerQuotationService#expireOverdueQuotations} already implements for the legacy
     * chain, and the gap that class's own {@code expireOverdueQuotations} SQL comment used to name
     * explicitly (now implemented here, and the design it names no longer exists either).
     * Same shape: a single guarded UPDATE (via {@link DealQuotationRepository
     * #expireOverdueQuotations}), one PR event + one CEO notification per quotation flipped,
     * NEVER changes the pricing request's own status. A {@code DEAL_DIRECT} quotation never
     * expires (D5) and this method never touches one — the repository query is scoped to
     * {@code origin = 'PRICING_REQUEST'}. Wired into the SAME {@link QuotationExpiryWorker}
     * {@code CustomerQuotationService}'s sweep already runs on, not a second scheduler.
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
            });
        }
        return expired.size();
    }

    @Transactional
    public DealQuotationDto cancel(long id, CancelRequest request, UserPrincipal actor) {
        DealQuotationDto quotation = requireQuotation(id);
        requireEditAccessForQuotation(actor, quotation);
        // Opus review (2026-09-15), REQUIRED: the ancestry a rejected-and-resubmitted row can grow
        // is a TREE, not a straight line -- cancelling a DRAFT row that itself has an OPEN child
        // let that child's grandparent look "free" again (hasOpenRevision checks DIRECT children
        // only, and CANCELLED is not open), so the grandparent could mint a SECOND, sibling branch
        // while the first branch (this row's own child) was still alive. Approving one branch's
        // leaf only ever walks UPWARD from that leaf -- it can never reach across to supersede a
        // SIBLING branch -- so both branches could end APPROVED: two independently-approved
        // quotations on the same ticket, proven against real Postgres. Requiring every cancel to
        // be a LEAF (no open child of its own) closes this by induction: a rep can only ever
        // cancel a row whose own subtree is already fully terminal (rejecting a child just
        // reopens it as DRAFT, which is itself "open" and blocks the parent's cancel too, so the
        // whole subtree must be walked down to CANCELLED before the cancel above it succeeds) --
        // so at most one LIVE path (DRAFT/PENDING_APPROVAL/APPROVED) can ever exist through a
        // chain at a time, and approve()'s upward walk is always walking the ONLY live path.
        //
        // Locks the ticket FIRST, same as #requireNoOpenRevision -- without it, a concurrent
        // cancel(id) and submit(id) (minting a child of id) could each read hasOpenRevision
        // against the pre-commit state and both proceed, recreating the exact race this guard
        // exists to close. M3's own precedent (createRevision's identical lock-then-check) is why
        // this is a lock, not just the existence check alone.
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
        requireEditAccessForQuotation(actor, source);
        // GLA-123 slice S1, defence in depth — unreachable today (APPROVED is itself unreachable
        // for this origin, requireApprovedForClone below would already refuse it), but explicit
        // rather than relying on that alone — see #requireStatusMachineEnabled's own Javadoc.
        requireStatusMachineEnabled(source);
        requireApprovedForClone(source, "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        requireNoOpenRevision(source); // locks the ticket
        // Opus review (2026-09-19): RE-CHECK the status AFTER the lock, not just before it. The
        // check above and the lock this line takes are two separate moments -- a concurrent
        // approve() elsewhere (which now supersedes siblings via the one-approved-per-deal sweep)
        // could have superseded THIS source in between, and the lock alone does not roll that
        // back into view: it only guarantees this call now sees whatever committed before it, not
        // that nothing changed since the FIRST read above. Re-fetching after the lock is what
        // actually closes the window; without it, a caller could mint a revision of a row that is
        // no longer the deal's live APPROVED document. Same message as the check above -- this is
        // the same rule, just re-asserted at the moment that actually matters.
        source = requireApprovedForClone(requireQuotation(id),
            "สร้างฉบับแก้ไขได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        long newId = insertRevisionCopyOf(source, actor);
        DealQuotationDto revision = requireQuotation(newId);
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.REVISION_REQUESTED, null, null,
            "สร้างใบเสนอราคาฉบับแก้ไข " + revision.number() + " จาก " + source.number());
        return revision;
    }

    /**
     * GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม): clone an APPROVED direct-deal quotation into
     * a new, INDEPENDENT DRAFT — same deal, next number in the SAME shared numbering family a
     * revision would use (see {@link #insertCopyOf}), everything copied verbatim.
     *
     * <p>⚠️ Owner ruling 2026-09-19 (UPDATES the invariant this Javadoc originally described): a
     * deal may hold only ONE APPROVED {@code DEAL_DIRECT} quotation at a time. Cloning ITSELF
     * still leaves {@code source} exactly as it was — {@code source} stays APPROVED while the
     * clone is only DRAFT/PENDING_APPROVAL, and this method never links through
     * {@code parentQuotationId} (only {@code derivedFromQuotationId}, audit-only — see
     * {@link DealQuotationDtos.DealQuotationDto#derivedFromQuotationId}'s own Javadoc), so the
     * pre-existing ancestor-supersede walk, {@link DealQuotationRepository#hasOpenRevision} and the
     * "แก้" bucket predicate can never reach {@code source} through it. But once the CLONE itself
     * is submitted and approved, {@link #approve}'s same-ticket sweep (a SEPARATE mechanism from
     * the ancestor walk — see that method's own comment) supersedes {@code source} then, exactly
     * as approving a revision or a second independent first-issue quotation would. So: clone
     * creation never supersedes anything; the clone's OWN later approval does.
     *
     * <p>Multiple clones of the same source are allowed (unlike a revision, there is no
     * {@link #requireNoOpenRevision}-style single-open-child guard) — only the ticket-wide advisory
     * lock is taken, so concurrent clones/revisions of the same family still serialise on the
     * shared {base}-{n} counter and can never collide on the UNIQUE {@code number}. Cloning a
     * SUPERSEDED source is refused (409) the same way a non-APPROVED one is — see the status check
     * below — so a clone can only ever be created off a source that is CURRENTLY the deal's one
     * live APPROVED document.
     */
    @Transactional
    public DealQuotationDto createReorder(long id, UserPrincipal actor) {
        DealQuotationDto source = requireQuotation(id);
        requireEditAccessForQuotation(actor, source);
        // GLA-123 slice S1, defence in depth — see #createRevision's identical comment.
        requireStatusMachineEnabled(source);
        requireApprovedForClone(source, "สร้างจากใบเดิมได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        // No requireNoOpenRevision here (deliberately) — multiple clones of the same APPROVED
        // source are allowed, and a clone never contends with an in-progress revision for anything.
        // Still locks the ticket, same advisory lock #requireNoOpenRevision/#createRevision use, so
        // this serialises against a CONCURRENT revision or reorder of the same family before
        // #insertCopyOf computes the next {base}-{n} suffix — without it, two callers could both
        // read the same MAX(quotation_revision_no) and race into the number's UNIQUE constraint.
        quotations.lockTicket(source.ticketId());
        // Opus review (2026-09-19): RE-CHECK the status AFTER the lock -- see
        // #createRevision's own comment on this exact pattern for the full reasoning. A concurrent
        // approve() elsewhere (one-approved-per-deal sweep) could have superseded this source
        // between the check above and the lock actually being acquired here; without re-fetching,
        // this would happily clone an already-SUPERSEDED source. Same message as the check above.
        source = requireApprovedForClone(requireQuotation(id),
            "สร้างจากใบเดิมได้จากใบเสนอราคาที่ได้รับอนุมัติแล้วเท่านั้น");
        long newId = insertReorderCopyOf(source, actor);
        DealQuotationDto reorder = requireQuotation(newId);
        tickets.addEvent(source.ticketId(), actor.id(), actor.name(), TicketEventKind.DEAL_QUOTATION_REORDERED,
            null, null,
            "สร้างใบเสนอราคา " + reorder.number() + " จากใบเดิม " + source.number() + " (สั่งเหมือนเดิม)");
        return reorder;
    }

    /** {@link #createRevision}'s copy step — a new DRAFT LINKED to {@code source} as its revision
     * ({@code parentQuotationId}), which is what makes {@code source} eventually SUPERSEDED once
     * this child reaches APPROVED. Thin wrapper over the shared {@link #insertCopyOf}. */
    private long insertRevisionCopyOf(DealQuotationDto source, UserPrincipal actor) {
        return insertCopyOf(source, actor, source.id(), null);
    }

    /** {@link #createReorder}'s copy step — a new DRAFT carrying NO {@code parentQuotationId} (so
     * clone CREATION never supersedes {@code source} — the clone's own later approval is what
     * does, through {@link #approve}'s separate same-ticket sweep, owner ruling 2026-09-19), only
     * the audit-only {@code derivedFromQuotationId}. Thin wrapper over the shared
     * {@link #insertCopyOf}. */
    private long insertReorderCopyOf(DealQuotationDto source, UserPrincipal actor) {
        return insertCopyOf(source, actor, null, source.id());
    }

    /**
     * The copy step {@link #createRevision}, {@link #submit}'s owner-feedback (2026-09-15)
     * "resubmit after a ตีกลับ mints a new number" path, AND {@link #createReorder} all need: a new
     * DRAFT row, every header field copied VERBATIM (see each parameter's own comment below —
     * unchanged from {@code createRevision}'s original body), its items and item pictures copied,
     * at the next number in the shared {base}-{n} family. Callers are responsible for their OWN
     * precondition (the three paths gate on different source statuses/rules) and for locking the
     * ticket + checking whatever open-revision guard applies BEFORE calling this — all
     * already-serialised requirements this method assumes rather than re-does, so it stays a plain
     * insert with no lock/guard duplicated between callers.
     *
     * <p>{@code parentQuotationId}/{@code derivedFromQuotationId} are the ONE thing that
     * distinguishes a revision from a reorder clone — exactly one of the two is non-null, decided
     * by the caller (see {@link #insertRevisionCopyOf}/{@link #insertReorderCopyOf}). Every other
     * field is copied identically regardless, which is what keeps revision behaviour byte-for-byte
     * unchanged by this refactor.
     */
    private long insertCopyOf(DealQuotationDto source, UserPrincipal actor,
                              Long parentQuotationId, Long derivedFromQuotationId) {
        // Works unchanged for BOTH a post-2026-09-11 parent ("QT-2026-0014-1", revisionNo 1 -> base
        // "QT-2026-0014") and a legacy pre-change parent ("QT-2026-0014" bare, revisionNo 1 -> base
        // itself unchanged) -- see DealQuotationRepository#baseNumber's own Javadoc for why one
        // formula covers both eras with no special-casing here.
        String baseNumber = DealQuotationRepository.baseNumber(source.number(), source.revisionNo());
        // M3 (found while testing the fix above): NOT source.revisionNo() + 1 -- see
        // DealQuotationRepository#nextRevisionNo's own Javadoc for the duplicate-key crash that
        // naive formula still has once a prior revision attempt was cancelled. Shared by revisions
        // AND reorder clones -- GLA-74 part 1 deliberately reuses this SAME ticket-scoped counter
        // (the task's own instruction: "do not re-implement numbering"), so a clone and a revision
        // of the same family can never mint the same suffix.
        int nextRevisionNo = quotations.nextRevisionNo(source.ticketId(), baseNumber);
        String newNumber = DealQuotationRepository.revisionNumber(baseNumber, nextRevisionNo);
        BigDecimal sourceVatRate = WastageCalculator.vatRateFor(source.documentLanguage());
        List<NewItem> items = source.items().stream()
            .map(item -> toNewItemFromDto(item, sourceVatRate, source.documentLanguage())).toList();
        long newId = quotations.insertDraft(new InsertDraftParams(
            source.ticketId(), newNumber, actor.id(), source.salesRepId(),
            source.customerName(), source.customerAddress(), source.customerTaxId(), source.customerPhone(),
            // The source's frozen snapshot, verbatim -- the rep re-chooses (or the ticket's
            // contact re-defaults) only on the copy's own next save through #update.
            new ContactSnapshot(source.contactId(), source.contactName(), source.contactPhone(), source.contactEmail()),
            source.projectName(), source.deptCode(), source.unitCode(), source.offerDate(),
            source.depositPercent(), source.remainderMode(), source.creditDays(), source.validityDays(),
            // V178: the copy inherits the source's validity MODE AND DATE verbatim — a DATE-mode
            // source's own "ภายในวันที่" deadline is a fact about the customer's order, not
            // something that should silently reset to a day count (or a stale date) on copy.
            // source.validityMode() is never null (the repository normalises a stored NULL to
            // DAYS), so resolveValidityMode is not needed on this path — same reasoning as
            // documentLanguage below.
            source.validityMode(), source.validityUntil(),
            // v3b: the copy inherits the source's ภาษาเอกสาร and currency verbatim, like every
            // other header field here — copying an English quotation must not silently reissue it
            // in Thai. source.documentLanguage() is never null (the repository normalises a stored
            // NULL to TH), so resolveDocumentLanguage is not needed on this path.
            source.customerNotes(), source.priceMode(), source.documentLanguage(),
            WastageCalculator.defaultCurrencyFor(source.documentLanguage()),
            // V179 — the copy carries the source's print-name override VERBATIM, same as every
            // other header field here; no re-validation (an id valid at the source's last save
            // stays whatever it was — this mirrors how the source's own contact/rep snapshot is
            // copied without re-checking against a live table).
            source.printedByDisplayId(), source.salesRepDisplayId(),
            // V180/V181 (items 2/4) — the copy inherits the source's honorific flag and
            // full-payment term VERBATIM, the same "copy every header field" rule as everything
            // else in this call — copying must not silently re-add "คุณ" or drop a chosen
            // zero-deposit term the rep already picked on the source document.
            source.omitContactHonorific(), source.fullPaymentTerm(),
            source.subtotalAmount(), parentQuotationId,
            nextRevisionNo, derivedFromQuotationId, items,
            // Owner-directed reversal of F2 (2026-09-26) — the copy inherits the source's manual
            // buyer name VERBATIM, same "copy every header field" rule as everything else here.
            source.orderedByName()));
        // GLA-75: "the copy carries the source's items verbatim" includes their pictures — the new
        // row's items point at the source's (immutable, shared) picture rows, matched by seq.
        // Shared by revisions and reorder clones alike.
        quotations.copyPictureLinks(source.id(), newId);
        return newId;
    }

    /**
     * The APPROVED-only precondition {@link #createRevision} and {@link #createReorder} both
     * check -- BEFORE taking the ticket lock (an early, cheap rejection) AND AGAIN right after
     * (the check that actually matters, per each caller's own comment on why a lock alone does
     * not re-validate a fact read before it was acquired). One method so the message can never
     * drift between the two call sites, or between a caller's own pre-lock and post-lock checks.
     */
    private DealQuotationDto requireApprovedForClone(DealQuotationDto quotation, String actionMessage) {
        if (!QuotationStatus.APPROVED.equals(quotation.docStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                actionMessage + " (ปัจจุบัน: " + quotation.docStatus() + ")");
        }
        return quotation;
    }

    /**
     * M3: two concurrent callers of {@link #insertRevisionCopyOf} against the SAME source both
     * compute the SAME deterministic child number ({base}-{revisionNo+1}, from the SAME source
     * row), and {@code sales.quotation.number} is UNIQUE (V6) -- so the second INSERT threw
     * DuplicateKeyException, surfaced as a bare 500 with no Thai message. Locking the ticket first
     * serialises callers for it, so the SECOND caller (once it acquires the lock after the first
     * commits) then observes the first caller's already-inserted child in
     * {@link DealQuotationRepository#hasOpenRevision} and 409s with an intelligible reason instead
     * of racing into the unique-index violation. Shared by {@link #createRevision} and
     * {@link #submit}'s resubmit-after-rejection path — both mint a revision the same way.
     */
    private void requireNoOpenRevision(DealQuotationDto source) {
        quotations.lockTicket(source.ticketId());
        if (quotations.hasOpenRevision(source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว");
        }
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

    /** Resolves the approver's signature bytes and hands everything to the adapter, which is
     * otherwise a pure function.
     *
     * <p>V175 (owner ruling 2026-09-13, "already-sent quotations never change afterwards"): the
     * bytes come from the snapshot frozen at approval — {@link DealQuotationRepository#approve}
     * writes it in the same statement as the status change — and NEVER from the live
     * {@code hr.employee_signature} row when a snapshot exists, even one with no image (the
     * approver had none on file then, so the document stays unsigned). The live read below is only
     * a defensive fallback for an approved row with no snapshot (V175's backfill, owner ruling
     * 2026-09-13 "Freeze them unsigned.", gives every pre-V175 approval one); a DRAFT/PENDING
     * row has {@code approvedById == null} and so gets no signature either way.
     *
     * <p>GLA-123 slice S2 REWORK (owner reversed the dual-approval design, 2026-09-20): this read
     * is now shared verbatim by BOTH origins — whoever approves a PRICING_REQUEST-origin
     * quotation freezes into the SAME single snapshot row DEAL_DIRECT's own {@code #approve}
     * writes, so there is no origin branching left here at all. */
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
     * <p>Opus review fix (2026-09-14): {@code name} used to come from the ticket's OWN frozen
     * {@code customer_name} column unconditionally, on the reasoning that a deal whose customer
     * row has been deleted out from under it should still print the name it was created with
     * instead of a blank. That reasoning is right for the deleted-row case, but it silently
     * defeated the owner's actual request ("sometimes there's a typo in the name so they should
     * be able to correct it", 2026-09-14, {@code CustomerDetailsFields}'s new ชื่อลูกค้า field):
     * a correction saved to {@code customers.customer.name} never reached the ticket's own frozen
     * column (no cascade exists, and none should — see V167's header on why an already-issued
     * document must stay frozen), so the printed name never changed. Now takes the LIVE
     * {@code customer.name()} whenever the customer row still exists (the normal case, and the
     * one this fix is FOR), falling back to the ticket's frozen name only when it does not — same
     * fallback shape address/taxId/phone already use just below.
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
     * Item 2 ("ไม่เติม “คุณ”", V180, owner ruling 2026-09-16): the nullable {@code Boolean} on the
     * wire resolves to {@code false} (UNticked, today's only behaviour) whenever it is absent —
     * shared by {@link #create}/{@link #update} so "missing means unticked" can never drift
     * between the two.
     */
    private boolean resolveOmitContactHonorific(Boolean omitContactHonorific) {
        return Boolean.TRUE.equals(omitContactHonorific);
    }

    /**
     * Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16): {@code true} only for an EXPLICIT
     * {@code depositPercent == 0}. A {@code null} depositPercent is NOT "no deposit" — it defaults
     * to 30% exactly like {@code DealQuotationRenderAdapter#depositLine}'s own reasoning — so a
     * quotation that simply never set a percent must not have its remainderMode/creditDays cleared
     * or accept a fullPaymentTerm. Shared by {@link #create}/{@link #update} so the "0% is the ONE
     * signal for no-deposit" rule can never drift between the two.
     */
    private boolean isZeroDeposit(Integer depositPercent) {
        return depositPercent != null && depositPercent == 0;
    }

    /**
     * Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16) — a {@code fullPaymentTerm} only ever
     * applies to a zero-deposit document; on any other (including a null/unset depositPercent,
     * which defaults to 30% — see {@link #isZeroDeposit}) it is forced back to null, so a rep who
     * unticks "ไม่รับมัดจำ" and re-enters an ordinary percentage can never leave a stale term
     * attached. On a genuinely zero-deposit document, null/blank passes through unchanged — a
     * DRAFT may be saved before the rep has picked one; only {@link #submit} refuses to advance it.
     * An unrecognised code never reaches here: {@code @Pattern} on the request field already 400s
     * it before bean validation lets the request through.
     */
    private String resolveFullPaymentTerm(Integer depositPercent, String fullPaymentTerm) {
        return isZeroDeposit(depositPercent) ? blankToNull(fullPaymentTerm) : null;
    }

    /**
     * Wording-scan fix 6 (2026-09-17) — the save-time half of the credit-days rule; the submit-time
     * half (a BLANK value, once {@code remainderMode} is CREDIT, may not advance past DRAFT) lives
     * inline in {@link #submit}, matching {@link #resolveFullPaymentTerm}'s own "create/update
     * permissive, submit strict" split for the zero-deposit payment term — the precedent this fix
     * was asked to follow. The two halves differ in ONE way {@code fullPaymentTerm} has no
     * equivalent for: an explicit non-blank but INVALID value (0 or negative) is refused HERE,
     * immediately, on every save — there is no "let a wrong number sit in a draft" case to permit,
     * unlike a merely blank one, which still may (create/update never call this for a blank value —
     * see the {@code creditDays <= 0} guard below, which a null short-circuits past).
     */
    private void requireValidCreditDays(String remainderMode, Integer creditDays) {
        if ("CREDIT".equals(remainderMode) && creditDays != null && creditDays <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน");
        }
    }

    /** V178: {@code null}/blank reads as DAYS — today's behaviour, and what every pre-V178 row
     * (and the whole legacy customer-quotation path) stores. */
    private String resolveValidityMode(String validityMode) {
        return isBlank(validityMode)
            ? WastageCalculator.VALIDITY_MODE_DAYS : validityMode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * V178: DAYS mode always stores {@code validity_until = NULL} — the day count in
     * {@code validity_days} is the only thing that matters, exactly as before this change.
     *
     * <p>DATE mode is refused outright on a document with NO special pricing (owner ruling
     * 2026-09-14 — a price-validity DEADLINE only makes sense when there is a special price or
     * discount to protect; see {@link DealQuotationRenderAdapter#hasSpecialPricing}'s own rule).
     * Otherwise it requires a date, and refuses one that predates the quotation's own document
     * date ({@code quotationDate} — today on create, the frozen creation date on update): a
     * validity deadline earlier than the document itself is never a fact a rep meant to type.
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
     * V179 (owner feedback #4, 2026-09-14) — validates a {@code printedByDisplayId}/
     * {@code salesRepDisplayId} candidate before it is ever written. Null passes straight through
     * (it means "use the real name", the default). A non-null id must be in the SAME eligible
     * union {@link #findQuotationDisplayNameOptions} lists — active AND (a sales-division member
     * OR a {@code can_create_quotation} grant holder) — checked by
     * {@link DealQuotationRepository#isEligibleQuotationDisplayName}, which shares its predicate
     * with the options query so the two can never disagree. Reused by both {@link #create} and
     * {@link #update} so the rule is defined exactly once.
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
     * V179 — the option list for the ผู้พิมพ์/พนักงานขาย print-name selectors. Gated the same as
     * every other quotation WRITE action ({@link #EDIT_ROLES} or the live
     * {@code can_create_quotation} grant) rather than {@link #requireEditAccess}'s per-deal
     * ownership rule: this list carries no single deal's context (it backs a dropdown that can be
     * opened before a specific quotation is even loaded), and every caller who may set the field
     * on SOME deal is entitled to see who they may choose from.
     */
    public List<CommissionRepOptionDto> findQuotationDisplayNameOptions(UserPrincipal actor) {
        if (!EDIT_ROLES.contains(actor.role()) && !hasQuotationGrant(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return quotations.findEligibleQuotationDisplayNameOptions(DivisionAccessPolicy.SALES_DIVISION_CODE);
    }

    // v3b's requirePriceModeAvailableInLanguage (SPECIAL_SQM refused on English) is GONE — owner
    // decision 2026-09-13: an English document may be priced per square metre. SPECIAL_SQM now means
    // "the rep states one price per sqm", and what that price means follows the document's
    // language (WastageCalculator#isEnglishPerSqm): VAT-inclusive baht turned into a per-piece net
    // on Thai (unchanged), USD per sqm with the quantity printed in sqm on English.

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
        requirePriceValidForType(input, lineType, priceMode, documentLanguage, rowNumber);
        // Wording-scan fix 3 (2026-09-17): an invalid lead-time VALUE (min < 1, or max < min) is
        // refused unconditionally, the same "always, including the lenient preview" reasoning as
        // requirePriceValidForType above — these are wrong values, not an incomplete row, so there
        // is no honest number to preview either. Applies to TILE and PLAIN rows alike (an
        // ADJUSTMENT row has no lead-time concept at all — see WastageCalculator.LINE_TYPE_ADJUSTMENT).
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

    private NewItem buildTileItem(ItemInput input, Integer rowNumber, String priceMode,
                                  String documentLanguage, BigDecimal vatRate) {
        // Owner decision 2026-09-13: SPECIAL_SQM on an ENGLISH document — the rep's USD/sqm IS the
        // printed Unit price and Net price, the quantity is boxes × sqm/box, and there is no VAT to
        // take out. Every Thai path below is untouched when this is false.
        boolean perSqm = WastageCalculator.isEnglishPerSqm(documentLanguage, priceMode);
        // Fetched ONCE and threaded through both #resolveSqmPerPiece AND the NewItem below --
        // #resolveSqmPerPiece used to run this same catalog lookup on its own; now the catalogue's
        // width_mm/height_mm also ride along so DealQuotationLines#sizeLine can print the face size
        // in centimetres from unambiguous millimetres (owner ruling 2026-09-12) without a second
        // round trip for the same catalogPriceId. See NewItem#catalogWidthMm's own comment.
        CatalogSqmBasis catalogBasis = resolveCatalogBasis(input);
        BigDecimal sqmPerPiece = resolveSqmPerPiece(input, catalogBasis);
        if (rowNumber != null) {
            requireItemComplete(rowNumber, input, sqmPerPiece, perSqm);
        }
        // Owner-approved "sell loose pieces" (2026-09-16): null reads as true, today's only
        // behaviour. Resolved ONCE here — WastageCalculator.Input, the stored NewItem and the
        // printed line all read this same boolean, never input.roundToFullBox() directly again.
        boolean roundToFullBox = input.roundToFullBox() == null || input.roundToFullBox();
        // Option B (owner decision, 2026-09-16): ตร.ม./กล่อง is now OPTIONAL for English per-sqm.
        // WITH a box area, behaviour is unchanged byte-for-byte — both box figures are still
        // required and the quantity can only ever be a whole number of boxes, so loose pieces is
        // still refused. WITHOUT one, the printed sqm quantity instead derives from piecesFinal ×
        // sqmPerPiece (DealQuotationLines#tilePrint), which has no box-count restriction at all —
        // piecesPerBox becomes optional too, and loose pieces is allowed exactly like any other
        // tile row.
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
        // Wording-scan fix 5 (2026-09-17): PIECES wastage must be a whole number of แผ่น — a
        // fractional value (e.g. 0.5) saved today, but WastageCalculator#applyWastage's PIECES
        // branch silently ROUNDS it (HALF_UP) for the arithmetic while the STORED/PRINTED
        // wastageValue stays the untouched fraction, so the printed "+ เผื่อ 0.5 แผ่น" disagrees
        // with the piece count the document actually adds. Refused here, before the value ever
        // reaches WastageCalculator, so it can never be stored at all. PERCENT wastage is
        // untouched — a percentage genuinely can be fractional (2.5%).
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
        // Wording-scan fix 4 (2026-09-17): an AREA-mode row whose typed area rounds to ZERO pieces
        // before wastage saves today ("(พื้นที่ 0.01 ตร.ม.ๆละ 2.78 แผ่น รวม 0 แผ่น ...)") — a document
        // line selling nothing. Checked on every path that reaches this point, the preview
        // (rowNumber == null) included, consistently with how #requirePriceValidForType already
        // behaves on the preview.
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
        // R-D input scale (review fix, 2026-09-15): persist the same 2dp list price
        // WastageCalculator#calculate computed netUnitPrice/lineAmount from (its own listPrice =
        // round2(unitPrice)) — sales.quotation_item.unit_price is NUMERIC(14,2) (V49), so storing
        // the raw, unrounded input here while the money math used the rounded value would let the
        // stored row disagree with its own printed amount on a 3dp-or-finer typed price. Null-safe
        // because perSqm rows legitimately omit unitPrice (specialPriceSqm stands in below).
        BigDecimal unitPrice = input.unitPrice() == null ? null : money2(input.unitPrice());
        BigDecimal perSqmLineAmount = null;
        if (perSqm) {
            specialPriceSqm = money2(input.specialPriceSqm());
            unitPrice = specialPriceSqm;
            netUnitPrice = specialPriceSqm;
            discountPct = null;
            BigDecimal qtySqm;
            try {
                // Same "amount = price/sqm × printed sqm quantity" formula either way (v3b, owner
                // decision 2026-09-13) — only the quantity's OWN derivation differs by hasBoxArea.
                qtySqm = hasBoxArea
                    ? WastageCalculator.sqmQuantityFromBoxes(result.boxes(), input.sqmPerBox())
                    : WastageCalculator.sqmQuantityFromPieces(result.piecesFinal(), sqmPerPiece);
            } catch (IllegalArgumentException e) {
                if (!hasBoxArea) {
                    // F5.1 fix (2026-09-16 review): without a box area, the ONLY way
                    // sqmQuantityFromPieces throws here is a missing/non-positive ตร.ม./แผ่น -- and
                    // this branch is reachable ONLY from the LENIENT calculate-line preview
                    // (rowNumber == null), since requireItemComplete (which names this exact field
                    // as "ตร.ม./แผ่น" on create/update) is skipped there. Before this fix, the raw
                    // exception message leaked verbatim as "ข้อมูลรายการไม่ถูกต้อง: sqmPerPiece is
                    // required for a per-sqm quantity, got: null" -- an English, implementation-detail
                    // string on a screen a sales rep sees while typing. Reuse the same Thai
                    // field-name wording the save/create path already uses instead.
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
            input.sqmPerBox(), roundToFullBox);
    }

    /**
     * S2 — a PLAIN row: description, quantity, unit, unit price, and NONE of the tile machinery.
     * No wastage, no ตร.ม./แผ่น, no แผ่น/กล่อง, no auto-composed description, no catalog link.
     * Freight (1 JOB), Mapei consumables (Bags/Barrels), the cut service, sanitary-ware ชุด rows.
     *
     * <p>{@code amount = round2(list × quantity × (1 − pct/100))} — R-D (quotation arithmetic
     * reconciliation, 2026-09-15), a single rounding of the unrounded product rather than
     * {@code round2(netUnitPrice × quantity)}, which double-rounds through the already-2dp net
     * unit price and drifts a satang on some rows (D1 / QN6900971-4, rows 5.3-5.7:
     * round2(netUnitPrice × qty) gives 35,799.64 against the printed 35,799.63). {@code list} here
     * is the input price PRE-ROUNDED to 2dp (review fix, 2026-09-15) — {@code
     * sales.quotation_item.unit_price} is NUMERIC(14,2) (V49), so a finer-than-2dp typed price
     * would otherwise be stored at 2dp while the amount kept computing from the unrounded value,
     * and the stored row could never reproduce its own printed amount. netUnitPrice below stays
     * the rounded DISPLAY figure printed in the ราคา/คงเหลือ column; the row's own discount is
     * applied to the unit price exactly as {@code WastageCalculator#calculate} does for a tile —
     * the discount is normally absent, which prints "Net".
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
        // R-D input scale (review fix, 2026-09-15): pre-round the LIST price to 2dp before any
        // multiplication AND persist that same 2dp value — see WastageCalculator#calculate's
        // matching comment. sales.quotation_item.unit_price is NUMERIC(14,2) (V49); storing the
        // unrounded input.unitPrice() while computing from it too would let the stored row and
        // the computed amount silently disagree on a 3dp-or-finer typed price.
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
            item.sqmPerBox(), item.roundToFullBox());
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
     *
     * <p>Takes the {@link CatalogSqmBasis} as a PARAMETER (see {@link #resolveCatalogBasis}) rather
     * than fetching it itself, so {@link #buildTileItem} can fetch it ONCE and also thread its
     * {@code width_mm}/{@code height_mm} through to {@code NewItem} for {@code
     * DealQuotationLines#sizeLine} (owner ruling 2026-09-12, "normalize it in the database...") —
     * without this it would need its own second {@code catalog.findSqmBasis} round trip for the
     * same {@code catalogPriceId}.
     */
    private BigDecimal resolveSqmPerPiece(ItemInput input, CatalogSqmBasis basis) {
        if (input.sqmPerPiece() != null) {
            return input.sqmPerPiece();
        }
        return basis == null ? null : sqmPerPieceFromCatalog(basis);
    }

    /** The catalogue basis lookup {@link #resolveSqmPerPiece} used to perform on its own —
     * extracted so {@link #buildTileItem} can fetch it exactly once and reuse it both for
     * ตร.ม./แผ่น AND for {@code DealQuotationLines#sizeLine}'s face-size-in-centimetres. {@code
     * null} when the item carries no catalog link at all (nothing to look up) or the linked row
     * does not exist. */
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

    /** Owner decision 2026-09-13, narrowed by Option B (2026-09-16): an English per-sqm row WITH a
     * box area (ตร.ม./กล่อง filled) needs BOTH box figures — its quantity is boxes × sqm/box, and
     * there is deliberately no pieces fallback for that combination. Only called by
     * {@link #buildTileItem} when {@code hasBoxArea} is true; a row with NO box area at all skips
     * this entirely (piecesPerBox becomes optional too — see {@link #requireItemComplete}). A
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
                                          String documentLanguage, Integer rowNumber) {
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
     * Wording-scan fix 3 (2026-09-17) — a lead-time range is only validated once it is actually
     * ENTERED (both {@code min}/{@code max} present): a lone value, or neither, is an incompleteness
     * question for {@link #requireItemComplete}/{@link #requireEveryTileItemHasALeadTime} to answer
     * (submit-only, unchanged by this fix), not an invalid-value one. Once both are present:
     * {@code min} must be at least 1 day (a lead time of 0 or negative days is not a real range) and
     * {@code max} must be at least {@code min} (so "90-75" cannot be saved as a range that runs
     * backwards). Refused on save — these are wrong VALUES, not a gap to fill in later — with the
     * exact message the frontend validator mirrors, so a rep sees the same sentence whichever side
     * catches it.
     *
     * <p>A stored row that predates this fix (0, or min &gt; max) is untouched by this check — it
     * only ever runs on a freshly-submitted {@code ItemInput}, never re-validates an
     * already-persisted {@link DealQuotationItemDto} — so an old row with such values still RENDERS
     * exactly as it always did (see {@code DealQuotationRenderAdapter#flushLeadTimeGroup}, which
     * prints whatever it is given rather than validating it).
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
        // Option B (owner decision, 2026-09-16): แผ่น/กล่อง stays required for every row EXCEPT an
        // English per-sqm row with no box area at all — that combination needs no box multiple
        // (its quantity derives from piecesFinal × sqmPerPiece instead, see
        // DealQuotationLines#tilePrint), exactly like any other tile row without a pieces-per-box.
        boolean hasBoxArea = input.sqmPerBox() != null && input.sqmPerBox().signum() > 0;
        boolean piecesPerBoxOptional = perSqm && !hasBoxArea;
        if (!piecesPerBoxOptional && (input.piecesPerBox() == null || input.piecesPerBox() < 1)) {
            missing.add("จำนวนแผ่นต่อกล่อง");
        }
        if (resolvedSqmPerPiece == null || resolvedSqmPerPiece.signum() <= 0) missing.add("ตร.ม./แผ่น");
        if (perSqm) {
            // ตร.ม./กล่อง itself is now OPTIONAL (Option B) — no separate check here. WITH one,
            // piecesPerBox is still required (the check above), and #buildTileItem's hasBoxArea
            // branch separately requires it be present too, refusing a partially-filled pair.
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
     * Owner feedback #7 (2026-09-14): a deliberate sales-workflow rule change, submit-only. Every
     * TILE row ({@code lineType} null or {@link WastageCalculator#LINE_TYPE_TILE}) must carry both
     * {@code leadTimeMinDays} and {@code leadTimeMaxDays} before the document can be submitted for
     * approval — the printed remark 3 used to fall back to a China/Thailand default that was wrong
     * for most real shipments; a later same-day owner request ("if ระยะเวลานำเข้า is not chosen
     * remove that from the หมายเหตุ") replaced that fallback with dropping the whole line
     * entirely (see {@code DealQuotationRenderAdapter#dropLeadTimeLineAndRenumber}), so a rep must
     * actually enter a lead time rather than let the document print a plausible-looking but false
     * one, or silently ship the document one remark shorter. PLAIN and ADJUSTMENT rows are exempt
     * from THIS gate — an ADJUSTMENT (ส่วนลดพิเศษ) row still has no lead-time concept at all (it
     * cannot "arrive"), but a PLAIN row (สินค้า/บริการอื่น — sanitaryware) CAN carry one since D1
     * (owner decision, 2026-09-16: her QN6900971-4 prints "ระยะเวลานำเข้า 75-90 วัน" against exactly
     * such a row) — it is simply never REQUIRED the way a TILE row's is, so a rep who leaves it
     * blank on a PLAIN row can still submit. A DRAFT may still be saved with no lead times on any
     * row type; this gate is submit only.
     */
    private void requireEveryTileItemHasALeadTime(List<DealQuotationItemDto> items) {
        List<String> missingSeqs = new ArrayList<>();
        for (DealQuotationItemDto item : items) {
            boolean tile = item.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(item.lineType());
            if (tile && (item.leadTimeMinDays() == null || item.leadTimeMaxDays() == null)) {
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
        // ตร.ม./กล่อง itself is now OPTIONAL (Option B) — no check here; a partially-filled pair
        // (sqmPerBox set, piecesPerBox blank) is already caught by the piecesPerBoxOptional branch
        // above, since hasBoxArea alone does not exempt piecesPerBox.
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
                item.piecesFinal(), item.piecesPerBox(), item.boxes(), item.sqmPerBox(), item.quantity(), item.unit(),
                item.specialPriceSqm(), item.roundToFullBox())
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
            .withSqmPerBox(item.sqmPerBox()).withRoundToFullBox(item.roundToFullBox());
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
        // MAJOR-3 fix (Opus re-review, 2026-09-20) — origin-aware, mirroring requireViewAccess's
        // own branch below: a real-DB probe found an `account` holder of the `can_create_quotation`
        // grant sailing through update/cancel/restore (the grant bypasses EDIT_ROLES entirely in
        // #requireEditAccess below) while GET 403s for the same actor under M3's own narrower view
        // gate — a write-without-read hole that let them read every ceo* field straight out of the
        // write response, since #update/#restoreRemovedItem both return #requireQuotation(id) with
        // no separate view check. EDIT_ROLES itself (sales, sales_manager) is left EXACTLY as-is
        // per the owner's own ruling on sales_manager's edit rights for this origin (Round 3
        // MINOR) — only the quotation-grant bypass and the "any deal regardless of role" shortcut
        // it created are removed for PRICING_REQUEST rows. Covers EVERY write path that reaches
        // this method: update (fixed below to call this instead of the raw ticket-only check),
        // submit/cancel/createRevision/createReorder, and every picture endpoint (via
        // #requireEditablePicture) — restoreRemovedItem already calls this too.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            if (!EDIT_ROLES.contains(actor.role())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
            if ("sales_manager".equals(actor.role())) {
                return; // any deal — same DEAL_DIRECT rule this role already gets, untouched.
            }
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            if (ticket.createdById() != actor.id()) {
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
        // GLA-123 slice S1 fix (Opus review M3) — origin-aware: a PRICING_REQUEST row uses the
        // NARROWER CustomerQuotationService-shaped gate (no account, no quotation-grant bypass),
        // checked FIRST and returning early so the broader DEAL_DIRECT rules below never apply to
        // it. Covers get/render/download/pictures — every read here funnels through this method.
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
            if (ticket.createdById() != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
            }
        }
    }

    /**
     * GLA-123 slice S3 BLOCKER-A fix (Opus review against real Postgres, 2026-09-23): the boolean
     * form of {@link #requireViewAccess}'s PRICING_REQUEST branch, factored out so {@link
     * #listForTicket} can FILTER a mixed-origin list with the exact same rule this method already
     * enforces for a single row — rather than duplicating the role/owner check a second time (the
     * duplication is exactly how {@code listForTicket} ended up leaking CEO price/discount to
     * import/account/grant-holders in the first place: it had its OWN, broader gate at the
     * method's entry — {@link #VIEW_ROLES} plus a full {@link #hasQuotationGrant} bypass — and
     * nothing origin-aware downstream of it).
     *
     * <p>On this origin, {@link DealQuotationItemDto#unitPrice}/{@code discountPct}/{@code
     * netUnitPrice} ARE the CEO's approved price and discount (not a rep's own typed price, as on
     * a DEAL_DIRECT row) — two prior owner rulings (S1 review MAJOR-3, then the "block import"
     * ruling) restrict reading them to {@link #PRICING_REQUEST_VIEW_ROLES} (sales-owner,
     * sales_manager, ceo) with NO {@link #hasQuotationGrant} bypass, unlike every other role/read
     * gate on this class.
     */
    private boolean canViewPricingRequestOriginRow(UserPrincipal actor, DealQuotationDto quotation) {
        if (!PRICING_REQUEST_VIEW_ROLES.contains(actor.role())) {
            return false;
        }
        if ("sales".equals(actor.role())) {
            TicketSummaryDto ticket = requireTicketSummary(quotation.ticketId());
            return ticket.createdById() == actor.id();
        }
        return true;
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
        DealQuotationDto quotation = quotations.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคานี้"));
        // M4(d) fix (Opus review, 2026-09-20) — the ONE single-row read path every other method in
        // this class funnels through (get/create/update/restore/cancel/...), so this is the one
        // place that needs to populate #removedCeoItems; see that field's own Javadoc for why it
        // is a separate query rather than baked into #findById's own SELECT.
        if ("PRICING_REQUEST".equals(quotation.origin())) {
            quotation = quotation.withRemovedCeoItems(quotations.findRemovedLinkedItems(id));
        }
        return quotation;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** GLA-123 slice S3 — mirrors {@code CustomerQuotationService#validateUuid} exactly, needed
     * here for the first time by {@link #recordOutcome}'s own {@code clientRequestId} replay
     * guard (no other method on this class validates one today). */
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

    private String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
