package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
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

/**
 * CR-1 (GLA-167), part D — an ใบขอซื้อ (IR) takes the lead time APPROVED on the pricing request.
 *
 * <p>Rule under test: one IR per factory; its lead time is the LONGEST among that factory's lines on
 * the deal's accepted (QUOTATION_ACCEPTED) pricing request — {@code min = max of the lines' mins},
 * {@code max = max of the lines' maxes} — falling back to {@link LeadTimeDefaults#forCountry} only
 * when NO line carries a lead time. Seeded by SQL (like {@code ImportRequestLeadTimeIntegrationTest})
 * so the assertion is about {@code createDrafts}, not about how the deal got to ORDER_RECEIVED.
 */
class ImportRequestLeadTimeFromPricingRequestIntegrationTest extends AbstractPostgresIntegrationTest {
    private ImportRequestService service;
    private long ownerId;
    private UserPrincipal owner;

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
            new EmployeeAuthRepository(jdbc));
        service = new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            new ImportRequestRepository(jdbc), new FactoryConfigRepository(jdbc), tickets, ticketService);
        ownerId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) VALUES ('CR1-OWN', 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of(), Long.class);
        owner = new UserPrincipal(ownerId, "owner-cr1@glr.co.th", "sales", "sales", ownerId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    /** D1 — longest lead time among the factory's lines; min and max are each the maximum. */
    @Test
    void createDraftsTakesTheLongestApprovedLeadTimeAmongTheFactorysLines() {
        insertFactory("D1 Factory", "CN"); // country default would be 30-45
        long ticket = insertTicket("CR1-D1");
        long t1 = insertTicketItem(ticket, "D1 Factory", "Line 1", 0);
        long t2 = insertTicketItem(ticket, "D1 Factory", "Line 2", 1);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1-PR");
        insertPricingRequestItem(pr, t1, "D1 Factory", 100, 120);
        insertPricingRequestItem(pr, t2, "D1 Factory", 120, 150);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(120);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(150);
    }

    /** D1 — min and max are taken independently: a short-min/long-max line and a long-min/short-max line. */
    @Test
    void theMinAndTheMaxAreEachTheMaximumAcrossLines_notTheRangeOfOneLine() {
        insertFactory("D1b Factory", "CN");
        long ticket = insertTicket("CR1-D1B");
        long t1 = insertTicketItem(ticket, "D1b Factory", "Line 1", 0);
        long t2 = insertTicketItem(ticket, "D1b Factory", "Line 2", 1);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1B-PR");
        insertPricingRequestItem(pr, t1, "D1b Factory", 10, 200);
        insertPricingRequestItem(pr, t2, "D1b Factory", 90, 100);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(90);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(200);
    }

    /** D1 — only the OWN factory's lines count, and a two-factory deal yields one IR per factory (D3). */
    @Test
    void eachFactorysIrUsesOnlyItsOwnLines_oneIrPerFactory() {
        insertFactory("D1c Factory A", "CN");
        insertFactory("D1c Factory B", "IT");
        long ticket = insertTicket("CR1-D1C");
        long ta = insertTicketItem(ticket, "D1c Factory A", "Line A", 0);
        long tb = insertTicketItem(ticket, "D1c Factory B", "Line B", 1);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1C-PR");
        insertPricingRequestItem(pr, ta, "D1c Factory A", 40, 60);
        insertPricingRequestItem(pr, tb, "D1c Factory B", 110, 130);

        List<ImportRequestDto> irs = service.createDrafts(ticket, null, owner);

        assertThat(irs).hasSize(2);
        ImportRequestDto irA = irs.stream().filter(i -> "D1c Factory A".equals(i.factoryName())).findFirst().orElseThrow();
        ImportRequestDto irB = irs.stream().filter(i -> "D1c Factory B".equals(i.factoryName())).findFirst().orElseThrow();
        assertThat(irA.leadTimeMinDays()).isEqualTo(40);
        assertThat(irA.leadTimeMaxDays()).isEqualTo(60);
        assertThat(irB.leadTimeMinDays()).isEqualTo(110);
        assertThat(irB.leadTimeMaxDays()).isEqualTo(130);
    }

    /** D1 fallback — no line has a lead time, so the country default still applies. (Passes before CR-1.) */
    @Test
    void fallsBackToTheCountryDefault_whenNoLineCarriesALeadTime() {
        insertFactory("D1d Factory", "IT"); // default 75-90
        long ticket = insertTicket("CR1-D1D");
        long t1 = insertTicketItem(ticket, "D1d Factory", "Line 1", 0);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1D-PR");
        insertPricingRequestItem(pr, t1, "D1d Factory", null, null);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(75);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(90);
    }

    /**
     * D1 mixed — a line WITH a lead time wins over the default even when a sibling has none: the
     * default is only for "no line has one".
     */
    @Test
    void aLineWithALeadTimeBeatsTheCountryDefault_evenIfASiblingHasNone() {
        insertFactory("D1e Factory", "CN"); // default 30-45
        long ticket = insertTicket("CR1-D1E");
        long t1 = insertTicketItem(ticket, "D1e Factory", "Line 1", 0);
        long t2 = insertTicketItem(ticket, "D1e Factory", "Line 2", 1);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1E-PR");
        insertPricingRequestItem(pr, t1, "D1e Factory", 100, 120);
        insertPricingRequestItem(pr, t2, "D1e Factory", null, null);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(100);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(120);
    }

    /**
     * D4 (R6, added by the implementer) — sales regenerates the IR after an approved lead-time change:
     * {@code revise} re-derives the lead time from the lines' CURRENT approved values instead of
     * carrying the predecessor's forward. The change is simulated by writing the approved range onto
     * the pricing-request items, which is exactly what LeadTimeChangeService#approve does.
     */
    @Test
    void reviseReDerivesTheLeadTimeFromTheLinesCurrentApprovedValues() {
        insertFactory("D4 Factory", "CN");
        long ticket = insertTicket("CR1-D4");
        long t1 = insertTicketItem(ticket, "D4 Factory", "Line 1", 0);
        long t2 = insertTicketItem(ticket, "D4 Factory", "Line 2", 1);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D4-PR");
        insertPricingRequestItem(pr, t1, "D4 Factory", 40, 60);
        insertPricingRequestItem(pr, t2, "D4 Factory", 30, 50);
        ImportRequestDto v1 = service.createDrafts(ticket, null, owner).get(0);
        assertThat(v1.leadTimeMinDays()).isEqualTo(40);
        assertThat(v1.leadTimeMaxDays()).isEqualTo(60);
        service.issue(v1.id(), null, owner);

        // an approved lead-time change lands on the lines
        jdbc.update("""
            UPDATE sales.pricing_request_item SET lead_time_min_days = 100, lead_time_max_days = 120
             WHERE pricing_request_id = :pr AND source_ticket_item_id = :ti
            """, Map.of("pr", pr, "ti", t1));

        ImportRequestDto v2 = service.revise(v1.id(), owner);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.leadTimeMinDays()).isEqualTo(100); // longest across the factory's lines
        assertThat(v2.leadTimeMaxDays()).isEqualTo(120);
    }

    // ── helpers (same shape as ImportRequestLeadTimeIntegrationTest) ──────────────────────────

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

    private long insertTicketItem(long ticket, String factory, String model, int sort) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, size, qty, unit, sort_order, factory)
            VALUES (:t, 'Brand', :model, '60x60', 10, 'pcs', :sort, :factory)
            RETURNING item_id
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("model", model)
                .addValue("sort", sort).addValue("factory", factory), Long.class);
    }

    private long insertAcceptedPricingRequest(long ticket, String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, status, requested_by)
            VALUES (:code, :ticket, 'DESIGNER', 'QUOTATION_ACCEPTED', :by)
            RETURNING pricing_request_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("ticket", ticket)
                .addValue("by", ownerId), Long.class);
    }

    private void insertPricingRequestItem(long pr, long ticketItem, String factory, Integer min, Integer max) {
        jdbc.update("""
            INSERT INTO sales.pricing_request_item
                (pricing_request_id, source_ticket_item_id, factory, requested_qty, requested_unit,
                 requested_unit_basis, quantity_type, lead_time_min_days, lead_time_max_days)
            VALUES (:pr, :ti, :factory, 10, 'PIECE', 'PER_PIECE', 'CONFIRMED', :min, :max)
            """, new MapSqlParameterSource().addValue("pr", pr).addValue("ti", ticketItem)
                .addValue("factory", factory).addValue("min", min, java.sql.Types.SMALLINT)
                .addValue("max", max, java.sql.Types.SMALLINT));
    }
}
