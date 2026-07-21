package th.co.glr.hr.pricingchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationDto;
import th.co.glr.hr.customerquotation.CustomerQuotationDtos.CustomerQuotationItemDto;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.CreateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.IssueCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationItemRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationRequests.UpdateCustomerQuotationRequest;
import th.co.glr.hr.customerquotation.CustomerQuotationService;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factory.FactoryEmailService;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.SendFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PriceCalcConfigRepository;
import th.co.glr.hr.pricing.PriceCalcService;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingDto;
import th.co.glr.hr.pricingcosting.PricingCostingDtos.PricingCostingItemDto;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.CreateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.RecalculateCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingRequests.SubmitCostingRequest;
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingCostingStatus;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionSalesViewDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ReturnPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingdecision.PricingDecisionStatus;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Composition UAT for the ENTIRE sales pricing chain (Steps 1-4), driven end to end on a single
 * deal through the real services — no mocks except the collaborators the per-step tests
 * themselves already mock ({@link FactoryEmailService}, {@link PriceCalcService}). Every
 * state transition below is produced by calling the real service that owns it; nothing is
 * hand-rolled via SQL to skip a step. The point of this file is NOT to re-prove any single
 * step's own business rules (unit-conversion math, idempotency, concurrency, etc. — all
 * already covered by {@code PricingRequestFlowIntegrationTest},
 * {@code PricingFactoryQuoteCostingIntegrationTest}, {@code PricingDecisionIntegrationTest} and
 * {@code CustomerQuotationIntegrationTest}); it is to catch a bug where one step writes a field
 * the next step reads differently — a class of bug the four isolated per-step tests cannot see
 * because each one only ever hand-builds its OWN precondition.
 */
class PricingChainEndToEndIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteRepository factoryQuoteRepository;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private PricingDecisionService decisionService;
    private CustomerQuotationRepository quotationRepository;
    private CustomerQuotationService quotationService;
    private TicketService ticketService;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private long accountUserId;
    private long salesManagerUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;

    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    private static final String FACTORY_A = "Factory A-Chain";
    private static final String FACTORY_B = "Factory B-Chain";

    @BeforeEach
    void wireAllFiveServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-chain-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage);

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        factoryQuoteRepository = factoryQuotes;
        FactoryEmailService factoryEmail = mock(FactoryEmailService.class);
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(UUID.randomUUID().toString());
        when(factoryEmail.send(anyLong(), anyString(), anyString(), any(), any(), any()))
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

        // PriceCalcService is unrelated legacy-quotation business logic that no step on this
        // chain exercises (Step 4 deliberately never touches sales.ticket_item price columns) —
        // mocked per the same precedent every per-step test already uses.
        PriceCalcService priceCalcMock = mock(PriceCalcService.class);
        ticketService = new TicketService(tickets, notifications, priceCalcMock,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);

        quotationRepository = new CustomerQuotationRepository(jdbc);
        quotationService = new CustomerQuotationService(quotationRepository, pricingRequests, decisionRepository,
            tickets, ticketService, customers, new QuotationRenderer(), notifications);

        salesRepId = createEmployee(employees, "พนักงานขาย เชน", "sales-chain@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า เชน", "import-chain@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร เชน", "ceo-chain@glr.co.th", "MD", "ผู้บริหาร");
        accountUserId = createEmployee(employees, "บัญชี เชน", "account-chain@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย เชน", "sales-manager-chain@glr.co.th", "SALES", "ฝ่ายขาย");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");

        // Two DIFFERENT factories, both country 'Thailand' (a real, non-all-zero
        // price_calc_config row already seeded by V26 — see that migration), so the chain
        // exercises real freight/insurance/duty/inland arithmetic end to end, not the all-zero
        // shortcut some per-step tests use for isolating unit-conversion math.
        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES
                (:factoryA, 'factory-a-chain@example.com', 'THB', 'piece', 'Thailand'),
                (:factoryB, 'factory-b-chain@example.com', 'THB', 'piece', 'Thailand')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of("factoryA", FACTORY_A, "factoryB", FACTORY_B));
        catalogProductIdFactoryA = insertCatalogProduct(FACTORY_A, "TH", "TEST-CHAIN-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct(FACTORY_B, "TH", "TEST-CHAIN-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Chain Test จำกัด", "0100000000009", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0009");
        ProjectDto project = projects.create(customer.id(), "โครงการ Chain Test");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Chain Test", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile Chain A", FACTORY_A), ticketItem("Cotto", "Tile Chain B", FACTORY_B))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The full chain: Steps 1 -> 2 -> 3 -> 4, one deal, two factories, mixed unit basis.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void fullPricingChain_stepOneThroughStepFour_composesWithoutShortcuts() {
        String lifecycleBefore = jdbc.queryForObject(
            "SELECT lifecycle FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(lifecycleBefore).isEqualTo(DealLifecycle.ACTIVE);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();

        // ── Step 1: create + submit the pricing request (real PricingRequestService) ──────
        PricingRequestRequests.CreatePricingRequestRequest createRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "chain acceptance walk", UUID.randomUUID().toString(),
            List.of(
                pricingItemPerPiece(FACTORY_A, catalogProductIdFactoryA, "SCG", "Tile Chain A", new BigDecimal("10")),
                pricingItemPerBox(FACTORY_B, catalogProductIdFactoryB, "Cotto", "Tile Chain B", new BigDecimal("5"))));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, createRequest, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.SUBMITTED);
        // Catalog snapshot must be fully resolved for BOTH items (Finding A gate).
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_item
             WHERE pricing_request_id = :id AND catalog_price_id IS NOT NULL AND catalog_base_price IS NOT NULL
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);

        // ── Step 1 -> 2 handoff: pickup, generate drafts, send via the real outbox worker ──
        pricingRequestService.pickup(pricingRequestId, importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        assertThat(drafts).hasSize(2);
        FactoryQuoteDto draftA = quoteFor(drafts, FACTORY_A);
        FactoryQuoteDto draftB = quoteFor(drafts, FACTORY_B);
        long itemAId = draftA.items().get(0).pricingRequestItemId();
        long itemBId = draftB.items().get(0).pricingRequestItemId();

        factoryQuoteService.send(draftA.id(),
            new SendFactoryQuoteRequest("factory-a-chain@example.com", null, null, UUID.randomUUID().toString()),
            importActor);
        factoryQuoteService.send(draftB.id(),
            new SendFactoryQuoteRequest("factory-b-chain@example.com", null, null, UUID.randomUUID().toString()),
            importActor);
        drainDispatches(); // real outbox worker path — the actual send() codepath production uses
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'FACTORY_EMAIL_SENT'
            """, Map.of("id", pricingRequestId), Long.class)).isEqualTo(2L);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);

        // Factory A responds first: multi-factory progression must NOT jump ahead while Factory
        // B is still pending.
        FactoryQuoteDto responseA = factoryQuoteService.receive(draftA.id(),
            responsePerPiece("REF-CHAIN-A", "100.00", itemAId), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        factoryQuoteService.markReadyForCosting(responseA.id(), importActor);

        FactoryQuoteDto responseB = factoryQuoteService.receive(draftB.id(),
            responsePerBox("REF-CHAIN-B", "1000.00", itemBId, new BigDecimal("5")), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.AWAITING_FACTORY_RESPONSE);
        factoryQuoteService.markReadyForCosting(responseB.id(), importActor);

        // ── Step 2: costing (real PricingCostingService), only now do both quotes exist ────
        PricingCostingDto costingDraft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("chain draft", null), importActor);
        assertThat(costingDraft.status()).isEqualTo(PricingCostingStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);

        costingService.recalculate(costingDraft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(costingDraft.id(),
            new RecalculateCostingRequest("pass 2"), importActor);
        assertThat(calculated.status()).isEqualTo(PricingCostingStatus.CALCULATED);

        PricingCostingDto submittedCosting = costingService.submit(calculated.id(),
            new SubmitCostingRequest("submit to CEO"), importActor);
        assertThat(submittedCosting.status()).isEqualTo(PricingCostingStatus.SUBMITTED);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        Map<Long, PricingCostingItemDto> costingItemsByRequestItem = new java.util.HashMap<>();
        for (PricingCostingItemDto item : costingService.get(submittedCosting.id(), ceoActor).items()) {
            costingItemsByRequestItem.put(item.pricingRequestItemId(), item);
        }
        assertThat(costingItemsByRequestItem).containsKeys(itemAId, itemBId);

        // ── Step 3: CEO selling-price decision (real PricingDecisionService) ───────────────
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decision.status()).isEqualTo(PricingDecisionStatus.DRAFT);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.CEO_REVIEWING);
        assertThat(decision.items()).hasSize(2);

        // Composition proof: the decision's frozen per-piece cost must trace back BYTE-FOR-BYTE
        // to the submitted costing item's own landed-cost-per-piece figure, not a recomputation.
        PricingDecisionItemDto rawItemA = decisionItemFor(decision, itemAId);
        PricingDecisionItemDto rawItemB = decisionItemFor(decision, itemBId);
        assertThat(rawItemA.frozenLandedCostPerPieceThb())
            .isEqualByComparingTo(costingItemsByRequestItem.get(itemAId).landedCostPerUnitThb());
        assertThat(rawItemB.frozenLandedCostPerPieceThb())
            .isEqualByComparingTo(costingItemsByRequestItem.get(itemBId).landedCostPerUnitThb());
        assertThat(rawItemA.requestedUnitBasis()).isEqualTo(UnitBasis.PER_PIECE);
        assertThat(rawItemB.requestedUnitBasis()).isEqualTo(UnitBasis.PER_BOX);

        // CEO changes item A's margin, sets minimum selling prices on both (required at approval).
        PricingDecisionDto updated = decisionService.update(decision.id(), new UpdatePricingDecisionRequest(
            "ปรับ margin item A", List.of(
                new UpdatePricingDecisionItemRequest(rawItemA.id(), new BigDecimal("0.35"), null,
                    new BigDecimal("10.00"), null),
                new UpdatePricingDecisionItemRequest(rawItemB.id(), null, null,
                    new BigDecimal("10.00"), null))),
            ceoActor);
        PricingDecisionItemDto updatedItemA = decisionItemFor(updated, itemAId);
        assertThat(updatedItemA.proposedMarginPct()).isEqualByComparingTo("0.35");

        PricingDecisionDto approved = decisionService.approve(decision.id(),
            new ApprovePricingDecisionRequest("อนุมัติ chain test", UUID.randomUUID().toString()), ceoActor);
        assertThat(approved.status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(pricingRequestService.get(pricingRequestId, ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);

        PricingDecisionItemDto approvedItemA = decisionItemFor(approved, itemAId);
        PricingDecisionItemDto approvedItemB = decisionItemFor(approved, itemBId);
        BigDecimal expectedApprovedA = approvedItemA.frozenLandedCostPerRequestedUnitThb()
            .multiply(BigDecimal.ONE.add(approvedItemA.approvedMarginPct())).setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedApprovedB = approvedItemB.frozenLandedCostPerRequestedUnitThb()
            .multiply(BigDecimal.ONE.add(approvedItemB.approvedMarginPct())).setScale(4, RoundingMode.HALF_UP);
        assertThat(approvedItemA.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(expectedApprovedA);
        assertThat(approvedItemB.approvedSellingPricePerRequestedUnit()).isEqualByComparingTo(expectedApprovedB);
        assertThat(approvedItemA.approvedMarginPct()).isEqualByComparingTo("0.35");

        // Sales-facing projection sees the approved price; raw decision endpoints stay forbidden.
        PricingDecisionSalesViewDto salesView = decisionService.salesView(pricingRequestId, salesActor);
        assertThat(salesView.items()).extracting(i -> i.approvedSellingPricePerRequestedUnit())
            .containsExactlyInAnyOrder(
                approvedItemA.approvedSellingPricePerRequestedUnit(), approvedItemB.approvedSellingPricePerRequestedUnit());
        assertThatThrownBy(() -> decisionService.get(approved.id(), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> decisionService.list(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // No quotation exists yet — Step 4 hasn't started.
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();

        // ── Step 4: customer quotation (real CustomerQuotationService) ─────────────────────
        CustomerQuotationDto draftQuotation = quotationService.create(pricingRequestId,
            new CreateCustomerQuotationRequest("30 days", "45 days", "รถขนส่ง", LocalDate.now().plusDays(30),
                "หมายเหตุลูกค้า", UUID.randomUUID().toString()), salesActor);
        assertThat(draftQuotation.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(draftQuotation.items()).hasSize(2);

        CustomerQuotationItemDto quoteItemA = quotationItemFor(draftQuotation, itemAId);
        CustomerQuotationItemDto quoteItemB = quotationItemFor(draftQuotation, itemBId);
        // Trace-back proof: the quotation's approved price is EXACTLY the CEO-approved price for
        // that exact item — not a recomputation, not a different item's price.
        assertThat(quoteItemA.approvedUnitPrice()).isEqualByComparingTo(approvedItemA.approvedSellingPricePerRequestedUnit());
        assertThat(quoteItemB.approvedUnitPrice()).isEqualByComparingTo(approvedItemB.approvedSellingPricePerRequestedUnit());
        assertThat(quoteItemA.finalUnitPrice()).isEqualByComparingTo(quoteItemA.approvedUnitPrice());
        assertThat(quoteItemB.finalUnitPrice()).isEqualByComparingTo(quoteItemB.approvedUnitPrice());
        assertThat(quoteItemA.requestedUnitBasis()).isEqualTo(UnitBasis.PER_PIECE);
        assertThat(quoteItemB.requestedUnitBasis()).isEqualTo(UnitBasis.PER_BOX);

        // Sales applies a permitted discount to item A only.
        BigDecimal permittedDiscount = quoteItemA.approvedUnitPrice()
            .subtract(quoteItemA.minimumSellingPricePerRequestedUnit())
            .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        CustomerQuotationDto discounted = quotationService.update(draftQuotation.id(), new UpdateCustomerQuotationRequest(
            null, null, null, null, null,
            List.of(new UpdateCustomerQuotationItemRequest(quoteItemA.id(), "รายการ A", "หมายเหตุ A", permittedDiscount))),
            salesActor);
        CustomerQuotationItemDto discountedItemA = quotationItemFor(discounted, itemAId);
        assertThat(discountedItemA.finalUnitPrice())
            .isEqualByComparingTo(quoteItemA.approvedUnitPrice().subtract(permittedDiscount));

        // Preview must be a pure read: no stage move, no pricing-request status move, nothing issued.
        CustomerQuotationDto previewed = quotationService.preview(discounted.id(), salesActor);
        assertThat(previewed.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isNotIn("QUOTE_DESIGN_SIDE", "QUOTE_BUYER");
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.quotation WHERE ticket_id = :id AND doc_status = 'ISSUED'",
            Map.of("id", ticketId), Long.class)).isZero();

        CustomerQuotationDto issued = quotationService.issue(discounted.id(),
            new IssueCustomerQuotationRequest(UUID.randomUUID().toString()), salesActor);
        assertThat(issued.docStatus()).isEqualTo(QuotationStatus.ISSUED);
        assertThat(pricingRequestService.get(pricingRequestId, salesActor).summary().status())
            .isEqualTo(PricingRequestStatus.QUOTATION_ISSUED);
        assertThat(jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class))
            .isEqualTo("QUOTE_DESIGN_SIDE");

        CustomerQuotationItemDto issuedItemA = quotationItemFor(issued, itemAId);
        CustomerQuotationItemDto issuedItemB = quotationItemFor(issued, itemBId);
        BigDecimal expectedLineSubtotalA = issuedItemA.finalUnitPrice().multiply(issuedItemA.requestedQuantity())
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedLineSubtotalB = issuedItemB.finalUnitPrice().multiply(issuedItemB.requestedQuantity())
            .setScale(2, RoundingMode.HALF_UP);
        assertThat(issuedItemA.lineSubtotal()).isEqualByComparingTo(expectedLineSubtotalA);
        assertThat(issuedItemB.lineSubtotal()).isEqualByComparingTo(expectedLineSubtotalB);
        // Item B took no discount: its final price is EXACTLY the CEO-approved price, byte-for-byte.
        assertThat(issuedItemB.finalUnitPrice()).isEqualByComparingTo(approvedItemB.approvedSellingPricePerRequestedUnit());

        // ── Composition proof: unit basis stays coherent across the WHOLE chain per item ────
        assertUnitBasisCoherentAcrossChain(itemAId, pricingRequestId, submittedCosting.id(), approved.id(), issued.id(),
            UnitBasis.PER_PIECE);
        assertUnitBasisCoherentAcrossChain(itemBId, pricingRequestId, submittedCosting.id(), approved.id(), issued.id(),
            UnitBasis.PER_BOX);

        // ── Legacy sales.ticket_item price columns were NEVER written by this chain ─────────
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*)
              FROM sales.ticket_item
             WHERE ticket_id = :ticketId
               AND (raw_price IS NOT NULL OR calced_cost IS NOT NULL OR proposed_price IS NOT NULL OR approved_price IS NOT NULL)
            """, Map.of("ticketId", ticketId), Long.class)).isZero();

        // ── Exactly one of each key milestone event across the chain ────────────────────────
        assertEventCount(pricingRequestId, PricingRequestEventKind.PRICING_REQUEST_CREATED, 1);
        assertEventCount(pricingRequestId, PricingRequestEventKind.FACTORY_EMAIL_SENT, 2); // one per factory
        assertEventCount(pricingRequestId, PricingRequestEventKind.PRICING_DECISION_APPROVED, 1);
        assertEventCount(pricingRequestId, PricingRequestEventKind.CUSTOMER_QUOTATION_ISSUED, 1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind
            """, Map.of("id", ticketId, "kind", TicketEventKind.QUOTATION_ISSUED), Long.class)).isEqualTo(1L);

        // ── The deal lifecycle stayed ACTIVE for the entire chain ───────────────────────────
        String lifecycleAfter = jdbc.queryForObject(
            "SELECT lifecycle FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
        assertThat(lifecycleAfter).isEqualTo(DealLifecycle.ACTIVE);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The cost-affecting return loop (design correction 4): CEO_REVIEWING ->
    // returnToImport -> COSTING_REVISION_REQUIRED -> new costing version -> CEO approves v2.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void costAffectingReturnLoop_composesThroughANewCostingVersionAndASecondDecision() {
        PricingChainFixture fixture = driveToReadyForCeoReview();

        PricingDecisionDto decisionV1 = decisionService.startReview(fixture.pricingRequestId(),
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decisionV1.decisionVersionNo()).isEqualTo(1);

        PricingDecisionDto returned = decisionService.returnToImport(decisionV1.id(),
            new ReturnPricingDecisionRequest("ราคาต้นทุนคลาดเคลื่อน กรุณาคำนวณใหม่"), ceoActor);
        assertThat(returned.status()).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(pricingRequestService.get(fixture.pricingRequestId(), importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_REVISION_REQUIRED);

        // Import reopens costing through the ONE named return path.
        PricingCostingDto costingV2 = costingService.createDraft(fixture.pricingRequestId(),
            new CreateCostingRequest("v2", null), importActor);
        assertThat(costingV2.versionNo()).isEqualTo(2);
        assertThat(pricingRequestService.get(fixture.pricingRequestId(), importActor).summary().status())
            .isEqualTo(PricingRequestStatus.COSTING_IN_PROGRESS);
        costingService.recalculate(costingV2.id(), new RecalculateCostingRequest("v2 pass 1"), importActor);
        PricingCostingDto costingV2Calculated = costingService.recalculate(costingV2.id(),
            new RecalculateCostingRequest("v2 pass 2"), importActor);
        PricingCostingDto costingV2Submitted = costingService.submit(costingV2Calculated.id(),
            new SubmitCostingRequest("resubmit v2"), importActor);
        assertThat(costingV2Submitted.status()).isEqualTo(PricingCostingStatus.SUBMITTED);
        assertThat(pricingRequestService.get(fixture.pricingRequestId(), importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        // CEO starts a SECOND decision version against the NEW costing and approves it.
        PricingDecisionDto decisionV2 = decisionService.startReview(fixture.pricingRequestId(),
            new StartPricingDecisionRequest(new BigDecimal("0.15"), "THB", null, UUID.randomUUID().toString()), ceoActor);
        assertThat(decisionV2.decisionVersionNo()).isEqualTo(2);
        assertThat(decisionV2.pricingCostingId()).isEqualTo(costingV2Submitted.id());
        for (PricingDecisionItemDto item : decisionV2.items()) {
            decisionService.update(decisionV2.id(), new UpdatePricingDecisionRequest(null, List.of(
                new UpdatePricingDecisionItemRequest(item.id(), null, null, new BigDecimal("1.00"), null))), ceoActor);
        }
        PricingDecisionDto approvedV2 = decisionService.approve(decisionV2.id(),
            new ApprovePricingDecisionRequest("อนุมัติ v2", UUID.randomUUID().toString()), ceoActor);
        assertThat(approvedV2.status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(pricingRequestService.get(fixture.pricingRequestId(), ceoActor).summary().status())
            .isEqualTo(PricingRequestStatus.APPROVED_FOR_QUOTATION);

        // The v1 RETURNED decision stays readable as history; exactly one row is APPROVED.
        List<PricingDecisionDto> history = decisionService.list(fixture.pricingRequestId(), ceoActor);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).status()).isEqualTo(PricingDecisionStatus.RETURNED);
        assertThat(history.get(1).status()).isEqualTo(PricingDecisionStatus.APPROVED);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_decision WHERE pricing_request_id = :id AND status = 'APPROVED'
            """, Map.of("id", fixture.pricingRequestId()), Long.class)).isEqualTo(1L);

        // The approved decision now composes into a customer quotation exactly as the main
        // chain test proves — the returned/superseded v1 leaves no trace in the final price.
        CustomerQuotationDto quotation = quotationService.create(fixture.pricingRequestId(),
            new CreateCustomerQuotationRequest(null, null, null, null, null, null), salesActor);
        assertThat(quotation.pricingDecisionId()).isEqualTo(approvedV2.id());
        assertThat(quotation.items()).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private record PricingChainFixture(long pricingRequestId, long itemAId, long itemBId) {}

    /** Drives Steps 1 and the 1->2 handoff and Step 2 to READY_FOR_CEO_REVIEW, exactly the way
     * the main chain test does it above — used by the return-loop test so it starts from a real,
     * fully-driven precondition rather than a hand-rolled SQL shortcut. */
    private PricingChainFixture driveToReadyForCeoReview() {
        PricingRequestRequests.CreatePricingRequestRequest createRequest = new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("5000.00"), "THB", "chain return-loop walk", UUID.randomUUID().toString(),
            List.of(
                pricingItemPerPiece(FACTORY_A, catalogProductIdFactoryA, "SCG", "Tile Chain A", new BigDecimal("10")),
                pricingItemPerBox(FACTORY_B, catalogProductIdFactoryB, "Cotto", "Tile Chain B", new BigDecimal("5"))));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, createRequest, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        FactoryQuoteDto draftA = quoteFor(drafts, FACTORY_A);
        FactoryQuoteDto draftB = quoteFor(drafts, FACTORY_B);
        long itemAId = draftA.items().get(0).pricingRequestItemId();
        long itemBId = draftB.items().get(0).pricingRequestItemId();

        factoryQuoteService.send(draftA.id(),
            new SendFactoryQuoteRequest("factory-a-chain@example.com", null, null, UUID.randomUUID().toString()),
            importActor);
        factoryQuoteService.send(draftB.id(),
            new SendFactoryQuoteRequest("factory-b-chain@example.com", null, null, UUID.randomUUID().toString()),
            importActor);
        drainDispatches();

        FactoryQuoteDto responseA = factoryQuoteService.receive(draftA.id(),
            responsePerPiece("REF-LOOP-A", "100.00", itemAId), importActor);
        factoryQuoteService.markReadyForCosting(responseA.id(), importActor);
        FactoryQuoteDto responseB = factoryQuoteService.receive(draftB.id(),
            responsePerBox("REF-LOOP-B", "1000.00", itemBId, new BigDecimal("5")), importActor);
        factoryQuoteService.markReadyForCosting(responseB.id(), importActor);

        PricingCostingDto draft = costingService.createDraft(pricingRequestId,
            new CreateCostingRequest("draft", null), importActor);
        costingService.recalculate(draft.id(), new RecalculateCostingRequest("pass 1"), importActor);
        PricingCostingDto calculated = costingService.recalculate(draft.id(), new RecalculateCostingRequest("pass 2"), importActor);
        costingService.submit(calculated.id(), new SubmitCostingRequest("submit"), importActor);
        assertThat(pricingRequestService.get(pricingRequestId, importActor).summary().status())
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);

        return new PricingChainFixture(pricingRequestId, itemAId, itemBId);
    }

    private void assertUnitBasisCoherentAcrossChain(long pricingRequestItemId, long pricingRequestId,
                                                     long costingId, long decisionId, long quotationId,
                                                     String expectedBasis) {
        assertThat(jdbc.queryForObject("""
            SELECT requested_unit_basis FROM sales.pricing_request_item WHERE pricing_request_item_id = :id
            """, Map.of("id", pricingRequestItemId), String.class)).isEqualTo(expectedBasis);
        assertThat(jdbc.queryForObject("""
            SELECT requested_unit_basis FROM sales.pricing_costing_item
             WHERE pricing_request_item_id = :itemId AND pricing_costing_id = :costingId
            """, Map.of("itemId", pricingRequestItemId, "costingId", costingId), String.class)).isEqualTo(expectedBasis);
        assertThat(jdbc.queryForObject("""
            SELECT requested_unit_basis FROM sales.pricing_decision_item
             WHERE pricing_request_item_id = :itemId AND pricing_decision_id = :decisionId
            """, Map.of("itemId", pricingRequestItemId, "decisionId", decisionId), String.class)).isEqualTo(expectedBasis);
        assertThat(jdbc.queryForObject("""
            SELECT requested_unit_basis FROM sales.quotation_item
             WHERE pricing_request_item_id = :itemId AND quotation_id = :quotationId
            """, Map.of("itemId", pricingRequestItemId, "quotationId", quotationId), String.class)).isEqualTo(expectedBasis);
        // Sanity: the request-id chain used to scope the costing/decision queries above is
        // itself the one this test drove — guards against a stray row from a different request
        // in this same schema silently satisfying the query.
        assertThat(jdbc.queryForObject("""
            SELECT pricing_request_id FROM sales.pricing_request_item WHERE pricing_request_item_id = :id
            """, Map.of("id", pricingRequestItemId), Long.class)).isEqualTo(pricingRequestId);
    }

    private void assertEventCount(long pricingRequestId, String eventKind, int expected) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = :kind
            """, Map.of("id", pricingRequestId, "kind", eventKind), Long.class)).isEqualTo((long) expected);
    }

    private PricingDecisionItemDto decisionItemFor(PricingDecisionDto decision, long pricingRequestItemId) {
        return decision.items().stream()
            .filter(item -> item.pricingRequestItemId() == pricingRequestItemId)
            .findFirst().orElseThrow();
    }

    private CustomerQuotationItemDto quotationItemFor(CustomerQuotationDto quotation, long pricingRequestItemId) {
        return quotation.items().stream()
            .filter(item -> item.pricingRequestItemId() == pricingRequestItemId)
            .findFirst().orElseThrow();
    }

    /** Simulates one outbox worker tick draining everything currently claimable — the actual
     * production codepath send() enqueues onto, not a shortcut around it. */
    private void drainDispatches() {
        for (long id : factoryQuoteService.claimableDispatchIds()) {
            factoryQuoteService.processDispatch(id);
        }
    }

    private FactoryQuoteDto quoteFor(List<FactoryQuoteDto> quotes, String factoryName) {
        return quotes.stream().filter(q -> factoryName.equals(q.factoryName())).findFirst().orElseThrow();
    }

    private ReceiveFactoryQuoteRequest responsePerPiece(String ref, String price, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("10.00"), "piece", UnitBasis.PER_PIECE,
                new BigDecimal(price), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    /** PER_BOX factory response (20 pieces/box); {@code sqmPerUnit} is required regardless of
     * unit basis because freight/insurance/inland are always computed per sqm of PHYSICAL piece. */
    private ReceiveFactoryQuoteRequest responsePerBox(String ref, String pricePerBox, long pricingRequestItemId,
                                                      BigDecimal quotedBoxes) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, quotedBoxes, "box", UnitBasis.PER_BOX,
                new BigDecimal(pricePerBox), "THB", null, new BigDecimal("0.5"), new BigDecimal("20"), null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItemPerPiece(
        String factory, long catalogProductId, String brand, String model, BigDecimal qty
    ) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qty, qty, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItemPerBox(
        String factory, long catalogProductId, String brand, String model, BigDecimal qtyBoxes
    ) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null, brand, model,
            brand + " " + model, null, null, "60x60", factory, qtyBoxes, qtyBoxes, "box", UnitBasis.PER_BOX,
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
