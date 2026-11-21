package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
import th.co.glr.hr.importrequest.ImportRequestRequests.UpdateImportRequestRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** Lead time / expected arrival (V184, owner decision 09-18 #2 — "they also need to automatically fill
 * estimations of how many days it'll take for the item to arrive like the direct quote form") — against real
 * Postgres, through the real service. */
class ImportRequestLeadTimeIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-leadtime-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));

        ownerId = insertEmployee("LT-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("LT-OTH"), "sales");
        importUser = principal(insertEmployee("LT-IMP"), "import");
        ceoUser = principal(insertEmployee("LT-CEO"), "ceo");
    }

    // ── 10-29 ruling B: lead-time popup contract (missing lead time is askable, not a dead end) ──

    private static final String MISSING_LEAD_TIME_PREFIX = "กรุณาระบุระยะเวลานำเข้าให้กับโรงงาน: ";

    private long dealWithThreeFactories() {
        insertFactory("LT Good Factory", "IT", null);
        insertFactory("LT Missing PL", "PL", null);
        insertFactory("LT Missing ZZ", "ZZ", "Farawaystan");
        long ticket = insertTicket("LT-B-" + System.nanoTime());
        insertItem(ticket, "LT Good Factory", "Line G", 0);
        insertItem(ticket, "LT Missing PL", "Line P", 1);
        insertItem(ticket, "LT Missing ZZ", "Line Z", 2);
        return ticket;
    }

    @Test
    void createDrafts_409ListsOnlyTheFactoriesWithNoLeadTime_andWritesNothing() {
        long ticket = dealWithThreeFactories();

        assertThatThrownBy(() -> service.createDrafts(ticket, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).startsWith(MISSING_LEAD_TIME_PREFIX)
                    .contains("LT Missing PL").contains("LT Missing ZZ").doesNotContain("LT Good Factory");
            });
        assertThat(service.list(ticket, owner)).isEmpty();
    }

    // ── editable while DRAFT (owning rep/CEO), and after ISSUE (import/CEO) ──────────────────────

    @Test
    void draftLeadTime_isEditableByTheOwningRepOrCeo_notByANonOwningRepOrImport() {
        insertFactory("Editable Draft Factory", "PL", null);
        long ticketId = insertTicket("LT-DRAFT-EDIT");
        insertItem(ticketId, "Editable Draft Factory", "Line", 0);
        long id = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();

        // Owning rep: allowed.
        ImportRequestDto afterOwner = service.update(id, new UpdateImportRequestRequest(
            null, null, null, null, null, null, null, null, null, 15, 25, null), owner);
        assertThat(afterOwner.leadTimeMinDays()).isEqualTo(15);
        assertThat(afterOwner.leadTimeMaxDays()).isEqualTo(25);

        // Non-owning rep: refused (wrong-way-round — this is the case that matters).
        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, 1, 2, null), otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Import: refused on the DRAFT-stage route — import edits lead time only AFTER issue.
        assertThatThrownBy(() -> service.update(id, new UpdateImportRequestRequest(
                null, null, null, null, null, null, null, null, null, 1, 2, null), importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        // Value untouched by either refused attempt.
        assertThat(service.get(id, owner).leadTimeMinDays()).isEqualTo(15);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-lt@glr.co.th", role, role, employeeId, true,
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
            VALUES (:code, 'ทดสอบ lead time', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String factory, String model, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, :brand, :model, '60x60', 10, 'pcs', :sort, :factory)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", "Brand")
                .addValue("model", model).addValue("sort", sortOrder).addValue("factory", factory));
    }
}
