package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** V184's factory-resolution cascade, the per-factory rollup, and the legacy one-shot import actions' refusal
 * on an IR-tracked deal — against real Postgres, through the real service. */
class ImportRequestFactoryResolutionIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private TicketService ticketService;
    private TicketRepository tickets;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-resolution-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));

        ownerId = insertEmployee("RESOLV-OWN");
        owner = principal(ownerId, "sales");
        importUser = principal(insertEmployee("RESOLV-IMP"), "import");
        ceoUser = principal(insertEmployee("RESOLV-CEO"), "ceo");
    }

    /** Step 5 of the plan's cascade: no direct id anywhere AND no name anywhere for a line refuses outright, naming
     * the line, rather than silently deriving a factory from brand (the 2026-09-18 correction). Zero rows must
     * land in the factory master — a refused create must not have side effects. */
    @Test
    void aLineWithNoFactoryAnywhereIsRefused_andCreatesNoFactoryRow() {
        long ticketId = insertTicket("RESOLV-1");
        insertItem(ticketId, "SomeBrand", "Unresolvable Line", "60x60", "10", "pcs", 0, null);
        int factoriesBefore = countFactories();

        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("Unresolvable Line");
            });
        assertThat(countFactories()).isEqualTo(factoriesBefore);
    }

    // ── rollup (owner decision 3) ─────────────────────────────────────────────────────────────

    @Test
    void rollup_firesOnlyOnceEveryFactoryIsReceived_notBefore() {
        insertFactory("Rollup Factory A", "IT", null);
        insertFactory("Rollup Factory B", "IT", null);
        long ticketId = insertTicket("RESOLV-5");
        insertItem(ticketId, "Brand", "Line A", "60x60", "10", "pcs", 0, "Rollup Factory A");
        insertItem(ticketId, "Brand", "Line B", "60x60", "10", "pcs", 1, "Rollup Factory B");

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        ImportRequestDto a = drafts.stream().filter(d -> "Rollup Factory A".equals(d.factoryName()))
            .findFirst().orElseThrow();
        ImportRequestDto b = drafts.stream().filter(d -> "Rollup Factory B".equals(d.factoryName()))
            .findFirst().orElseThrow();

        receiveAllSteps(a.id());
        // Only ONE of two factories RECEIVED — must NOT roll up yet.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        receiveAllSteps(b.id());
        // Both RECEIVED — rolls up now.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void receiveAllSteps(long importRequestId) {
        for (String step : List.of(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP,
                ImportRequestStep.IN_TRANSIT, ImportRequestStep.AWAITING_CUSTOMS, ImportRequestStep.RECEIVED)) {
            service.advanceStep(importRequestId, new AdvanceImportStepRequest(step, null, null), importUser);
        }
    }

    private String fulfillmentStatus(long ticketId) {
        return jdbc.queryForObject(
            "SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private int countFactories() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM price_catalog.factories", Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-resolv@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name, String country, String countryOther) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, country_other, default_currency)
            VALUES (:name, :country, :countryOther, 'EUR') RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country)
                .addValue("countryOther", countryOther), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบ resolution', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String brand, String model, String size, String qty,
                            String unit, int sortOrder, String factory) {
        insertItemWithCatalogAndColor(ticket, brand, model, size, qty, unit, sortOrder, factory,
            null, null, null);
    }

    /** Line-prefill fixture builder (owner decision 09-18 #3 §A): adds a catalog link and/or colour/texture on top
     * of #insertItem's shape. */
    private void insertItemWithCatalogAndColor(long ticket, String brand, String model, String size,
            String qty, String unit, int sortOrder, String factory, Long catalogPriceId,
            String color, String texture) {
        jdbc.update("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, unit, sort_order, factory, catalog_price_id,
                 color, texture)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory, :catalogPriceId,
                    :color, :texture)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory)
                .addValue("catalogPriceId", catalogPriceId)
                .addValue("color", color).addValue("texture", texture));
    }
}
