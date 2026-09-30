package th.co.glr.hr.importdeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository;
import th.co.glr.hr.importrequest.ImportRequestRenderer;
import th.co.glr.hr.importrequest.ImportRequestRepository;
import th.co.glr.hr.importrequest.ImportRequestService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CommentRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.RecordDeliveryRequest;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Authorization evidence for the per-deal IMPORT view (owner-confirmed scope, mirrors the account
 * finance-view ruling): {@code GET /api/import/deals/{id}} + the paired 403 of import on
 * {@code GET /api/tickets/{id}}.
 *
 * <p>Real Postgres, real {@link ImportDealService}/{@link TicketService}/{@link
 * ImportRequestService} and real repositories — Mockito cannot show that the row-scope predicate
 * survives into the WHERE clause. Every case is written wrong-way-round: what the caller CANNOT
 * reach, not what they can.
 *
 * <p>{@code @EnabledIf} is declared HERE, not inherited from the base class — JUnit's {@code
 * @EnabledIf} is not {@code @Inherited} (see {@link AbstractPostgresIntegrationTest}).
 *
 * <p>NOT covered, deliberately: the frontend page and its wiring (a later slice); a cross-deal
 * import list endpoint (out of scope — /fulfilment keeps its client-side per-deal fetch); document
 * attachment reads for import (an open owner question, see the branch report — {@code
 * TicketAccessPolicy#canViewDocuments} is unchanged and pinned by its own tests).
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ImportDealAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    // Sentinel values planted in every cost-bearing column of the fixture item. If any of them
    // shows up anywhere in the serialized response, a price/cost field leaked — and because they
    // are planted (not null), an accidentally-empty DTO cannot make the absence check vacuous.
    private static final String RAW_PRICE = "987654.32";
    private static final String PROPOSED_PRICE = "876543.21";
    private static final String APPROVED_PRICE = "765432.10";
    private static final String CALCED_COST = "654321.09";
    private static final String MANUAL_PRICE = "543210.98";
    private static final String OVERRIDE_NOTE = "ราคา manual override = 543210.98";

    private static final List<String> FORBIDDEN_JSON_KEYS = List.of(
        "rawPrice", "approvedPrice", "proposedPrice", "manualPrice", "calcedCost", "calcedPrice",
        "weightMultiplier", "commission", "discount", "margin", "rawCurrency", "amountPayable",
        "amountPaid", "amountOutstanding", "quotation", "quotations", "unitPrice", "stockSalePrice");

    private TicketRepository tickets;
    private TicketService ticketService;
    private ImportRequestService importRequestService;
    private ImportDealService service;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal salesManager;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal accountUser;
    private UserPrincipal hrUser;

    private long ticketId;
    private long factoryId;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-import-deal-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));
        importRequestService = new ImportRequestService(
            new ImportRequestQueryRepository(jdbc), new ImportRequestRenderer(),
            new ImportRequestRepository(jdbc), new FactoryConfigRepository(jdbc), tickets, ticketService);
        service = new ImportDealService(tickets, importRequestService);

        ownerId = insertEmployee("IMPDEAL-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("IMPDEAL-OTH"), "sales");
        salesManager = principal(insertEmployee("IMPDEAL-SM"), "sales_manager");
        importUser = principal(insertEmployee("IMPDEAL-IMP"), "import");
        ceoUser = principal(insertEmployee("IMPDEAL-CEO"), "ceo");
        accountUser = principal(insertEmployee("IMPDEAL-ACC"), "account");
        hrUser = principal(insertEmployee("IMPDEAL-HR"), "hr");

        factoryId = insertFactory("Padana Import Deal Test");

        // The in-scope deal: ORDER_RECEIVED (import's own floor), items carrying real cost data.
        ticketId = insertTicket("IMPDEAL-1", "บริษัท ทดสอบนำเข้า จำกัด", DealStage.ORDER_RECEIVED);
        insertCostBearingItem(ticketId, "Padana Import Deal Test", "Lithos Nero Nat", "60x60 cm", "308", 0);
        insertCostBearingItem(ticketId, "Padana Import Deal Test", "Terrazzo White Nat", "30x60 cm", "120", 1);
        // A price-bearing event (its note quotes a price) and a real comment — only the comment
        // may ever surface in the import view.
        tickets.addEvent(ticketId, ownerId, "พนักงานขาย", TicketEventKind.PRICE_OVERRIDDEN, null, null,
            OVERRIDE_NOTE);
        tickets.addEvent(ticketId, ownerId, "พนักงานขาย", TicketEventKind.COMMENTED, null, null,
            "ลูกค้าขอเลื่อนส่งของ");
    }

    // ── 1. import, in-scope deal: 200, right shape, NO cost field anywhere ───────────────────────

    @Test
    void importReadsAnInScopeDeal_withFactoriesItemsAndCustomer() {
        ImportRequestDto ir = issueOneImportRequest();

        ImportDealDto deal = service.get(ticketId, importUser);

        assertThat(deal).isNotNull();
        assertThat(deal.id()).isEqualTo(ticketId);
        assertThat(deal.code()).isEqualTo("IMPDEAL-1");
        assertThat(deal.customerName()).isEqualTo("บริษัท ทดสอบนำเข้า จำกัด");
        assertThat(deal.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(deal.items()).hasSize(2);
        assertThat(deal.items()).extracting(ImportDealDtos.ImportDealItemDto::model)
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");
        assertThat(deal.items().get(0).code()).isEqualTo("LNN-308");
        assertThat(deal.items().get(0).size()).isEqualTo("60x60 cm");
        assertThat(deal.items().get(0).qty()).isEqualByComparingTo("308");
        assertThat(deal.items().get(0).unit()).isEqualTo("PIECE");
        assertThat(deal.items().get(0).brand()).isEqualTo("Padana Import Deal Test");
        // Read-only delivery status: fulfilment status + per-item delivered quantity.
        assertThat(deal.fulfillmentStatus()).isEqualTo("PARTIALLY_DELIVERED");
        assertThat(deal.items().get(0).qtyDelivered()).isEqualByComparingTo("5");
        // The stored per-factory rows are the same data ImportRequestService#list serves.
        assertThat(deal.importRequests()).extracting(ImportRequestDto::id).containsExactly(ir.id());
        assertThat(deal.importRequests().get(0).factoryName()).isEqualTo("Padana Import Deal Test");
        assertThat(deal.importRequests().get(0).importStep()).isNotNull();
    }

    @Test
    void importResponseContainsNoCostFieldAndNoCostValue_evenThoughTheDealItemsCarryThem() throws Exception {
        issueOneImportRequest();

        ImportDealDto deal = service.get(ticketId, importUser);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deal);

        // Sanity: the DTO is non-trivial, so the absences below are not vacuous.
        assertThat(json).contains("Lithos Nero Nat").contains("IMPDEAL-1");
        List<String> keys = new ArrayList<>();
        collectKeys(new ObjectMapper().findAndRegisterModules().readTree(json), keys);
        assertThat(keys).doesNotContainAnyElementsOf(FORBIDDEN_JSON_KEYS);
        assertThat(keys).noneMatch(k -> k.toLowerCase().contains("price") || k.toLowerCase().contains("cost"));
        // And none of the planted VALUES leaked under some other name.
        assertThat(json).doesNotContain(RAW_PRICE, PROPOSED_PRICE, APPROVED_PRICE, CALCED_COST, MANUAL_PRICE);
    }

    @Test
    void importSeesTheCommentThread_butNotThePriceBearingEventFeed() throws Exception {
        ImportDealDto deal = service.get(ticketId, importUser);

        assertThat(deal).isNotNull();
        assertThat(deal.comments()).extracting(ImportDealDtos.ImportDealCommentDto::message)
            .containsExactly("ลูกค้าขอเลื่อนส่งของ");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(deal);
        assertThat(json).doesNotContain(OVERRIDE_NOTE);
    }

    @Test
    void aDealInScopeOnlyThroughALivePricingRequest_isReadable() {
        // Second disjunct of the import scope: a live pricing request, stage still pre-order.
        long early = insertTicket("IMPDEAL-PCR", "ลูกค้า PCR", DealStage.NEGOTIATION);
        insertPricingRequest(early, "SUBMITTED");

        assertThat(service.get(early, importUser)).isNotNull();
    }

    // ── 2. import, OUT-of-scope deal: 403 ─────────────────────────────────────────────────────────

    @Test
    void importCannotReadALeadStageDealWithNoPricingRequest() {
        long lead = insertTicket("IMPDEAL-LEAD", "ลูกค้าใหม่", DealStage.LEAD_APPROACH);

        assertForbidden(() -> service.get(lead, importUser));
    }

    @Test
    void importCannotReadADealWhoseOnlyPricingRequestIsTerminalBelowTheStageFloor() {
        long below = insertTicket("IMPDEAL-BELOW", "ลูกค้าต่ำกว่าเกณฑ์", DealStage.NEGOTIATION);
        insertPricingRequest(below, "QUOTATION_ACCEPTED");

        assertForbidden(() -> service.get(below, importUser));
    }

    @Test
    void importCannotReadAClosedLostDeal_evenAtAnInScopeStage() {
        long lost = insertTicket("IMPDEAL-LOST", "ลูกค้าหลุด", DealStage.PROCUREMENT);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id",
            Map.of("id", lost));

        assertForbidden(() -> service.get(lost, importUser));
    }

    @Test
    void importCannotReadACancelledDeal_evenAtAnInScopeStage() {
        long cancelled = insertTicket("IMPDEAL-CANC", "ลูกค้ายกเลิก", DealStage.PROCUREMENT);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CANCELLED', status = 'cancelled' WHERE ticket_id = :id",
            Map.of("id", cancelled));

        assertForbidden(() -> service.get(cancelled, importUser));
    }

    /** No existence oracle: a missing deal is indistinguishable from an out-of-scope one to import. */
    @Test
    void importGetsForbiddenNotNotFound_forANonExistentDeal() {
        assertForbidden(() -> service.get(9_999_999L, importUser));
    }

    // ── 3. every other role: 403, even on an in-scope deal ───────────────────────────────────────

    @Test
    void salesOwnerCannotUseTheImportView_evenOnTheirOwnDeal() {
        assertForbidden(() -> service.get(ticketId, owner));
    }

    @Test
    void otherRolesCannotUseTheImportView() {
        assertForbidden(() -> service.get(ticketId, otherSalesRep));
        assertForbidden(() -> service.get(ticketId, salesManager));
        assertForbidden(() -> service.get(ticketId, accountUser));
        assertForbidden(() -> service.get(ticketId, hrUser));
    }

    /** The role gate runs before any lookup, so a wrong role learns nothing about existence either. */
    @Test
    void aWrongRoleGetsForbiddenNotNotFound_forANonExistentDeal() {
        assertForbidden(() -> service.get(9_999_999L, owner));
        assertForbidden(() -> service.get(9_999_999L, accountUser));
    }

    // ── 4. ceo: allowed, and NOT row-scoped ──────────────────────────────────────────────────────

    @Test
    void ceoReadsAnInScopeDeal() {
        assertThat(service.get(ticketId, ceoUser)).isNotNull();
    }

    @Test
    void ceoIsNotRowScoped_canReadADealOutsideImportsScope() {
        long lead = insertTicket("IMPDEAL-CEOLEAD", "ลูกค้าใหม่", DealStage.LEAD_APPROACH);
        // Positive control: import is refused this very deal, so the ceo's success is the scope
        // difference, not an accident of the fixture.
        assertForbidden(() -> service.get(lead, importUser));

        ImportDealDto deal = service.get(lead, ceoUser);

        assertThat(deal).isNotNull();
        assertThat(deal.id()).isEqualTo(lead);
    }

    @Test
    void ceoGetsNotFound_forANonExistentDeal() {
        assertThatThrownBy(() -> service.get(9_999_999L, ceoUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    // ── 5. the paired removal: import loses GET /tickets/{id}, and ONLY that ─────────────────────

    @Test
    void importCannotReadTheWholeDealViaTicketsGet() {
        assertForbidden(() -> ticketService.get(ticketId, importUser));
    }

    /** The guard is precise: every other viewer keeps GET /tickets/{id}. */
    @Test
    void otherViewersStillReadTicketsGet() {
        assertThatCode(() -> ticketService.get(ticketId, ceoUser)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, owner)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.get(ticketId, salesManager)).doesNotThrowAnyException();
        // account is refused too since the H1 lockdown (it reads GET /api/finance/deals/{id} instead).
        assertForbidden(() -> ticketService.get(ticketId, accountUser));
    }

    /** Sub-paths import still needs are NOT blocked by the tickets-get guard. */
    @Test
    void importCanStillReachItsImportRequestSubPaths() {
        issueOneImportRequest();

        assertThat(importRequestService.list(ticketId, importUser)).hasSize(1);
        assertThatCode(() -> ticketService.listDeliveries(ticketId, importUser)).doesNotThrowAnyException();
        assertThatCode(() -> ticketService.actions(ticketId, importUser)).doesNotThrowAnyException();
    }

    // ── 6. comments + delivery ────────────────────────────────────────────────────────────────────

    @Test
    void importCanPostAComment_andReadsItBackThroughTheImportView() {
        ticketService.comment(ticketId, new CommentRequest("นำเข้า: โรงงานยืนยันวันส่งแล้ว"), importUser);

        ImportDealDto deal = service.get(ticketId, importUser);

        assertThat(deal).isNotNull();
        assertThat(deal.comments()).extracting(ImportDealDtos.ImportDealCommentDto::message)
            .contains("นำเข้า: โรงงานยืนยันวันส่งแล้ว");
        assertThat(deal.comments()).extracting(ImportDealDtos.ImportDealCommentDto::actorName)
            .doesNotContainNull();
    }

    @Test
    void importCannotWriteDelivery() {
        var request = new RecordDeliveryRequest("WAREHOUSE", null,
            List.of(new RecordDeliveryRequest.Line(firstItemId(), new BigDecimal("1"))), null);

        assertForbidden(() -> ticketService.recordPartialDelivery(ticketId, request, importUser));
        assertForbidden(() -> ticketService.completeDelivery(ticketId, null, importUser));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private ImportRequestDto issueOneImportRequest() {
        List<ImportRequestDto> drafts = importRequestService.createDrafts(ticketId, null, ceoUser);
        return importRequestService.issue(drafts.get(0).id(), null, ceoUser);
    }

    private long firstItemId() {
        return jdbc.queryForObject(
            "SELECT MIN(item_id) FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketId), Long.class);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    private static void collectKeys(JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                into.add(name);
                collectKeys(node.get(name), into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, into));
        }
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-impdeal@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ดีลนำเข้า') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES (:name, 'IT', 'EUR') RETURNING factory_id
            """, Map.of("name", name), Long.class);
    }

    private long insertTicket(String code, String customerName, String stage) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage, fulfillment_status)
            VALUES (:code, 'ทดสอบดีลนำเข้า', :by, :customer, 'quotation_issued', 'DEPOSIT_PAID',
                    :stage, :fulfil)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("customer", customerName).addValue("stage", stage)
                .addValue("fulfil", DealStage.ORDER_RECEIVED.equals(stage) ? "PARTIALLY_DELIVERED" : null),
            Long.class);
    }

    /** A line whose every cost-bearing column is populated with a recognisable sentinel. */
    private void insertCostBearingItem(long ticket, String factory, String model, String size, String qty,
                                       int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, color, texture, size, qty, unit,
                   unit_basis, sort_order, factory, catalog_product_code, qty_delivered,
                   raw_price, raw_currency, proposed_price, approved_price, currency, calced_cost,
                   manual_price, weight_multiplier)
            VALUES (:t, :brand, :model, 'Nero', 'Nat', :size, :qty, 'pcs',
                    'PIECE', :sort, :factory, :code, 5,
                    :raw, 'EUR', :proposed, :approved, 'THB', :cost,
                    :manual, 3)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", factory)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("sort", sortOrder)
                .addValue("factory", factory)
                .addValue("code", sortOrder == 0 ? "LNN-308" : "TWN-120")
                .addValue("raw", new BigDecimal(RAW_PRICE)).addValue("proposed", new BigDecimal(PROPOSED_PRICE))
                .addValue("approved", new BigDecimal(APPROVED_PRICE)).addValue("cost", new BigDecimal(CALCED_COST))
                .addValue("manual", new BigDecimal(MANUAL_PRICE)));
    }

    private long insertPricingRequest(long ticket, String status) {
        boolean cancelled = "CANCELLED".equals(status);
        return jdbc.queryForObject("""
            INSERT INTO sales.pricing_request
                (request_code, ticket_id, recipient_type, status, requested_by, cancelled_at)
            VALUES (:code, :ticketId, 'DESIGNER', :status, :requestedBy, :cancelledAt)
            RETURNING pricing_request_id
            """,
            new MapSqlParameterSource()
                .addValue("code", "PCR-ID-" + ticket + "-" + status.substring(0, 3))
                .addValue("ticketId", ticket)
                .addValue("status", status)
                .addValue("requestedBy", ownerId)
                .addValue("cancelledAt", cancelled ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                    java.sql.Types.TIMESTAMP),
            Long.class);
    }
}
