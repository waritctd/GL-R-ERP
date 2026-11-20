package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkFactoryContactedRequest;
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
 * Real-DB acceptance + authz + concurrency coverage for the CEO Selling Price Decision.
 * Builds directly on the fixtures/style of
 * {@code PricingFactoryQuoteCostingIntegrationTest} — a fresh SUBMITTED costing is the
 * precondition every test starts from.
 */
class PricingDecisionIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private NotificationRepository notificationRepository;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long secondCeoUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal secondCeoActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        notificationRepository = notifications;
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-decision-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V152 (V109 engine wiring): shared by FactoryQuoteService's markReadyForCosting auto-advance check and
        // PricingDecisionService's startReview/recalculateCost.
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = createEmployee(employees, "พนักงานขาย สาม", "sales-step3@glr.co.th", "SALES", "แผนกขาย");
        long otherSalesId = createEmployee(employees, "พนักงานขาย อื่น", "sales-step3-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สาม", "import-step3@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร สาม", "ceo-step3@glr.co.th", "MD", "ผู้บริหาร");
        secondCeoUserId = createEmployee(employees, "ผู้บริหาร สอง", "ceo-step3-2@glr.co.th", "MD", "ผู้บริหาร");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย สาม", "sales-manager-step3@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        secondCeoActor = actor(secondCeoUserId, "ceo");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        // V152 (V109 engine wiring): factory country must be one of V109's seeded origin countries (Italy/Spain/China)
        // or LandedCostCalculator's freight lookup 422s ("ไม่พบ อัตราค่าขนส่ง") — 'Thailand'/'TestLand3' (pre-V109)
        // are no longer costable.
        catalogProductIdFactoryA = insertCatalogProduct("Factory A3", "IT", "TEST-A3-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B3", "IT", "TEST-B3-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Step 3 จำกัด", "0100000000003", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0003");
        ProjectDto project = projects.create(customer.id(), "โครงการ Step 3");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Step 3", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A3", "Factory A3"), ticketItem("Cotto", "Tile B3", "Factory B3"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // Acceptance scenario (end to end, real Postgres)

    // Design correction 1: unit basis

    // Design correction 2: never leak cost to Sales

    @Test
    void salesView_rejectsANonOwningSalesRep() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = approveWithFlatMargin(pricingRequestId, new BigDecimal("0.20"));
        // The owning rep (salesActor, who created the ticket) sees it fine...
        assertThat(decisionService.salesView(pricingRequestId, salesActor).items()).isNotEmpty();
        // ...a different sales rep, wrong-way-round, must not.
        assertThatThrownBy(() -> decisionService.salesView(pricingRequestId, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.APPROVED);
    }

    // Stage L3 (release runbook): sales/sales_manager must never reach the RAW cost/margin/quote endpoints.

    /** MUTATION-CHECK RECORD (actually run, not simulated): temporarily changed
     * PricingDecisionService.RAW_DECISION_ROLES from Set.of("import", "ceo") to Set.of("import", "ceo", "sales")
     * (reintroducing the leak this test guards against) and ran this class. */
    @Test
    void salesAndSalesManager_cannotReachRawPricingDecision() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        assertThatThrownBy(() -> decisionService.get(decision.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.get(decision.id(), salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Positive control: import/ceo (RAW_DECISION_ROLES) are unaffected.
        assertThat(decisionService.get(decision.id(), ceoActor)).isNotNull();
        assertThat(decisionService.list(pricingRequestId, importActor)).isNotEmpty();
    }

    /** Positive control for the L3 redaction (not a blackout): the owning sales rep, AND the sales manager, can
     * still reach the approved selling price through salesView even though both are locked out of the raw get/list
     * endpoints above. */
    @Test
    void salesAndSalesManager_canStillReachSalesViewForApprovedDecision() {
        long pricingRequestId = twoItemSubmittedCosting();
        approveWithFlatMargin(pricingRequestId, new BigDecimal("0.20"));

        assertThat(decisionService.salesView(pricingRequestId, salesActor).items()).isNotEmpty();
        assertThat(decisionService.salesView(pricingRequestId, salesManagerActor).items()).isNotEmpty();
    }

    // Stage L4 (release runbook): ceo is in RAW_DECISION_ROLES/RAW_COSTING_ROLES/RAW_QUOTE_ROLES for READS, but
    // must stay read-only — factory-quote and costing MUTATIONS are IMPORT_ROLES-only. Untested until now. Written
    // wrong-way-round: can ceo mutate.

    // Design correction 3: freeze factory-quote mutations from CEO_REVIEWING

    // Design correction 4: one return-to-Import path

    // Design correction 7 + Phase 1 UI simplification: margin required (unless overridden), minimum selling price
    // auto-populated, server recomputes

    // "ปรับราคาเอง" (Phase 1 UI simplification) — CEO selling-price override, via PUT /pricing-decisions/{id}
    // (PricingDecisionService#update -> applyItemUpdates).

    @Test
    void sellingPriceOverride_setsTheFinalPrice_andTheFormulaStopsDrivingThatLine() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        BigDecimal formulaPrice = item.proposedSellingPricePerRequestedUnit();
        BigDecimal overridePrice = formulaPrice.add(new BigDecimal("777.0000"));

        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null,
            List.of(new UpdatePricingDecisionItemRequest(item.id(), null, null,
                "ลูกค้าต่อรองราคาสุดท้ายเป็นตัวเลขนี้โดยตรง", overridePrice, false))), ceoActor);

        PricingDecisionItemDto overridden = itemById(updated, item.id());
        assertThat(overridden.manualSellingPricePerRequestedUnit()).isEqualByComparingTo(overridePrice);
        // The formula's own output survives untouched, sitting beside the override — never overwritten (same shape as
        // the cost override's manualLandedCostPerUnitThb precedent).
        assertThat(overridden.proposedSellingPricePerRequestedUnit()).isEqualByComparingTo(formulaPrice);
        assertThat(overridden.effectiveSellingPricePerRequestedUnit()).isEqualByComparingTo(overridePrice);

        // approve() freezes the OVERRIDE verbatim, not a margin recomputation — the formula never runs for this line
        // at all, so a margin that produces a totally different number is irrelevant to the frozen result.
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest(null, null), ceoActor);
        PricingDecisionItemDto approvedItem = itemById(approved, item.id());
        assertThat(approvedItem.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(overridePrice);
        // Minimum auto-population also uses the OVERRIDDEN (effective) price, not the formula's.
        assertThat(approvedItem.minimumSellingPricePerRequestedUnit()).isEqualByComparingTo(overridePrice);
    }

    /** Design correction 7: the server must recompute the approved selling price from the frozen cost and the
     * margin being frozen in — never trust a stored/client-influenced selling price verbatim. */
    @Test
    void approve_recomputesSellingPriceFromCostAndMargin_ignoringAnyStoredOrCorruptedPrice() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }
        PricingDecisionItemDto item = decision.items().get(0);
        BigDecimal correctPrice = formulaSellingPrice(item.frozenLandedCostPerRequestedUnitThb(), "0.20");

        // Corrupt the stored proposed price directly — a malicious/buggy client cannot influence approval through this
        // column.
        jdbc.update("""
            UPDATE sales.pricing_decision_item
               SET proposed_selling_price_per_requested_unit = 999999.9999
             WHERE pricing_decision_item_id = :id
            """, Map.of("id", item.id()));

        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest(null, null), ceoActor);
        PricingDecisionItemDto approvedItem = itemById(approved, item.id());
        assertThat(approvedItem.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(correctPrice);
        assertThat(approvedItem.approvedSellingPricePerRequestedUnit())
            .isNotEqualByComparingTo("999999.9999");
    }

    // Design correction 8: idempotent + concurrency-safe approval

    @Test
    void approveRetryWithSameClientRequestId_isIdempotent_doesNotDuplicateEventOrNotification() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }
        String clientRequestId = UUID.randomUUID().toString();

        PricingDecisionDto first = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", clientRequestId), ceoActor);
        PricingDecisionDto retry = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", clientRequestId), ceoActor);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_APPROVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification
             WHERE employee_id = :salesRepId AND type = 'PRICING_DECISION_APPROVED'
            """, Map.of("salesRepId", salesRepId), Long.class)).isEqualTo(1L);
    }

    /** Two CEOs racing to approve the SAME decision: exactly one wins, the other is refused cleanly (409), and
     * exactly one approval event / one notification is recorded. */
    @Test
    void approveConcurrently_exactlyOneWins_oneApprovalEventOneNotification() throws Exception {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }

        var txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            jdbc.getJdbcTemplate().getDataSource());
        var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);

        Callable<Long> byFirstCeo = () -> txTemplate.execute(status ->
            decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("ceo1", UUID.randomUUID().toString()),
                ceoActor).id());
        Callable<Long> bySecondCeo = () -> txTemplate.execute(status ->
            decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("ceo2", UUID.randomUUID().toString()),
                secondCeoActor).id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Long> successes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<Long> f1 = executor.submit(byFirstCeo);
            Future<Long> f2 = executor.submit(bySecondCeo);
            for (Future<Long> f : List.of(f1, f2)) {
                try {
                    successes.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_decision WHERE pricing_request_id = :id AND status = 'APPROVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_APPROVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification
             WHERE employee_id = :salesRepId AND type = 'PRICING_DECISION_APPROVED'
            """, Map.of("salesRepId", salesRepId), Long.class)).isEqualTo(1L);
    }

    // Helpers

    private PricingDecisionItemDto itemById(PricingDecisionDto decision, long itemId) {
        return decision.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    private PricingDecisionDto approveWithFlatMargin(long pricingRequestId, BigDecimal marginPct) {
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(marginPct, "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, new BigDecimal("1.00"), null, null, false))), ceoActor);
        }
        return decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
    }

    /** Two-item, two-factory scenario driven to READY_FOR_CEO_REVIEW (the factory-quote flow), the
     * precondition every CEO pricing-decision operation starts from. The CEO owns costing: this
     * does not create any {@code sales.pricing_costing} row itself —
     * markReadyForCosting's auto-advance (once the LAST factory quote resolves) is what gets the
     * request here; {@code decisionService.startReview}, called separately by every test below,
     * is what computes and persists the costing. */
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

    /**
     * Selling-price formula hand-reimplemented independently of
     * {@link th.co.glr.hr.pricingcosting.PricingFormulaEngine#sellingPrice}: {@code cost x
     * (1+margin) x selling_buffer}, rounded HALF_UP to 2dp.
     * Buffer (1.07) matches the seeded default, which every fixture
     * in this class relies on being current (nothing here mutates
     * {@code sales.pricing_formula_config}); {@code selling_price_round_up_to} is not
     * consulted at all.
     */
    private BigDecimal formulaSellingPrice(BigDecimal costPerRequestedUnitThb, BigDecimal marginPct) {
        BigDecimal raw = costPerRequestedUnitThb.multiply(BigDecimal.ONE.add(marginPct)).multiply(new BigDecimal("1.07"));
        return raw.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal formulaSellingPrice(BigDecimal costPerRequestedUnitThb, String marginPct) {
        return formulaSellingPrice(costPerRequestedUnitThb, new BigDecimal(marginPct));
    }

    private ReceiveFactoryQuoteRequest response(String ref, String currency, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, currency, "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal(price), currency, null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "step 3 request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A3", "Factory A3", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B3", "Factory B3", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A3".equals(factory) ? catalogProductIdFactoryA
            : "Factory B3".equals(factory) ? catalogProductIdFactoryB : null;
        // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestService#createDraft persists —
        // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
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
