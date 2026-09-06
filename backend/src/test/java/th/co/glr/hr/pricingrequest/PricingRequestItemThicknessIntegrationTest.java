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
import th.co.glr.hr.catalog.ThicknessDefaultRepository;
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
 * ฝ่ายนำเข้า must supply ความหนา when the catalog resolves none — {@link
 * PricingRequestItemThicknessService} against a real Postgres, through the real service AND
 * repository (a Mockito-mocked repository could not prove the {@code v_priceable_product} join or
 * the {@code collection_thickness_default} upsert actually land where they should).
 *
 * <p>Authorization cases are written wrong-way-round on purpose: each asserts the caller
 * <em>cannot</em> reach what they should not, and that a rejected write left the database
 * untouched. "Import/CEO can set a thickness" is the easy case and proves the least.
 */
class PricingRequestItemThicknessIntegrationTest extends AbstractPostgresIntegrationTest {

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private PricingRequestItemThicknessService thicknessService;
    private CatalogRepository catalog;
    private ThicknessDefaultRepository thicknessDefaults;

    private long ticketId;
    private UserPrincipal salesActor;
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
        thicknessDefaults = new ThicknessDefaultRepository(jdbc);
        thicknessService = new PricingRequestItemThicknessService(
            pricingRequests, pricingRequestService, catalog, thicknessDefaults);

