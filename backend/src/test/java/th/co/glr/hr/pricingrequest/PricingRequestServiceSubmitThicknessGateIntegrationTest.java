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
 * {@code PricingRequestService#submit}'s new thickness gate (owner-ruled scope change,
 * 2026-09-06: "sales need to be forced to fill in the thickness, not import") — against a real
 * Postgres, through the real service AND repository. A Mockito-mocked repository could prove the
 * DECISION (see {@code PricingRequestServiceTest#submit_rejectsWhenAnItemsThicknessIsUnresolved}),
 * but not that it survives into real SQL: the gate reads {@code resolvedThicknessMm}, which
 * {@code PricingRequestRepository#findItems} computes via a LEFT JOIN keyed off {@code
 * catalog_price_id ?? product_id} — exactly the column {@code snapshotCatalogSelections} (called
 * immediately before this gate) writes. Only a real Postgres round trip proves the ORDER matters:
 * checking before the snapshot would read a stale (pre-snapshot) link and wrongly refuse a line
 * that has actually just resolved.
 *
 * <p>Mutation-check target: comment out the gate in {@code PricingRequestService#submit} (the
 * block immediately after {@code items = requests.findItems(id);}) and confirm {@code
 * submit_refusesWhenALineHasNoResolvableThickness} — and only that test — goes red.
 */
class PricingRequestServiceSubmitThicknessGateIntegrationTest extends AbstractPostgresIntegrationTest {

    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private PricingRequestItemThicknessService thicknessService;

    private long ticketId;
    private UserPrincipal salesActor;

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
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-pricing-submit-thickness-test-uploads");
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        CatalogRepository catalog = new CatalogRepository(jdbc);
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(new FactoryQuoteRepository(jdbc),
            pricingRequests, fxRates, new FactoryConfigRepository(jdbc), catalog, formulaEngine);
        FactoryQuoteCarryForward carryForward =
            new FactoryQuoteCarryForward(new FactoryQuoteRepository(jdbc), pricingRequests, landedCostCalculator);
        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, carryForward);
        thicknessService = new PricingRequestItemThicknessService(pricingRequests, pricingRequestService, catalog);

        long salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "sales-submit-thickness@glr.co.th", "SALES", "แผนกขาย");
        salesActor = actor(salesRepId, "sales");

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบเกตความหนา จำกัด", "0100000000098", "98 ถนนทดสอบ", "สำนักงานใหญ่", "02-998-9998");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบเกตความหนา");
        TicketService ticketService = new TicketService(tickets, notifications,
            objectMapper, customers, new QuotationRenderer(), pricingRequestService);
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ดีลทดสอบเกตความหนา", "NORMAL", customer.name(), customer.id(), project.id(), null,
                null, null, List.of(new TicketItemRequest("Brand", "Model", "White", "Matte", "60x60",
                    "Submit Gate Test Factory", new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB"))),
            salesActor);
        ticketId = created.summary().id();
    }

    @Test
    void submit_refusesWhenALineHasNoResolvableThickness() {
        long pricingRequestId = draftWithUnlinkedItem();

        assertThatThrownBy(() -> pricingRequestService.submit(pricingRequestId, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(e.getMessage())
                    .as("names the offending line the way LandedCostCalculator#itemLabel does")
                    .containsPattern("รายการที่ \\d+");
            });

        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .as("a refused submit must leave the request in DRAFT")
            .isEqualTo(PricingRequestStatus.DRAFT);
    }

    @Test
    void submit_succeedsOnceTheOverrideIsSupplied() {
        long pricingRequestId = draftWithUnlinkedItem();
        long itemId = onlyItem(pricingRequestId).id();
        // The real workflow this scope change asks for: Sales resolves the gap through
        // PricingRequestItemThicknessService, in DRAFT, before ever calling submit() — not a raw
        // repository write.
        thicknessService.setItemThickness(pricingRequestId, itemId, new SetItemThicknessRequest(new BigDecimal("8")), salesActor);

        PricingRequestDetailDto submitted = pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.pricing_request WHERE pricing_request_id = :id",
            Map.of("id", pricingRequestId), String.class))
            .isEqualTo(PricingRequestStatus.SUBMITTED);
    }

    @Test
    void submit_succeedsForACatalogLinkedItemWithNoOverrideNeeded() {
        // The ordering this gate depends on: the check runs AFTER snapshotCatalogSelections and
        // the item re-read that follows it, so a line that resolves PURELY via the catalog join
        // (never touching PricingRequestItemThicknessService at all) must submit cleanly — proving
        // the gate reads the POST-snapshot state, not a stale pre-snapshot one.
        long priceId = insertCatalogProduct("Submit Gate Test Factory", "IT", "SUBMIT-GATE-" + UUID.randomUUID(),
            new BigDecimal("50.00"), "THB", "per_piece", "ACTIVE", new BigDecimal("9.5"));
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, priceId, null, "Brand", "Linked Model", "Brand Linked Model", null, null, "1x1",
            "Submit Gate Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        long pricingRequestId = draftPricingRequest(item);

        PricingRequestDetailDto submitted = pricingRequestService.submit(pricingRequestId, salesActor);

        assertThat(submitted.summary().status()).isEqualTo(PricingRequestStatus.SUBMITTED);
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private long draftWithUnlinkedItem() {
        PricingRequestItemRequest item = new PricingRequestItemRequest(
            null, null, null, "Brand", "Free-text Model", "Brand Free-text Model", null, null, "1x1",
            "Submit Gate Test Factory", new BigDecimal("10"), null, "piece", UnitBasis.PER_PIECE,
            QuantityType.CONFIRMED, null, null, null);
        return draftPricingRequest(item);
    }

    private long draftPricingRequest(PricingRequestItemRequest item) {
        CreatePricingRequestRequest request = new CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            null, "THB", "submit thickness gate test", UUID.randomUUID().toString(), List.of(item));
        return pricingRequestService.createDraft(ticketId, request, salesActor).summary().id();
    }

    private PricingRequestDtos.PricingRequestItemDto onlyItem(long pricingRequestId) {
        List<PricingRequestDtos.PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        assertThat(items).hasSize(1);
        return items.get(0);
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
