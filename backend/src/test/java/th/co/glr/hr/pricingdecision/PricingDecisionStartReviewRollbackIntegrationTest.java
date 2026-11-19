package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
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
import th.co.glr.hr.pricingcosting.PricingCostingService;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
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

/** Closes the same gap for PricingDecisionService#startReview that CommissionInvoiceSentinelRollbackIntegrationTest
 * closed for CommissionService: every existing pricing-decision integration test hand-wires new
 * PricingDecisionService, which has no Spring proxy and therefore no transaction at all. */
class PricingDecisionStartReviewRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingCostingRepository costingRepository;
    private PricingCostingService costingService;
    private PricingDecisionRepository decisionRepository;
    private NotificationRepository notifications;
    private FxRateRepository fxRates;
    private LandedCostCalculator landedCostCalculator;
    private PricingFormulaEngine formulaEngine;

    private long salesRepId;
    private long importUserId;
    private long ceoUserId;
    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-decision-rollback-test-uploads");
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        fxRates = new FxRateRepository(jdbc);
        formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        // V141 ("CEO owns costing"): shared by FactoryQuoteService's markReadyForCosting auto-advance check and
        // PricingDecisionService's startReview (see buildDecisionService).
        landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        costingRepository = new PricingCostingRepository(jdbc);
        // V141: PricingCostingService is READ-ONLY now (list/get) — Import's costing create/recalculate/submit is
        // gone; the CEO computes it via PricingDecisionService.
        costingService = new PricingCostingService(costingRepository, pricingRequests, tickets);
        decisionRepository = new PricingDecisionRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = createEmployee(employees, "พนักงานขาย โรลแบ็ก", "sales-rollback@glr.co.th", "SALES", "แผนกขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า โรลแบ็ก", "import-rollback@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        ceoUserId = createEmployee(employees, "ผู้บริหาร โรลแบ็ก", "ceo-rollback@glr.co.th", "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        catalogProductIdFactoryA = insertCatalogProduct("Factory A3-rollback", "IT", "TEST-A3-RB-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B3-rollback", "IT", "TEST-B3-RB-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Rollback จำกัด", "0100000000004", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0004");
        ProjectDto project = projects.create(customer.id(), "โครงการ Rollback");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Rollback", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(ticketItem("SCG", "Tile A3", "Factory A3-rollback"),
                    ticketItem("Cotto", "Tile B3", "Factory B3-rollback"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // Tests

    @Test
    void startReviewFailingAfterTheStatusTransition_rollsBackEveryWrite() {
        long pricingRequestId = twoItemSubmittedCosting();

        // Fault injector: delegates everything to a real PricingRequestRepository except addEvent, which is
        // startReview's LAST write (see class Javadoc).
        PricingRequestRepository faulty = org.mockito.Mockito.spy(new PricingRequestRepository(jdbc));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("injected failure"))
            .when(faulty).addEvent(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
        PricingDecisionService faultyService = transactional(buildDecisionService(faulty));

        assertThatThrownBy(() -> faultyService.startReview(pricingRequestId,
                new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
                ceoActor))
            .isInstanceOf(DataIntegrityViolationException.class);

        // V141 ("CEO owns costing"): startReview now computes+persists the costing itself, BEFORE the pricing_decision
        // insert — steps 1-2 of 6, ahead of the pricing_decision/ pricing_decision_item/status/event writes the
        // pre-V141 assertions below already covered.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_costing WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .as("startReview inserts sales.pricing_costing (step 1 of 6) BEFORE the injected "
                + "addEvent failure (step 6) — a non-zero count here means the @Transactional "
                + "boundary let an orphan costing row survive a later failure")
            .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_costing_item pci
                  JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
                 WHERE pc.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("sales.pricing_costing_item rows (step 2) must not survive either — same "
                + "transaction boundary as the pricing_costing row above")
            .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales.pricing_decision WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), Long.class))
            .as("startReview inserts sales.pricing_decision (step 3 of 6) BEFORE the injected "
                + "addEvent failure (step 6) — a non-zero count here means the @Transactional "
                + "boundary let a partial CEO-review survive a later failure")
            .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_decision_item pdi
                  JOIN sales.pricing_decision pd ON pd.pricing_decision_id = pdi.pricing_decision_id
                 WHERE pd.pricing_request_id = :id
                """, Map.of("id", pricingRequestId), Long.class))
            .as("sales.pricing_decision_item rows (step 4) must not survive either — same "
                + "transaction boundary as the pricing_decision row above")
            .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
                Map.of("id", pricingRequestId), String.class))
            .as("the status UPDATE (step 5, READY_FOR_CEO_REVIEW -> CEO_REVIEWING) must roll back "
                + "too, or this pricing request would be stuck showing 'under CEO review' with no "
                + "reviewable pricing_decision row behind it")
            .isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales.pricing_request_event
                 WHERE pricing_request_id = :id AND event_kind = 'PRICING_DECISION_STARTED'
                """, Map.of("id", pricingRequestId), Long.class))
            .as("the event insert is exactly where the fault was injected (step 6) — it must not "
                + "have left a lone committed event row behind either")
            .isZero();
    }

    // Helpers

    private PricingDecisionService buildDecisionService(PricingRequestRepository pricingRequestsForDecision) {
        return new PricingDecisionService(decisionRepository, pricingRequestsForDecision, costingRepository,
            tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
    }

    /** Two-item, two-factory scenario driven to READY_FOR_CEO_REVIEW, mirroring
     * PricingDecisionIntegrationTest#twoItemSubmittedCosting() — the precondition startReview requires. */
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
            new BigDecimal("1000.00"), "THB", "rollback test request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A3", "Factory A3-rollback", new BigDecimal("10")),
                pricingItem("Cotto", "Tile B3", "Factory B3-rollback", new BigDecimal("5"))));
    }

    private PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, BigDecimal qty
    ) {
        Long productId = "Factory A3-rollback".equals(factory) ? catalogProductIdFactoryA
            : "Factory B3-rollback".equals(factory) ? catalogProductIdFactoryB : null;
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
