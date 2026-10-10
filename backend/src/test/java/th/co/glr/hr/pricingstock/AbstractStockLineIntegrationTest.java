package th.co.glr.hr.pricingstock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
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
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestEventKind;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Shared real-service / real-repository / real-Postgres fixture for slice 1 of the stock-line
 * feature (IA .design/stock-item-pricing/INFORMATION_ARCHITECTURE.md, sections 0, 1.1-1.4, 1.7).
 * No mocks: every collaborator is the production class, hand-wired the same way
 * {@code PricingChainEndToEndIntegrationTest} wires the pricing chain.
 *
 * <p><b>Two ways to put a pricing request on the books, on purpose.</b>
 * <ul>
 *   <li>{@link #persistDraft} writes the items straight through {@link PricingRequestRepository}
 *       (persistence only, no service validation) so that a test about ROUTING, ETAs or the CEO
 *       decision does not fail in its own fixture on the (also new) "stock lines need no
 *       factory/origin country/lead time" validation rule. That rule has its own tests, which DO go
 *       through {@code createDraft}.</li>
 *   <li>The service path ({@code pricingRequestService.createDraft}) is used only where the
 *       validation/round-trip behaviour is itself the thing under test.</li>
 * </ul>
 *
 * <p>Every line built here carries the full new-form fields (V185) so that a resulting decision is
 * "new form eligible" (PER_PIECE + sqmPerPiece), i.e. the CEO price-mode picker applies to it.
 */
public abstract class AbstractStockLineIntegrationTest extends AbstractPostgresIntegrationTest {

    protected static final String IN_THAILAND = "IN_THAILAND";
    protected static final String IN_TRANSIT = "IN_TRANSIT";
    protected static final String FACTORY_A = "Factory A-StockLine";
    protected static final String FACTORY_B = "Factory B-StockLine";

    protected TicketRepository tickets;
    protected PricingRequestRepository pricingRequests;
    protected PricingRequestService pricingRequestService;
    protected FactoryQuoteService factoryQuoteService;
    protected PricingDecisionRepository decisionRepository;
    protected PricingDecisionService decisionService;
    protected PricingCostingRepository costingRepository;
    protected NotificationRepository notifications;

    protected long salesRepId;
    protected long otherSalesRepId;
    protected long salesManagerId;
    protected long importUserId;
    protected long ceoUserId;
    protected long accountUserId;
    protected UserPrincipal salesActor;
    protected UserPrincipal otherSalesActor;
    protected UserPrincipal salesManagerActor;
    protected UserPrincipal importActor;
    protected UserPrincipal ceoActor;
    protected UserPrincipal accountActor;

    protected long ticketId;
    protected long catalogProductFactoryA;
    protected long catalogProductFactoryB;
    /** thickness_mm = null, so any line pointing at it is UNCOSTABLE (V156). */
    protected long catalogProductUncostable;

    @BeforeEach
    protected void wireStockLineFixture() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-stock-line-test-uploads");

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());

        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCost = new LandedCostCalculator(factoryQuotes, pricingRequests, fxRates,
            new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCost);
        decisionRepository = new PricingDecisionRepository(jdbc);
        costingRepository = new PricingCostingRepository(jdbc);
        decisionService = new PricingDecisionService(decisionRepository, pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCost, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));

        salesRepId = createEmployee(employees, "พนักงานขาย สต็อก", "sales-stock@glr.co.th", "SALES", "แผนกขาย");
        otherSalesRepId = createEmployee(employees, "พนักงานขายอื่น สต็อก", "sales-stock-2@glr.co.th", "SALES", "แผนกขาย");
        salesManagerId = createEmployee(employees, "ผจก.ขาย สต็อก", "sm-stock@glr.co.th", "SALES", "ฝ่ายขาย");
        importUserId = createEmployee(employees, "ฝ่ายนำเข้า สต็อก", "import-stock@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        accountUserId = createEmployee(employees, "บัญชี สต็อก", "acct-stock@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        // Matches CeoApproverRule.SQL_PREDICATE (position กรรมการผู้จัดการ) so hr.notification
        // fan-out for role "ceo" actually reaches this employee.
        ceoUserId = employees.create(new UpsertEmployeeRequest(
            null, null, "ผู้บริหาร สต็อก", null, null, null, null, null, null, null,
            "ceo-stock@glr.co.th", null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");

        catalogProductFactoryA = insertCatalogProduct(FACTORY_A, "IT", "TEST-STOCK-A-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductFactoryB = insertCatalogProduct(FACTORY_B, "IT", "TEST-STOCK-B-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductUncostable = insertCatalogProduct("Factory Uncostable-StockLine", "IT", "TEST-STOCK-U-001",
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", null);

        CustomerDto customer = customers.create(
            "บริษัท Stock Line Test จำกัด", "0100000000194", "194 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0194");
        ProjectDto project = projects.create(customer.id(), "โครงการ Stock Line Test");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Stock Line Test", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null, List.of(new TicketItemRequest("SCG", "Tile Seed", "White", "Matte", "60x60",
                    FACTORY_A, new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ── line builders ────────────────────────────────────────────────────────────────────────

    /** An IMPORT (สั่งนำเข้า) line: full new-form fields, origin country + lead time present. */
    protected PricingRequestItemRequest importLine(String model, Long catalogProductId, String factory, int pieces) {
        return line(model, catalogProductId, factory, pieces, "ไทย-สต็อก", 3, 7, null);
    }

    /** An import line with NO factory, NO catalog link (a free-text product Import must route). */
    protected PricingRequestItemRequest importLineWithoutFactory(String model, int pieces) {
        return line(model, null, null, pieces, "ไทย-สต็อก", 3, 7, null);
    }

    /**
     * A STOCK line the way R7 says it arrives: no factory, no origin country, no lead time - those
     * three inputs are hidden for a stock line, so nothing is sent.
     */
    protected PricingRequestItemRequest stockLine(String model, String stockSource, int pieces) {
        return line(model, null, null, pieces, null, null, null, stockSource);
    }

    /** A stock line that nevertheless resolves to a factory through its catalog product. */
    protected PricingRequestItemRequest stockLineWithCatalog(String model, String stockSource,
                                                             long catalogProductId, String factory, int pieces) {
        return line(model, catalogProductId, factory, pieces, null, null, null, stockSource);
    }

    /** A line that is COMPLETE as an import line (origin country + lead time) but carries an arbitrary
     * {@code stockSource} - used to probe source validation without tripping an unrelated
     * "missing origin country" 400 first. */
    protected PricingRequestItemRequest lineWithFullImportFields(String model, String stockSource, int pieces) {
        return line(model, null, null, pieces, "ไทย-สต็อก", 3, 7, stockSource);
    }

    private PricingRequestItemRequest line(String model, Long catalogProductId, String factory, int pieces,
                                           String originCountry, Integer leadMin, Integer leadMax,
                                           String stockSource) {
        BigDecimal qty = BigDecimal.valueOf(pieces);
        return new PricingRequestItemRequest(null, catalogProductId, null, "SCG", model, "SCG " + model,
            "White", "Matte", "60x60", factory,
            // requestedQty/Sqm/Unit/Basis: what PricingRequestService#resolveItem would derive, spelled
            // out so a repository-persisted line (persistDraft) is identical to a service-created one.
            qty, qty.multiply(new BigDecimal("0.36")), "แผ่น", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, originCountry, leadMin, leadMax, null, null, null, null, stockSource);
    }

    // ── pricing-request builders ─────────────────────────────────────────────────────────────

    protected CreatePricingRequestRequest requestOf(List<PricingRequestItemRequest> items) {
        return new CreatePricingRequestRequest(PricingRequestRecipient.DESIGNER, null, "Designer Co.",
            LocalDate.now().plusDays(14), new BigDecimal("1000.00"), "THB", "stock line test",
            UUID.randomUUID().toString(), items);
    }

    protected CreatePricingRequestRequest requestOf(PricingRequestItemRequest... items) {
        return requestOf(List.of(items));
    }

    /** DRAFT, persisted through the repository (no service validation) - see the class Javadoc. */
    protected long persistDraft(PricingRequestItemRequest... items) {
        long id = pricingRequests.create(ticketId, pricingRequests.nextRequestCode(), requestOf(items), salesRepId);
        pricingRequests.addEvent(id, ticketId, salesRepId, salesActor.name(),
            PricingRequestEventKind.PRICING_REQUEST_CREATED, null, PricingRequestStatus.DRAFT, null, null);
        return id;
    }

    /** {@link #persistDraft} then the real {@code submit} by the owning rep. */
    protected long persistAndSubmit(PricingRequestItemRequest... items) {
        long id = persistDraft(items);
        pricingRequestService.submit(id, salesActor);
        return id;
    }

    /** persistAndSubmit + the real import pickup, i.e. the request sits in IMPORT_REVIEWING. */
    protected long persistSubmitAndPickUp(PricingRequestItemRequest... items) {
        long id = persistAndSubmit(items);
        assertThat(status(id)).as("fixture: submit must leave the request with import").isEqualTo(PricingRequestStatus.SUBMITTED);
        pricingRequestService.pickup(id, importActor);
        return id;
    }

    // ── factory quotes ───────────────────────────────────────────────────────────────────────

    /** Import receives a response covering EVERY item of {@code draft}, then marks it ready for costing. */
    protected void answerAndMarkReady(FactoryQuoteDto draft) {
        List<ReceiveFactoryQuoteItemRequest> items = new ArrayList<>();
        for (var draftItem : draft.items()) {
            items.add(new ReceiveFactoryQuoteItemRequest(
                draftItem.pricingRequestItemId(), null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null));
        }
        factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
        FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
            new ReceiveFactoryQuoteRequest("REF-" + draft.factoryName(), "THB", "30 days", "45 days",
                "revision", "note", items, UUID.randomUUID().toString()),
            importActor);
        factoryQuoteService.markReadyForCosting(responded.id(), importActor);
    }

    /** generateDrafts, then answer + mark ready every draft; returns the drafts. */
    protected List<FactoryQuoteDto> quoteEveryImportLineReady(long pricingRequestId) {
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : drafts) {
            // generateDrafts returns EVERY quote of the request; a quote answered on an earlier call
            // is not a draft any more and must not be re-answered (that would open a revision).
            if ("DRAFT".equals(draft.status())) {
                answerAndMarkReady(draft);
            }
        }
        return drafts;
    }

    // ── seeding a decision for STOCK lines ─────────────────────────────────────────────────

    /**
     * Writes, straight through the repositories, what {@code PricingDecisionService#startReview} is
     * specified to leave behind for the STOCK lines of {@code pricingRequestId}: one DRAFT decision
     * whose stock lines are decision items with NO costing link, NO frozen cost, NO list price and
     * NO proposed price, PER_PIECE, quantity = the requested pieces. The request is put in
     * CEO_REVIEWING. Used ONLY by tests of {@code update}/{@code approve}/{@code returnToImport}/the
     * quotation, so that each of them fails on ITS OWN assertion rather than inside an unbuilt
     * {@code startReview}; {@code startReview} itself has its own tests that do not use this.
     *
     * <p>The shape written here is exactly what the {@code startReview} tests assert, so the two
     * cannot drift apart unnoticed. An empty costing (total 0) is what the decision hangs off,
     * because {@code pricing_decision.pricing_costing_id} stays NOT NULL (an open design point).
     */
    protected long seedStockOnlyDecision(long pricingRequestId) {
        long costingId = costingRepository.createComputed(pricingRequestId, null, ceoUserId, BigDecimal.ZERO);
        long decisionId = decisionRepository.createDraft(pricingRequestId, costingId, new BigDecimal("0.20"),
            "THB", BigDecimal.ONE, "THB", LocalDate.now(), null, null, ceoUserId).decisionId();
        List<PricingDecisionRepository.WriteItem> stockItems = new ArrayList<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(pricingRequestId)) {
            if (item.stockSource() != null) {
                stockItems.add(new PricingDecisionRepository.WriteItem(item.id(), null, UnitBasis.PER_PIECE,
                    item.requestedQty(), item.requestedQty(), null, null, "THB", null, null, null));
            }
        }
        decisionRepository.insertItems(decisionId, stockItems);
        forceStatus(pricingRequestId, PricingRequestStatus.CEO_REVIEWING);
        return decisionId;
    }

    /** Raw: the decision item id for a request item - a lookup that does not go through findItems. */
    protected long decisionItemId(long decisionId, long pricingRequestItemId) {
        return jdbc.queryForObject("""
            SELECT pricing_decision_item_id FROM sales.pricing_decision_item
             WHERE pricing_decision_id = :d AND pricing_request_item_id = :i
            """, Map.of("d", decisionId, "i", pricingRequestItemId), Long.class);
    }

    /** Raw: puts a seeded decision in NEW-FORM state (price_mode set) without going through update(). */
    protected void seedPriceMode(long decisionId, String priceMode) {
        jdbc.update("UPDATE sales.pricing_decision SET price_mode = :m WHERE pricing_decision_id = :d",
            Map.of("m", priceMode, "d", decisionId));
    }

    /** Raw: the CEO's ราคาตั้ง + the net it yields, as PRECONDITION state for approve()/quotation tests. */
    protected void seedListPriceAndNet(long decisionId, long pricingRequestItemId, BigDecimal listPrice,
                                       BigDecimal netPrice) {
        jdbc.update("""
            UPDATE sales.pricing_decision_item
               SET list_unit_price = :list, net_unit_price = :net
             WHERE pricing_decision_id = :d AND pricing_request_item_id = :i
            """, Map.of("list", listPrice, "net", netPrice, "d", decisionId, "i", pricingRequestItemId));
    }

    // ── reads / raw state ────────────────────────────────────────────────────────────────────

    protected String status(long pricingRequestId) {
        return jdbc.queryForObject("SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class);
    }

    /** Raw status write, used ONLY to put a request into a state a test needs as a PRECONDITION
     * without driving unrelated (and possibly not-yet-built) behaviour to get there. */
    protected void forceStatus(long pricingRequestId, String status) {
        // chk_pricing_request_cancelled_pair: CANCELLED must carry who/when it was cancelled.
        jdbc.update("""
            UPDATE sales.pricing_request
               SET status = :s,
                   cancelled_at = CASE WHEN :s = 'CANCELLED' THEN now() ELSE cancelled_at END,
                   cancelled_by = CASE WHEN :s = 'CANCELLED' THEN :ceo ELSE cancelled_by END
             WHERE pricing_request_id = :id
            """, Map.of("s", status, "id", pricingRequestId, "ceo", ceoUserId));
    }

    protected PricingRequestItemDto itemByModel(long pricingRequestId, String model) {
        return pricingRequests.findItems(pricingRequestId).stream()
            .filter(i -> model.equals(i.model())).findFirst().orElseThrow();
    }

    protected long itemId(long pricingRequestId, String model) {
        return itemByModel(pricingRequestId, model).id();
    }

    /** Raw ETA write as a PRECONDITION (does not go through the unbuilt setInTransitArrival). */
    protected void seedEta(long pricingRequestId, String model, LocalDate eta) {
        jdbc.update("""
            UPDATE sales.pricing_request_item SET expected_arrival_date = :eta
             WHERE pricing_request_id = :id AND model = :model
            """, Map.of("eta", eta, "id", pricingRequestId, "model", model));
    }

    protected long factoryId(String factoryName) {
        return jdbc.queryForObject("SELECT factory_id FROM price_catalog.factories WHERE name = :n",
            Map.of("n", factoryName), Long.class);
    }

    /** Notification rows addressed to {@code employeeId} about this pricing request. */
    protected long notificationCount(long employeeId, long pricingRequestId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.notification WHERE employee_id = :e AND link = :link",
            Map.of("e", employeeId, "link", "/pricing-requests/" + pricingRequestId), Long.class);
    }

    protected long eventCount(long pricingRequestId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request_event WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Long.class);
    }

    protected long factoryQuoteCount(long pricingRequestId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.factory_quote WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), Long.class);
    }

    protected LocalDate eta() {
        return LocalDate.now().plusDays(30);
    }

    // ── people ───────────────────────────────────────────────────────────────────────────────

    protected long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                  String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    protected UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
