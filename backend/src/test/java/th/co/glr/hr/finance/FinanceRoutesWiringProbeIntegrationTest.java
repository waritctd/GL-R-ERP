package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.CsrfCookieFilter;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.PostgresTestSupport;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealActivityKind;
import th.co.glr.hr.ticket.DealActivityRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Full-stack wiring test for the finance routes (added in the Opus review of H1, kept as part of the suite).
 *
 * <p>Live wiring proof: the FULL Spring context (real controllers, real Jackson, real SecurityFilterChain AND the
 * real CsrfCookieFilter, in production order) against real Postgres. Every /api/finance/deals route is called with
 * EXACTLY the path and JSON body hrApi.js / its callers send (TicketDetailPage.handleRecordPayment /
 * handleSetBilling, UpdateStageModal, the `{}` revoke body), with the XSRF cookie + X-XSRF-TOKEN header pair the
 * real client (client.js csrfHeaders) sends.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class FinanceRoutesWiringProbeIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    private static final String XSRF = "probe-xsrf-token-value";

    private final MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired TicketRepository tickets;
    @Autowired TicketService ticketService;
    @Autowired EmployeeRepository employees;
    @Autowired AttachmentRepository attachments;

    private UserPrincipal account;
    private UserPrincipal ceo;
    private UserPrincipal sales;
    private long salesRepId;

    @Autowired
    FinanceRoutesWiringProbeIntegrationTest(WebApplicationContext context, CsrfCookieFilter csrf,
                                            @Qualifier("springSecurityFilterChain") Filter security) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(csrf, security).build();
    }

    @BeforeEach
    void users() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        salesRepId = employee("probe-sales-" + tag);
        sales = principal(salesRepId, "sales");
        account = principal(employee("probe-account-" + tag), "account");
        ceo = principal(employee("probe-ceo-" + tag), "ceo");
    }

    // ── client-shaped requests ──────────────────────────────────────────────────────────────

    /** apiRequest(path) -- GET, no body, no CSRF header (safe method). */
    private MockHttpServletResponse getAs(String path, UserPrincipal actor) throws Exception {
        return mvc.perform(get(path).session(session(actor)).cookie(new Cookie("XSRF-TOKEN", XSRF))).andReturn().getResponse();
    }

    /** apiRequest(path, {method:'POST', body}) -- JSON content-type only when there is a body; X-XSRF-TOKEN always. */
    private MockHttpServletResponse postAs(String path, String body, UserPrincipal actor) throws Exception {
        MockHttpServletRequestBuilder b = post(path).session(session(actor))
            .cookie(new Cookie("XSRF-TOKEN", XSRF)).header("X-XSRF-TOKEN", XSRF);
        if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b).andReturn().getResponse();
    }

    private JsonNode okDeal(MockHttpServletResponse r, long id, String what) throws Exception {
        String content = r.getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getStatus()).as(what + " -> " + content).isEqualTo(200);
        JsonNode root = json.readTree(content);
        assertThat(root.has("deal")).as(what + " has {deal}").isTrue();
        assertThat(root.has("ticket")).as(what + " has no {ticket}").isFalse();
        JsonNode deal = root.get("deal");
        assertThat(deal.get("id").asLong()).isEqualTo(id);
        for (String key : List.of("code", "salesStage", "lifecycle", "moneyMilestone", "milestoneTrack", "customerName",
                "items", "money", "documents", "comments", "availableActions")) {
            assertThat(deal.has(key)).as(what + " deal." + key).isTrue();
        }
        assertThat(deal.get("milestoneTrack")).hasSize(5);
        for (String key : List.of("amountPayable", "amountPaid", "amountOutstanding", "paymentStatus", "paymentDueDate",
                "closeConfirmedAt", "amountVatBasis", "payments")) {
            assertThat(deal.get("money").has(key)).as(what + " deal.money." + key).isTrue();
        }
        for (String key : List.of("acceptedQuotation", "depositNotices", "remainingInvoices", "taxInvoices",
                "billingNotes", "purchaseOrders", "contracts")) {
            assertThat(deal.get("documents").has(key)).as(what + " deal.documents." + key).isTrue();
        }
        return deal;
    }

    // ── the probe ───────────────────────────────────────────────────────────────────────────

    @Test
    void account_everyFinanceRoute_liveThroughSecurityAndCsrf() throws Exception {
        List<String> log = new ArrayList<>();
        long a = s15Deal("DEPOSIT_NOTICE_ISSUED");

        JsonNode d = okDeal(getAs("/api/finance/deals/" + a, account), a, "GET");
        log.add("GET deal " + d.get("moneyMilestone").get("key").asText());

        d = okDeal(postAs("/api/finance/deals/" + a + "/comments", "{\"message\":\"probe comment\"}", account), a, "comments");
        assertThat(d.get("comments")).hasSize(1);

        d = okDeal(postAs("/api/finance/deals/" + a + "/deposit-paid", null, account), a, "deposit-paid");
        assertThat(d.get("money").get("paymentStatus").asText()).isEqualTo("DEPOSIT_PAID");

        // TicketDetailPage.handleRecordPayment's literal payload shape
        d = okDeal(postAs("/api/finance/deals/" + a + "/payments",
            "{\"kind\":\"DEPOSIT\",\"amount\":1000,\"receivedAt\":\"2026-09-30T03:00:00.000Z\",\"note\":null,"
                + "\"receiptRef\":null,\"allowOverpayment\":false}", account), a, "payments");
        assertThat(d.get("money").get("payments")).hasSize(2);

        // Billing is out of scope for the finance view: the route no longer exists.
        assertThat(postAs("/api/finance/deals/" + a + "/billing", "{\"dueDate\":\"2026-10-31\"}", account).getStatus())
            .isGreaterThanOrEqualTo(400);

        d = okDeal(postAs("/api/finance/deals/" + a + "/final-payment", null, account), a, "final-payment");
        assertThat(d.get("money").get("paymentStatus").asText()).isEqualTo("FULLY_PAID");

        long b = closableDeal();
        d = okDeal(postAs("/api/finance/deals/" + b + "/close/confirm", null, account), b, "close/confirm");
        assertThat(d.get("money").get("closeConfirmedAt").isNull()).isFalse();
        // TicketDetailPage sends revokeCloseConfirmation(ticketId, {}) -> body "{}"
        d = okDeal(postAs("/api/finance/deals/" + b + "/close/revoke", "{}", account), b, "close/revoke");
        assertThat(d.get("money").get("closeConfirmedAt").isNull()).isTrue();

        long c = stageReadyDeal();
        // UpdateStageModal: onSubmit({ stage, note: note.trim() || undefined }) -> JSON.stringify drops `note`
        d = okDeal(postAs("/api/finance/deals/" + c + "/stage", "{\"stage\":\"DEPOSIT_RECEIVED\"}", account), c, "stage");
        assertThat(d.get("salesStage").asText()).isEqualTo("DEPOSIT_RECEIVED");
        // and a non-money stage is refused through the full stack
        assertThat(postAs("/api/finance/deals/" + c + "/stage", "{\"stage\":\"NEGOTIATION\"}", account).getStatus())
            .isEqualTo(403);
        System.out.println("PROBE account ok: " + log);
    }

    @Test
    void ceo_financeRoutes_liveThroughSecurityAndCsrf() throws Exception {
        long a = s15Deal("DEPOSIT_NOTICE_ISSUED");
        okDeal(getAs("/api/finance/deals/" + a, ceo), a, "ceo GET");
        okDeal(postAs("/api/finance/deals/" + a + "/comments", "{\"message\":\"ceo\"}", ceo), a, "ceo comments");
        for (String r : List.of("deposit-paid", "final-payment", "close/confirm")) {
            assertThat(postAs("/api/finance/deals/" + a + "/" + r, null, ceo).getStatus()).as("ceo " + r).isEqualTo(403);
        }
        assertThat(postAs("/api/finance/deals/" + a + "/payments",
            "{\"kind\":\"DEPOSIT\",\"amount\":1000,\"receivedAt\":null,\"note\":null,\"receiptRef\":null,"
                + "\"allowOverpayment\":false}", ceo).getStatus()).as("ceo payments").isEqualTo(403);

        long b = closableDeal();
        okDeal(postAs("/api/finance/deals/" + b + "/close/confirm", null, account), b, "account confirm");
        okDeal(postAs("/api/finance/deals/" + b + "/close/revoke", "{}", ceo), b, "ceo revoke");

        long c = stageReadyDeal();
        okDeal(postAs("/api/finance/deals/" + c + "/stage", "{\"stage\":\"DEPOSIT_RECEIVED\"}", ceo), c, "ceo stage");
    }

    @Test
    void guards_csrfAuthRolesAndRemovedRoutes_liveThroughTheFullStack() throws Exception {
        long a = s15Deal("DEPOSIT_NOTICE_ISSUED");

        // CSRF: same POST without the header -> CsrfCookieFilter 403, nothing written.
        MockHttpServletResponse noCsrf = mvc.perform(post("/api/finance/deals/" + a + "/comments").session(session(account))
            .cookie(new Cookie("XSRF-TOKEN", XSRF)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"x\"}")).andReturn().getResponse();
        assertThat(noCsrf.getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'COMMENTED'",
            new MapSqlParameterSource("id", a), Integer.class)).isZero();

        // unauthenticated -> 401 (SecurityConfig default-deny)
        assertThat(mvc.perform(get("/api/finance/deals/" + a)).andReturn().getResponse().getStatus()).isEqualTo(401);

        // role: the deal's own sales rep is refused
        assertThat(getAs("/api/finance/deals/" + a, sales).getStatus()).isEqualTo(403);
        // missing -> 404
        assertThat(getAs("/api/finance/deals/999999999", account).getStatus()).isEqualTo(404);
        // account refused the ticket read
        assertThat(getAs("/api/tickets/" + a, account).getStatus()).isEqualTo(403);

        // the four removed /tickets routes, exactly as the OLD client called them
        List<String> seen = new ArrayList<>();
        for (UserPrincipal actor : List.of(account, ceo)) {
            for (String r : List.of("deposit-paid", "final-payment", "close/confirm")) {
                int s = postAs("/api/tickets/" + a + "/" + r, null, actor).getStatus();
                seen.add(actor.role() + " " + r + "=" + s);
                assertThat(s).as(actor.role() + " POST /api/tickets/{id}/" + r).isEqualTo(404);
            }
            int s = postAs("/api/tickets/" + a + "/payments", "{\"kind\":\"DEPOSIT\",\"amount\":1}", actor).getStatus();
            seen.add(actor.role() + " payments=" + s);
            assertThat(s).as(actor.role() + " POST /api/tickets/{id}/payments").isEqualTo(405);
        }
        assertThat(jdbc.queryForObject("SELECT payment_status FROM sales.ticket WHERE ticket_id = :id",
            new MapSqlParameterSource("id", a), String.class)).isEqualTo("DEPOSIT_NOTICE_ISSUED");
        System.out.println("PROBE removed routes: " + seen);
    }

    // ── fixtures (same shapes as FinanceDealTestBase) ───────────────────────────────────────

    private long ticket(String stage) {
        long id = tickets.create(
            new CreateTicketRequest("ดีลโพรบ", "NORMAL", "ลูกค้าโพรบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), salesRepId, "พนักงานขาย โพรบ");
        tickets.updateSalesStage(id, stage);
        return id;
    }

    private long s15Deal(String paymentStatus) {
        long id = ticket("PROCUREMENT");
        long q = tickets.createQuotation(id, "QT-PROBE-" + id, salesRepId, new BigDecimal("100000.00")).id();
        tickets.markQuotationStatus(id, q, "ACCEPTED");
        tickets.updatePaymentStatusUnchecked(id, paymentStatus);
        return id;
    }

    private long closableDeal() {
        long id = ticket("DELIVERED");
        long q = tickets.createQuotation(id, "QT-PROBE-CL-" + id, salesRepId, new BigDecimal("1000.00")).id();
        tickets.markQuotationStatus(id, q, "ACCEPTED");
        // payable = 1,000 + 7% VAT = 1,070 (owner ruling 2026-09-30): 500 + 570
        for (String kind : List.of("DEPOSIT", "BALANCE")) {
            jdbc.update("""
                INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref)
                VALUES (:t, :k, :amt, 'THB', now(), :by, :ref)
                """, new MapSqlParameterSource().addValue("t", id).addValue("k", kind)
                .addValue("amt", "DEPOSIT".equals(kind) ? new BigDecimal("500.00") : new BigDecimal("570.00")).addValue("by", salesRepId)
                .addValue("ref", "PROBE-" + id + "-" + kind));
        }
        jdbc.update("""
            UPDATE sales.ticket SET status = 'quotation_issued', payment_status = 'FULLY_PAID',
                   fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id
            """, new MapSqlParameterSource("id", id));
        attachments.save(id, null, "tax-invoice.pdf", "uploads/probe-" + id + ".pdf", "application/pdf", 10L,
            AttachType.INVOICE, salesRepId);
        return id;
    }

    private long stageReadyDeal() {
        long id = s15Deal("DEPOSIT_PAID");
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', next_follow_up_at = CURRENT_DATE + 3 "
            + "WHERE ticket_id = :id", new MapSqlParameterSource("id", id));
        ticketService.addActivity(id, new DealActivityRequest(LocalDate.now(), DealActivityKind.CALL, null), sales);
        return id;
    }

    private long employee(String emailLocal) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, "โพรบ " + emailLocal, null, null, null, null, null, null, null,
            emailLocal + "@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private static MockHttpSession session(UserPrincipal actor) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return s;
    }
}
