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
import th.co.glr.hr.importrequest.ImportRequestRequests.SetLeadTimeRequest;
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
 * CR-1 (GLA-167), part D (owner rulings B-R4 / B-R5) — an ใบขอซื้อ (IR) takes its lead time from the
 * deal's CURRENT QUOTATION ITEMS, never from the pricing-request lines.
 *
 * <p>Current quotation: PRICING_REQUEST route = the ACCEPTED quotation (items reach their factory
 * through {@code pricing_request_item_id}); direct route = the APPROVED DEAL_DIRECT quotation (items
 * reach their factory through {@code catalog_price_id}; hand-typed lines are ignored). The legacy
 * engine ({@code origin IS NULL}) carries no per-item lead time. Per factory:
 * {@code min = max(lead_time_min_days)}, {@code max = max(lead_time_max_days)} over that factory's
 * quotation items that carry a lead time, else {@link LeadTimeDefaults#forCountry}.
 *
 * <p>Every fixture deliberately gives the pricing-request lines a DIFFERENT lead time from the
 * quotation items, so a read from the wrong table cannot pass. Seeded by SQL so the assertion is
 * about {@code createDrafts}/{@code revise}, not about how the deal got to ORDER_RECEIVED.
 */
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
            new EmployeeAuthRepository(jdbc));
        service = new ImportRequestService(new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            new ImportRequestRepository(jdbc), new FactoryConfigRepository(jdbc), tickets, ticketService);
        ownerId = jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) VALUES ('CR1-OWN', 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of(), Long.class);
        owner = new UserPrincipal(ownerId, "owner-cr1@glr.co.th", "sales", "sales", ownerId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
        importUser = new UserPrincipal(ownerId, "import-cr1@glr.co.th", "import", "import", ownerId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    // ── PRICING_REQUEST route: the ACCEPTED quotation ────────────────────────────────────────

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

    /** D1 — min and max are each the maximum, taken independently. */
    @Test
    void prRoute_theMinAndTheMaxAreEachTheMaximumAcrossItems() {
        insertFactory("D1b Factory", "CN");
        long ticket = insertTicket("CR1-D1B");
        long t1 = insertTicketItem(ticket, "D1b Factory", "Line 1", 0, null);
        long t2 = insertTicketItem(ticket, "D1b Factory", "Line 2", 1, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1B-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D1b Factory", 1, 2);
        long p2 = insertPricingRequestItem(pr, t2, "D1b Factory", 1, 2);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, 10, 200);
        insertQuotationItem(q, p2, null, 90, 100);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(90);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(200);
    }

    /** D1 — only the OWN factory's items count; a two-factory deal yields one IR per factory. */
    @Test
    void prRoute_eachFactorysIrUsesOnlyItsOwnQuotationItems() {
        insertFactory("D1c Factory A", "CN");
        insertFactory("D1c Factory B", "IT");
        long ticket = insertTicket("CR1-D1C");
        long ta = insertTicketItem(ticket, "D1c Factory A", "Line A", 0, null);
        long tb = insertTicketItem(ticket, "D1c Factory B", "Line B", 1, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1C-PR");
        long pa = insertPricingRequestItem(pr, ta, "D1c Factory A", 1, 2);
        long pb = insertPricingRequestItem(pr, tb, "D1c Factory B", 1, 2);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, pa, null, 40, 60);
        insertQuotationItem(q, pb, null, 110, 130);

        List<ImportRequestDto> irs = service.createDrafts(ticket, null, owner);

        assertThat(irs).hasSize(2);
        ImportRequestDto irA = irs.stream().filter(i -> "D1c Factory A".equals(i.factoryName())).findFirst().orElseThrow();
        ImportRequestDto irB = irs.stream().filter(i -> "D1c Factory B".equals(i.factoryName())).findFirst().orElseThrow();
        assertThat(irA.leadTimeMinDays()).isEqualTo(40);
        assertThat(irA.leadTimeMaxDays()).isEqualTo(60);
        assertThat(irB.leadTimeMinDays()).isEqualTo(110);
        assertThat(irB.leadTimeMaxDays()).isEqualTo(130);
    }

    /** D1 fallback — no quotation item has a lead time: country default, NOT the PR line's 5-6. */
    @Test
    void prRoute_fallsBackToTheCountryDefault_whenNoQuotationItemCarriesALeadTime() {
        insertFactory("D1d Factory", "IT"); // default 75-90
        long ticket = insertTicket("CR1-D1D");
        long t1 = insertTicketItem(ticket, "D1d Factory", "Line 1", 0, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1D-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D1d Factory", 5, 6);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, null, null);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(75);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(90);
    }

    /** A quotation item WITH a lead time beats the default even when a sibling has none. */
    @Test
    void prRoute_anItemWithALeadTimeBeatsTheCountryDefault_evenIfASiblingHasNone() {
        insertFactory("D1e Factory", "CN"); // default 30-45
        long ticket = insertTicket("CR1-D1E");
        long t1 = insertTicketItem(ticket, "D1e Factory", "Line 1", 0, null);
        long t2 = insertTicketItem(ticket, "D1e Factory", "Line 2", 1, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1E-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D1e Factory", 1, 2);
        long p2 = insertPricingRequestItem(pr, t2, "D1e Factory", 1, 2);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, 100, 120);
        insertQuotationItem(q, p2, null, null, null);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(100);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(120);
    }

    /** Only the ACCEPTED quotation is current: an ISSUED (not yet accepted) one is ignored. */
    @Test
    void prRoute_aQuotationThatIsNotAcceptedIsIgnored() {
        insertFactory("D1f Factory", "CN"); // default 30-45
        long ticket = insertTicket("CR1-D1F");
        long t1 = insertTicketItem(ticket, "D1f Factory", "Line 1", 0, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D1F-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D1f Factory", 1, 2);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ISSUED", pr);
        insertQuotationItem(q, p1, null, 200, 300);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(30);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(45);
    }

    // ── direct route: the APPROVED DEAL_DIRECT quotation ─────────────────────────────────────

    /** D2 — items reach their factory through catalog_price_id; the hand-typed line (no link) is ignored. */
    @Test
    void directRoute_usesTheApprovedQuotationItemsByCatalogFactory_andIgnoresHandTypedLines() {
        long catalogPrice = insertCatalogProduct("D2 Factory", "CN", "D2-CODE", new java.math.BigDecimal("10"), "THB", "per_piece");
        long ticket = insertTicket("CR1-D2");
        insertTicketItem(ticket, "D2 Factory", "Line 1", 0, catalogPrice);
        long q = insertQuotation(ticket, "DEAL_DIRECT", "APPROVED", null);
        insertQuotationItem(q, null, catalogPrice, 100, 120);
        insertQuotationItem(q, null, null, 400, 500); // hand-typed: no catalog link, no factory -> ignored

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(100);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(120);
    }

    /** Direct route, only hand-typed lines carry a lead time -> nothing attributable -> country default. */
    @Test
    void directRoute_onlyHandTypedLinesCarryALeadTime_fallsBackToTheCountryDefault() {
        long catalogPrice = insertCatalogProduct("D2b Factory", "IT", "D2B-CODE", new java.math.BigDecimal("10"), "THB", "per_piece"); // default 75-90
        long ticket = insertTicket("CR1-D2B");
        insertTicketItem(ticket, "D2b Factory", "Line 1", 0, catalogPrice);
        long q = insertQuotation(ticket, "DEAL_DIRECT", "APPROVED", null);
        insertQuotationItem(q, null, null, 400, 500);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(75);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(90);
    }

    /** A DEAL_DIRECT quotation that is not APPROVED (e.g. SUPERSEDED) is not current. */
    @Test
    void directRoute_aSupersededQuotationIsIgnored() {
        long catalogPrice = insertCatalogProduct("D2c Factory", "CN", "D2C-CODE", new java.math.BigDecimal("10"), "THB", "per_piece"); // 30-45
        long ticket = insertTicket("CR1-D2C");
        insertTicketItem(ticket, "D2c Factory", "Line 1", 0, catalogPrice);
        long q = insertQuotation(ticket, "DEAL_DIRECT", "SUPERSEDED", null);
        insertQuotationItem(q, null, catalogPrice, 200, 300);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(30);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(45);
    }

    /** Only an APPROVED DEAL_DIRECT quotation is the direct route's current one: an APPROVED legacy row is not. */
    @Test
    void directRoute_anApprovedQuotationOfAnotherOriginIsIgnored() {
        long catalogPrice = insertCatalogProduct("D2d Factory", "CN", "D2D-CODE", new java.math.BigDecimal("10"), "THB", "per_piece"); // 30-45
        long ticket = insertTicket("CR1-D2D");
        insertTicketItem(ticket, "D2d Factory", "Line 1", 0, catalogPrice);
        long q = insertQuotation(ticket, null, "APPROVED", null);
        insertQuotationItem(q, null, catalogPrice, 200, 300);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(30);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(45);
    }

    // ── legacy engine ────────────────────────────────────────────────────────────────────────

    /** The legacy engine (origin IS NULL) has no per-item lead time: even a stray value is ignored. */
    @Test
    void legacyQuotation_isIgnored_countryDefaultApplies() {
        insertFactory("D3 Factory", "CN"); // 30-45
        long ticket = insertTicket("CR1-D3");
        long t1 = insertTicketItem(ticket, "D3 Factory", "Line 1", 0, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-D3-PR");
        long p1 = insertPricingRequestItem(pr, t1, "D3 Factory", 1, 2);
        long q = insertQuotation(ticket, null, "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, 300, 400);

        ImportRequestDto ir = service.createDrafts(ticket, null, owner).get(0);

        assertThat(ir.leadTimeMinDays()).isEqualTo(30);
        assertThat(ir.leadTimeMaxDays()).isEqualTo(45);
    }

    // ── revise ───────────────────────────────────────────────────────────────────────────────

    /**
     * An approved lead-time change WITHOUT a new quotation must not reach the IR: the PR line is
     * edited (what LeadTimeChangeService#approve does), the quotation is untouched, and revise
     * still shows the quotation's value.
     */
    @Test
    void revise_anApprovedChangeWithNoNewQuotation_doesNotChangeTheIr() {
        long[] d = issuedPrRouteIr("D4", 40, 60);
        jdbc.update("""
            UPDATE sales.pricing_request_item SET lead_time_min_days = 100, lead_time_max_days = 120
             WHERE pricing_request_id = :pr
            """, Map.of("pr", d[1]));

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.leadTimeMinDays()).isEqualTo(40);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(60);
    }

    /** A NEW quotation carrying the changed lead time reaches the IR on revise. */
    @Test
    void revise_aNewQuotationWithADifferentLeadTime_isPickedUp() {
        long[] d = issuedPrRouteIr("D4b", 40, 60);
        jdbc.update("UPDATE sales.quotation_item SET lead_time_min_days = 100, lead_time_max_days = 120 WHERE quotation_id = :q",
            Map.of("q", d[2]));

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.leadTimeMinDays()).isEqualTo(100);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(120);
    }

    /**
     * Review finding 9: import's manual post-issue lead time (POST /import-requests/{id}/lead-time)
     * survives a revise as long as the QUOTATION-derived value the IR was built from is unchanged.
     */
    @Test
    void revise_keepsImportsManualLeadTime_whenTheQuotationIsUnchanged() {
        long[] d = issuedPrRouteIr("D5", 40, 60);
        service.setLeadTime(d[0], new SetLeadTimeRequest(55, 66), importUser);

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.leadTimeMinDays()).isEqualTo(55);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(66);
    }

    /** ...but a CHANGED quotation value wins over the manual one. */
    @Test
    void revise_takesTheNewQuotationValue_overAManualOne_whenTheQuotationChanged() {
        long[] d = issuedPrRouteIr("D5b", 40, 60);
        service.setLeadTime(d[0], new SetLeadTimeRequest(55, 66), importUser);
        jdbc.update("UPDATE sales.quotation_item SET lead_time_min_days = 100, lead_time_max_days = 120 WHERE quotation_id = :q",
            Map.of("q", d[2]));

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.leadTimeMinDays()).isEqualTo(100);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(120);
    }

    // ── revise with no stored derived value (country-default IRs, pre-V195 rows) ─────────────

    /**
     * (a) An IR first built from the COUNTRY DEFAULT (the quotation had no lead time) must pick up a
     * quotation lead time that appears later, and keep it on the following revise. The "new quotation"
     * is SQL-seeded rather than approved through DealQuotationService: that service needs a dozen
     * collaborators (signatures, approver snapshots, numbering) to approve one, while revise reads
     * nothing but the sales.quotation / quotation_item rows, which is exactly what is seeded.
     */
    @Test
    void revise_anIrBuiltFromTheCountryDefault_picksUpALaterQuotationLeadTime_andKeepsItOnTheNextRevise() {
        long[] d = issuedPrRouteIr("D6", null, null); // CN default 30-45
        jdbc.update("UPDATE sales.quotation_item SET lead_time_min_days = 100, lead_time_max_days = 120 WHERE quotation_id = :q",
            Map.of("q", d[2]));

        ImportRequestDto v2 = service.revise(d[0], owner);
        assertThat(v2.leadTimeMinDays()).isEqualTo(100);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(120);

        service.issue(v2.id(), null, owner);
        ImportRequestDto v3 = service.revise(v2.id(), owner);
        assertThat(v3.leadTimeMinDays()).isEqualTo(100);
        assertThat(v3.leadTimeMaxDays()).isEqualTo(120);
    }

    /** (b) A row with NO stored derived value and a HAND-edited lead time (differs from the default) keeps it. */
    @Test
    void revise_withNoStoredDerivedValue_keepsALeadTimeThatDiffersFromTheCountryDefault() {
        long[] d = issuedPrRouteIr("D6b", 40, 60);
        service.setLeadTime(d[0], new SetLeadTimeRequest(55, 66), importUser);
        clearDerived(d[0]); // pre-V195 shape
        jdbc.update("UPDATE sales.quotation_item SET lead_time_min_days = 100, lead_time_max_days = 120 WHERE quotation_id = :q",
            Map.of("q", d[2]));

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.leadTimeMinDays()).isEqualTo(55);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(66);
    }

    /** (c) A pre-V195-shaped row whose lead time still EQUALS the country default takes the fresh quotation value. */
    @Test
    void revise_withNoStoredDerivedValue_andALeadTimeEqualToTheCountryDefault_takesTheFreshQuotationValue() {
        long[] d = issuedPrRouteIr("D6c", null, null); // CN default 30-45, never edited
        clearDerived(d[0]);
        jdbc.update("UPDATE sales.quotation_item SET lead_time_min_days = 100, lead_time_max_days = 120 WHERE quotation_id = :q",
            Map.of("q", d[2]));

        ImportRequestDto v2 = service.revise(d[0], owner);

        assertThat(v2.leadTimeMinDays()).isEqualTo(100);
        assertThat(v2.leadTimeMaxDays()).isEqualTo(120);
    }

    private void clearDerived(long irId) {
        jdbc.update("UPDATE sales.import_request SET derived_lead_time_min_days = NULL, derived_lead_time_max_days = NULL WHERE import_request_id = :id",
            Map.of("id", irId));
    }

    /** Builds a one-factory PR-route deal whose quotation carries {@code min-max}, creates and ISSUES its IR. Returns {irId, prId, quotationId}. */
    private long[] issuedPrRouteIr(String tag, Integer min, Integer max) {
        insertFactory(tag + " Factory", "CN");
        long ticket = insertTicket("CR1-" + tag);
        long t1 = insertTicketItem(ticket, tag + " Factory", "Line 1", 0, null);
        long pr = insertAcceptedPricingRequest(ticket, "CR1-" + tag + "-PR");
        long p1 = insertPricingRequestItem(pr, t1, tag + " Factory", 1, 2);
        long q = insertQuotation(ticket, "PRICING_REQUEST", "ACCEPTED", pr);
        insertQuotationItem(q, p1, null, min, max);
        ImportRequestDto v1 = service.createDrafts(ticket, null, owner).get(0);
        if (min != null) {
            assertThat(v1.leadTimeMinDays()).isEqualTo(min);
        }
        service.issue(v1.id(), null, owner);
        return new long[] {v1.id(), pr, q};
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
