package th.co.glr.hr.importrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.ItemFactoryInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryCountryInput;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryLeadTimeInput;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Slice 6c: the shared wiring and the one "every kind of line" deal that the split preview ({@link
 * ImportRequestSplitPreviewIntegrationTest}) runs against — real Postgres, real service, real repositories.
 *
 * <p>The deal's lines, in sort order, exercise every branch of the factory cascade and of the lead-time choice:
 * <ol start="0">
 *   <li>E1 — "Pin Echo" (IT), already covered by a live IR before the deal grows (skipped);
 *   <li>A1 — "Pin Alpha" (IT) by typed name, with an ACCEPTED PR-route quotation lead time of 100–120 (QUOTATION);
 *   <li>A2 — "Pin Alpha", 10 ordered, 4 from stock (remaining 6);
 *   <li>B1 — "Pin Beta" (CN) by catalogue link, a direct factory id (country default 30–45);
 *   <li>C1 — " Pin New Co " typed, matching no factory (auto-create, needs a country);
 *   <li>C2 — "pin  new co", the same new factory once normalized;
 *   <li>D1 — no factory at all (the rep's pick, or a 409);
 *   <li>S1 — "Pin Alpha", fully from stock (never ordered);
 *   <li>B2 — "pin beta" typed, matching the existing "Pin Beta" by normalized name.
 * </ol>
 * "Pin Delta" (PL, no country default) is the factory the rep picks for D1.
 */
abstract class SplitFixtureSupport extends AbstractPostgresIntegrationTest {

    protected ImportRequestService service;
    protected ImportRequestRepository stored;

    protected long ownerId;
    protected UserPrincipal owner;
    protected UserPrincipal otherSalesRep;
    protected UserPrincipal importUser;
    protected UserPrincipal ceoUser;
    protected UserPrincipal salesManager;
    protected UserPrincipal accountUser;

    private int seq;

    @BeforeEach
    void wireSplitFixture() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        stored = new ImportRequestRepository(jdbc);
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, new RecordingSalesMailer(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), new FileStorageService("/tmp/glr-ir-split-test-uploads"),
            factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper,
            new CustomerRepository(jdbc), new QuotationRenderer(), pricingRequestService,
            new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc),
            new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc,
                new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored,
            new FactoryConfigRepository(jdbc), tickets, ticketService, notifications, new th.co.glr.hr.importrequest.ShipmentRoundRepository(jdbc));

        ownerId = insertEmployee("SPLIT-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("SPLIT-OTH"), "sales");
        importUser = principal(insertEmployee("SPLIT-IMP"), "import");
        ceoUser = principal(insertEmployee("SPLIT-CEO"), "ceo");
        salesManager = principal(insertEmployee("SPLIT-SM"), "sales_manager");
        accountUser = principal(insertEmployee("SPLIT-ACC"), "account");
    }

    /** The ids a test needs from {@link #richDeal()}. */
    protected record RichDeal(long ticketId, long d1ItemId, long deltaFactoryId, long echoFactoryId,
                              long alphaFactoryId, long betaFactoryId) {}

    /** Builds the deal described in the class Javadoc. E1's factory is covered by a live, ISSUED IR created
     * through the real createDrafts before the other lines are added. */
    protected RichDeal richDeal() {
        long echo = insertFactory("Pin Echo", "IT");
        long alpha = insertFactory("Pin Alpha", "IT");
        long delta = insertFactory("Pin Delta", "PL");
        long betaPrice = insertCatalogProduct("Pin Beta", "CN", "PB-001", new BigDecimal("10"), "EUR", "per_piece");
        long beta = jdbc.queryForObject("SELECT factory_id FROM price_catalog.factories WHERE name = 'Pin Beta'",
            Map.of(), Long.class);

        long ticket = insertTicket("SPLIT-1");
        insertItem(ticket, "Pin Echo", "Echo One", "60x60 cm", "5", "0", 0, null);
        service.createDrafts(ticket, null, owner);

        long a1 = insertItem(ticket, "Pin Alpha", "Alpha One", "60x120 cm", "308", "0", 1, null);
        insertItem(ticket, "Pin Alpha", "Alpha Two", "30x60 cm", "10", "4", 2, null);
        insertItem(ticket, null, "Beta One", "60x60 cm", "44", "0", 3, betaPrice);
        insertItem(ticket, " Pin New Co ", "New One", "20x20 cm", "7", "0", 4, null);
        insertItem(ticket, "pin  new co", "New Two", "20x20 cm", "8", "0", 5, null);
        long d1 = insertItem(ticket, null, "Free Text One", "10x10 cm", "3", "0", 6, null);
        insertItem(ticket, "Pin Alpha", "Stock Only", "60x60 cm", "12", "12", 7, null);
        insertItem(ticket, "pin beta", "Beta Two", "60x60 cm", "9", "0", 8, null);

        long pr = insertAcceptedPricingRequest(ticket, "SPLIT-1-PR");
        long p1 = insertPricingRequestItem(pr, a1);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, 100, 120);
        return new RichDeal(ticket, d1, delta, echo, alpha, beta);
    }

    /** The request the confirm dialog's retries would end up sending for {@link #richDeal()}. */
    protected static CreateImportRequestsRequest fullRequest(RichDeal deal) {
        return new CreateImportRequestsRequest(null,
            List.of(new NewFactoryCountryInput("Pin New Co", "TH", null)),
            List.of(new ItemFactoryInput(deal.d1ItemId(), deal.deltaFactoryId())),
            List.of(new NewFactoryLeadTimeInput("Pin Delta", 10, 20)));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    protected static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-split@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    protected long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'แยกโรงงาน') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    protected long insertFactory(String name, String country) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, :country, 'EUR') RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country), Long.class);
    }

    /** quotation_issued + DEPOSIT_PAID + ORDER_RECEIVED: the floor createDrafts needs. */
    protected long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status, sales_stage)
            VALUES (:code, 'ทดสอบแยกโรงงาน', :by, 'บริษัท แยกโรงงาน จำกัด', 'quotation_issued', 'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    protected long insertItem(long ticket, String factory, String model, String size, String qty, String fromStock,
                              int sort, Long catalogPriceId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, qty_from_stock, unit, sort_order, factory, catalog_price_id)
            VALUES (:t, 'Brand', :model, :size, :qty, :stock, 'pcs', :sort, :factory, :cp)
            RETURNING item_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("stock", new BigDecimal(fromStock))
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

    /** A PR line with NO factory of its own, so the ticket line's typed name still decides the factory. */
    private long insertPricingRequestItem(long pr, long ticketItem) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, source_ticket_item_id, requested_qty, requested_unit,
                 requested_unit_basis, quantity_type)
            VALUES (:pr, :ti, 10, 'PIECE', 'PER_PIECE', 'CONFIRMED')
            RETURNING pricing_request_item_id
            """, new MapSqlParameterSource().addValue("pr", pr).addValue("ti", ticketItem), Long.class);
    }

    /** A live (APPROVED) direct quotation with no items, addressed to the OWNER — so it adds no lead-time row and does
     * not collide with richDeal's PR-route quotation on (ticket, recipient, version). Its presence is what makes the
     * confirm step show the factory picker (fix round L1). */
    protected long insertLiveDirectQuotation(long ticket) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, recipient_type)
            VALUES (:t, :number, :by, 'DEAL_DIRECT', 'APPROVED', 'OWNER')
            RETURNING quotation_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("number", "QT-SPLIT-D" + (++seq))
                .addValue("by", ownerId), Long.class);
    }

    protected long insertQuotation(long ticket, String origin, String docStatus, Long pricingRequestId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, origin, doc_status, pricing_request_id)
            VALUES (:t, :number, :by, :origin, :status, :pr)
            RETURNING quotation_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("number", "QT-SPLIT-" + (++seq))
                .addValue("by", ownerId).addValue("origin", origin).addValue("status", docStatus)
                .addValue("pr", pricingRequestId), Long.class);
    }

    protected void insertQuotationItem(long quotation, Long pricingRequestItemId, Long catalogPriceId,
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
