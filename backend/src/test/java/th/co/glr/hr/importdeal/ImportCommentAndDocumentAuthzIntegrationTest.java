package th.co.glr.hr.importdeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.attachment.AttachmentController;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CommentRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Authorization evidence for the two cost leaks that survived the import per-deal page slice, and
 * for the document widening that goes with them (owner rulings 2026-09-30: import must see NO
 * internal cost; import comment access is row-scoped to its import scope; import may download all
 * four attachment types for deals in its scope).
 *
 * <ul>
 *   <li><b>Leak A</b> — {@code POST /api/tickets/{id}/comments} returned {@code projectForRole(…,
 *       "import")}, which stripped the quotation chain but passed every item's cost columns
 *       through. The response must now be cost-free.
 *   <li><b>Leak B</b> — the same route used {@code requireViewAccess}, which has no import row
 *       scope, so import could comment on (and read back) any deal in the pipeline.
 *   <li><b>Documents</b> — {@code TicketAccessPolicy#canViewDocuments} refused every non-participant
 *       import; it must admit import for deals inside {@code TicketRepository#isInImportScope}
 *       (all four {@code AttachType}s) and still refuse it outside.
 * </ul>
 *
 * <p>Real Postgres, real services and repositories — Mockito cannot show that the scope predicate
 * reaches the WHERE clause. Written wrong-way-round: what import CANNOT reach. Sentinels are
 * planted in every cost column so an accidentally-empty DTO cannot make an absence check vacuous.
 *
 * <p>{@code @EnabledIf} is declared HERE, not inherited (JUnit's {@code @EnabledIf} is not
 * {@code @Inherited}; see {@link AbstractPostgresIntegrationTest}).
 *
 * <p>NOT covered, deliberately: the {@code summary} money fields ({@code amountPayable} etc.) that
 * the import worklist ({@code GET /api/tickets}) already returned to import before this change —
 * customer payment amounts, not item cost, and a separate ruling; import as the deal's
 * <i>assignee</i> (a participant — unchanged pre-#389 grant, pinned by TicketAccessPolicyTest);
 * the frontend.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ImportCommentAndDocumentAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String RAW_PRICE = "987654.32";
    private static final String PROPOSED_PRICE = "876543.21";
    private static final String APPROVED_PRICE = "765432.10";
    private static final String CALCED_COST = "654321.09";
    private static final String CALCED_PRICE = "555555.55";
    private static final String MANUAL_PRICE = "543210.98";
    private static final String STOCK_SALE_PRICE = "432109.87";
    // Summary money totals (customer payable / received / outstanding): 123456.78 quoted, 45678.90 received.
    private static final String PAYABLE = "123456.78";
    private static final String PAID = "45678.90";
    private static final String OUTSTANDING = "77777.88";

    private static final String OVERRIDE_NOTE = "ราคา manual override = 543210.98";

    private static final List<String> ATTACH_TYPES = List.of("PO", "SIGNED_QUOTATION", "INVOICE", "OTHER");

    private TicketRepository tickets;
    private TicketService ticketService;
    private MockMvc mvc;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal otherSalesRep;
    private UserPrincipal salesManager;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;
    private UserPrincipal hrUser;
    private UserPrincipal accountUser;

    /** Inside import's scope (ORDER_RECEIVED), items carrying real cost data. */
    private long inScopeId;
    /** Outside import's scope (LEAD_APPROACH, no pricing request), items carrying real cost data. */
    private long outOfScopeId;

    @BeforeEach
    void wireRealCollaborators() {
        tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-import-comment-doc-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        ticketService = new TicketService(tickets, notifications, objectMapper, new CustomerRepository(jdbc),
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc));
        mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(new AttachmentRepository(jdbc),
                new SessionContext(), tickets, new AuditService(new AuditLogRepository(jdbc), objectMapper),
                fileStorage))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        ownerId = insertEmployee("IMPCD-OWN");
        owner = principal(ownerId, "sales");
        otherSalesRep = principal(insertEmployee("IMPCD-OTH"), "sales");
        salesManager = principal(insertEmployee("IMPCD-SM"), "sales_manager");
        importUser = principal(insertEmployee("IMPCD-IMP"), "import");
        ceoUser = principal(insertEmployee("IMPCD-CEO"), "ceo");
        hrUser = principal(insertEmployee("IMPCD-HR"), "hr");
        accountUser = principal(insertEmployee("IMPCD-ACC"), "account");

        inScopeId = insertDealWithCosts("IMPCD-IN", DealStage.ORDER_RECEIVED);
        outOfScopeId = insertDealWithCosts("IMPCD-OUT", DealStage.LEAD_APPROACH);
    }

    // ── Leak A: the comment response must carry no cost ─────────────────────────────────────────

    @Test
    void importCommentResponse_hasNoCostField_noCostValue_andNoPriceBearingEvent() throws Exception {
        TicketDto response = ticketService.comment(inScopeId, new CommentRequest("นำเข้า: โรงงานยืนยันแล้ว"),
            importUser);

        // Non-vacuous: the items ARE there (the fixture planted costs on both), only stripped.
        assertThat(response.items()).hasSize(2);
        for (TicketItemDto item : response.items()) {
            assertThat(item.rawPrice()).as("rawPrice").isNull();
            assertThat(item.proposedPrice()).as("proposedPrice").isNull();
            assertThat(item.approvedPrice()).as("approvedPrice").isNull();
            assertThat(item.calcedCost()).as("calcedCost").isNull();
            assertThat(item.calcedPrice()).as("calcedPrice").isNull();
            assertThat(item.manualPrice()).as("manualPrice").isNull();
            assertThat(item.stockSalePrice()).as("stockSalePrice").isNull();
            assertThat(item.catalogPrice()).as("catalogPrice").isNull();
            assertThat(item.rawCurrency()).as("rawCurrency").isNull();
            assertThat(item.manualOverrideReason()).as("manualOverrideReason").isNull();
            // int, so it cannot be null: the neutral column default (no weighting), not the planted 3.
            assertThat(item.weightMultiplier()).as("weightMultiplier").isEqualTo(1);
        }
        // Everything import legitimately works with is intact.
        assertThat(response.items()).extracting(TicketItemDto::model)
            .containsExactly("Lithos Nero Nat", "Terrazzo White Nat");
        assertThat(response.items().get(0).qty()).isEqualByComparingTo("308");
        assertThat(response.items().get(0).qtyDelivered()).isEqualByComparingTo("5");
        // The quotation projection is still applied.
        assertThat(response.quotation()).isNull();
        assertThat(response.quotations()).isEmpty();
        // The price-bearing override event is not handed over; the comment thread is.
        assertThat(response.events()).extracting(e -> e.kind()).containsOnly(TicketEventKind.COMMENTED);
        assertThat(response.events()).extracting(e -> e.message())
            .contains("นำเข้า: โรงงานยืนยันแล้ว", "ลูกค้าขอเลื่อนส่งของ");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(json).contains("Lithos Nero Nat");
        assertThat(json).doesNotContain(RAW_PRICE, PROPOSED_PRICE, APPROVED_PRICE, CALCED_COST, CALCED_PRICE,
            MANUAL_PRICE, STOCK_SALE_PRICE, OVERRIDE_NOTE);
    }

    /** Positive controls: the strip is import-only. ceo / owner / sales_manager still see the costs. */
    @Test
    void otherRolesCommentResponseStillCarriesTheCosts() throws Exception {
        for (UserPrincipal actor : List.of(ceoUser, owner, salesManager)) {
            TicketDto response = ticketService.comment(inScopeId, new CommentRequest("hi"), actor);

            assertThat(response.items()).as(actor.role()).hasSize(2);
            assertThat(response.items().get(0).rawPrice()).as(actor.role() + " rawPrice")
                .isEqualByComparingTo(RAW_PRICE);
            assertThat(response.items().get(0).calcedCost()).as(actor.role() + " calcedCost")
                .isEqualByComparingTo(CALCED_COST);
            assertThat(response.items().get(0).manualPrice()).as(actor.role() + " manualPrice")
                .isEqualByComparingTo(MANUAL_PRICE);
            assertThat(response.items().get(0).weightMultiplier()).as(actor.role() + " weight").isEqualTo(3);
            assertThat(response.events()).as(actor.role() + " events").extracting(e -> e.kind())
                .contains(TicketEventKind.PRICE_OVERRIDDEN);
        }
    }

    // ── Summary money totals: null for import everywhere it receives a TicketSummaryDto ─────────

    @Test
    void importCommentResponseSummary_hasNoMoneyTotals_butKeepsTheRestOfTheSummary() throws Exception {
        TicketDto response = ticketService.comment(inScopeId, new CommentRequest("hi"), importUser);

        TicketSummaryDto summary = response.summary();
        assertThat(summary.amountPayable()).as("amountPayable").isNull();
        assertThat(summary.amountPaid()).as("amountPaid").isNull();
        assertThat(summary.amountOutstanding()).as("amountOutstanding").isNull();
        // Everything import legitimately works with is intact (non-vacuous: a real, populated summary).
        assertThat(summary.id()).isEqualTo(inScopeId);
        assertThat(summary.code()).isEqualTo("IMPCD-IN");
        assertThat(summary.customerName()).isEqualTo("บริษัท ทดสอบ จำกัด");
        assertThat(summary.salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(json).doesNotContain(PAYABLE, PAID, OUTSTANDING);
    }

    @Test
    void importWorklistRows_haveNoMoneyTotals_onBothListPaths() {
        // Precondition (wrong-way-round control): the same deal DOES carry money for the ceo.
        TicketSummaryDto asCeo = pageRow(ceoUser, inScopeId);
        assertThat(asCeo.amountPayable()).isEqualByComparingTo(PAYABLE);

        TicketSummaryDto paged = pageRow(importUser, inScopeId);
        assertThat(paged.code()).isEqualTo("IMPCD-IN");
        assertThat(paged.amountPayable()).as("listPage amountPayable").isNull();
        assertThat(paged.amountPaid()).as("listPage amountPaid").isNull();
        assertThat(paged.amountOutstanding()).as("listPage amountOutstanding").isNull();

        TicketSummaryDto plain = ticketService.list(null, importUser).stream()
            .filter(r -> r.id() == inScopeId).findFirst().orElseThrow();
        assertThat(plain.amountPayable()).as("list amountPayable").isNull();
        assertThat(plain.amountPaid()).as("list amountPaid").isNull();
        assertThat(plain.amountOutstanding()).as("list amountOutstanding").isNull();
    }

    /** The strip must not disturb the worklist itself: same rows, same total, still scoped. */
    @Test
    void importWorklistStillListsTheInScopeDealAndNotTheOutOfScopeOne() {
        var page = ticketService.listPage(null, importUser, PageRequest.resolve(0, 100));

        assertThat(page.items()).extracting(TicketSummaryDto::id).contains(inScopeId).doesNotContain(outOfScopeId);
        assertThat(page.total()).isEqualTo(page.items().size());
    }

    /** Positive controls: the strip is import-only. */
    @Test
    void otherRolesStillSeeTheMoneyTotals() {
        for (UserPrincipal actor : List.of(ceoUser, owner, salesManager)) {
            TicketSummaryDto row = pageRow(actor, inScopeId);
            assertThat(row.amountPayable()).as(actor.role() + " list payable").isEqualByComparingTo(PAYABLE);
            assertThat(row.amountPaid()).as(actor.role() + " list paid").isEqualByComparingTo(PAID);
            assertThat(row.amountOutstanding()).as(actor.role() + " list outstanding").isEqualByComparingTo(OUTSTANDING);

            TicketSummaryDto viaComment = ticketService.comment(inScopeId, new CommentRequest("hi"), actor).summary();
            assertThat(viaComment.amountPayable()).as(actor.role() + " comment payable").isEqualByComparingTo(PAYABLE);
        }
        // account reads the deal (its own worklist scope needs a pending payment, so use the deal read).
        TicketSummaryDto asAccount = ticketService.get(inScopeId, accountUser).summary();
        assertThat(asAccount.amountPayable()).isEqualByComparingTo(PAYABLE);
        assertThat(asAccount.amountPaid()).isEqualByComparingTo(PAID);
        assertThat(asAccount.amountOutstanding()).isEqualByComparingTo(OUTSTANDING);
    }

    // ── Leak B: import's comment path is row-scoped ─────────────────────────────────────────────

    @Test
    void importCannotCommentOnADealOutsideItsScope_andNothingIsWritten() {
        assertForbidden(() -> ticketService.comment(outOfScopeId, new CommentRequest("ไม่ควรเขียนได้"), importUser));

        assertThat(commentCount(outOfScopeId, "ไม่ควรเขียนได้")).isZero();
    }

    @Test
    void importCannotCommentOnAClosedLostDeal_evenAtAnInScopeStage() {
        long lost = insertDealWithCosts("IMPCD-LOST", DealStage.PROCUREMENT);
        jdbc.update("UPDATE sales.ticket SET lifecycle = 'CLOSED_LOST' WHERE ticket_id = :id", Map.of("id", lost));

        assertForbidden(() -> ticketService.comment(lost, new CommentRequest("x"), importUser));
    }

    /** No existence oracle: a missing deal is indistinguishable from an out-of-scope one to import. */
    @Test
    void importGets403NotNotFound_commentingOnANonExistentDeal() {
        assertForbidden(() -> ticketService.comment(9_999_999L, new CommentRequest("x"), importUser));
    }

    @Test
    void importCanCommentOnAnInScopeDeal_andTheCommentIsPersisted() {
        ticketService.comment(inScopeId, new CommentRequest("นำเข้า: ส่งของวันจันทร์"), importUser);

        assertThat(commentCount(inScopeId, "นำเข้า: ส่งของวันจันทร์")).isEqualTo(1);
    }

    /** Positive controls: the scope is import-only; every other role's comment path is unchanged. */
    @Test
    void otherRolesCanStillCommentOnADealOutsideImportsScope() {
        assertThat(ticketService.comment(outOfScopeId, new CommentRequest("ceo"), ceoUser)).isNotNull();
        assertThat(ticketService.comment(outOfScopeId, new CommentRequest("owner"), owner)).isNotNull();
        assertThat(ticketService.comment(outOfScopeId, new CommentRequest("sm"), salesManager)).isNotNull();
        // The sales-owner check is preserved: another rep still cannot comment on someone's deal.
        assertForbidden(() -> ticketService.comment(outOfScopeId, new CommentRequest("x"), otherSalesRep));
        // hr is not a viewer role at all.
        assertForbidden(() -> ticketService.comment(outOfScopeId, new CommentRequest("x"), hrUser));
    }

    // ── Documents: import may download all four types, in scope only ────────────────────────────

    @Test
    void importCanListAndDownloadEveryAttachmentType_onAnInScopeDeal() throws Exception {
        Map<String, Long> ids = uploadOneOfEachType(inScopeId);

        mvc.perform(get("/api/tickets/{id}/attachments", inScopeId).session(session(importUser)))
            .andExpect(status().isOk());
        for (String type : ATTACH_TYPES) {
            mvc.perform(get("/api/attachments/{id}/file", ids.get(type)).session(session(importUser)))
                .andExpect(status().isOk());
        }
    }

    @Test
    void importCannotListOrDownloadAnyAttachment_onADealOutsideItsScope() throws Exception {
        Map<String, Long> ids = uploadOneOfEachType(outOfScopeId);

        mvc.perform(get("/api/tickets/{id}/attachments", outOfScopeId).session(session(importUser)))
            .andExpect(status().isForbidden());
        for (String type : ATTACH_TYPES) {
            mvc.perform(get("/api/attachments/{id}/file", ids.get(type)).session(session(importUser)))
                .andExpect(status().isForbidden());
        }
    }

    /** Widening documents READS does not widen WRITES: import still cannot attach to an in-scope deal. */
    @Test
    void importStillCannotUploadADocument_evenOnAnInScopeDeal() throws Exception {
        mvc.perform(multipart("/api/tickets/{id}/attachments", inScopeId)
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8)))
                .param("attachType", "PO").session(session(importUser)))
            .andExpect(status().isForbidden());
    }

    /** Positive controls + the roles that must stay refused: the widening is import-only. */
    @Test
    void documentAccessForOtherRolesIsUnchanged() throws Exception {
        Map<String, Long> ids = uploadOneOfEachType(outOfScopeId);
        Long po = ids.get("PO");

        for (UserPrincipal allowed : List.of(ceoUser, owner, salesManager)) {
            mvc.perform(get("/api/attachments/{id}/file", po).session(session(allowed))).andExpect(status().isOk());
        }
        for (UserPrincipal refused : List.of(otherSalesRep, hrUser)) {
            mvc.perform(get("/api/attachments/{id}/file", po).session(session(refused)))
                .andExpect(status().isForbidden());
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private Map<String, Long> uploadOneOfEachType(long ticketId) throws Exception {
        Map<String, Long> ids = new java.util.LinkedHashMap<>();
        for (String type : ATTACH_TYPES) {
            mvc.perform(multipart("/api/tickets/{id}/attachments", ticketId)
                    .file(new MockMultipartFile("file", type.toLowerCase() + ".pdf", "application/pdf",
                        ("content-" + type).getBytes(StandardCharsets.UTF_8)))
                    .param("attachType", type).session(session(ceoUser)))
                .andExpect(status().isOk());
            ids.put(type, jdbc.queryForObject(
                "SELECT MAX(attachment_id) FROM sales.attachment WHERE ticket_id = :t AND attach_type = :ty",
                Map.of("t", ticketId, "ty", type), Long.class));
        }
        return ids;
    }

    private TicketSummaryDto pageRow(UserPrincipal actor, long ticketId) {
        return ticketService.listPage(null, actor, PageRequest.resolve(0, 100)).items().stream()
            .filter(r -> r.id() == ticketId).findFirst()
            .orElseThrow(() -> new AssertionError("deal " + ticketId + " not on " + actor.role() + "'s list"));
    }

    private int commentCount(long ticketId, String message) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :t AND kind = 'COMMENTED' AND message = :m",
            Map.of("t", ticketId, "m", message), Integer.class);
    }

    private static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-impcd@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'คอมเมนต์นำเข้า') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    /** A deal at {@code stage} with two cost-bearing lines, a price-bearing event and a real comment. */
    private long insertDealWithCosts(String code, String stage) {
        long id = jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage, fulfillment_status)
            VALUES (:code, 'ทดสอบคอมเมนต์นำเข้า', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage, NULL)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", stage), Long.class);
        insertCostBearingItem(id, "Lithos Nero Nat", "60x60 cm", "308", 0);
        insertCostBearingItem(id, "Terrazzo White Nat", "30x60 cm", "120", 1);
        tickets.addEvent(id, ownerId, "พนักงานขาย", TicketEventKind.PRICE_OVERRIDDEN, null, null, OVERRIDE_NOTE);
        tickets.addEvent(id, ownerId, "พนักงานขาย", TicketEventKind.COMMENTED, null, null, "ลูกค้าขอเลื่อนส่งของ");
        // Real summary money: an ISSUED quotation drives amountPayable, a receipt drives amountPaid.
        tickets.createQuotation(id, "QT-" + code, ownerId, new BigDecimal(PAYABLE));
        tickets.insertPaymentReceipt(id, "DEPOSIT", new BigDecimal(PAID), ownerId, null, null, null, null);
        return id;
    }

    private void insertCostBearingItem(long ticket, String model, String size, String qty, int sortOrder) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, model, color, texture, size, qty, unit,
                   unit_basis, sort_order, factory, qty_delivered,
                   raw_price, raw_currency, proposed_price, approved_price, currency, calced_cost,
                   calced_price, manual_price, manual_override_reason, weight_multiplier,
                   sourced_from_stock, stock_sale_price)
            VALUES (:t, 'Padana', :model, 'Nero', 'Nat', :size, :qty, 'pcs',
                    'PIECE', :sort, 'Padana', 5,
                    :raw, 'EUR', :proposed, :approved, 'THB', :cost,
                    :calcedPrice, :manual, 'ปรับราคา 543210.98', 3,
                    TRUE, :stockSale)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("model", model)
                .addValue("size", size).addValue("qty", new BigDecimal(qty)).addValue("sort", sortOrder)
                .addValue("raw", new BigDecimal(RAW_PRICE)).addValue("proposed", new BigDecimal(PROPOSED_PRICE))
                .addValue("approved", new BigDecimal(APPROVED_PRICE)).addValue("cost", new BigDecimal(CALCED_COST))
                .addValue("calcedPrice", new BigDecimal(CALCED_PRICE))
                .addValue("manual", new BigDecimal(MANUAL_PRICE))
                .addValue("stockSale", new BigDecimal(STOCK_SALE_PRICE)));
    }
}
