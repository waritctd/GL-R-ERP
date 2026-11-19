package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.customerquotation.DiscountApprovalRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for the CEO price-mode picker (NET / SPECIAL_SQM / DIRECT_NET) on
 * {@code sales.pricing_decision} (+ {@code _item}), the mandatory CEO-only stripping of every new
 * field for any other role that can reach {@link PricingDecisionService#get}/{@link
 * PricingDecisionService#list} (import), the auto-calculated list price, "ปรับราคาเอง"
 * kept as the NET list-price override, mode-switch net recompute, no silent success on a no-op
 * save, oversized/scale validation, explicit clear
 * semantics, and a real customer-quotation IT proving a CEO discount never raises a
 * discount-approval request.
 *
 * <p>Setup mirrors {@link PricingDecisionIntegrationTest} (same fixtures, same helper shapes) —
 * duplicated here rather than shared, to avoid coupling this suite's lifecycle to an
 * already-large existing file. {@link #twoItemPricingRequest()}'s items are the new-form
 * shape (PER_PIECE, {@code sqmPerPiece} set) — the ONLY shape the mode picker is
 * eligible for.
 */
class PricingDecisionCeoPriceModeIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private CustomerQuotationService quotationService;
    private TicketRepository tickets;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal accountActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-decision-ceo-mode-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage,
            factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        PricingDecisionRepository decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        quotationService = new CustomerQuotationService(new CustomerQuotationRepository(jdbc), pricingRequests,
            decisionRepository, tickets, ticketService, customers, new QuotationRenderer(), notifications,
            new DiscountApprovalRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย CEOP", "sales-ceop@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า CEOP", "import-ceop@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร CEOP", "ceo-ceop@glr.co.th", "MD", "ผู้บริหาร");
        long salesManagerId = createEmployee(employees, "ผจก.ขาย CEOP", "sm-ceop@glr.co.th", "SALES", "ฝ่ายขาย");
        long accountId = createEmployee(employees, "บัญชี CEOP", "acct-ceop@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        accountActor = actor(accountId, "account");

        catalogProductIdFactoryA = insertCatalogProduct("Factory A-CEOP", "IT", "TEST-A-CEOP-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B-CEOP", "IT", "TEST-B-CEOP-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        // The "starts uncostable" fixture the override/approve ITs below need is a FREE-TEXT
        // line (see freeTextUncostableItem): a null catalogue thickness does not make
        // a line uncostable (resolveThicknessMm falls back to the Sales value), so the line links no
        // catalog PRODUCT — it is uncostable for lack of a resolvable origin country instead.
        // The factory ROW must still exist, though: costShipment's factoryConfigs.findByName (an
        // RFQ-factory existence check, price_catalog.factories) runs per shipment BEFORE the per-item
        // set-aside and 422s on an unknown factory. Seed just that row (its product is never linked).
        insertCatalogProduct("Factory Uncostable-CEOP", "IT", "TEST-UNCOST-CEOP-001",
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", null);

        CustomerDto customer = customers.create(
            "บริษัท CEO Pricing จำกัด", "0100000000099", "999 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0099");
        ProjectDto project = projects.create(customer.id(), "โครงการ CEO Pricing");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล CEO Pricing", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A-CEOP", "Factory A-CEOP"),
                    ticketItem("Cotto", "Tile B-CEOP", "Factory B-CEOP"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The auto-calculated formula price is a REAL stored value (list_unit_price),
    // not a placeholder — NET with zero discount must approve without the CEO typing anything.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void listUnitPrice_isAutoPopulatedAtStartReview_fromTheFormulaPrice_notAPlaceholder() {
        PricingDecisionDto decision = startNewFormReview();
        for (PricingDecisionItemDto item : decision.items()) {
            assertThat(item.listUnitPrice()).isNotNull();
            assertThat(item.listUnitPrice()).isEqualByComparingTo(
                item.proposedSellingPricePerRequestedUnit().setScale(2, RoundingMode.HALF_UP));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // "ปรับราคาเอง" only ever clears the uncosted gate for
    // NET (or no mode chosen yet) -- DIRECT_NET/SPECIAL_SQM never consult the override at all, so
    // it must never fake-clear their gate. Reproduced end to end against a genuine new-form
    // decision (an uncosted item, via the same oneItemUncostedSubmittedCosting fixture the
    // deriveListAndNet real-DB IT above uses).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_uncostedSpecialSqmItem_withOverride_isStillBlocked_reviewMinor1Probe() {
        long pricingRequestId = oneItemUncostedSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);

        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "SPECIAL_SQM", List.of(specialSqmItem(item.id(), new BigDecimal("1350")))), ceoActor);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(overrideItem(item.id(), new BigDecimal("300"),
                "ทดสอบ probe: override ไม่ควรปิด uncosted gate ของ SPECIAL_SQM"))), ceoActor);

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    // Net derivation per mode (reuses WastageCalculator, never reimplements it)

    @Test
    void net_derivesFromListPriceAndDiscount_andFreezesOnApprove() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        // list_unit_price is server-derived -- pin a round fixture value for both
        // items so the discount arithmetic below is exact. itemB gets NO discount sent at all;
        // it still ends up priced because setting the mode recomputes EVERY item's net from its
        // own stored list price (recomputeNetsForModeSwitch), not just the items
        // named in this call.
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        setListUnitPriceForTest(itemB.id(), new BigDecimal("500"));

        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("10")))),
            ceoActor);

        assertThat(updated.priceMode()).isEqualTo("NET");
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        // round2(1000 * (1 - 10/100)) = 900.00 -- WastageCalculator#calculate's own NET formula.
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo("900.00");
        PricingDecisionItemDto updatedB = itemById(updated, itemB.id());
        // discount omitted -> defaults to 0 -> net == list.
        assertThat(updatedB.netUnitPrice()).isEqualByComparingTo("500.00");

        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        for (PricingDecisionItemDto item : approved.items()) {
            assertThat(item.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(item.netUnitPrice());
            assertThat(item.minimumSellingPricePerRequestedUnit()).isEqualByComparingTo(item.netUnitPrice());
            assertThat(item.approvedMarginPct()).isNull();
        }
    }

    @Test
    void specialSqm_usesWastageCalculatorNetPerPieceFromSpecialSqm() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        assertThat(itemA.sqmPerPiece()).isEqualByComparingTo("0.36");

        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "SPECIAL_SQM", List.of(specialSqmItem(itemA.id(), new BigDecimal("1350")))),
            ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        BigDecimal expected = WastageCalculator.netPerPieceFromSpecialSqm(new BigDecimal("1350"), new BigDecimal("0.36"));
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo(expected);
        // Pinned sample (WastageCalculatorTest): 1350 @ 0.36 sqm/piece -> 453.84.
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo("453.84");
    }

    @Test
    void directNet_usesTypedValueRoundedToTwoDp() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);

        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "DIRECT_NET", List.of(directNetItem(itemA.id(), new BigDecimal("777.005")))),
            ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo(new BigDecimal("777.005").setScale(2, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Switching the mode recomputes every item's net from ITS OWN stored
    // inputs for the NEW mode, never leaving a stale figure from the old mode.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void modeSwitch_probeScenario_net1000at10pct_thenDirectNetWithNoInput_neverFreezes900() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        setListUnitPriceForTest(itemB.id(), new BigDecimal("1000"));

        PricingDecisionDto net = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(
                discountItem(itemA.id(), new BigDecimal("10")),
                discountItem(itemB.id(), new BigDecimal("10")))),
            ceoActor);
        assertThat(itemById(net, itemA.id()).netUnitPrice()).isEqualByComparingTo("900.00");

        // Switch to DIRECT_NET with NO item touched -- the review's own probe scenario.
        PricingDecisionDto switched = decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "DIRECT_NET", List.of()), ceoActor);
        for (PricingDecisionItemDto item : switched.items()) {
            // Never the OLD NET figure (900.00) -- the item has no direct_net_price yet, so its net must be NULL, not a
            // stale carry-over.
            assertThat(item.netUnitPrice()).isNull();
        }

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    // Legacy decisions keep today's behaviour exactly

    // AUTHZ (mandatory, wrong-way-round, real DB, through the real service): CEO-only fields must be null for
    // import on get()/list(), even though import CAN see cost/margin.

    @Test
    void ceoOnlyFields_areStrippedForImport_onGetAndList_butVisibleForCeo() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("10")))),
            ceoActor);

        // CEO sees everything.
        PricingDecisionDto asCeo = decisionService.get(decision.id(), ceoActor);
        assertThat(asCeo.priceMode()).isEqualTo("NET");
        PricingDecisionItemDto ceoItemA = itemById(asCeo, itemA.id());
        assertThat(ceoItemA.listUnitPrice()).isEqualByComparingTo("1000");
        assertThat(ceoItemA.discountPct()).isEqualByComparingTo("10");
        assertThat(ceoItemA.netUnitPrice()).isEqualByComparingTo("900.00");
        // Import still sees cost -- this phase must not narrow that.
        assertThat(ceoItemA.frozenLandedCostPerRequestedUnitThb()).isNotNull();

        // Import sees cost/margin (unchanged) but NEVER the new price-mode fields -- wrong-way-round: assert the field
        // is ABSENT for the role that should not see it, not merely present for ceo.
        PricingDecisionDto asImport = decisionService.get(decision.id(), importActor);
        assertThat(asImport.priceMode()).isNull();
        PricingDecisionItemDto importItemA = itemById(asImport, itemA.id());
        assertThat(importItemA.listUnitPrice()).isNull();
        assertThat(importItemA.discountPct()).isNull();
        assertThat(importItemA.specialPriceSqm()).isNull();
        assertThat(importItemA.directNetPrice()).isNull();
        assertThat(importItemA.netUnitPrice()).isNull();
        // Cost is untouched for import -- proves this is a narrow strip, not a wholesale one.
        assertThat(importItemA.frozenLandedCostPerRequestedUnitThb())
            .isEqualByComparingTo(ceoItemA.frozenLandedCostPerRequestedUnitThb());

        // Same strip on the list() endpoint.
        List<PricingDecisionDto> listAsImport = decisionService.list(decision.pricingRequestId(), importActor);
        assertThat(listAsImport).hasSize(1);
        assertThat(listAsImport.get(0).priceMode()).isNull();
        assertThat(itemById(listAsImport.get(0), itemA.id()).netUnitPrice()).isNull();
        List<PricingDecisionDto> listAsCeo = decisionService.list(decision.pricingRequestId(), ceoActor);
        assertThat(listAsCeo.get(0).priceMode()).isEqualTo("NET");
    }

    // Helpers

    /** {@code listUnitPrice} is not a client-settable field
     * on {@link UpdatePricingDecisionItemRequest} — the CEO never types it, it is
     * always the server-computed formula price, refreshed by {@code deriveListAndNet} whenever the
     * formula reference changes ({@code overrideItemCost}/{@code recomputeCostingInPlace}).
     * Callers that need
     * a NET-mode net derived from a KNOWN, round list price call {@link
     * #setListUnitPriceForTest} first (a direct fixture write, exactly like this file already does
     * for {@code sqm_per_piece} below) and then send only the discount here. */
    private UpdatePricingDecisionItemRequest discountItem(long itemId, BigDecimal discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            discountPct, false, null, false, null, false);
    }

    private UpdatePricingDecisionItemRequest specialSqmItem(long itemId, BigDecimal specialPriceSqm) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            null, false, specialPriceSqm, false, null, false);
    }

    private UpdatePricingDecisionItemRequest directNetItem(long itemId, BigDecimal directNetPrice) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            null, false, null, false, directNetPrice, false);
    }

    private UpdatePricingDecisionItemRequest overrideItem(long itemId, BigDecimal overridePrice, String reason) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, reason, overridePrice, false,
            null, false, null, false, null, false);
    }

    /** Test-only fixture setup: {@code list_unit_price} cannot
     * be set through the CEO API — it is always server-derived from the landed-cost formula,
     * whose own output is not a round number. Several tests below need a DETERMINISTIC list price
     * to pin an exact discount/net arithmetic result independently of that formula, so they poke
     * the column directly here. */
    private void setListUnitPriceForTest(long itemId, BigDecimal listUnitPrice) {
        jdbc.update("""
            UPDATE sales.pricing_decision_item SET list_unit_price = :listUnitPrice
             WHERE pricing_decision_item_id = :itemId
            """, Map.of("itemId", itemId, "listUnitPrice", listUnitPrice));
    }

    private PricingDecisionItemDto itemById(PricingDecisionDto decision, long itemId) {
        return decision.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    private PricingDecisionDto startNewFormReview() {
        long pricingRequestId = twoItemSubmittedCosting();
        return decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
    }

    private long twoItemSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, twoItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    /** Mirrors {@link #twoItemSubmittedCosting}, but with a SINGLE new-form FREE-TEXT item (see
     * {@link #freeTextUncostableItem}) -- V156's "the whole shipment is uncostable" branch in
     * {@code LandedCostCalculator#calculate} handles this as a valid, fully-supported state, not a
     * failure: the item still reaches READY_FOR_CEO_REVIEW and then {@code startReview}, just with a
     * null effective cost until the CEO resolves it via {@link
     * PricingDecisionService#overrideItemCost}. */
    private long oneItemUncostedSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemUncostedPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    private PricingRequestRequests.CreatePricingRequestRequest oneItemUncostedPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "ceo price mode uncosted request", UUID.randomUUID().toString(),
            List.of(freeTextUncostableItem("Uncostable", "Tile U-CEOP", "Factory Uncostable-CEOP", new BigDecimal("10"))));
    }

    /**
     * A free-text import line (no catalog link) that reaches startReview UNCOSTABLE and set aside
     * (frozenLandedCost == null), so the CEO's override/approve gates below have something to act on.
     *
     * <p>A "catalog product with a null thickness" is not a usable fixture:
     * LandedCostCalculator#resolveThicknessMm
     * falls back to the line's own thicknessMm, so a null CATALOG thickness does not make a line
     * uncostable — the Sales value (required at submit) costs it. The surviving set-aside path is a
     * line with no resolvable ORIGIN country: resolveOriginCountryCode reads the catalogue only (no
     * request fallback), so a line with productId == null has no country and is set aside before the
     * freight lookup. A real factory's country is NOT NULL (V151), so only a free-text line can hit it.
     */
    private PricingRequestRequests.PricingRequestItemRequest freeTextUncostableItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        // productId == null (free-text): the only field that differs from pricingItem(). factory is a
        // free-text name — enough to route the factory-email/quote step, but it links no catalog row.
        return new PricingRequestRequests.PricingRequestItemRequest(null, null, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            // "อื่นๆ" has no ISO code and no freight band, so the line stays uncostable
            // ("ไทย-สต็อก" now costs domestically, owner ruling 2026-11-11).
            false, "อื่นๆ", 3, 7, null, null, null, "โปรตุเกส", null);
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "ceo price mode request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A-CEOP", "Factory A-CEOP", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B-CEOP", "Factory B-CEOP", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A-CEOP".equals(factory) ? catalogProductIdFactoryA
            : "Factory B-CEOP".equals(factory) ? catalogProductIdFactoryB : null;
        // V185 (direct-deal-form parity): PER_PIECE, sqm_per_piece=0.36 -- the Phase 1 new-form shape Phase 2's mode
        // picker is eligible for. Mirrors PricingDecisionIntegrationTest's own identical fixture.
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, qty.intValueExact(), WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
