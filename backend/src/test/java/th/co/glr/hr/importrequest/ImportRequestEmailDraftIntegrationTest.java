package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/** The per-factory order-email DRAFT (owner decision 09-18 #3 §B: "go ahead [order-email draft]") — against
 * real Postgres, through the real service. Mirrors the factory RFQ composer's own discipline: the backend
 * drafts, a human copies and sends, this system never sends mail itself. */
class ImportRequestEmailDraftIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal salesManager;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-email-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth, new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));

        ownerId = insertEmployee("EM-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("EM-OTH"), "sales");
        importUser = principal(insertEmployee("EM-IMP"), "import");
        ceoUser = principal(insertEmployee("EM-CEO"), "ceo");
        salesManager = principal(insertEmployee("EM-SM"), "sales_manager");
    }

    // ── draft generation at issue ────────────────────────────────────────────────────────────

    @Test
    void issue_generatesAnEmailDraft_toTheFactorysAddress_withNoPricingContent() {
        insertFactory("Email Factory", "IT", "sales@emailfactory.example");
        long ticketId = insertTicket("EM-ISSUE");
        insertItem(ticketId, "Email Factory", "Lithos Nero", "60x60 cm", "100", "pcs", 0);
        long id = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();

        ImportRequestDto issued = service.issue(id, null, owner);

        assertThat(issued.emailTo()).isEqualTo("sales@emailfactory.example");
        assertThat(issued.emailSubject()).contains(issued.docNumber()).contains("GL&R");
        assertThat(issued.emailBody()).contains("Lithos Nero").contains("60x60 cm").contains("100");
        assertThat(issued.emailSentAt()).isNull();
        // Cheap nit (REVIEW ROUND 2): "Attached:" must name the REAL download filename
        // (ImportRequestController#storedFile's own "IR-<doc>-<factory>.pdf") so a human copying this line into their
        // mail client names what they actually downloaded.
        assertThat(issued.emailBody())
            .contains("Attached: IR-" + issued.docNumber() + "-" + issued.factoryName() + ".pdf");

        // No price/discount/pricing-mode/wastage anywhere in the draft (hard boundary, checked on the body CONTENT).
        String lower = issued.emailBody().toLowerCase(java.util.Locale.ROOT);
        assertThat(lower).doesNotContain("price").doesNotContain("discount")
            .doesNotContain("wastage").doesNotContain("cost");
    }

    // ── marking sent ──────────────────────────────────────────────────────────────────────────

    @Test
    void markEmailSent_isAllowedToImportCeoAndOwningRep_notToANonOwningRepOrSalesManager() {
        insertFactory("Mark Sent Factory", "IT", "a@example.com");
        long ticketId = insertTicket("EM-MARK");
        insertItem(ticketId, "Mark Sent Factory", "Line", "60x60", "10", "pcs", 0);

        // Non-owning rep and sales_manager: refused.
        long draftForOther = DraftSeed.seed(jdbc, ticketId, owner).get(0).id();
        service.issue(draftForOther, null, owner);
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, otherSalesRep))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, salesManager))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(service.get(draftForOther, owner).emailSentAt()).isNull();

        // Import: allowed.
        ImportRequestDto afterImport = service.markEmailSent(draftForOther, importUser);
        assertThat(afterImport.emailSentAt()).isNotNull();
        assertThat(afterImport.emailSentByName()).isEqualTo(importUser.name());

        // Second call: idempotent 409, not a silent re-stamp.
        assertThatThrownBy(() -> service.markEmailSent(draftForOther, ceoUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-email@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'อีเมลใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name, String country, String email) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency, email)
            VALUES (:name, :country, 'EUR', :email) RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country)
                .addValue("email", email), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบอีเมลใบขอซื้อ', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String factory, String model, String size, String qty,
                            String unit, int sortOrder) {
        insertItemWithColor(ticket, factory, model, size, qty, unit, sortOrder, null, null);
    }

    private void insertItemWithColor(long ticket, String factory, String model, String size,
            String qty, String unit, int sortOrder, String color, String texture) {
        insertItemWithBrandModelColor(ticket, factory, factory, model, size, qty, unit, sortOrder,
            color, texture);
    }

    /** Like #insertItemWithColor, but with a REAL product brand distinct from the factory name. */
    private void insertItemWithBrandModelColor(long ticket, String brand, String factory, String model,
            String size, String qty, String unit, int sortOrder, String color, String texture) {
        jdbc.update("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, unit, sort_order, factory, color, texture)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory, :color, :texture)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory)
                .addValue("color", color).addValue("texture", texture));
    }
}
