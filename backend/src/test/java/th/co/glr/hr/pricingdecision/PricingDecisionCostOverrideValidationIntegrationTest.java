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
import th.co.glr.hr.pricingrequest.UnitBasis;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Pins the INPUT VALIDATION on {@code PUT /api/pricing-decisions/{}/items/{}/cost-override}
 * ({@link PricingDecisionService#overrideItemCost}) — the mandatory {@code reason} and the
 * non-negative {@code manualLandedCostPerUnitThb} — which until now nothing tested at all.
 *
 * <p><b>Why this file exists.</b> V141 ("CEO owns costing") shipped the guard, and PR #769 built
 * the CEO override UI on top of it, enforcing the same reason client-side. But the backend side of
 * that contract rested on two things only: reading the code, and the {@code CHECK} constraints
 * in {@code V141__ceo_owns_pricing_costing.sql}. {@link PricingCostingAuthzIntegrationTest}
 * covers <i>who may call</i> {@code overrideItemCost}; {@link PricingDecisionIntegrationTest}
 * covers the happy path and override staleness. Neither ever passes a blank, whitespace-only, or
 * null reason, nor a negative cost, and no test anywhere asserted either rejection message.
 *
 * <p><b>Where the guard actually lives, and why the CHECK constraints are not a substitute.</b>
 * Enforcement is in the SERVICE ({@code PricingDecisionService#overrideItemCost}, the two guards
 * at the top of the method), which is what makes the refusal a clean {@code 400} the UI can render.
 * The database backstops it, but only partially. Three of the tests below exist specifically to pin
 * the places the constraints cannot reach, and the last point governs how all of them assert:
 * <ul>
 *   <li>{@code chk_pricing_costing_item_override_reason} is conditioned on
 *       {@code manual_landed_cost_per_unit_thb IS NOT NULL}, so on the CLEAR path
 *       ({@code manualLandedCostPerUnitThb == null}) the database requires no reason whatsoever —
 *       the service guard is the ONLY thing enforcing it there. See
 *       {@link #overrideItemCost_requiresAReasonOnTheClearPathToo_whereNoCheckConstraintApplies}.</li>
 *   <li>That same constraint tests {@code btrim(override_reason) <> ''}, and one-argument
 *       {@code btrim} strips <b>spaces only</b> — a tab- or newline-only reason satisfies it. Java's
 *       {@code isBlank()} does not, so on the SET path the service is strictly stronger than the
 *       constraint, not merely faster. See
 *       {@link #overrideItemCost_refusesAWhitespaceOnlyReason_andWritesNothing}.</li>
 *   <li>{@code chk_pricing_costing_item_override_nonnegative} sees the value only AFTER
 *       {@code money4} rounds it to 4dp, so a negative smaller than half a satang
 *       ({@code -0.00004}) reaches the database as {@code 0.0000} and is accepted. See
 *       {@link #overrideItemCost_refusesANegativeCostTooSmallForTheCheckConstraintToCatch}.</li>
 *   <li>A constraint violation surfaces as a {@code DataIntegrityViolationException} → 500, not a
 *       400 with a Thai message. Every negative test here therefore asserts the {@link ApiException}
 *       <b>status AND message</b>, not merely that something was thrown — a test that accepts any
 *       exception passes just as happily when the guard is gone and Postgres is doing the refusing.</li>
 * </ul>
 *
 * <p>Written wrong-way-round throughout, per CLAUDE.md: each refusal test asserts the write did
 * <b>not</b> happen, with a direct query against the real row (all six override columns still
 * untouched, no {@code PRICING_COSTING_ITEM_COST_OVERRIDDEN} event), rather than trusting the
 * thrown status code. {@link #overrideItemCost_acceptsAValidReason_onBothTheSetAndTheClearPath} is
 * the positive control that keeps the whole file honest: it proves the guard discriminates rather
 * than simply refusing everything, which is the failure mode a suite of negative-only tests cannot
 * distinguish from a correct one.
 *
 * <p>Real service, real repository, real Postgres — no Mockito on the path under test. The service
 * is reached through {@link AbstractPostgresIntegrationTest#transactional} because
 * {@code overrideItemCost} is {@code @Transactional} and this suite hand-wires services with
 * {@code new} (no Spring context, so a bare annotation would otherwise be inert): with the proxy in
 * place a refusal rolls back exactly as it does in production, which is what makes the
 * "nothing was written" assertions mean something.
 */
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
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), factoryEmail, notifications, fileStorage, dispatchProperties,
            landedCostCalculator);
        PricingCostingRepository costingRepository = new PricingCostingRepository(jdbc);
        decisionService = new PricingDecisionService(new PricingDecisionRepository(jdbc), pricingRequests,
            costingRepository, tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        decisions = transactional(decisionService);
        th.co.glr.hr.pricing.PriceCalcService priceCalcMock = mock(th.co.glr.hr.pricing.PriceCalcService.class);
        TicketService ticketService = new TicketService(tickets, notifications, priceCalcMock, objectMapper,
            customers, new QuotationRenderer(), pricingRequestService);

        long salesRepId = createEmployee(employees, "พนักงานขาย โอเวอร์ไรด์", "sales-override-validation@glr.co.th",
            "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า โอเวอร์ไรด์", "import-override-validation@glr.co.th",
            "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createEmployee(employees, "ผู้บริหาร โอเวอร์ไรด์", "ceo-override-validation@glr.co.th",
            "MD", "ผู้บริหาร");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Factory OverrideValidation', 'factory-override-validation@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE
            SET email = EXCLUDED.email, currency = EXCLUDED.currency, unit = EXCLUDED.unit, country = EXCLUDED.country
            """, Map.of());
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The mandatory reason. Three shapes, because a guard can be wrong in three different ways:
    // absent entirely (`reason == null`), present but empty (`""`), and present but only
    // whitespace — the last being the one a naive `reason != null` or `reason.isEmpty()` check
    // waves straight through. The whitespace value is "  \t \n  " rather than plain spaces on
    // purpose; see that test's own Javadoc for why the choice is load-bearing.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /**
     * The case that separates {@code isBlank()} from {@code isEmpty()}, and the reason this test is
     * written separately rather than folded into the empty-string one above: a guard weakened to
     * {@code reason.isEmpty()} — or to a bare null check — leaves every other test in this file
     * green while letting a CEO record a money-affecting override justified by nothing.
     *
     * <p><b>This is the one refusal in the file with no database backstop on the SET path either,
     * and the reason is a genuine asymmetry worth knowing.</b> {@code
     * chk_pricing_costing_item_override_reason} tests {@code btrim(override_reason) <> ''}, and
     * one-argument {@code btrim} in PostgreSQL strips <i>spaces only</i> — not tabs, not newlines.
     * Java's {@code String#isBlank()} uses {@code Character.isWhitespace} and strips all of them. So
     * a pure-space reason is caught by both (the database as a 500, the service as this 400), while
     * the {@code "  \t \n  "} used here escapes the constraint entirely and is caught by the service
     * alone. Verified, not assumed: removing the service guard and re-running this test writes the
     * tab-and-newline reason to the row with no exception at all, and {@code
     * SELECT btrim(E'  \t \n  ') = ''} returns false on PostgreSQL 16.
     */
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

    /**
     * The CLEAR direction ({@code manualLandedCostPerUnitThb == null}) needs a reason just as much
     * as the SET direction — clearing an override is money-affecting too, which is the whole point
     * of {@code CostOverrideRequest}'s "mandatory in BOTH directions" contract.
     *
     * <p>This is the case with no database backstop at all: {@code
     * chk_pricing_costing_item_override_reason} only fires when {@code
     * manual_landed_cost_per_unit_thb IS NOT NULL}, and a clear sets it to NULL. Delete the service
     * guard and {@code clearOverride} — which does not take a reason argument in the first place —
     * wipes a live override with no justification recorded anywhere, and Postgres accepts it
     * without complaint.
     */
    @Test
    void overrideItemCost_requiresAReasonOnTheClearPathToo_whereNoCheckConstraintApplies() {
        Fixture fixture = draftDecisionWithOneItem();
        decisions.overrideItemCost(fixture.decisionId(), fixture.itemId(),
            new CostOverrideRequest(new BigDecimal("888.0000"), "ต่อรองกับโรงงานได้ราคาที่ดีกว่า"), ceoActor);

        // Deliberately an EMPTY reason, not a whitespace one: this test is about the clear path
        // having no database backstop, and reusing the whitespace case here would make it go red
        // alongside the isBlank() test under the same mutation instead of isolating its own guard.
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // The non-negative cost.
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /**
     * {@code -0.00004} is the value that proves the service guard is load-bearing rather than
     * decorative: {@code money4} ({@code setScale(4, HALF_UP)}) rounds it to {@code 0.0000} BEFORE
     * {@code applyOverride} ever sees it, so {@code chk_pricing_costing_item_override_nonnegative}
     * ({@code >= 0}) is satisfied and Postgres stores a perfectly valid zero-cost override. Only
     * the service's own comparison — which runs against the RAW request value, before rounding —
     * catches it.
     *
     * <p>Zero itself is legitimate (a free sample, a factory absorbing the cost), so the guard is
     * deliberately {@code < 0} and not {@code <= 0}; this test pins the sign check, not a
     * "must be positive" rule that does not exist.
     */
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Positive control.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Without this test the six above are indistinguishable from a method that refuses every
     * request it is ever given. It drives both directions end to end and asserts the writes
     * actually landed in the real row.
     */
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The three ids every assertion below needs, resolved once per test. */
    private record Fixture(long pricingRequestId, long decisionId, long itemId, long costingItemId) {}

    /**
     * Wrong-way-round assertion shared by every refusal test: not just "no manual cost", but ALL SIX
     * override columns still untouched. {@code overridden_by}/{@code overridden_at} matter
     * independently — {@code clearOverride} stamps those two even when it nulls everything else, so
     * checking the manual cost alone would not distinguish "nothing happened" from "the row was
     * cleared". And no event, since {@code overrideItemCost} writes one on every successful call.
     */
    private void assertNothingWasOverridden(Fixture fixture) {
        assertThat(overrideColumn(fixture, "manual_landed_cost_per_unit_thb", BigDecimal.class)).isNull();
        assertThat(overrideColumn(fixture, "override_reason", String.class)).isNull();
        assertThat(overrideColumn(fixture, "overridden_by", Long.class)).isNull();
        assertThat(overrideColumn(fixture, "overridden_at", java.time.Instant.class)).isNull();
        assertThat(overrideColumn(fixture, "override_fx_rate", BigDecimal.class)).isNull();
        assertThat(overrideColumn(fixture, "override_calc_config_version", Integer.class)).isNull();
        assertThat(overrideEventCount(fixture)).isZero();

        // The decision item's frozen cost is derived from the effective cost, so a refused override
        // must leave it on the computed figure too — read back through the real service, not the DTO
        // captured before the call.
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

    /**
     * One-item deal driven all the way to a DRAFT pricing decision on a CEO_REVIEWING request —
     * the precondition {@code overrideItemCost} needs before its own validation is even reachable
     * ({@code requireOpenDecisionForMutation}). One item rather than two: nothing here depends on
     * a second line, and the factory-quote round trip is the expensive part of the setup.
     */
    private Fixture draftDecisionWithOneItem() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, oneItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        for (FactoryQuoteDto draft : factoryQuoteService.generateDrafts(pricingRequestId, importActor)) {
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

    private PricingRequestRequests.CreatePricingRequestRequest oneItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "cost override validation request", UUID.randomUUID().toString(),
            List.of(new PricingRequestRequests.PricingRequestItemRequest(null, catalogProductId, null,
                "SCG", "Tile OV", "SCG Tile OV", null, null, "60x60", "Factory OverrideValidation",
                new BigDecimal("10"), new BigDecimal("10"), "piece", UnitBasis.PER_PIECE,
                QuantityType.CONFIRMED, null, null, null)));
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
