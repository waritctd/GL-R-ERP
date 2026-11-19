package th.co.glr.hr.importdeal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
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
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/** Authorization evidence for the two cost leaks that survived the import per-deal page slice, and for the
 * document widening that goes with them. The response must now be cost-free. Real Postgres, real services and
 * repositories — Mockito cannot show that the scope predicate reaches the WHERE clause. */
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
    /** The quotation's persisted pre-VAT subtotal. */
    private static final String QUOTATION_SUBTOTAL = "123456.78";
    /** Payable is VAT-inclusive (owner ruling 2026-09-30): 123456.78 + round2(123456.78 x 0.07) = 123456.78 + 8641.97. */
    private static final String PAYABLE = "132098.75";
    private static final String PAID = "45678.90";
    private static final String OUTSTANDING = "86419.85";

    private static final String OVERRIDE_NOTE = "ราคา manual override = 543210.98";

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
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));
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

    // ── Summary money totals: null for import everywhere it receives a TicketSummaryDto ─────────

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
        // account reads the deal's money through its finance view since the H1 lockdown (GET /tickets/{id} refuses
        // account); the deal sits at S10, inside account's list scope.
        th.co.glr.hr.finance.FinanceDealService finance = new th.co.glr.hr.finance.FinanceDealService(tickets,
            new th.co.glr.hr.finance.FinanceDealRepository(jdbc), new th.co.glr.hr.deposit.DepositNoticeRepository(jdbc),
            new th.co.glr.hr.deposit.RemainingInvoiceRepository(jdbc), new AttachmentRepository(jdbc), ticketService);
        var asAccount = finance.get(inScopeId, accountUser).money();
        assertThat(asAccount.amountPayable()).isEqualByComparingTo(PAYABLE);
        assertThat(asAccount.amountPaid()).isEqualByComparingTo(PAID);
        assertThat(asAccount.amountOutstanding()).isEqualByComparingTo(OUTSTANDING);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private TicketSummaryDto pageRow(UserPrincipal actor, long ticketId) {
        return ticketService.listPage(null, actor, PageRequest.resolve(0, 100)).items().stream()
            .filter(r -> r.id() == ticketId).findFirst()
            .orElseThrow(() -> new AssertionError("deal " + ticketId + " not on " + actor.role() + "'s list"));
    }

    private static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
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
        tickets.createQuotation(id, "QT-" + code, ownerId, new BigDecimal(QUOTATION_SUBTOTAL));
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
