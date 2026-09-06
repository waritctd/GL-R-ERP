package th.co.glr.hr.pricingrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
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
import th.co.glr.hr.factoryquote.FactoryQuoteCarryForward;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemThicknessRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Sales must supply ความหนา when the catalog resolves none (owner-ruled SCOPE CHANGE, 2026-09-06:
 * "sales need to be forced to fill in the thickness, not import") — {@link
 * PricingRequestItemThicknessService} against a real Postgres, through the real service AND
 * repository (a Mockito-mocked repository could not prove the {@code v_priceable_product} join,
 * that sales's owner-scoping survives into {@code PricingRequestService#get}'s real SQL, or that
 * clearing/setting an override never touches {@code collection_thickness_default}).
 *
 * <p>Authorization cases are written wrong-way-round on purpose: each asserts the caller
 * <em>cannot</em> reach what they should not, and that a rejected write left the database
 * untouched. "Sales/CEO can set a thickness" is the easy case and proves the least — see
 * {@code importIsRefused_theEntirePointOfThisChange}, {@code
 * nonOwnerSalesCannotSetThicknessOnAnotherRepsDraft}, and {@code
 * salesCannotSetThicknessOnceTheRequestLeavesDraft} for the three wrong-way-round cases this
 * scope change specifically asks for.
 *
 * <p>Fixtures deliberately stay in {@code DRAFT} wherever the test does not itself need a later
 * status: {@code PricingRequestService#submit} now refuses a request while any line's thickness is
 * unresolved (the sibling gate this same scope change adds — see {@code
 * PricingRequestServiceSubmitThicknessGateIntegrationTest}), so a fixture built the OLD way —
 * submit an unresolved free-text line, then have Import/CEO fill it in afterward — would 422 at
 * the {@code submit()} call before ever reaching this service. The two tests that DO need a
 * post-DRAFT status ({@code salesCannotSetThicknessOnceTheRequestLeavesDraft}, {@code
 * ceoCanCorrectAnOverrideAfterPickup_provingTheWiderCeoWindow}) resolve the line's thickness via
 * THIS service, as the owning sales rep, WHILE STILL IN DRAFT — i.e. the real, intended workflow —
 * before calling submit()/pickup().
 */
class PricingRequestItemThicknessIntegrationTest extends AbstractPostgresIntegrationTest {

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private PricingRequestItemThicknessService thicknessService;
    private CatalogRepository catalog;

    private long ticketId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal importActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal employeeActor;

    @BeforeEach
    void wireRealCollaborators() {
        pricingRequests = new PricingRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-thickness-test-uploads");
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        catalog = new CatalogRepository(jdbc);
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(new FactoryQuoteRepository(jdbc),
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc), catalog, formulaEngine);
        FactoryQuoteCarryForward carryForward =
            new FactoryQuoteCarryForward(new FactoryQuoteRepository(jdbc), pricingRequests, landedCostCalculator);
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, carryForward);
        thicknessService = new PricingRequestItemThicknessService(pricingRequests, pricingRequestService, catalog);

        long salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-thickness@glr.co.th", "SALES", "แผนกขาย");
        long otherSalesRepId = createEmployee(employees, "พนักงานขาย คนอื่น", "sales-other-thickness@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import-thickness@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createManagingDirector(employees, "ผู้บริหาร ทดสอบ", "ceo-thickness@glr.co.th");
        long accountUserId = createEmployee(employees, "บัญชี ทดสอบ", "account-thickness@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        long salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-thickness@glr.co.th", "SALES", "ฝ่ายขาย");
        long employeeUserId = createEmployee(employees, "พนักงานทั่วไป", "employee-thickness@glr.co.th", "GEN", "ฝ่ายทั่วไป");
        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
        employeeActor = actor(employeeUserId, "employee");

        // develop's V163 (merge_factory_email_into_catalog_factories) DROPPED sales.factory_config
        // and folded its email/unit columns into price_catalog.factories, so the seed that used to
        // sit here no longer has a table to write to. Nothing below needs it: insertCatalogProduct
        // (AbstractPostgresIntegrationTest) already creates the price_catalog.factories row these
        // tests link against, and this class exercises the thickness ROLE GATE, never RFQ email —
        // which V163's own column comment says may be blank without blocking a draft anyway.

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบความหนา จำกัด", "0100000000099", "99 ถนนทดสอบ", "สำนักงานใหญ่", "02-999-9999");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบความหนา");
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีลทดสอบความหนา", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(new TicketItemRequest("Brand", "Model", "White", "Matte", "60x60",
                    "Thickness Test Factory", new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ── authorization, wrong-way-round ───────────────────────────────────────

    @Test
    void importIsRefused_theEntirePointOfThisChange() {
        // Deliberately the SIMPLEST possible fixture (a bare DRAFT, no submit/pickup at all): the
        // role gate runs before anything else in setItemThickness, so this needs no more than
        // that to prove import is refused. Mutation-check target — re-add "import" to
        // PricingRequestItemThicknessService.ITEM_THICKNESS_ROLES and confirm THIS test (and no
        // other) goes red.
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

        assertThatThrownBy(() -> thicknessService.setItemThickness(pricingRequestId, itemId, request, importActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(rawOverrideOf(itemId)).isNull();
    }

    @Test
    void wrongRolesCannotSetThickness_andTheDatabaseStaysUntouched() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

        for (UserPrincipal wrongActor : List.of(salesManagerActor, accountActor, employeeActor)) {
            assertThatThrownBy(() -> thicknessService.setItemThickness(pricingRequestId, itemId, request, wrongActor))
                .as("role " + wrongActor.role() + " must be rejected")
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(resolvedThicknessOf(pricingRequestId, itemId))
            .as("a rejected write must leave the item's thickness exactly as it was")
            .isNull();
        assertThat(rawOverrideOf(itemId)).isNull();
    }

    @Test
    void nonOwnerSalesCannotSetThicknessOnAnotherRepsDraft() {
        // A DRAFT is the owning rep's private scratchpad (PricingRequestService#requireViewable's
        // canSeeDraft) — a DIFFERENT sales rep must not even discover it exists, let alone write
        // to it. This is the check PricingRequestItemThicknessService inherits "for free" from
        // composing PricingRequestService#get rather than re-deriving ownership itself — see that
        // class's own Javadoc.
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

        assertThatThrownBy(() -> thicknessService.setItemThickness(pricingRequestId, itemId, request, otherSalesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(rawOverrideOf(itemId)).isNull();
    }

    @Test
    void salesCannotSetThicknessOnceTheRequestLeavesDraft() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        // Resolve it the real way — the owning rep, in DRAFT — so submit() below does not 422 on
        // the sibling gate (PricingRequestService#submit).
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("6")), salesActor);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);

        assertThatThrownBy(() -> thicknessService.setItemThickness(
                pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("9")), salesActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(rawOverrideOf(itemId))
            .as("a refused write must leave the earlier, correctly-set value untouched")
            .isEqualByComparingTo("6");
    }

    // ── the behaviour that matters ───────────────────────────────────────────

    @Test
    void salesCanSetThicknessOnOwnDraftUnlinkedLine_writingTheItemsOwnOverride() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);

        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("9.25")), salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.thicknessMmOverride()).isEqualByComparingTo("9.25");
        assertThat(item.resolvedThicknessMm()).isEqualByComparingTo("9.25");
        assertThat(item.thicknessIsDefault()).isFalse();
    }

    @Test
    void ceoCanSetThicknessOnAStuckDraft_theFallbackIsNotOwnerScoped() {
        // ceoActor never created this ticket/request (salesActor did, in @BeforeEach) — succeeding
        // here IS the proof that ceo is not owner-scoped, on top of proving the fallback reaches
        // DRAFT at all.
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);

        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("7.5")), ceoActor);

        assertThat(onlyItem(detail).thicknessMmOverride()).isEqualByComparingTo("7.5");
    }

    @Test
    void ceoCanCorrectAnOverrideAfterPickup_provingTheWiderCeoWindow() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("6")), salesActor);
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        assertThat(pricingRequests.findSummary(pricingRequestId).orElseThrow().status())
            .isEqualTo(PricingRequestStatus.IMPORT_REVIEWING);

        // sales is refused here (see salesCannotSetThicknessOnceTheRequestLeavesDraft); ceo, the
        // fallback, may still correct it — proving CEO_EDITABLE_STATUSES really is wider than
        // SALES_EDITABLE_STATUSES, not merely declared to be.
        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("11")), ceoActor);

        assertThat(onlyItem(detail).thicknessMmOverride()).isEqualByComparingTo("11");
    }

    @Test
    void ceoCanSetThicknessOnALinkedLineWithNoCatalogThickness_writingTheItemsOwnOverride_neverTheSharedDefault() {
        // Ruling 3 (owner-ruled, 2026-09-06): "A sales-entered thickness is stored on THIS DEAL
        // LINE ONLY — never the shared collection_thickness_default." Routing on link status was
        // REMOVED; this test seeds a LINKED line (the case that used to route to the shared
        // table) specifically to prove it no longer does, even for the CEO fallback.
        String collection = "THICKNESS-COLLECTION-" + UUID.randomUUID();
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
        long factoryId = factoryIdOf(priceId);
        assertThat(catalogResolvedThicknessOf(priceId)).as("seeded with no thickness").isNull();
        long pricingRequestId = linkedLineDraftPricingRequest(priceId);
        long itemId = onlyItemId(pricingRequestId);

        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("12.0")), ceoActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.thicknessMmOverride())
            .as("routing dropped 2026-09-06 — a LINKED line now writes its OWN override too")
            .isEqualByComparingTo("12.0");
        assertThat(item.resolvedThicknessMm()).isEqualByComparingTo("12.0");
        assertThat(item.thicknessIsDefault())
            .as("resolved via this line's own override, NOT a collection default")
            .isFalse();
        assertThat(catalogResolvedThicknessOf(priceId))
            .as("the catalog's own product row must be untouched")
            .isNull();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM price_catalog.collection_thickness_default WHERE factory_id = :factoryId AND collection = :collection",
            new MapSqlParameterSource().addValue("factoryId", factoryId).addValue("collection", collection), Integer.class))
            .as("the shared collection default must never get a row from this endpoint any more")
            .isZero();

        // A second, different product in the SAME (factory, collection) must stay UNRESOLVED — the
        // opposite of the old (pre-2026-09-06) shared-default behaviour, and exactly what Ruling 3
        // is guarding against ("a rep's guess must not become every future deal's default").
        long siblingPriceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-SIBLING-" + UUID.randomUUID(),
            new BigDecimal("60.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", siblingPriceId).addValue("c", collection));
        assertThat(catalogResolvedThicknessOf(siblingPriceId))
            .as("the sibling must NOT have inherited a shared default")
            .isNull();
    }

    @Test
    void nullClearsAnUnlinkedLinesOwnOverride() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("7")), salesActor);
        assertThat(resolvedThicknessOf(pricingRequestId, itemId)).isNotNull();

        PricingRequestDetailDto cleared = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(null), salesActor);

        assertThat(onlyItem(cleared).thicknessMmOverride()).isNull();
        assertThat(onlyItem(cleared).resolvedThicknessMm()).isNull();
    }

    @Test
    void nullClearsALinkedLinesOwnOverrideToo() {
        String collection = "THICKNESS-CLEAR-" + UUID.randomUUID();
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-CLR-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
        long pricingRequestId = linkedLineDraftPricingRequest(priceId);
        long itemId = onlyItemId(pricingRequestId);
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("15")), ceoActor);
        assertThat(rawOverrideOf(itemId)).isNotNull();

        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(null), ceoActor);

        assertThat(rawOverrideOf(itemId))
            .as("cleared back to the honest NO_THICKNESS state, not a stored zero")
            .isNull();
        assertThat(catalogResolvedThicknessOf(priceId))
            .as("the shared catalog table was never touched by either the set or the clear")
            .isNull();
    }

    @Test
    void refusesWhenTheLineAlreadyResolvesAThicknessFromTheCatalog() {
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-RESOLVED-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", new BigDecimal("10"));
        long pricingRequestId = linkedLineDraftPricingRequest(priceId);
        long itemId = onlyItemId(pricingRequestId);
        assertThat(catalogResolvedThicknessOf(priceId)).as("seeded with a real thickness already").isNotNull();

        assertThatThrownBy(() -> thicknessService.setItemThickness(
                pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("99")), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(catalogResolvedThicknessOf(priceId))
            .as("a refused write must leave the real catalog value untouched")
            .isEqualByComparingTo("10");
    }

    @Test
    void returns404ForAnItemThatDoesNotBelongToTheRequest() {
        long pricingRequestId = unlinkedLineDraftPricingRequest();

        assertThatThrownBy(() -> thicknessService.setItemThickness(
                pricingRequestId, 999_999_999L, new SetItemThicknessRequest(new BigDecimal("5")), ceoActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    /** A bare DRAFT with one unlinked (free-text) item — no catalog match, so no rung of the
     * catalog chain can ever resolve its thickness; only an override (this service) can. */
    private long unlinkedLineDraftPricingRequest() {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, null, null, "Brand", "Free-text Model", "Brand Free-text Model", null, null, "1x1",
            "Thickness Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return draftPricingRequest(item);
    }

    /** A bare DRAFT with one item linked to {@code priceId}. */
    private long linkedLineDraftPricingRequest(long priceId) {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, priceId, null, "Brand", "Linked Model", "Brand Linked Model", null, null, "1x1",
            "Thickness Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return draftPricingRequest(item);
    }

    private long draftPricingRequest(PricingRequestItemRequest item) {
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "thickness test request", UUID.randomUUID().toString(), List.of(item));
        return pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
    }

    private long onlyItemId(long pricingRequestId) {
        return onlyItem(pricingRequestId).id();
    }

    private PricingRequestItemDto onlyItem(long pricingRequestId) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private PricingRequestItemDto onlyItem(PricingRequestDetailDto detail) {
        assertThat(detail.items()).hasSize(1);
        return detail.items().get(0);
    }

    private BigDecimal resolvedThicknessOf(long pricingRequestId, long itemId) {
        return pricingRequests.findItems(pricingRequestId).stream()
            .filter(i -> i.id() == itemId)
            .findFirst()
            .orElseThrow()
            .resolvedThicknessMm();
    }

    private BigDecimal rawOverrideOf(long itemId) {
        return jdbc.queryForObject(
            "SELECT thickness_mm_override FROM sales.pricing_request_item WHERE pricing_request_item_id = :id",
            Map.of("id", itemId), BigDecimal.class);
    }

    private BigDecimal catalogResolvedThicknessOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT thickness_mm FROM price_catalog.v_priceable_product WHERE price_id = :id",
            Map.of("id", priceId), BigDecimal.class);
    }

    private long factoryIdOf(long priceId) {
        return jdbc.queryForObject(
            "SELECT factory_id FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", priceId), Long.class);
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** See PricingFactoryQuoteCostingIntegrationTest's identical helper for why a real position
     * matters: CeoApproverRule keys the notified set on position กรรมการผู้จัดการ alone. */
    private long createManagingDirector(EmployeeRepository employees, String nameTh, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, "MD", "ผู้บริหาร", "ผู้บริหาร",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("30000"),
            null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
