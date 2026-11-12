package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.support.RecordingSalesMailer;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.ProcurementSourceDto;
import th.co.glr.hr.ticket.ProcurementSourceRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.StockReservationRequest;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Stage-10 "จัดซื้อสินค้า" decision: legacy deals (order confirmed before 2026-11-07) get their
 * import/stock split from the rep once; newer deals only record what the quotation already says.
 * Real services over real Postgres, no mocks. Same package as {@code DealQuotationConfirmOrderIntegrationTest}
 * because the quotation wiring ({@code wirePricingRequestDependencies}) is package-private.
 */
class ProcurementSourceDecisionIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String V208 = "db/migration/V208__ticket_procurement_source_decided.sql";
    private static final String OLD = "2026-11-06 10:00:00+07";
    private static final String NEW = "2026-11-08 10:00:00+07";
    private static final String DECIDED_EVENT_DESC = "เลือกที่มาสินค้า: นำเข้า 1 รายการ · จากสต็อก 1 รายการ";

    private TicketRepository tickets;
    private RecordingSalesMailer mailer;
    private TicketService ticketService;
    private DealQuotationService quotationService;
    private DealQuotationService confirmer;
    private CustomerRepository customers;
    private EmployeeRepository employees;

    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal importActor;
    private CustomerDto customer;
    private long projectId;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        mailer = new RecordingSalesMailer(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, mailer);
        customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        PricingRequestService pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications,
            objectMapper, new ContactRepository(jdbc), new FileStorageService("/tmp/glr-stage10-procure-test-uploads"),
            factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers, new QuotationRenderer(),
            pricingRequestService, new EmployeeAuthRepository(jdbc), new ContactRepository(jdbc),
            new ProjectRepository(jdbc), new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)));
        Mailer noMail = new Mailer() {
            @Override public void send(String to, String subject, String body) {}
            @Override public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                           List<Mailer.InlineImage> inlineImages) {}
            @Override public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}
            @Override public void sendWithAttachments(String to, String subject, String body, List<Mailer.Attachment> attachments) {}
        };
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)),
            tickets, customers, new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), new EmployeeSignatureRepository(jdbc),
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, new PricingDecisionRepository(jdbc),
            new CustomerQuotationRepository(jdbc));
        quotationService.wireTicketService(ticketService);
        confirmer = transactional(quotationService);

        salesActor = actor(createEmployee("พนักงานขาย 207", "sales-207@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        otherSalesActor = actor(createEmployee("พนักงานขาย 207 อื่น", "sales-207-o@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        salesManagerActor = actor(createEmployee("ผจก.ขาย 207", "sm-207@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย"), "sales_manager");
        ceoActor = actor(createEmployee("ผู้บริหาร 207", "ceo-207@glr.co.th", "MD", "ผู้บริหาร", "กรรมการผู้จัดการ"), "ceo");
        importActor = actor(createEmployee("นำเข้า 207", "import-207@glr.co.th", "PCIM", "ฝ่ายนำเข้า", null), "import");
        customer = customers.create("บริษัท จัดซื้อ จำกัด", "0100000000207", "207 ถนนทดสอบ", "สนญ.", "02-000-0207");
        projectId = projects.create(customer.id(), "โครงการจัดซื้อ 207").id();
    }

    // ── legacy flag ──────────────────────────────────────────────────────────────────────

    @Test
    void legacyFlagFollowsQuotationIssueDate() {
        long oldDeal = confirmedDeal(OLD, false, false);
        long newDeal = confirmedDeal(NEW, false, false);
        long pcrDeal = pipelineTicket();
        assertThat(ticketService.getProcurementSource(oldDeal, salesActor).legacy()).isTrue();
        assertThat(ticketService.getProcurementSource(newDeal, salesActor).legacy()).isFalse();
        assertThat(ticketService.getProcurementSource(pcrDeal, salesActor).legacy()).isFalse();
        assertThat(ticketService.procurementSourceAwaitingRep(summaryOf(oldDeal))).isTrue();
        assertThat(ticketService.procurementSourceAwaitingRep(summaryOf(newDeal))).isFalse();
    }

    /** Legacy follows the LATEST DEAL_PROMOTED_FROM_QUOTATION event's quotation (same order as findOrderConfirmedQuotationId). */
    @Test
    void legacyFollowsTheLatestPromotionEvent() {
        long deal = confirmedDeal(OLD, false, false);
        long oldQt = promotedQuotationId(deal);
        backdatePromotions(deal);
        long newQt = secondDirectQuotation(deal, NEW);
        addPromotion(deal, oldQt, "2026-11-06 11:00:00+07");
        addPromotion(deal, newQt, "2026-11-08 11:00:00+07");
        assertThat(ticketService.getProcurementSource(deal, salesActor).legacy()).isFalse();
        assertConflict(() -> ticketService.decideProcurementSource(deal, split(itemIds(deal), true, true), salesActor),
            "ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา แก้ไขไม่ได้");

        long reversed = confirmedDeal(OLD, false, false);
        long oldQt2 = promotedQuotationId(reversed);
        backdatePromotions(reversed);
        long newQt2 = secondDirectQuotation(reversed, NEW);
        addPromotion(reversed, newQt2, "2026-11-06 11:00:00+07");
        addPromotion(reversed, oldQt2, "2026-11-08 11:00:00+07");
        assertThat(ticketService.getProcurementSource(reversed, salesActor).legacy()).isTrue();
    }

    @Test
    void aTicketItemSourcedFromStockMeansNotLegacy() {
        long deal = confirmedDeal(OLD, false, false);
        assertThat(ticketService.getProcurementSource(deal, salesActor).legacy()).isTrue();
        jdbc.update("UPDATE sales.ticket_item SET sourced_from_stock = true WHERE item_id = :i", Map.of("i", itemIds(deal).get(0)));
        assertThat(ticketService.getProcurementSource(deal, salesActor).legacy()).isFalse();
    }

    @Test
    void ceoSeesAnUndecidedLegacyDealAsEditable() {
        long deal = confirmedDeal(OLD, false, false);
        assertThat(ticketService.getProcurementSource(deal, ceoActor).editable()).isTrue();
    }

    /** pending = decision window open and undecided. */
    @Test
    void pendingIsTrueForAnUndecidedDealOfEitherKindAndFalseOnceDecidedOrFromStock() {
        long oldDeal = confirmedDeal(OLD, false, false);
        long newDeal = confirmedDeal(NEW, false, true);
        assertThat(ticketService.getProcurementSource(oldDeal, salesActor).pending()).isEqualTo(true);
        assertThat(ticketService.getProcurementSource(newDeal, salesActor).pending()).isEqualTo(true);
        assertThat(ticketService.getProcurementSource(pipelineTicket(), salesActor).pending()).isEqualTo(false);

        ticketService.decideProcurementSource(oldDeal, split(itemIds(oldDeal), false, true), salesActor);
        ticketService.decideProcurementSource(newDeal, split(itemIds(newDeal), false, true), salesActor);
        assertThat(ticketService.getProcurementSource(oldDeal, salesActor).pending()).isEqualTo(false);
        assertThat(ticketService.getProcurementSource(newDeal, salesActor).pending()).isEqualTo(false);

        long fromStock = confirmedDeal(OLD, false, false);
        ticketService.reserveStock(fromStock, new StockReservationRequest(List.of(
            new StockReservationRequest.Line(itemIds(fromStock).get(0), new BigDecimal("10"), null),
            new StockReservationRequest.Line(itemIds(fromStock).get(1), new BigDecimal("10"), null))), ceoActor);
        assertThat(ticketService.getProcurementSource(fromStock, salesActor).pending()).isEqualTo(false);
    }

    /** A legacy decide keeps the CEO's partial coverage on a line the rep leaves as import. */
    @Test
    void legacyDecideKeepsAnExistingPartialForAnImportLine() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        ticketService.reserveStock(deal, new StockReservationRequest(
            List.of(new StockReservationRequest.Line(ids.get(0), new BigDecimal("3"), null))), ceoActor);

        ticketService.decideProcurementSource(deal, split(ids, false, true), salesActor);

        List<Map<String, Object>> rows = stockRows(deal);
        assertThat((BigDecimal) rows.get(0).get("qty_from_stock")).isEqualByComparingTo("3");
        assertThat((BigDecimal) rows.get(1).get("qty_from_stock")).isEqualByComparingTo((BigDecimal) rows.get(1).get("qty"));
    }

    /** A line the CEO FULLY covered is cleared when the rep marks it import; only a partial survives. */
    @Test
    void legacyDecideClearsAFullyCoveredLineTheRepMarksImport() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        ticketService.reserveStock(deal, new StockReservationRequest(
            List.of(new StockReservationRequest.Line(ids.get(0), new BigDecimal("10"), null))), ceoActor);

        ticketService.decideProcurementSource(deal, split(ids, false, false), salesActor);

        List<Map<String, Object>> rows = stockRows(deal);
        assertThat((BigDecimal) rows.get(0).get("qty_from_stock")).isEqualByComparingTo("0");
        assertThat((BigDecimal) rows.get(1).get("qty_from_stock")).isEqualByComparingTo("0");
    }

    /** Bangkok-date boundary: 2026-11-07 01:00+07 is 2026-11-06 18:00 UTC, so a UTC-date cast would call it legacy. */
    @Test
    void legacyFlagUsesTheBangkokDate() {
        long justAfterCutoff = confirmedDeal("2026-11-07 01:00:00+07", false, false);
        long justBeforeCutoff = confirmedDeal("2026-11-06 23:30:00+07", false, false);
        assertThat(ticketService.getProcurementSource(justAfterCutoff, salesActor).legacy()).isFalse();
        assertThat(ticketService.getProcurementSource(justBeforeCutoff, salesActor).legacy()).isTrue();
    }

    @Test
    void notEditableOnceTheCeoCoveredEveryLine() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        ticketService.reserveStock(deal, new StockReservationRequest(List.of(
            new StockReservationRequest.Line(ids.get(0), new BigDecimal("10"), null),
            new StockReservationRequest.Line(ids.get(1), new BigDecimal("10"), null))), ceoActor);
        assertThat(tickets.findById(deal).orElseThrow().summary().fulfillmentStatus()).isEqualTo("FROM_STOCK");

        assertThat(ticketService.getProcurementSource(deal, salesActor).editable()).isFalse();
        assertThat(ticketService.procurementSourceAwaitingRep(summaryOf(deal))).isFalse();
    }

    @Test
    void getStartsFromCurrentCoverage() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        ticketService.reserveStock(deal, new StockReservationRequest(
            List.of(new StockReservationRequest.Line(ids.get(1), new BigDecimal("10"), null))), ceoActor);

        ProcurementSourceDto dto = ticketService.getProcurementSource(deal, salesActor);

        assertThat(dto.lines()).containsExactly(new ProcurementSourceDto.Line(ids.get(0), false),
            new ProcurementSourceDto.Line(ids.get(1), true));
        assertThat(dto.editable()).isTrue();
        assertThat(dto.decidedAt()).isNull();
        assertThat(dto.quotationNumber()).isNotBlank();
    }

    // ── legacy decisions ─────────────────────────────────────────────────────────────────

    @Test
    void oldDealSplitWritesCoverage() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);

        ticketService.decideProcurementSource(deal, split(ids, false, true), salesActor);

        List<Map<String, Object>> rows = stockRows(deal);
        assertThat((BigDecimal) rows.get(0).get("qty_from_stock")).isEqualByComparingTo("0");
        assertThat((BigDecimal) rows.get(1).get("qty_from_stock")).isEqualByComparingTo((BigDecimal) rows.get(1).get("qty"));
        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNotNull();
        assertThat(eventMessages(deal, TicketEventKind.PROCUREMENT_SOURCE_DECIDED)).containsExactly(DECIDED_EVENT_DESC);
        assertThat(managerNotices(smId)).hasSize(1);
    }

    @Test
    void oldDealAllStockRoutesToDelivery() {
        long deal = confirmedDeal(OLD, false, false);
        ticketService.decideProcurementSource(deal, split(itemIds(deal), true, true), salesActor);
        var s = tickets.findById(deal).orElseThrow().summary();
        assertThat(s.fulfillmentStatus()).isEqualTo("FROM_STOCK");
        assertThat(s.salesStage()).isEqualTo(DealStage.DELIVERY_SCHEDULING);
    }

    @Test
    void secondDecisionIsRefused() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        ticketService.decideProcurementSource(deal, split(ids, false, true), salesActor);

        assertConflict(() -> ticketService.decideProcurementSource(deal, split(ids, true, true), salesActor),
            "เลือกที่มาสินค้าของดีลนี้แล้ว แก้ไขไม่ได้");

        assertThat(ticketService.getProcurementSource(deal, salesActor).lines()).extracting(
            ProcurementSourceDto.Line::fromStock).containsExactly(false, true);
        assertThat(ticketService.getProcurementSource(deal, salesActor).editable()).isFalse();
    }

    // ── new deals ────────────────────────────────────────────────────────────────────────

    @Test
    void newDealConfirmUnchangedRecordsOnly() {
        long deal = confirmedDeal(NEW, false, true);
        List<Long> ids = itemIds(deal);
        List<Map<String, Object>> before = stockRows(deal);

        ticketService.decideProcurementSource(deal, split(ids, false, true), salesActor);

        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNotNull();
        assertThat(stockRows(deal)).isEqualTo(before);
    }

    @Test
    void newDealChangeRefused() {
        long deal = confirmedDeal(NEW, false, true);
        List<Map<String, Object>> before = stockRows(deal);

        assertConflict(() -> ticketService.decideProcurementSource(deal, split(itemIds(deal), true, true), salesActor),
            "ดีลนี้ใช้ที่มาสินค้าตามใบเสนอราคา แก้ไขไม่ได้");

        assertThat(stockRows(deal)).isEqualTo(before);
        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNull();
    }

    // ── validation ───────────────────────────────────────────────────────────────────────

    @Test
    void emptyLinesRejected() {
        long deal = confirmedDeal(OLD, false, false);
        assertBadRequest(() -> ticketService.decideProcurementSource(deal,
            new ProcurementSourceRequest(List.of()), salesActor));
        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNull();
    }

    @Test
    void missingItemRejected() {
        long deal = confirmedDeal(OLD, false, false);
        List<Long> ids = itemIds(deal);
        List<Map<String, Object>> before = stockRows(deal);
        assertBadRequest(() -> ticketService.decideProcurementSource(deal, new ProcurementSourceRequest(
            List.of(new ProcurementSourceRequest.Line(ids.get(0), true))), salesActor));
        assertThat(stockRows(deal)).isEqualTo(before);
        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNull();
    }

    // ── cannot reach ─────────────────────────────────────────────────────────────────────

    @Test
    void nonOwnerSalesForbidden() {
        long deal = confirmedDeal(OLD, false, false);
        assertForbiddenAndUntouched(deal, otherSalesActor);
    }

    @Test
    void salesManagerForbidden() {
        long deal = confirmedDeal(OLD, false, false);
        assertForbiddenAndUntouched(deal, salesManagerActor);
    }

    @Test
    void importForbidden() {
        long deal = confirmedDeal(OLD, false, false);
        assertForbiddenAndUntouched(deal, importActor);
    }

    @Test
    void beforeOrderReceivedConflict() {
        long deal = pipelineTicket();
        assertConflict(() -> ticketService.decideProcurementSource(deal, new ProcurementSourceRequest(
            List.of(new ProcurementSourceRequest.Line(1L, true))), salesActor),
            "เลือกที่มาสินค้าได้หลังจากยืนยันคำสั่งซื้อของลูกค้าแล้วเท่านั้น");
        assertThat(ticketService.procurementSourceAwaitingRep(summaryOf(deal))).isFalse();
        assertThat(ticketService.getProcurementSource(deal, salesActor).editable()).isFalse();
    }

    @Test
    void ceoMayDecide() {
        long smId = mailer.seedRoleEmployee("sales_manager");
        long deal = confirmedDeal(OLD, false, false);
        ticketService.decideProcurementSource(deal, split(itemIds(deal), false, true), ceoActor);
        assertThat(ticketService.getProcurementSource(deal, ceoActor).decidedAt()).isNotNull();
        assertThat(managerNotices(smId)).isEmpty();
    }

    // ── migration backfill ───────────────────────────────────────────────────────────────

    @Test
    void backfillMarksDealsWithImportRequests() throws Exception {
        long withIr = confirmedDeal(OLD, false, false);
        long without = confirmedDeal(OLD, false, false);
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory 207', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        jdbc.update("""
            INSERT INTO sales.import_request
                (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                 import_step, import_step_at, first_issued_date)
            VALUES (:t, :f, 'Factory 207', 'Brand', 1, 'ISSUED', 'IR-207-1', CURRENT_DATE,
                    'CONTACTED', CURRENT_DATE, CURRENT_DATE)
            """, Map.of("t", withIr, "f", factoryId));
        // The deals above were created after V208 ran; reset the column and replay the shipped file.
        jdbc.update("UPDATE sales.ticket SET procurement_source_decided_at = NULL", Map.of());

        runV208();

        assertThat(ticketService.getProcurementSource(withIr, salesActor).decidedAt()).isNotNull();
        assertThat(ticketService.getProcurementSource(without, salesActor).decidedAt()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private ProcurementSourceRequest split(List<Long> ids, boolean a, boolean b) {
        return new ProcurementSourceRequest(List.of(new ProcurementSourceRequest.Line(ids.get(0), a),
            new ProcurementSourceRequest.Line(ids.get(1), b)));
    }

    /** A deal with two lines A, B, order confirmed by the owner; its quotation's issued_at is moved to {@code issuedAt}. */
    private long confirmedDeal(String issuedAt, boolean aStock, boolean bStock) {
        long deal = pipelineTicket();
        UpsertDealQuotationRequest d = draft(tile("Item A", aStock), tile("Item B", bStock));
        DealQuotationDto q = quotationService.create(deal, d, salesActor);
        quotationService.submit(q.id(), salesActor);
        quotationService.approve(q.id(), new ApproveRequest(null), salesManagerActor);
        confirmer.confirmOrderFromDirectQuotation(q.id(), salesActor);
        jdbc.update("UPDATE sales.quotation SET issued_at = :at::timestamptz WHERE ticket_id = :t AND origin = 'DEAL_DIRECT'",
            Map.of("at", issuedAt, "t", deal));
        return deal;
    }

    private th.co.glr.hr.ticket.TicketSummaryDto summaryOf(long deal) {
        return tickets.findById(deal).orElseThrow().summary();
    }

    private long promotedQuotationId(long deal) {
        return tickets.findOrderConfirmedQuotationId(deal).orElseThrow();
    }

    /** A DEAL_DIRECT quotation issued at {@code issuedAt} (borrowed from a scratch deal: a deal may hold one live direct QT). */
    private long secondDirectQuotation(long deal, String issuedAt) {
        return promotedQuotationId(confirmedDeal(issuedAt, false, false));
    }

    /** The confirm-order event is stamped now(); push it before the events the test inserts. */
    private void backdatePromotions(long deal) {
        jdbc.update("UPDATE sales.ticket_event SET created_at = '2026-11-01 10:00:00+07'::timestamptz WHERE ticket_id = :t AND kind = 'DEAL_PROMOTED_FROM_QUOTATION'",
            Map.of("t", deal));
    }

    private void addPromotion(long deal, long quotationId, String at) {
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status,
                                            related_document_type, related_document_id, created_at)
            SELECT :t, e.actor_id, e.actor_name, e.kind, e.from_status, e.to_status, 'QUOTATION', :q, :at::timestamptz
              FROM sales.ticket_event e WHERE e.ticket_id = :t AND e.kind = 'DEAL_PROMOTED_FROM_QUOTATION' LIMIT 1
            """, Map.of("t", deal, "q", quotationId, "at", at));
    }

    private ItemInput tile(String model, boolean stock) {
        return new ItemInput(
            null, null, null, "SCG", model, "White", "Matte", "60x60", new BigDecimal("10.00"),
            new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES, null, 10,
            WastageCalculator.WASTAGE_MODE_NONE, null, 4, new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก",
            3, 7, null, WastageCalculator.LINE_TYPE_TILE, null, null, null, null, null, null, null, null,
            null, null, false).withStockSource(stock ? "IN_THAILAND" : null);
    }

    private UpsertDealQuotationRequest draft(ItemInput... lines) {
        return new UpsertDealQuotationRequest(null, null, null, null, 50, "CASH_ON_DELIVERY",
            0, 30, null, "NET", "TH", "THB", List.of(lines)).withRecipientType("OWNER");
    }

    private long pipelineTicket() {
        return ticketService.create(new CreateTicketRequest("ดีล pipeline", "NORMAL", customer.name(), customer.id(),
            projectId, null, null, null, List.of(), LocalDate.now().plusDays(7)), salesActor).summary().id();
    }

    private List<Long> itemIds(long ticketId) {
        return jdbc.queryForList("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t ORDER BY sort_order",
            Map.of("t", ticketId), Long.class);
    }

    private List<Map<String, Object>> stockRows(long ticketId) {
        return jdbc.queryForList("SELECT qty, qty_from_stock FROM sales.ticket_item WHERE ticket_id = :t ORDER BY sort_order",
            Map.of("t", ticketId));
    }

    private List<String> eventMessages(long ticketId, String kind) {
        return jdbc.queryForList("SELECT message FROM sales.ticket_event WHERE ticket_id = :t AND kind = :k ORDER BY event_id",
            Map.of("t", ticketId, "k", kind), String.class);
    }

    private List<String> managerNotices(long recipientId) {
        return jdbc.queryForList("SELECT message FROM hr.notification WHERE type = :type AND employee_id = :id",
            Map.of("type", TicketEventKind.STOCK_RESERVED, "id", recipientId), String.class);
    }

    private void assertForbiddenAndUntouched(long deal, UserPrincipal who) {
        List<Map<String, Object>> before = stockRows(deal);
        assertThatThrownBy(() -> ticketService.decideProcurementSource(deal, split(itemIds(deal), false, true), who))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(stockRows(deal)).isEqualTo(before);
        assertThat(eventMessages(deal, TicketEventKind.PROCUREMENT_SOURCE_DECIDED)).isEmpty();
        assertThat(ticketService.getProcurementSource(deal, salesActor).decidedAt()).isNull();
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void runV208() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(V208)) {
            assertThat(in).as(V208 + " on the classpath").isNotNull();
            jdbc.getJdbcOperations().execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh,
                                String positionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionNameTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
