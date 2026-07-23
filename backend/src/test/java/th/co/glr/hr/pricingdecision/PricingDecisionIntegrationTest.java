package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.MarkNotAvailableRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.StartNegotiationRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.RecalculatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
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
 * Real-DB acceptance + authz + concurrency coverage for Step 3 (CEO Selling Price Decision).
 * Builds directly on Step 2's fixtures/style
 * ({@code PricingFactoryQuoteCostingIntegrationTest}) — a fresh SUBMITTED costing is the
 * precondition every test starts from.
 */
class PricingDecisionIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private NotificationRepository notificationRepository;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long secondCeoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal secondCeoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;
    // All-zero price_calc_config country -- see PricingFactoryQuoteCostingIntegrationTest's own
    // identical fixture for why: landedCostPerUnitThb == goodsCostThb exactly, so margin/price
    // arithmetic assertions are not diluted by freight/insurance/duty/inland.
    private long catalogProductIdFactoryC;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        notificationRepository = notifications;
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-decision-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        AppProperties dispatchProperties = new AppProperties();
        dispatchProperties.getFactoryQuoteDispatch().setReclaimTimeoutSeconds(2);
        dispatchProperties.getFactoryQuoteDispatch().setMaxAttempts(3);
        dispatchProperties.getFactoryQuoteDispatch().setBackoffBaseSeconds(1);
        dispatchProperties.getFactoryQuoteDispatch().setBatchSize(20);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties);
        costingRepository = new PricingCostingRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        costingService = new PricingCostingService(costingRepository, pricingRequests, factoryQuotes, tickets,
            fxRates, new PriceCalcConfigRepository(jdbc), new FactoryConfigRepository(jdbc), notifications);
        decisionRepository = new PricingDecisionRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests, costingRepository,
            tickets, fxRates, notifications);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        TicketService ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        salesRepId = createEmployee(employees, "พนักงานขาย สาม", "sales-step3@glr.co.th", "SALES", "แผนกขาย");
        long otherSalesId = createEmployee(employees, "พนักงานขาย อื่น", "sales-step3-other@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สาม", "import-step3@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร สาม", "ceo-step3@glr.co.th", "MD", "ผู้บริหาร");
        secondCeoUserId = createEmployee(employees, "ผู้บริหาร สอง", "ceo-step3-2@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี สาม", "account-step3@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย สาม", "sales-manager-step3@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        secondCeoActor = actor(secondCeoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                ('Factory A3', 'factory-a3@example.com', 'THB', 'piece', 'Thailand'),
                ('Factory B3', 'factory-b3@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryA = insertCatalogProduct("Factory A3", "TH", "TEST-A3-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B3", "TH", "TEST-B3-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory C3', 'factory-c3@example.com', 'THB', 'piece', 'TestLand3')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
        catalogProductIdFactoryC = insertCatalogProduct("Factory C3", "XX", "TEST-C3-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        jdbc.update("""
            INSERT INTO sales.price_calc_config
                (version, country, freight_per_sqm, insurance_per_sqm, inland_factory_to_port_per_sqm,
                 inland_port_to_warehouse_per_sqm, import_duty_pct, margin_pct, is_current)
            VALUES (1, 'TestLand3', 0, 0, 0, 0, 0, 0, TRUE)
            """, Map.of());

        CustomerDto customer = customers.create(
            "บริษัท Step 3 จำกัด", "0100000000003", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0003");
        ProjectDto project = projects.create(customer.id(), "โครงการ Step 3");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Step 3", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A3", "Factory A3"), ticketItem("Cotto", "Tile B3", "Factory B3"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Acceptance scenario (end to end, real Postgres)
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void acceptanceScenario_submitApproveAndSalesVisibility() {
        String ticketStatusBefore = jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        String salesStageBefore = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);

        long pricingRequestId = twoItemSubmittedCosting();

        // CEO opens the review.
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(decision.items()).hasSize(2);
        // Default margin (20%) applied to every item at creation.
        for (PricingDecisionItemDto item : decision.items()) {
            assertThat(item.proposedMarginPct()).isEqualByComparingTo("0.20");
            assertThat(item.proposedSellingPricePerRequestedUnit())
                .isEqualByComparingTo(item.frozenLandedCostPerRequestedUnitThb().multiply(new BigDecimal("1.20")));
        }

        // CEO changes Item A's margin to 35% and sets minimum selling prices for both items
        // (required at approval — design correction 5).
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            "ปรับ margin item A", List.of(
                new UpdatePricingDecisionItemRequest(itemA.id(), new BigDecimal("0.35"), new BigDecimal("0.10"),
                    new BigDecimal("90.00"), null),
                new UpdatePricingDecisionItemRequest(itemB.id(), null, new BigDecimal("0.10"),
                    new BigDecimal("90.00"), null))),
            ceoActor);
        PricingDecisionItemDto updatedA = itemById(updated, itemA.id());
        assertThat(updatedA.proposedMarginPct()).isEqualByComparingTo("0.35");
        assertThat(updatedA.proposedSellingPricePerRequestedUnit())
            .isEqualByComparingTo(updatedA.frozenLandedCostPerRequestedUnitThb().multiply(new BigDecimal("1.35")));

        // CEO approves all items.
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        assertThat(approved.status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(ceoUserId);
        for (PricingDecisionItemDto item : approved.items()) {
            assertThat(item.approvedMarginPct()).isEqualByComparingTo(item.proposedMarginPct());
            assertThat(item.approvedSellingPricePerRequestedUnit())
                .isEqualByComparingTo(item.frozenLandedCostPerRequestedUnitThb()
                    .multiply(BigDecimal.ONE.add(item.approvedMarginPct())));
        }
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);

        // Sales sees the approved selling prices...
        PricingDecisionSalesViewDto salesView = decisionService.salesView(pricingRequestId, salesActor);
        assertThat(salesView.items()).hasSize(2);
        assertThat(salesView.items()).extracting(i -> i.approvedSellingPricePerRequestedUnit())
            .containsExactlyInAnyOrderElementsOf(
                approved.items().stream().map(PricingDecisionItemDto::approvedSellingPricePerRequestedUnit).toList());

        // ...but cannot see raw cost (structural: PricingDecisionSalesItemDto has no cost/margin
        // accessor at all — see design correction 2). Confirm sales cannot reach the raw endpoints.
        assertThatThrownBy(() -> decisionService.get(decision.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Deal stage/status unchanged, no quotation created.
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(ticketStatusBefore);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo(salesStageBefore);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();

        // Exactly one approval event and one notification.
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_APPROVED'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM hr.notification
             WHERE employee_id = :salesRepId AND type = 'PRICING_DECISION_APPROVED'
            """, Map.of("salesRepId", salesRepId), Long.class)).isEqualTo(1L);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 1: unit basis
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A per-box request and a per-piece request at the SAME physical quantity (200 pieces / 10
     * boxes of 20) must land on the same TOTAL landed cost and the same total selling price at
     * the same margin — the per-requested-unit PRICE differs (it's per box vs per piece) but the
     * total does not. Mirrors {@code unitConversion_*} in
     * {@code PricingFactoryQuoteCostingIntegrationTest}.
     */
    @Test
    void unitBasis_perBoxAndPerPieceRequests_atSamePhysicalQuantity_produceTheSameTotal() {
        long perBoxRequestId = singleItemSubmittedCosting(new BigDecimal("10"), UnitBasis.PER_BOX,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00", new BigDecimal("0.5"), new BigDecimal("20"), null);
        long perPieceRequestId = singleItemSubmittedCosting(new BigDecimal("200"), UnitBasis.PER_PIECE,
            UnitBasis.PER_BOX, new BigDecimal("10.00"), "1000.00", new BigDecimal("0.5"), new BigDecimal("20"), null);

        PricingDecisionDto perBoxDecision = decisionService.startReview(perBoxRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.10"), "THB", null, null), ceoActor);
        PricingDecisionDto perPieceDecision = decisionService.startReview(perPieceRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.10"), "THB", null, null), ceoActor);

        PricingDecisionItemDto perBoxItem = perBoxDecision.items().get(0);
        PricingDecisionItemDto perPieceItem = perPieceDecision.items().get(0);

        assertThat(perBoxItem.requestedUnitBasis()).isEqualTo(UnitBasis.PER_BOX);
        assertThat(perPieceItem.requestedUnitBasis()).isEqualTo(UnitBasis.PER_PIECE);
        assertThat(perBoxItem.normalizedQuantityPieces()).isEqualByComparingTo("200.000000");
        assertThat(perPieceItem.normalizedQuantityPieces()).isEqualByComparingTo("200");

        // Per-REQUESTED-UNIT price differs (per box vs per piece)...
        assertThat(perBoxItem.frozenLandedCostPerRequestedUnitThb())
            .isNotEqualByComparingTo(perPieceItem.frozenLandedCostPerRequestedUnitThb());
        // ...but the TOTAL (price-per-unit * requested quantity) is identical.
        BigDecimal perBoxTotal = perBoxItem.frozenLandedCostPerRequestedUnitThb().multiply(perBoxItem.requestedQuantity());
        BigDecimal perPieceTotal = perPieceItem.frozenLandedCostPerRequestedUnitThb().multiply(perPieceItem.requestedQuantity());
        assertThat(perBoxTotal).isEqualByComparingTo("10000.0000");
        assertThat(perPieceTotal).isEqualByComparingTo("10000.0000");
        assertThat(perBoxTotal).isEqualByComparingTo(perPieceTotal);

        // Selling price at the same margin is also basis-invariant in TOTAL terms.
        BigDecimal perBoxSellingTotal = perBoxItem.proposedSellingPricePerRequestedUnit().multiply(perBoxItem.requestedQuantity());
        BigDecimal perPieceSellingTotal = perPieceItem.proposedSellingPricePerRequestedUnit().multiply(perPieceItem.requestedQuantity());
        assertThat(perBoxSellingTotal).isEqualByComparingTo(perPieceSellingTotal);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 2: never leak cost to Sales
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void salesView_returns404WhenNoDecisionHasBeenApprovedYet() {
        long pricingRequestId = twoItemSubmittedCosting();
        decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        assertThatThrownBy(() -> decisionService.salesView(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

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

    @Test
    void accountRole_cannotReachDecisionsCostingsOrFactoryQuotes() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        assertThatThrownBy(() -> decisionService.get(decision.id(), accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.list(pricingRequestId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.salesView(pricingRequestId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.list(pricingRequestId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, accountActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Stage L3 (release runbook): sales/sales_manager must never reach the RAW cost/margin/quote
    // endpoints — only the account denial was previously asserted
    // (accountRole_cannotReachDecisionsCostingsOrFactoryQuotes above). sales/sales_manager is the
    // more important direction (the whole point of the PCR split is that raw supplier prices,
    // landed cost, and CEO margin never reach sales), and was untested at the service level.
    // Written wrong-way-round: can sales/sales_manager reach the raw endpoint.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * MUTATION-CHECK RECORD (actually run, not simulated): temporarily changed {@code
     * PricingDecisionService.RAW_DECISION_ROLES} from {@code Set.of("import", "ceo")} to {@code
     * Set.of("import", "ceo", "sales")} (reintroducing the leak this test guards against) and ran
     * this class. Two tests went red, both expected — both assert the same guard for a
     * {@code salesActor}: this test ({@code salesAndSalesManager_cannotReachRawPricingDecision})
     * and the pre-existing {@code acceptanceScenario_submitApproveAndSalesVisibility}, which has
     * its own {@code decisionService.get}/{@code .list} sales-denial assertion baked in near its
     * end. No other test in the 17-test class flipped. Reverted {@code RAW_DECISION_ROLES};
     * {@code git diff} against the pre-mutation tree was empty afterwards.
     */
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

    @Test
    void salesAndSalesManager_cannotReachRawPricingCosting() {
        long pricingRequestId = twoItemSubmittedCosting();
        long costingId = costingService.list(pricingRequestId, importActor).get(0).id();

        assertThatThrownBy(() -> costingService.get(costingId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.get(costingId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Positive control: import/ceo (RAW_COSTING_ROLES) are unaffected.
        assertThat(costingService.get(costingId, ceoActor)).isNotNull();
        assertThat(costingService.list(pricingRequestId, importActor)).isNotEmpty();
    }

    @Test
    void salesAndSalesManager_cannotReachRawFactoryQuote() {
        long pricingRequestId = twoItemSubmittedCosting();
        long quoteId = factoryQuoteRepository.findCurrentByFactory(pricingRequestId, "Factory A3").orElseThrow().id();

        assertThatThrownBy(() -> factoryQuoteService.get(quoteId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.get(quoteId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.list(pricingRequestId, salesManagerActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Positive control: import/ceo (RAW_QUOTE_ROLES) are unaffected.
        assertThat(factoryQuoteService.get(quoteId, ceoActor)).isNotNull();
        assertThat(factoryQuoteService.list(pricingRequestId, importActor)).isNotEmpty();
    }

    /**
     * Positive control for the L3 redaction (not a blackout): the owning sales rep, AND the sales
     * manager, can still reach the approved selling price through {@code salesView} even though
     * both are locked out of the raw {@code get}/{@code list} endpoints above.
     */
    @Test
    void salesAndSalesManager_canStillReachSalesViewForApprovedDecision() {
        long pricingRequestId = twoItemSubmittedCosting();
        approveWithFlatMargin(pricingRequestId, new BigDecimal("0.20"));

        assertThat(decisionService.salesView(pricingRequestId, salesActor).items()).isNotEmpty();
        assertThat(decisionService.salesView(pricingRequestId, salesManagerActor).items()).isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Stage L4 (release runbook): ceo is in RAW_DECISION_ROLES/RAW_COSTING_ROLES/RAW_QUOTE_ROLES
    // for READS, but must stay read-only — factory-quote and costing MUTATIONS are
    // IMPORT_ROLES-only. Untested until now. Written wrong-way-round: can ceo mutate.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * MUTATION-CHECK RECORD (actually run, not simulated): temporarily changed {@code
     * FactoryQuoteService.IMPORT_ROLES} from {@code Set.of("import")} to {@code Set.of("import",
     * "ceo")} (reintroducing the vulnerability this test guards against) and ran this class.
     * {@code ceo_cannotMutateFactoryQuoteOrCosting_importOnlyRemainsAbleTo} went red at its first
     * assertion (the {@code send} call): with {@code ceo} added to {@code IMPORT_ROLES}, {@code
     * requireRole} no longer throws, so the call falls through to the next guard
     * ({@code requireMutablePricingRequest}, DRAFT_STATUSES) and throws {@code 409 CONFLICT}
     * instead of the expected {@code 403 FORBIDDEN} — AssertJ halts the test at that first failed
     * assertion, so {@code receive}/{@code markReadyForCosting} never ran this pass, but the role
     * gate's removal is unambiguously what the red test is reporting (a 409 instead of a clean
     * 403 is still proof {@code requireRole} stopped blocking {@code ceoActor} first). No other
     * test in the 17-test class flipped. Reverted {@code IMPORT_ROLES}; {@code git diff} was
     * empty afterwards.
     */
    @Test
    void ceo_cannotMutateFactoryQuoteOrCosting_importOnlyRemainsAbleTo() {
        long pricingRequestId = twoItemSubmittedCosting();
        FactoryQuoteDto quoteBefore = factoryQuoteRepository.findCurrentByFactory(pricingRequestId, "Factory A3").orElseThrow();
        long quoteId = quoteBefore.id();
        long costingId = costingService.list(pricingRequestId, importActor).get(0).id();
        int costingCountBefore = costingService.list(pricingRequestId, importActor).size();

        // ceo cannot mutate the factory quote — requireRole runs before any state lookup, so this
        // is provably a role check, not a side effect of the quote's current status (already
        // READY_FOR_COSTING, with a SUBMITTED costing on top, by the time twoItemSubmittedCosting()
        // returns).
        assertThatThrownBy(() -> factoryQuoteService.send(quoteId,
                new SendFactoryQuoteRequest("ceo-attempt@example.com", null, null, UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.receive(quoteId,
                new ReceiveFactoryQuoteRequest("REF-CEO-ATTEMPT", "THB", "30 days", "45 days", "revision", "note",
                    List.of(new ReceiveFactoryQuoteItemRequest(
                        1L, null, null, new BigDecimal("1.00"), "piece", "piece",
                        new BigDecimal("999.00"), "THB", null, new BigDecimal("1.00"), null, null,
                        "45 days", null, null)),
                    UUID.randomUUID().toString()),
                ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> factoryQuoteService.markReadyForCosting(quoteId, ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // DB re-read: the quote row is untouched by any of the three failed ceo attempts.
        FactoryQuoteDto quoteAfter = factoryQuoteRepository.findCurrentByFactory(pricingRequestId, "Factory A3").orElseThrow();
        assertThat(quoteAfter.status()).isEqualTo(quoteBefore.status());
        assertThat(quoteAfter.updatedAt()).isEqualTo(quoteBefore.updatedAt());

        // ceo cannot mutate costing either.
        assertThatThrownBy(() -> costingService.createDraft(pricingRequestId,
                new CreateCostingRequest("ceo-attempt", UUID.randomUUID().toString()), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.recalculate(costingId, new RecalculateCostingRequest("ceo-attempt"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> costingService.submit(costingId, new SubmitCostingRequest("ceo-attempt"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // DB re-read: no new/changed costing rows from any of the ceo attempts.
        assertThat(costingService.list(pricingRequestId, importActor)).hasSize(costingCountBefore);

        // Positive control: import (IMPORT_ROLES) still can — proven by twoItemSubmittedCosting()
        // itself already having driven this exact pricing request's factory quote and costing
        // through send/receive/markReadyForCosting/createDraft/recalculate/submit as importActor.
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 3: freeze factory/costing mutations from CEO_REVIEWING
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void ceoReviewing_freezesFactoryQuoteAndCostingMutations() {
        long pricingRequestId = twoItemSubmittedCosting();
        long anyQuoteId = factoryQuoteRepository.findCurrentByFactory(pricingRequestId, "Factory A3").orElseThrow().id();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.CEO_REVIEWING);

        assertThatThrownBy(() -> factoryQuoteService.startNegotiation(anyQuoteId,
            new StartNegotiationRequest("note"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> factoryQuoteService.markNotAvailable(anyQuoteId,
            new MarkNotAvailableRequest("unavailable"), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> factoryQuoteService.uploadAttachment(anyQuoteId,
            new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes()), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("v2", null), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 4: one return-to-Import path
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void returnToImport_reachesCostingRevisionRequired_andImportCanReopenCosting() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);

        PricingDecisionDto returned = decisionService.returnToImport(decision.id(),
            new ReturnPricingDecisionRequest("ราคาต้นทุนคลาดเคลื่อน กรุณาคำนวณใหม่"), ceoActor);
        assertThat(returned.status()).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_REVISION_REQUIRED);

        // Import can now reopen costing — the single named return path.
        PricingCostingDto v2 = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("v2", null), importActor);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);
        costingService.recalculate(v2.id(), new RecalculateCostingRequest("v2 pass 1"), importActor);
        PricingCostingDto v2Calculated = costingService.recalculate(v2.id(), new RecalculateCostingRequest("v2 pass 2"), importActor);
        PricingCostingDto v2Submitted = costingService.submit(v2Calculated.id(), new SubmitCostingRequest("resubmit"), importActor);
        assertThat(v2Submitted.status()).isEqualTo(th.co.glr.hr.pricingcosting.PricingCostingStatus.SUBMITTED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // CEO can start a SECOND decision version against the new costing; the first RETURNED
        // decision stays readable as history.
        PricingDecisionDto decisionV2 = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.15"), "THB", null, null), ceoActor);
        assertThat(decisionV2.decisionVersionNo()).isEqualTo(2);
        assertThat(decisionV2.pricingCostingId()).isEqualTo(v2Submitted.id());
        List<PricingDecisionDto> history = decisionService.list(pricingRequestId, ceoActor);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).status()).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(history.get(1).status()).isEqualTo(PricingDecisionStatus.DRAFT);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 5+7: minimum selling price + margin required, server recomputes
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approve_rejectsWhenAnyItemLacksMinimumSellingPriceOrMargin() {
        long pricingRequestId = twoItemSubmittedCosting();
        // No defaultMarginPct at all -> every item starts with a null margin and null price.
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(null, "THB", null, null), ceoActor);

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest(null, null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // Set margin on both items but leave minimum selling price null on one -> still rejected.
        PricingDecisionItemDto itemA = decision.items().get(0);
        PricingDecisionItemDto itemB = decision.items().get(1);
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(itemA.id(), new BigDecimal("0.2"), null, new BigDecimal("10"), null),
            new UpdatePricingDecisionItemRequest(itemB.id(), new BigDecimal("0.2"), null, null, null))), ceoActor);

        assertThatThrownBy(() -> decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest(null, null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // Fill in the last minimum selling price -> approval now succeeds.
        decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
            new UpdatePricingDecisionItemRequest(itemB.id(), null, null, new BigDecimal("10"), null))), ceoActor);
        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest(null, null), ceoActor);
        assertThat(approved.status()).isEqualTo(PricingDecisionStatus.APPROVED);
    }

    /**
     * Design correction 7: the server must recompute the approved selling price from the frozen
     * cost and the margin being frozen in — never trust a stored/client-influenced selling price
     * verbatim. Proven by corrupting the stored {@code proposed_selling_price_per_requested_unit}
     * directly via SQL between PUT and approve, then asserting the APPROVED price is still the
     * mathematically-correct value, not the corrupted one.
     */
    @Test
    void approve_recomputesSellingPriceFromCostAndMargin_ignoringAnyStoredOrCorruptedPrice() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
        }
        PricingDecisionItemDto item = decision.items().get(0);
        BigDecimal correctPrice = item.frozenLandedCostPerRequestedUnitThb().multiply(new BigDecimal("1.20"))
            .setScale(4, java.math.RoundingMode.HALF_UP);

        // Corrupt the stored proposed price directly — a malicious/buggy client cannot influence
        // approval through this column.
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Design correction 8: idempotent + concurrency-safe approval
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approveRetryWithSameClientRequestId_isIdempotent_doesNotDuplicateEventOrNotification() {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
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

    /**
     * Two CEOs racing to approve the SAME decision: exactly one wins, the other is refused
     * cleanly (409), and exactly one approval event / one notification is recorded. Mirrors
     * {@code createCustomerChangeRevisionSerializesConcurrentCallersOnTheSameChainAndAvoidsDuplicateRevisionNumbers}
     * in {@code PricingFactoryQuoteCostingIntegrationTest} — this harness wires services with
     * {@code new}, so {@code @Transactional} is inert without an explicit transaction; wrap each
     * racing call in a {@code TransactionTemplate} bound to the same DataSource so the advisory
     * lock (transaction-scoped) actually spans the whole {@code approve()} call.
     */
    @Test
    void approveConcurrently_exactlyOneWins_oneApprovalEventOneNotification() throws Exception {
        long pricingRequestId = twoItemSubmittedCosting();
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
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

    @Test
    void onlyOneApprovedDecisionCanEverExist_databaseLevel() {
        long pricingRequestId = twoItemSubmittedCosting();
        approveWithFlatMargin(pricingRequestId, new BigDecimal("0.20"));

        // A second APPROVED row for the same pricing request, inserted directly (bypassing the
        // service entirely), must violate the partial unique index — proves the DB-level
        // backstop described in V72's own comment is real, not just documentation.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO sales.pricing_decision
                (decision_code, pricing_request_id, pricing_costing_id, decision_version_no, status,
                 currency, fx_rate_used, fx_source, fx_effective_date, approved_by, approved_at)
            SELECT 'PCD-TEST-DUP', pricing_request_id, pricing_costing_id, 99, 'APPROVED',
                   currency, fx_rate_used, fx_source, fx_effective_date, approved_by, now()
              FROM sales.pricing_decision WHERE pricing_request_id = :id AND status = 'APPROVED'
            """, Map.of("id", pricingRequestId)))
            .isInstanceOf(DuplicateKeyException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private PricingDecisionItemDto itemById(PricingDecisionDto decision, long itemId) {
        return decision.items().stream().filter(i -> i.id() == itemId).findFirst().orElseThrow();
    }

    private PricingDecisionDto approveWithFlatMargin(long pricingRequestId, BigDecimal marginPct) {
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(marginPct, "THB", null, null), ceoActor);
        for (PricingDecisionItemDto item : decision.items()) {
            decisionService.update(decision.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
        }
        return decisionService.approve(decision.id(), new ApprovePricingDecisionRequest("อนุมัติ", null), ceoActor);
    }

    /** Two-item, two-factory scenario driven to a SUBMITTED costing (Step 2's own flow), the
     * precondition every Step 3 operation starts from. */
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
        PricingCostingDto draft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        costingService.recalculate(draft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(draft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        return pricingRequestId;
    }

    /** Single-item, single-factory (zero-cost-config "Factory C3") scenario, mirroring
     * {@code PricingFactoryQuoteCostingIntegrationTest#singleItemCosting} but driven all the way
     * to a SUBMITTED costing. */
    private long singleItemSubmittedCosting(
        BigDecimal requestedQty, String requestedUnitBasis, String quotedUnitBasis, BigDecimal quotedQuantity,
        String rawPrice, BigDecimal sqmPerUnit, BigDecimal piecesPerBox, BigDecimal linearMPerUnit
    ) {
        PricingRequestRequests.PricingRequestItemRequest item = new PricingRequestRequests.PricingRequestItemRequest(
            null, catalogProductIdFactoryC, null, "TestBrand3", "TestModel3", "TestBrand3 TestModel3",
            null, null, "1x1", "Factory C3", requestedQty, requestedQty, "unit", requestedUnitBasis,
            QuantityType.CONFIRMED, null, null, null);
        PricingRequestRequests.CreatePricingRequestRequest request = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "step 3 unit test", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        FactoryQuoteDto draft = quoteFor(factoryQuoteService.generateDrafts(pricingRequestId, importActor), "Factory C3");
        ReceiveFactoryQuoteRequest response = new ReceiveFactoryQuoteRequest("REF-UNIT", "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                draft.items().get(0).pricingRequestItemId(), null, null, quotedQuantity, quotedUnitBasis, quotedUnitBasis,
                new BigDecimal(rawPrice), "THB", null, sqmPerUnit, piecesPerBox, linearMPerUnit,
                "45 days", null, null)),
            UUID.randomUUID().toString());
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(), response, importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("unit test", UUID.randomUUID().toString()), importActor);
        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
        return pricingRequestId;
    }

    private FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream().filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
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
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
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
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
