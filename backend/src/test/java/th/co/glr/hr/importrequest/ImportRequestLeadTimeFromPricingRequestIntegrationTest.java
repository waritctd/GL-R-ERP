package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
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

/** CR-1 (GLA-167), part D (owner rulings B-R4 / B-R5) — an ใบขอซื้อ (IR) takes its lead time from the deal's
 * CURRENT QUOTATION ITEMS, never from the pricing-request lines. The legacy engine (origin IS NULL) carries no
 * per-item lead time. */
class ImportRequestLeadTimeFromPricingRequestIntegrationTest extends AbstractPostgresIntegrationTest {
    private ImportRequestService service;
    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal importUser; // import owns the post-issue lead-time edit
    private int seq;

    @BeforeEach
    void wire() {
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        ObjectMapper objectMapper = new ObjectMapper();
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), new FileStorageService("/tmp/glr-cr1-ir-test-uploads"),
            factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            new CustomerRepository(jdbc), new QuotationRenderer(), pricingRequestService,
            new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        service = new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            new ImportRequestRepository(jdbc), new FactoryConfigRepository(jdbc), tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));
        ownerId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) VALUES ('CR1-OWN', 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of(), Long.class);
        owner = new UserPrincipal(ownerId, "owner-cr1@glr.co.th", "sales", "sales", ownerId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
        importUser = new UserPrincipal(ownerId, "import-cr1@glr.co.th", "import", "import", ownerId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    /** D1 — longest lead time among the factory's quotation items; the PR lines (10-20) are ignored. */
    @Test
    void prRoute_takesTheLongestLeadTimeAmongTheFactorysAcceptedQuotationItems() {
        insertFactory("D1 Factory", "CN"); // country default would be 30-45
        long ticket = insertTicket("CR1-D1");
        long t1 = insertTicketItem(ticket, "D1 Factory", "Line 1", 0, null);
        long t2 = insertTicketItem(ticket, "D1 Factory", "Line 2", 1, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D1 Factory", 10, 20);
        long p2 = insertPricingRequestItem(pr, t2, "D1 Factory", 11, 21);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, 100, 120);
        insertQuotationItem(q, p2, null, 120, 150);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(120);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(150);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void insertFactory(String name, String country) {
        jdbc.update("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, 'EUR')
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country));
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES (:code, 'ทดสอบ CR1', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued', 'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private long insertTicketItem(long ticket, String factory, String model, int sort, Long catalogPriceId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory, catalog_price_id)
            VALUES (:t, 'Brand', :model, '60x60', 10, 'pcs', :sort, :factory, :cp)
            RETURNING item_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("model", model)
                .addValue("sort", sort).addValue("factory", factory).addValue("cp", catalogPriceId), Long.class);
    }

    private long insertAcceptedPricingRequest(long ticket, String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, status, requested_by)
            VALUES (:code, :ticket, 'DESIGNER', 'QUOTATION_ACCEPTED', :by)
            RETURNING pricing_request_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("ticket", ticket)
                .addValue("by", ownerId), Long.class);
    }

    private long insertPricingRequestItem(long pr, long ticketItem, String factory, Integer min, Integer max) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, source_ticket_item_id, factory, requested_qty, requested_unit,
                 requested_unit_basis, quantity_type, lead_time_min_days, lead_time_max_days)
            VALUES (:pr, :ti, :factory, 10, 'PIECE', 'PER_PIECE', 'CONFIRMED', :min, :max)
            RETURNING pricing_request_item_id
            """, new MapSqlParameterSource().addValue("pr", pr).addValue("ti", ticketItem)
                .addValue("factory", factory).addValue("min", min, java.sql.Types.SMALLINT)
                .addValue("max", max, java.sql.Types.SMALLINT), Long.class);
    }

    /** {@code origin} null = the legacy engine. */
    private long insertQuotation(long ticket, String origin, String docStatus, Long pricingRequestId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, pricing_request_id)
            VALUES (:t, :number, :by, :origin, :status, :pr)
            RETURNING quotation_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("number", "QT-CR1-" + (++seq))
                .addValue("by", ownerId).addValue("origin", origin).addValue("status", docStatus)
                .addValue("pr", pricingRequestId), Long.class);
    }

    private void insertQuotationItem(long quotation, Long pricingRequestItemId, Long catalogPriceId,
                                     Integer min, Integer max) {
        jdbc.update("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, unit_price, amount, pricing_request_item_id, catalog_price_id,
                 lead_time_min_days, lead_time_max_days)
            VALUES (:q, :seq, 1, 1, :pri, :cp, :min, :max)
            """, new MapSqlParameterSource().addValue("q", quotation).addValue("seq", ++seq)
                .addValue("pri", pricingRequestItemId).addValue("cp", catalogPriceId)
                .addValue("min", min, java.sql.Types.SMALLINT).addValue("max", max, java.sql.Types.SMALLINT));
    }
}
