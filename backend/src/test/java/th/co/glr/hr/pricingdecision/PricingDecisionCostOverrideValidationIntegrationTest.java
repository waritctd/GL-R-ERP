package th.co.glr.hr.pricingdecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import th.co.glr.hr.config.AppProperties;
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
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionItemDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.CostOverrideRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Pins the INPUT VALIDATION on PUT /api/pricing-decisions/{/items/{}/cost-override}
 * (PricingDecisionService#overrideItemCost) — the mandatory reason and the non-negative
 * manualLandedCostPerUnitThb — which until now nothing tested at all. */
class PricingDecisionCostOverrideValidationIntegrationTest extends AbstractPostgresIntegrationTest {
    /** The two messages under test, quoted from {@code PricingDecisionService#overrideItemCost}. */
    private static final String REASON_REQUIRED =
        "ต้องระบุเหตุผลในการปรับต้นทุน ไม่ว่าจะปรับหรือยกเลิกการปรับก็ตาม";
    private static final String COST_MUST_NOT_BE_NEGATIVE = "ต้นทุนที่ปรับต้องไม่ติดลบ";

    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    /** The proxy actually exercised by the tests — see the class Javadoc. */
    private PricingDecisionService decisions;

    private UserPrincipal salesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();

        FileStorageService fileStorage = new FileStorageService("/tmp/glr-cost-override-validation-test-uploads");
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        decisionService = new PricingDecisionService(new PricingDecisionRepository(jdbc), pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        decisions = transactional(decisionService);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        long salesRepId = createEmployee(employees, "พนักงานขาย โอเวอร์ไรด์", "sales-override-validation@glr.co.th",
            "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า โอเวอร์ไรด์", "import-override-validation@glr.co.th",
            "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร โอเวอร์ไรด์", "ceo-override-validation@glr.co.th",
            "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        catalogProductId = insertCatalogProduct("Factory OverrideValidation", "IT", "TEST-OV-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท Override Validation จำกัด", "0100000000011", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0011");
        ProjectDto project = projects.create(customer.id(), "โครงการ Override Validation");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีล Override Validation", "NORMAL", customer.name(), customer.id(),
                project.id(), null, null, null, List.of(ticketItem())),
            salesActor);
        ticketId = created.summary().id();
    }

    // The mandatory reason. Three shapes, because a guard can be wrong in three different ways: absent entirely
    // (`reason == null`), present but empty (`""`), and present but only whitespace — the last being the one a
    // naive `reason != null` or `reason.isEmpty()` check waves straight through.

    @Test
    void overrideItemCost_refusesANullReason_andWritesNothing() {
        Fixture fixture = draftDecisionWithOneItem();

        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), null), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(REASON_REQUIRED);
            });

        assertNothingWasOverridden(fixture);
    }

    @Test
    void overrideItemCost_refusesAnEmptyReason_andWritesNothing() {
        Fixture fixture = draftDecisionWithOneItem();

        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), ""), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(REASON_REQUIRED);
            });

        assertNothingWasOverridden(fixture);
    }

    /** The case that separates isBlank from isEmpty, and the reason this test is written separately rather than
     * folded into the empty-string one above: a guard weakened to reason.isEmpty. Java's String#isBlank() uses
     * Character.isWhitespace and strips all of them. */
    @Test
    void overrideItemCost_refusesAWhitespaceOnlyReason_andWritesNothing() {
        Fixture fixture = draftDecisionWithOneItem();

        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), "  \t \n  "), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(REASON_REQUIRED);
            });

        assertNothingWasOverridden(fixture);
    }

    /** The CLEAR direction (manualLandedCostPerUnitThb == null) needs a reason just as much as the SET direction —
     * clearing an override is money-affecting too, which is the whole point of CostOverrideRequest's "mandatory in
     * BOTH directions" contract. */
    @Test
    void overrideItemCost_requiresAReasonOnTheClearPathToo_whereNoCheckConstraintApplies() {
        Fixture fixture = draftDecisionWithOneItem();
        decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), "ต่อรองกับโรงงานได้ราคาที่ดีกว่า"), ceoActor);

        // Deliberately an EMPTY reason, not a whitespace one: this test is about the clear path having no database
        // backstop, and reusing the whitespace case here would make it go red alongside the isBlank() test under the
        // same mutation instead of isolating its own guard.
        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(null, ""), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(REASON_REQUIRED);
            });

        // Wrong-way-round: the live override must survive the refused clear, untouched.
        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class))
            .as("a refused clear must not wipe the existing override")
            .isEqualByComparingTo("888.0000");
        assertThat(overrideColumn(fixture, "override_reason", String.class))
            .isEqualTo("ต่อรองกับโรงงานได้ราคาที่ดีกว่า");
        assertThat(overrideEventCount(fixture))
            .as("only the successful set should have left an event behind")
            .isEqualTo(1L);
    }

    // The non-negative cost.

    @Test
    void overrideItemCost_refusesANegativeCost_andWritesNothing() {
        Fixture fixture = draftDecisionWithOneItem();

        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("-0.0001"), "พิมพ์เครื่องหมายลบผิด"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(COST_MUST_NOT_BE_NEGATIVE);
            });

        assertNothingWasOverridden(fixture);
    }

    /** -0.00004 is the value that proves the service guard is load-bearing rather than decorative: money4 (setScale)
     * rounds it to 0.0000 BEFORE applyOverride ever sees it, so chk_pricing_costing_item_override_nonnegative is
     * satisfied and Postgres stores a perfectly valid zero-cost override. */
    @Test
    void overrideItemCost_refusesANegativeCostTooSmallForTheCheckConstraintToCatch() {
        Fixture fixture = draftDecisionWithOneItem();

        assertThatThrownBy(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("-0.00004"), "ค่าติดลบที่เล็กกว่าครึ่งสตางค์"), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).isEqualTo(COST_MUST_NOT_BE_NEGATIVE);
            });

        assertNothingWasOverridden(fixture);
    }

    // Positive control.

    /** Without this test the six above are indistinguishable from a method that refuses every request it is ever
     * given. It drives both directions end to end and asserts the writes actually landed in the real row. */
    @Test
    void overrideItemCost_acceptsAValidReason_onBothTheSetAndTheClearPath() {
        Fixture fixture = draftDecisionWithOneItem();

        PricingDecisionDto afterSet = decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), "ต่อรองกับโรงงานได้ราคาที่ดีกว่า"), ceoActor);

        assertThat(afterSet.items().stream().filter(i -> i.id() == fixture.itemId()).findFirst().orElseThrow()
            .frozenLandedCostPerPieceThb()).isEqualByComparingTo("888.0000");
        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class))
            .isEqualByComparingTo("888.0000");
        assertThat(overrideColumn(fixture, "override_reason", String.class))
            .isEqualTo("ต่อรองกับโรงงานได้ราคาที่ดีกว่า");
        assertThat(overrideEventCount(fixture)).isEqualTo(1L);

        // Zero is a legal override value, not a disguised clear — the guard rejects `< 0` only.
        assertThatCode(() -> decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(BigDecimal.ZERO, "โรงงานรับผิดชอบต้นทุนให้ทั้งหมด"), ceoActor))
            .doesNotThrowAnyException();
        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class))
            .isEqualByComparingTo("0.0000");

        // And the clear path, with a reason, genuinely clears.
        decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(null, "กลับไปใช้ต้นทุนที่คำนวณได้"), ceoActor);

        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class)).isNull();
        assertThat(overrideColumn(fixture, "override_reason", String.class)).isNull();
        assertThat(overrideEventCount(fixture)).isEqualTo(3L);
    }

    // Helpers

    /** The three ids every assertion below needs, resolved once per test. */
    private record Fixture(long pricingRequestId, long decisionId, long itemId, long costingItemId) {}

    /** Wrong-way-round assertion shared by every refusal test: not just "no manual cost", but ALL SIX override
     * columns still untouched. And no event, since overrideItemCost writes one on every successful call. */
    private void assertNothingWasOverridden(Fixture fixture) {
        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class)).isNull();
        assertThat(overrideColumn(fixture, "override_reason", String.class)).isNull();
        assertThat(overrideColumn(fixture, "overridden_by", Long.class)).isNull();
        assertThat(overrideColumn(fixture, "overridden_at", java.time.Instant.class)).isNull();
        assertThat(overrideColumn(fixture, "override_fx_rate", BigDecimal.class)).isNull();
        assertThat(overrideColumn(fixture, "override_calc_config_version", Integer.class)).isNull();
        assertThat(overrideEventCount(fixture)).isZero();

        // The decision item's frozen cost is derived from the effective cost, so a refused override must leave it on
        // the computed figure too — read back through the real service, not the DTO captured before the call.
        PricingDecisionItemDto item = decisionService.get(fixture.decisionId(), ceoActor).items().stream()
            .filter(i -> i.id() == fixture.itemId()).findFirst().orElseThrow();
        assertThat(item.frozenLandedCostPerPieceThb()).isEqualByComparingTo(
            jdbc.queryForObject("""
                SELECT landed_cost_per_unit_thb FROM sales.pricing_costing_item
                 WHERE pricing_costing_item_id = :id
                """, Map.of("id", fixture.costingItemId()), BigDecimal.class));
    }

    private <T> T overrideColumn(Fixture fixture, String column, Class<T> type) {
        // Column name is a compile-time literal from this file only — never caller input.
        return jdbc.queryForObject(
            "SELECT " + column + " FROM sales.pricing_costing_item WHERE pricing_costing_item_id = :id",
            Map.of("id", fixture.costingItemId()), type);
    }

    private long overrideEventCount(Fixture fixture) {
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales.pricing_request_event
             WHERE pricing_request_id = :id AND event_kind = 'PRICING_COSTING_ITEM_COST_OVERRIDDEN'
            """, Map.of("id", fixture.pricingRequestId()), Long.class);
        return count == null ? 0L : count;
    }

    /** One-item deal driven all the way to a DRAFT pricing decision on a CEO_REVIEWING request — the precondition
     * overrideItemCost needs before its own validation is even reachable (requireOpenDecisionForMutation). */
    private Fixture draftDecisionWithOneItem() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : factoryQuoteService.generateDrafts(pricingRequestId, importActor)) {
            factoryQuoteService.markContacted(draft.id(), new MarkFactoryContactedRequest(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), draft.items().get(0).pricingRequestItemId()), importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        PricingDecisionDto decision = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, null), ceoActor);
        PricingDecisionItemDto item = decision.items().get(0);
        return new Fixture(pricingRequestId, decision.id(), item.id(), item.pricingCostingItemId());
    }

    private ReceiveFactoryQuoteRequest response(String ref, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days", "revision", "note",
            List.of(new ReceiveFactoryQuoteItemRequest(pricingRequestItemId, null, null,
                new BigDecimal("1.00"), "piece", "piece", new BigDecimal("100.00"), "THB", null,
                new BigDecimal("1.00"), null, null, "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    // V185 (direct-deal-form parity): color/texture/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
    // required on every item PricingRequestService#createDraft persists —
    // requestedQty/requestedUnit/requestedUnitBasis are derived instead.
    private PricingRequestRequests.CreatePricingRequestRequest oneItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "cost override validation request", UUID.randomUUID().toString(),
            List.of(new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null,
                "SCG", "Tile OV", "SCG Tile OV", "White", "Matte", "60x60", "Factory OverrideValidation",
                null, null, null, null,
                QuantityType.CONFIRMED, null, null, null,
                null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
                null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
                false, "ไทย-สต็อก", 3, 7, null, null, null)));
    }

    private TicketItemRequest ticketItem() {
        return new TicketItemRequest("SCG", "Tile OV", "White", "Matte", "60x60", "Factory OverrideValidation",
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
