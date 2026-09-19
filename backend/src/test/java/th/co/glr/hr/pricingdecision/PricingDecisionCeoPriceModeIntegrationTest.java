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
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
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
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.CostOverrideRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Real-DB coverage for Phase 2 of the sales pricing-flow redesign (owner rulings 2026-09-18/19,
 * V187): the CEO price-mode picker (NET / SPECIAL_SQM / DIRECT_NET) on {@code sales.pricing_decision}
 * (+ {@code _item}), the mandatory CEO-only stripping of every new field for any other role that
 * can reach {@link PricingDecisionService#get}/{@link PricingDecisionService#list} (import), and
 * the Opus-review follow-up (2026-09-19): the auto-calculated list price (ruling A), "ปรับราคาเอง"
 * kept as the NET list-price override (ruling B), mode-switch net recompute (finding #2), the
 * silent-success fix (finding #3), oversized/scale validation (findings #4/#5), explicit clear
 * semantics (finding #6), and a real customer-quotation IT proving a CEO discount never raises a
 * discount-approval request (finding #8).
 *
 * <p>Setup mirrors {@link PricingDecisionIntegrationTest} (same fixtures, same helper shapes) —
 * duplicated here rather than shared, to avoid coupling this new-feature suite's lifecycle to an
 * already-large existing file. {@link #twoItemPricingRequest()}'s items are the Phase 1 (V185)
 * new-form shape (PER_PIECE, {@code sqmPerPiece} set) — the ONLY shape Phase 2's mode picker is
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
    private long catalogProductIdUncostable;

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
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));
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
        // V156 uncostable fixture (owner correction, 2026-09-19): thicknessMm=null means
        // LandedCostCalculator#resolveThicknessMm can never resolve a freight band for this
        // product, so any pricing-request item pointing at it reaches startReview with
        // frozenLandedCostPerRequestedUnitThb == null -- exactly the "starts uncostable" state the
        // new deriveListAndNet real-DB IT below needs.
        catalogProductIdUncostable = insertCatalogProduct("Factory Uncostable-CEOP", "IT", "TEST-UNCOST-CEOP-001",
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
    // Owner ruling A: the auto-calculated formula price is a REAL stored value (list_unit_price),
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

    @Test
    void net_zeroDiscount_approvesWithoutTheCeoTypingAnything() {
        PricingDecisionDto decision = startNewFormReview();
        // The CEO picks NET and NEVER edits a single item.
        PricingDecisionDto afterMode = decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);
        for (PricingDecisionItemDto item : afterMode.items()) {
            assertThat(item.netUnitPrice()).isNotNull();
            assertThat(item.netUnitPrice()).isEqualByComparingTo(item.listUnitPrice());
        }

        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        for (PricingDecisionItemDto item : approved.items()) {
            assertThat(item.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(item.netUnitPrice());
            assertThat(item.minimumSellingPricePerRequestedUnit()).isEqualByComparingTo(item.netUnitPrice());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Owner correction (2026-09-19): list_unit_price is NEVER a client input -- it must be
    // refreshed by every path that recomputes the formula reference, or an item that starts
    // uncostable can become costable via overrideItemCost yet stay permanently unable to reach a
    // NET approval (review finding #1's deadlock through a second door -- the exact bug the
    // coordinator's correction identified in the original Phase 2 implementation).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void overrideItemCost_onAnUncostedItem_populatesListUnitPrice_andNetApprovesAtListEqualsNet() {
        // 1. An uncosted new-form item -- thickness_mm=null means startReview freezes it with a
        // null cost, a null formula reference, and (per the ORIGINAL wrong design) a permanently
        // null list_unit_price.
        long pricingRequestId = oneItemUncostedSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto uncostedItem = decision.items().get(0);
        assertThat(uncostedItem.frozenLandedCostPerRequestedUnitThb()).isNull();
        assertThat(uncostedItem.listUnitPrice()).isNull();

        // 2. Apply overrideItemCost -- the CEO supplies a landed cost by hand, exactly as the
        // uncosted-item UI flow requires (approve()'s uncosted gate).
        PricingDecisionDto afterOverride = decisionService.overrideItemCost(decision.id(), uncostedItem.id(),
            new CostOverrideRequest(new BigDecimal("500.0000"), "ไม่มีข้อมูลความหนาในแคตตาล็อก ใส่ต้นทุนเอง"), ceoActor);
        PricingDecisionItemDto costedItem = itemById(afterOverride, uncostedItem.id());
        assertThat(costedItem.frozenLandedCostPerRequestedUnitThb()).isNotNull();

        // 3. Assert list_unit_price is now populated -- this is the exact fix: overrideItemCost
        // must refresh list_unit_price the same way startReview originally did, not leave it
        // frozen at null forever.
        assertThat(costedItem.listUnitPrice()).isNotNull();
        assertThat(costedItem.listUnitPrice()).isEqualByComparingTo(
            costedItem.proposedSellingPricePerRequestedUnit().setScale(2, RoundingMode.HALF_UP));

        // 4. Choose NET with no discount.
        PricingDecisionDto withMode = decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);
        PricingDecisionItemDto pricedItem = itemById(withMode, uncostedItem.id());
        assertThat(pricedItem.netUnitPrice()).isNotNull();
        assertThat(pricedItem.netUnitPrice()).isEqualByComparingTo(pricedItem.listUnitPrice());

        // 5. Approve succeeds at approved = net = list -- the uncosted item, which the ORIGINAL
        // (wrong) design could never approve under NET even after overrideItemCost, now clears
        // approve()'s uncosted gate AND its missing-price gate in one shot.
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        PricingDecisionItemDto approvedItem = itemById(approved, uncostedItem.id());
        assertThat(approvedItem.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(pricedItem.netUnitPrice());
        assertThat(approvedItem.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(pricedItem.listUnitPrice());
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit()).isEqualByComparingTo(pricedItem.netUnitPrice());
    }

    @Test
    void recalculateCost_refreshesListUnitPrice_andRecomputesNet_forTheCurrentMode() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionDto withMode = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("10")))), ceoActor);
        PricingDecisionItemDto beforeRecalc = itemById(withMode, itemA.id());
        assertThat(beforeRecalc.listUnitPrice()).isNotNull();
        assertThat(beforeRecalc.netUnitPrice()).isEqualByComparingTo(
            beforeRecalc.listUnitPrice().multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));

        // A manual cost override on the OTHER item changes nothing about itemA's own cost, but
        // recalculateCost re-derives EVERY item's frozen cost + formula reference from the bound
        // costing -- itemA's list_unit_price/net_unit_price must come out identical (same cost,
        // same margin, same stored discount), proving the refresh path is exercised and stable,
        // not merely a no-op that happens to leave stale values looking unchanged.
        PricingDecisionItemDto itemB = decision.items().get(1);
        decisionService.overrideItemCost(decision.id(), itemB.id(),
            new CostOverrideRequest(new BigDecimal("42.0000"), "ทดสอบ: เปลี่ยนต้นทุนรายการอื่น"), ceoActor);

        PricingDecisionDto recalculated = decisionService.recalculateCost(decision.id(), ceoActor);
        PricingDecisionItemDto afterRecalc = itemById(recalculated, itemA.id());
        assertThat(afterRecalc.listUnitPrice()).isEqualByComparingTo(beforeRecalc.listUnitPrice());
        assertThat(afterRecalc.discountPct()).isEqualByComparingTo(beforeRecalc.discountPct());
        assertThat(afterRecalc.netUnitPrice()).isEqualByComparingTo(beforeRecalc.netUnitPrice());
        assertThat(afterRecalc.netUnitPrice()).isEqualByComparingTo(
            afterRecalc.listUnitPrice().multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Opus review minor #1 (2026-09-19): "ปรับราคาเอง" only ever clears the uncosted gate for
    // NET (or no mode chosen yet) -- DIRECT_NET/SPECIAL_SQM never consult the override at all, so
    // it must never fake-clear their gate. Reproduced end to end against a genuine new-form
    // decision (an uncosted item, via the same oneItemUncostedSubmittedCosting fixture the
    // deriveListAndNet real-DB IT above uses).
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_uncostedDirectNetItem_withOverride_isStillBlocked_reviewMinor1Probe() {
        // The review's own probe: an uncosted DIRECT_NET item with directNetPrice=100 and an
        // override of 300 must NOT approve at 100 with no cost backing it at all -- DIRECT_NET's
        // own computeNetUnitPrice branch never even looks at manualSellingPricePerRequestedUnit.
        long pricingRequestId = oneItemUncostedSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        assertThat(item.frozenLandedCostPerRequestedUnitThb()).isNull();

        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "DIRECT_NET", List.of(directNetItem(item.id(), new BigDecimal("100")))), ceoActor);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(overrideItem(item.id(), new BigDecimal("300"),
                "ทดสอบ probe: override ไม่ควรปิด uncosted gate ของ DIRECT_NET"))), ceoActor);

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

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

    @Test
    void approve_uncostedNetItem_withOverride_stillApproves_reviewMinor1ScopedCorrectly() {
        // The fix must be precisely scoped: NET (and legacy/no-mode-yet) still lets "ปรับราคาเอง"
        // clear the uncosted gate, because ruling B makes the override NET's effective list price
        // -- there IS real price backing (the CEO's own explicit figure), just not a landed cost.
        long pricingRequestId = oneItemUncostedSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);

        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(overrideItem(item.id(), new BigDecimal("300"), "ทดสอบ: override เป็นราคาตั้งของ NET"))),
            ceoActor);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);

        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
        assertThat(itemById(approved, item.id()).approvedSellingPricePerRequestedUnit()).isEqualByComparingTo("300.00");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Opus review minor #3 (2026-09-19): an override or margin large enough to overflow a derived
    // NUMERIC column must 400, never reach Postgres as a raw overflow 500.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void overrideItemCost_hugeCost_is400_neverA500_reviewMinor3Probe() {
        // The review's own probe value: 1e15 overflows manual_landed_cost_per_unit_thb's own
        // NUMERIC(18,4) column before the derived selling price is even computed.
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.overrideItemCost(decision.id(), itemId,
            new CostOverrideRequest(new BigDecimal("1000000000000000"), "ทดสอบ probe: 1e15"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_hugeMarginPct_is400_neverA500_reviewMinor3Probe() {
        // Legacy (margin-formula) path -- "this covers the legacy override path too".
        long pricingRequestId = legacyPerBoxSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(new UpdatePricingDecisionItemRequest(itemId,
                new BigDecimal("1000000"), null, null, null, false))), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void startReview_hugeDefaultMarginPct_is400_neverA500_reviewMinor3Probe() {
        long pricingRequestId = twoItemSubmittedCosting();
        assertThatThrownBy(() -> decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("1000000"), "THB", null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void overrideItemCost_moderateCostWithLargeMargin_overflowsDerivedListPrice_is400_reviewMinor3Probe() {
        // Neither the cost NOR the margin individually exceeds ITS OWN bound, but their PRODUCT
        // overflows list_unit_price's NUMERIC(14,2) column -- the multiplicative case only
        // deriveListAndNet's own bound check (not the two input-level ones) can catch.
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("500"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.overrideItemCost(decision.id(), itemId,
            new CostOverrideRequest(new BigDecimal("5000000000"),
                "ทดสอบ probe: ต้นทุน x margin เกินขอบเขตราคาตั้ง"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Net derivation per mode (reuses WastageCalculator, never reimplements it)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void net_derivesFromListPriceAndDiscount_andFreezesOnApprove() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        // list_unit_price is server-derived (ruling A) -- pin a round fixture value for both
        // items so the discount arithmetic below is exact. itemB gets NO discount sent at all;
        // it still ends up priced because setting the mode recomputes EVERY item's net from its
        // own stored list price (finding #2's recomputeNetsForModeSwitch), not just the items
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
        // Owner's own pinned sample (WastageCalculatorTest): 1350 @ 0.36 sqm/piece -> 453.84.
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
    // Owner ruling B: "ปรับราคาเอง" REPLACES the auto-calculated price as NET's list price;
    // discount still applies on top of it.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void manualOverride_replacesAutoListPrice_andDiscountStillAppliesOnTop() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);

        // CEO sets "ปรับราคาเอง" (mirrors the legacy override mutation exactly) BEFORE picking a
        // mode -- must still work; the override is not itself mode-dependent.
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(overrideItem(itemA.id(), new BigDecimal("2000"), "ราคาพิเศษลูกค้า VIP"))), ceoActor);

        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("10")))), ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        assertThat(updatedA.manualSellingPricePerRequestedUnit()).isEqualByComparingTo("2000");
        // 2000 (override, NOT the auto list price) x (1 - 10/100) = 1800.00.
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo("1800.00");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review finding #2: switching the mode recomputes every item's net from ITS OWN stored
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
            // Never the OLD NET figure (900.00) -- the item has no direct_net_price yet, so its
            // net must be NULL, not a stale carry-over.
            assertThat(item.netUnitPrice()).isNull();
        }

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void modeSwitch_backToNet_recomputesFromStillStoredListPriceAndDiscount() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        setListUnitPriceForTest(itemB.id(), new BigDecimal("1000"));
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(
                discountItem(itemA.id(), new BigDecimal("10")),
                discountItem(itemB.id(), new BigDecimal("10")))),
            ceoActor);
        decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "DIRECT_NET", List.of()), ceoActor);

        // Switch BACK to NET -- list_unit_price/discount_pct were never cleared by the trip
        // through DIRECT_NET (only net_unit_price was), so the net recomputes to the same figure.
        PricingDecisionDto backToNet = decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);
        assertThat(itemById(backToNet, itemA.id()).netUnitPrice()).isEqualByComparingTo("900.00");
        assertThat(itemById(backToNet, itemB.id()).netUnitPrice()).isEqualByComparingTo("900.00");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review finding #3: a no-op save must not log PRICING_DECISION_UPDATED or change anything.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void update_noOpCall_doesNotLogAnEvent_orChangeUpdatedAt() {
        PricingDecisionDto decision = startNewFormReview();
        long eventsBefore = countUpdatedEvents(decision.pricingRequestId());
        var updatedAtBefore = jdbc.queryForObject(
            "SELECT updated_at FROM sales.pricing_decision WHERE pricing_decision_id = :id",
            Map.of("id", decision.id()), java.sql.Timestamp.class);

        // ceoNote=null, priceMode=null, items=empty -- touches literally nothing.
        PricingDecisionDto result = decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, List.of()), ceoActor);

        assertThat(result.priceMode()).isEqualTo(decision.priceMode());
        assertThat(countUpdatedEvents(decision.pricingRequestId())).isEqualTo(eventsBefore);
        var updatedAtAfter = jdbc.queryForObject(
            "SELECT updated_at FROM sales.pricing_decision WHERE pricing_decision_id = :id",
            Map.of("id", decision.id()), java.sql.Timestamp.class);
        assertThat(updatedAtAfter).isEqualTo(updatedAtBefore);
    }

    @Test
    void update_itemEntryThatTouchesNothing_isSkipped_notCountedAsAChange() {
        PricingDecisionDto decision = startNewFormReview();
        long eventsBefore = countUpdatedEvents(decision.pricingRequestId());
        // A request naming a real item id but setting nothing on it -- exactly what a CEO
        // clicking "save" on an untouched row would send.
        UpdatePricingDecisionItemRequest noOp = new UpdatePricingDecisionItemRequest(
            decision.items().get(0).id(), null, null, null, null, false,
            null, false, null, false, null, false);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(noOp)), ceoActor);
        assertThat(countUpdatedEvents(decision.pricingRequestId())).isEqualTo(eventsBefore);
    }

    @Test
    void update_discountOnly_worksBecauseListPriceAlreadyExistsFromRulingA() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);

        // Only discountPct sent -- listUnitPrice is not even a settable field any more -- must
        // still derive a net from the auto-populated list price (review finding #3, second half;
        // owner correction 2026-09-19 makes this the ONLY way a NET item is ever priced now).
        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(discountItem(itemA.id(), new BigDecimal("10")))),
            ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo(
            updatedA.listUnitPrice().multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review findings #4/#5: bounds matching column scale, 400 not 500; round to 2dp before
    // deriving the net so the stored input and the stored net always agree.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void update_oversizedDirectNetPrice_is400_neverAServerError() {
        // listUnitPrice is no longer a client-settable field at all (owner correction, 2026-09-19
        // -- see UpdatePricingDecisionItemRequest's own Javadoc), so its own oversized-value test
        // is retired; directNetPrice (NUMERIC(14,2), same MAX_PRICE_14_2 bound) previously had NO
        // dedicated oversized-value coverage of its own, so this repoints there instead of simply
        // deleting the case findings #4/#5 exist to guard.
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "DIRECT_NET", List.of(directNetItem(itemId, new BigDecimal("9999999999999.99")))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_oversizedSpecialPriceSqm_is400_neverAServerError() {
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "SPECIAL_SQM", List.of(specialSqmItem(itemId, new BigDecimal("99999999999.99")))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_discountWithMoreThanTwoDecimals_roundsBeforeStoringAndDeriving() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("12.345")))),
            ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        // 12.345 rounds HALF_UP to 12.35 -- both the STORED discount and the net it derives from
        // must reflect the ROUNDED figure, so the two can always reproduce each other.
        assertThat(updatedA.discountPct()).isEqualByComparingTo("12.35");
        assertThat(updatedA.netUnitPrice()).isEqualByComparingTo(
            new BigDecimal("1000").multiply(new BigDecimal("1").subtract(new BigDecimal("12.35").divide(new BigDecimal("100"))))
                .setScale(2, RoundingMode.HALF_UP));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review finding #6: explicit clear semantics -- a blanked discount really clears to 0.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void update_clearingDiscount_resetsNetToListPrice_notLeftAtTheOldDiscount() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemA.id(), new BigDecimal("10")))),
            ceoActor);

        PricingDecisionDto cleared = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(new UpdatePricingDecisionItemRequest(itemA.id(), null, null, null, null, false,
                null, true, null, false, null, false))),
            ceoActor);
        PricingDecisionItemDto clearedA = itemById(cleared, itemA.id());
        assertThat(clearedA.discountPct()).isNull();
        // Cleared discount reads as 0 in the NET formula -> net == list price, NOT the old 900.00.
        assertThat(clearedA.netUnitPrice()).isEqualByComparingTo("1000.00");
    }

    @Test
    void update_setAndClearSameField_is400() {
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        // discountPct now stands in for the old listUnitPrice set+clear case (listUnitPrice is no
        // longer client-settable at all) -- exercises the exact same requireNotSetAndCleared guard
        // (review finding #6).
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
                new BigDecimal("10"), true, null, false, null, false))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Validation (pre-existing coverage, still true under the new rulings)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_blocksWhenAnyNewFormItemHasNoNetPrice() {
        // DIRECT_NET, not NET: NET auto-fills list_unit_price for every item (owner ruling A), so
        // leaving an item "un-priced" under NET no longer reproduces a missing net once a mode is
        // picked. DIRECT_NET has no auto-fill at all -- typing it for only ONE of the two items is
        // what actually leaves the other with netUnitPrice == null here.
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "DIRECT_NET", List.of(directNetItem(itemA.id(), new BigDecimal("1000")))),
            ceoActor); // itemB never priced

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void update_specialSqmWithoutSqmPerPiece_is422_evenThoughPhase1RequiresIt() {
        // Defence-in-depth: requireNewFormEligible already guarantees sqm_per_piece is non-null
        // for every item of a mode-bearing decision, but computeNetUnitPrice must still refuse a
        // null one rather than NPE if that invariant is ever violated. Exercised in two steps so
        // requireNewFormEligible's OWN (correct, and stricter) rejection is not what fires
        // instead: step 1 locks the decision into SPECIAL_SQM via itemB (still fully eligible);
        // only THEN is itemA's sqm_per_piece removed and itemA priced -- a state
        // requireNewFormEligible never re-checks after the mode is already set.
        PricingDecisionDto decision = startNewFormReview();
        long itemAId = decision.items().get(0).id();
        long itemBId = decision.items().get(1).id();
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "SPECIAL_SQM", List.of(specialSqmItem(itemBId, new BigDecimal("1350")))),
            ceoActor);
        jdbc.update("""
            UPDATE sales.pricing_request_item SET sqm_per_piece = NULL
             WHERE pricing_request_item_id = :id
            """, Map.of("id", decision.items().get(0).pricingRequestItemId()));

        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(specialSqmItem(itemAId, new BigDecimal("1350")))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    @Test
    void update_priceModeFieldsWithoutModeChosen_is400() {
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(discountItem(itemId, new BigDecimal("10")))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_invalidPriceMode_is400() {
        PricingDecisionDto decision = startNewFormReview();
        assertThatThrownBy(() -> decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "BOGUS", List.of()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void update_discountPctOutOfRange_is400() {
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemId, new BigDecimal("150")))),
            ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Legacy decisions keep today's behaviour exactly
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void legacyDecision_cannotSetPriceMode() {
        // PER_BOX request items are NOT the Phase 1 new-form shape -- singleItemSubmittedCosting
        // mirrors PricingDecisionIntegrationTest's own legacy (non-PER_PIECE) fixture.
        long pricingRequestId = legacyPerBoxSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decision.items().get(0).requestedUnitBasis()).isEqualTo(UnitBasis.PER_BOX);

        assertThatThrownBy(() -> decisionService.update(decision.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // The margin path is untouched: a plain (no priceMode) update still works exactly as
        // before for this legacy decision.
        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, List.of(new UpdatePricingDecisionItemRequest(decision.items().get(0).id(),
                new BigDecimal("0.30"), null, null, null, false))),
            ceoActor);
        assertThat(updated.priceMode()).isNull();
        assertThat(itemById(updated, decision.items().get(0).id()).proposedMarginPct()).isEqualByComparingTo("0.30");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // AUTHZ (mandatory, wrong-way-round, real DB, through the real service): CEO-only fields
    // must be null for import on get()/list(), even though import CAN see cost/margin.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

        // Import sees cost/margin (unchanged) but NEVER the new price-mode fields -- wrong-way-round:
        // assert the field is ABSENT for the role that should not see it, not merely present for ceo.
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

    @Test
    void import_cannotSetPriceModeFields_viaUpdate() {
        PricingDecisionDto decision = startNewFormReview();
        long itemId = decision.items().get(0).id();
        assertThatThrownBy(() -> decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(discountItem(itemId, new BigDecimal("10")))),
            importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void salesAndAccount_cannotReachRawDecisionAtAll_soPriceModeFieldsAreUnreachable() {
        // sales/sales_manager/account never reach get()/list() in the first place (design
        // correction 2, unchanged by this phase) -- the strip in this phase is therefore only
        // ever exercised for import, but this pins that the OTHER non-ceo roles stay refused
        // outright rather than accidentally gaining a read path to the new fields.
        PricingDecisionDto decision = startNewFormReview();
        assertThatThrownBy(() -> decisionService.get(decision.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.get(decision.id(), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.get(decision.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Review finding #8: a real-DB IT driving a new-form decision (WITH a CEO discount) through
    // CustomerQuotationService create + issue, proving NO discount-approval request is raised.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void newFormDecisionWithDiscount_neverRaisesADiscountApproval_andIssuesCleanly() {
        PricingDecisionDto decision = startNewFormReview();
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        setListUnitPriceForTest(itemA.id(), new BigDecimal("1000"));
        setListUnitPriceForTest(itemB.id(), new BigDecimal("500"));
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            null, "NET", List.of(
                // A REAL discount (10%) -- the whole point of this test: approved ==
                // minimum for a new-form item (owner ruling), so a discounted NET price must
                // still never trip customerquotation's V155 per-line discount-approval gate.
                discountItem(itemA.id(), new BigDecimal("10")),
                discountItem(itemB.id(), new BigDecimal("5")))),
            ceoActor);
        decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);

        CustomerQuotationDto draft = quotationService.create(decision.pricingRequestId(),
            new CreateCustomerQuotationRequest("30 days", "45 days", "รถขนส่ง", LocalDate.now().plusDays(30),
                "หมายเหตุลูกค้า", UUID.randomUUID().toString()),
            salesActor);
        assertThat(draft.items()).hasSize(2);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation_item_discount_approval
             WHERE quotation_item_id IN (SELECT quotation_item_id FROM sales.quotation_item WHERE quotation_id = :id)
            """, Map.of("id", draft.id()), Long.class)).isZero();

        CustomerQuotationDto issued = quotationService.issue(draft.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(issued.docStatus()).isEqualTo("ISSUED");

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.quotation_item_discount_approval
             WHERE quotation_item_id IN (SELECT quotation_item_id FROM sales.quotation_item WHERE quotation_id = :id)
            """, Map.of("id", draft.id()), Long.class)).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private long countUpdatedEvents(long pricingRequestId) {
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_UPDATED'
            """, Map.of("id", pricingRequestId), Long.class);
        return count == null ? 0 : count;
    }

    /** Owner correction (2026-09-19): {@code listUnitPrice} is no longer a client-settable field
     * on {@link UpdatePricingDecisionItemRequest} at all — the CEO never types it (ruling A), it is
     * always the server-computed formula price, refreshed by {@code deriveListAndNet} whenever the
     * formula reference changes ({@code overrideItemCost}/{@code recomputeCostingInPlace}). This
     * replaces the old {@code netItem(itemId, listUnitPrice, discountPct)} shape — callers that need
     * a NET-mode net derived from a KNOWN, round list price now call {@link
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

    /** Test-only fixture setup (owner correction, 2026-09-19): {@code list_unit_price} can no
     * longer be set through the CEO API — it is always server-derived from the landed-cost formula,
     * whose own output is not a round number. Several tests below need a DETERMINISTIC list price
     * to pin an exact discount/net arithmetic result independently of that formula, so they poke
     * the column directly here, exactly like this file already pokes {@code sqm_per_piece} in
     * {@code update_specialSqmWithoutSqmPerPiece_is422_evenThoughPhase1RequiresIt}. */
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
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), "THB", "100.00", draft.items().get(0).pricingRequestItemId()),
                importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    /** Mirrors {@link #twoItemSubmittedCosting}, but with a SINGLE new-form item pointing at
     * {@code catalogProductIdUncostable} (thickness_mm=null) -- V156's "the whole shipment is
     * uncostable" branch in {@code LandedCostCalculator#calculate} handles this as a valid,
     * fully-supported state, not a failure: the item still reaches READY_FOR_CEO_REVIEW and then
     * {@code startReview}, just with a null effective cost until the CEO resolves it via {@link
     * PricingDecisionService#overrideItemCost}. */
    private long oneItemUncostedSubmittedCosting() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemUncostedPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
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
            List.of(pricingItem("Uncostable", "Tile U-CEOP", "Factory Uncostable-CEOP", new BigDecimal("10"))));
    }

    /** A single PER_BOX item (not the Phase 1 new-form shape) driven to READY_FOR_CEO_REVIEW --
     * the "legacy" fixture the mode-picker eligibility gate must refuse. Bypasses
     * PricingRequestService.createDraft on purpose, exactly like
     * PricingDecisionIntegrationTest#singleItemSubmittedCosting's own identical comment explains:
     * createDraft (as of V185) forces every item's requestedUnitBasis to PER_PIECE, which would
     * make it impossible to construct the non-PER_PIECE shape this test needs.
     * PricingRequestRepository.create performs the exact same DB write createDraft would. */
    private long legacyPerBoxSubmittedCosting() {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryA, null, "SCG", "Tile Legacy", "SCG Tile Legacy", null, null,
            "60x60", "Factory A-CEOP", new BigDecimal("10"), new BigDecimal("10"), "box", UnitBasis.PER_BOX,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "legacy per-box request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), request, salesRepId);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draft = drafts.get(0);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), new ReceiveFactoryQuoteRequest(
            "REF-LEGACY", "THB", "30 days", "45 days", "revision", "note", List.of(
                new ReceiveFactoryQuoteItemRequest(draft.items().get(0).pricingRequestItemId(), null, null,
                    new BigDecimal("10"), "box", "box", new BigDecimal("1000.00"), "THB", null,
                    new BigDecimal("0.5"), new BigDecimal("20"), null, "45 days", null, null)),
            UUID.randomUUID().toString()), importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        return pricingRequestId;
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
            : "Factory B-CEOP".equals(factory) ? catalogProductIdFactoryB
            : "Factory Uncostable-CEOP".equals(factory) ? catalogProductIdUncostable : null;
        // V185 (direct-deal-form parity): PER_PIECE, sqm_per_piece=0.36 -- the Phase 1 new-form
        // shape Phase 2's mode picker is eligible for. Mirrors PricingDecisionIntegrationTest's
        // own identical fixture.
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