        long salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-thickness@glr.co.th", "SALES", "แผนกขาย");
        long importUserId = createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "import-thickness@glr.co.th", "PCIM", "ฝ่ายนำเข้า");
        long ceoUserId = createManagingDirector(employees, "ผู้บริหาร ทดสอบ", "ceo-thickness@glr.co.th");
        long accountUserId = createEmployee(employees, "บัญชี ทดสอบ", "account-thickness@glr.co.th", "ACCT", "ฝ่ายบัญชี");
        long salesManagerUserId = createEmployee(employees, "ผู้จัดการฝ่ายขาย", "sales-manager-thickness@glr.co.th", "SALES", "ฝ่ายขาย");
        long employeeUserId = createEmployee(employees, "พนักงานทั่วไป", "employee-thickness@glr.co.th", "GEN", "ฝ่ายทั่วไป");
        salesActor = actor(salesRepId, "sales");
        importActor = actor(importUserId, "import");
        ceoActor = actor(ceoUserId, "ceo");
        accountActor = actor(accountUserId, "account");
        salesManagerActor = actor(salesManagerUserId, "sales_manager");
        employeeActor = actor(employeeUserId, "employee");

        jdbc.update("""
            INSERT INTO sales.factory_config (factory_name, email, currency, unit, country)
            VALUES ('Thickness Test Factory', 'thickness-factory@example.com', 'THB', 'piece', 'Italy')
            ON CONFLICT (factory_name) DO UPDATE SET email = EXCLUDED.email
            """, Map.of());

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
    void wrongRolesCannotSetThickness_andTheDatabaseStaysUntouched() {
        long pricingRequestId = unlinkedLinePricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        SetItemThicknessRequest request = new SetItemThicknessRequest(new BigDecimal("8.5"));

        for (UserPrincipal wrongActor : List.of(salesActor, salesManagerActor, accountActor, employeeActor)) {
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

    // ── the behaviour that matters ───────────────────────────────────────────

    @Test
    void importCanSetThicknessOnAnUnlinkedLine_writingTheItemsOwnOverride() {
        long pricingRequestId = unlinkedLinePricingRequest();
        long itemId = onlyItemId(pricingRequestId);

        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("9.25")), importActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.thicknessMmOverride()).isEqualByComparingTo("9.25");
        assertThat(item.resolvedThicknessMm()).isEqualByComparingTo("9.25");
        assertThat(item.thicknessIsDefault()).isFalse();
    }

    @Test
    void ceoCanSetThicknessOnALinkedLine_upsertingTheSharedCollectionDefault() {
        String collection = "THICKNESS-COLLECTION-" + UUID.randomUUID();
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
        long factoryId = factoryIdOf(priceId);
        assertThat(catalogResolvedThicknessOf(priceId)).as("seeded with no thickness").isNull();

        long pricingRequestId = linkedLinePricingRequest(priceId);
        long itemId = onlyItemId(pricingRequestId);

        PricingRequestDetailDto detail = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("12.0")), ceoActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.thicknessMmOverride())
            .as("a LINKED line must route to the shared catalog default, never its own override column")
            .isNull();
        assertThat(item.resolvedThicknessMm()).isEqualByComparingTo("12.0");
        assertThat(item.thicknessIsDefault())
            .as("resolved via a collection default, not the product's own thickness_mm")
            .isTrue();
        assertThat(catalogResolvedThicknessOf(priceId)).isEqualByComparingTo("12.0");
        // Landed exactly where the routing rule says it must: ONE row in the shared table, keyed
        // by (factory, collection) — not on the pricing_request_item row at all.
        assertThat(jdbc.queryForObject("""
            SELECT thickness_mm FROM price_catalog.collection_thickness_default
             WHERE factory_id = :factoryId AND collection = :collection AND size_norm IS NULL
            """, new MapSqlParameterSource().addValue("factoryId", factoryId).addValue("collection", collection),
            BigDecimal.class)).isEqualByComparingTo("12.0");

        // Genuinely SHARED: a second, different product in the same (factory, collection) also
        // resolves now, without ever being touched directly — proves this landed in
        // collection_thickness_default, not on the pricing_request_item row.
        long siblingPriceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-SIBLING-" + UUID.randomUUID(),
            new BigDecimal("60.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", siblingPriceId).addValue("c", collection));
        assertThat(catalogResolvedThicknessOf(siblingPriceId)).isEqualByComparingTo("12.0");
    }

    @Test
    void nullClearsAnUnlinkedLinesOwnOverride() {
        long pricingRequestId = unlinkedLinePricingRequest();
        long itemId = onlyItemId(pricingRequestId);
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("7")), importActor);
        assertThat(resolvedThicknessOf(pricingRequestId, itemId)).isNotNull();

        PricingRequestDetailDto cleared = thicknessService.setItemThickness(
            pricingRequestId, itemId, new SetItemThicknessRequest(null), importActor);

        assertThat(onlyItem(cleared).thicknessMmOverride()).isNull();
        assertThat(onlyItem(cleared).resolvedThicknessMm()).isNull();
    }

    @Test
    void nullClearsALinkedLinesSharedCollectionDefault() {
        String collection = "THICKNESS-CLEAR-" + UUID.randomUUID();
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-CLR-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", null);
        jdbc.update("UPDATE price_catalog.product_prices SET collection = :c WHERE price_id = :id",
            new MapSqlParameterSource().addValue("id", priceId).addValue("c", collection));
        long pricingRequestId = linkedLinePricingRequest(priceId);
        long itemId = onlyItemId(pricingRequestId);
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("15")), ceoActor);
        assertThat(catalogResolvedThicknessOf(priceId)).isNotNull();

        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(null), ceoActor);

        assertThat(catalogResolvedThicknessOf(priceId))
            .as("cleared back to the honest NO_THICKNESS state, not a stored zero")
            .isNull();
    }

    @Test
    void refusesWhenTheLineAlreadyResolvesAThicknessFromTheCatalog() {
        long priceId = insertCatalogProduct("Thickness Test Factory", "IT", "THICK-RESOLVED-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", new BigDecimal("10"));
        long pricingRequestId = linkedLinePricingRequest(priceId);
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
        long pricingRequestId = unlinkedLinePricingRequest();

        assertThatThrownBy(() -> thicknessService.setItemThickness(
                pricingRequestId, 999_999_999L, new SetItemThicknessRequest(new BigDecimal("5")), importActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private long unlinkedLinePricingRequest() {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, null, null, "Brand", "Free-text Model", "Brand Free-text Model", null, null, "1x1",
            "Thickness Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return createSubmittedPickedUpRequest(item);
    }

    private long linkedLinePricingRequest(long priceId) {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, priceId, null, "Brand", "Linked Model", "Brand Linked Model", null, null, "1x1",
            "Thickness Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return createSubmittedPickedUpRequest(item);
    }

    private long createSubmittedPickedUpRequest(PricingRequestItemRequest item) {
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "thickness test request", UUID.randomUUID().toString(), List.of(item));
        long pricingRequestId = pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        return pricingRequestId;
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
