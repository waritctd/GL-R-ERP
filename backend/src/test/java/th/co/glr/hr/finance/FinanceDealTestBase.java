package th.co.glr.hr.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.deposit.DepositNoticeRepository;
import th.co.glr.hr.deposit.RemainingInvoiceRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketController;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Shared real-Postgres wiring for the finance deal tests: the REAL TicketService + repositories and the
 * REAL FinanceDealService/Controller and TicketController behind MockMvc (so a removed route is observed
 * as a missing route, not assumed). Nothing here is mocked.
 */
abstract class FinanceDealTestBase extends AbstractPostgresIntegrationTest {

    /** Any JSON key containing one of these (case-insensitive) is a cost/pricing/tracking/list-price leak. */
    static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
        "pricingrequest", "cost", "landed", "factory", "fx", "margin",
        "winprobability", "designer", "owner", "buyer",
        "events", "activit", "assignedto", "proposed", "rawprice", "approvedprice",
        // A5: catalog/list-price/discount/weighting/manual-override/ceo-only shaped keys.
        "catalog", "manual", "weight", "discount", "listprice", "listunit");
    /** The ONE sanctioned comment-shaped key: the deal's COMMENT events (B2). Anything else with "comment" is a leak. */
    static final String SANCTIONED_COMMENTS_KEY = "comments";

    TicketRepository tickets;
    TicketService ticketService;
    FinanceDealService service;
    DepositNoticeRepository depositNotices;
    RemainingInvoiceRepository remainingInvoices;
    AttachmentRepository attachments;
    MockMvc financeMvc;
    MockMvc ticketsMvc;
    final ObjectMapper json = new ObjectMapper();

    long salesRepId;
    UserPrincipal salesRep;
    UserPrincipal salesManager;
    UserPrincipal importUser;
    UserPrincipal hrUser;
    UserPrincipal employeeUser;
    UserPrincipal accountUser;
    UserPrincipal ceoUser;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        depositNotices = new DepositNoticeRepository(jdbc);
        remainingInvoices = new RemainingInvoiceRepository(jdbc);
        attachments = new AttachmentRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        // pricingRequests (dead-deal cascade only) is never reached by the paths under test -- null is safe,
        // exactly as DealEntryAccessIntegrationTest's own wiring already does.
        ticketService = new TicketService(tickets, notifications, new ObjectMapper(), new CustomerRepository(jdbc),
            new QuotationRenderer(), (th.co.glr.hr.pricingrequest.PricingRequestService) null,
            new EmployeeAuthRepository(jdbc));
        service = new FinanceDealService(tickets, new FinanceDealRepository(jdbc), depositNotices,
            remainingInvoices, attachments, ticketService);
        financeMvc = MockMvcBuilders.standaloneSetup(new FinanceDealController(service, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        ticketsMvc = MockMvcBuilders.standaloneSetup(new TicketController(ticketService, new SessionContext()))
            .setControllerAdvice(new ApiExceptionHandler()).build();

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        salesRepId = createEmployee(employees, "พนักงานขาย ทดสอบ", "fin-sales@glr.co.th");
        salesRep = principal(salesRepId, "sales");
        salesManager = principal(createEmployee(employees, "ผู้จัดการขาย ทดสอบ", "fin-sm@glr.co.th"), "sales_manager");
        importUser = principal(createEmployee(employees, "ฝ่ายนำเข้า ทดสอบ", "fin-import@glr.co.th"), "import");
        hrUser = principal(createEmployee(employees, "ฝ่ายบุคคล ทดสอบ", "fin-hr@glr.co.th"), "hr");
        employeeUser = principal(createEmployee(employees, "พนักงาน ทดสอบ", "fin-emp@glr.co.th"), "employee");
        accountUser = principal(createEmployee(employees, "ฝ่ายบัญชี ทดสอบ", "fin-account@glr.co.th"), "account");
        ceoUser = principal(createEmployee(employees, "ซีอีโอ ทดสอบ", "fin-ceo@glr.co.th"), "ceo");
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────────────────

    static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return session;
    }

    static void collectKeys(JsonNode node, List<String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                out.add(e.getKey());
                collectKeys(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, out));
        }
    }

    /** Fails if any key in {@code tree} looks like a cost / pricing / tracking / list-price / activity leak. */
    static List<String> forbiddenKeysIn(JsonNode tree) {
        List<String> keys = new ArrayList<>();
        collectKeys(tree, keys);
        List<String> bad = new ArrayList<>();
        for (String key : keys) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (SANCTIONED_COMMENTS_KEY.equals(key)) continue;
            if (lower.contains("comment")) { bad.add(key); continue; }
            // "ceo" is matched as a WORD (leading, or a camel-case / snake-case word), never as a substring:
            // the legitimate key "invoiceOnFile" contains the letters c-e-O.
            if (key.startsWith("ceo") || key.contains("Ceo") || lower.contains("_ceo")) { bad.add(key); continue; }
            for (String fragment : FORBIDDEN_KEY_FRAGMENTS) {
                if (lower.contains(fragment)) { bad.add(key); break; }
            }
        }
        return bad;
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    long createTicket(String salesStage) {
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีลทดสอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย ทดสอบ");
        tickets.updateSalesStage(ticketId, salesStage);
        return ticketId;
    }

    /** A live deal at S15 (PROCUREMENT) with an accepted ฿100,000 quotation -- inside account's list scope. */
    long createS15Deal(String paymentStatus) {
        long ticketId = createTicket("PROCUREMENT");
        insertAcceptedQuotation(ticketId, "QT-LK-" + ticketId, new BigDecimal("100000.00"));
        if (paymentStatus != null) {
            tickets.updatePaymentStatusUnchecked(ticketId, paymentStatus);
        }
        return ticketId;
    }

    long insertAcceptedQuotation(long ticketId, String number, BigDecimal total) {
        long quotationId = tickets.createQuotation(ticketId, number, salesRepId, total).id();
        tickets.markQuotationStatus(ticketId, quotationId, "ACCEPTED");
        return quotationId;
    }

    void insertQuotationItem(long quotationId, int seq, String description, BigDecimal qty, String unit,
                             BigDecimal unitPrice, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO sales.quotation_item (quotation_id, seq, description, qty, unit_basis, unit_price, amount)
            VALUES (:q, :seq, :d, :qty, :unit, :up, :amt)
            """, new MapSqlParameterSource().addValue("q", quotationId).addValue("seq", seq)
            .addValue("d", description).addValue("qty", qty).addValue("unit", unit)
            .addValue("up", unitPrice).addValue("amt", amount));
    }

    void insertPayment(long ticketId, String kind, BigDecimal amount) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
            VALUES (:t, :k, :a, 'THB', now(), :by, :ref)
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("k", kind).addValue("a", amount)
            .addValue("by", salesRepId).addValue("ref", "FIN-" + ticketId + "-" + kind));
    }

    long attach(long ticketId, String fileName, String type) {
        return attachments.save(ticketId, null, fileName, "uploads/" + fileName, "application/pdf", 10L, type,
            salesRepId).id();
    }

    void sql(String statement, long ticketId) {
        jdbc.update(statement, new MapSqlParameterSource("id", ticketId));
    }

    String paymentStatusOf(long ticketId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId), String.class);
    }

    String salesStageOf(long ticketId) {
        return jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId), String.class);
    }

    int receiptCount(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.payment_receipt WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId), Integer.class);
    }

    boolean closeConfirmed(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT close_confirmed_at IS NOT NULL FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId), Boolean.class));
    }

    long createEmployee(EmployeeRepository employees, String name, String email) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
