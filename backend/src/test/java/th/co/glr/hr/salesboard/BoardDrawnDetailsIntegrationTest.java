package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.ticket.QuietRule;
import th.co.glr.hr.ticket.TicketSummaryDto;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 6.2 (drawn details): the board card's new {@code BoardDeal} fields — {@code quotationNumber} (the value
 * quotation's own number), {@code buyerName}, and the money state {@code expectedDepositWeek}, {@code depositReceived},
 * {@code paymentDueDate}, {@code overdue} — through the real controller, {@link SalesBoardService},
 * {@link SalesBoardRepository} and Postgres.
 *
 * <p>The due date and the overdue flag must be the finance worklist's own ({@code TicketSummaryDto#paymentDueDate} /
 * {@code #overdue}): each due-date test asserts the absolute value AND equality with the deal's summary.
 *
 * <p>Written wrong-way-round for scope: a rep's board (and a manager's own my-work) never carries another rep's deal
 * or any of its new fields; account / import / hr are refused every board read, so they receive none of them.
 * Annotated itself — {@code @EnabledIf} is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class BoardDrawnDetailsIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {
    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        QuietRule quietRule = new QuietRule(jdbc, 14);
        VisitService visits = new VisitService(new VisitRepository(jdbc), ticketService,
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T03:00:00Z"), VisitService.BANGKOK));
        SalesBoardService board = new SalesBoardService(new SalesBoardRepository(jdbc, quietRule), quietRule,
            new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), visits, new CashRepository(jdbc));
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
        mvc = MockMvcBuilders
            .standaloneSetup(new SalesBoardController(board, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    // ── paymentDueDate / overdue: the finance summary's own ──────────────────────────────────────

    @Test
    void anOverdueDeal_showsTheSummarysDueDateAndOverdueFlag() throws Exception {
        long id = creditDealDelivered(31, "AWAITING_FINAL_PAYMENT");

        JsonNode card = byId(ok(get("/api/sales/my-work"), salesActor).get("board"), id);

        TicketSummaryDto summary = summary(id);
        assertThat(card.path("paymentDueDate").asText()).isEqualTo(today().minusDays(1).toString())
            .isEqualTo(String.valueOf(summary.paymentDueDate()));
        assertThat(card.path("overdue").asBoolean()).isTrue().isEqualTo(summary.overdue());
    }

    // ── scope: wrong way round ──────────────────────────────────────────────────────────────────

    @Test
    void aRepsBoardNeverCarriesAnotherRepsDeal_orAnyOfItsNewFields() throws Exception {
        long theirs = deal(otherSalesActor);
        DealQuotationDto theirQuotation = approved(theirs, otherSalesActor, "DESIGNER");
        setBuyer(theirs, "ผู้ซื้อของคนอื่น จำกัด");
        expected(theirs, "DEPOSIT", LocalDate.of(2026, 11, 30), "OPEN");
        long mine = deal(salesActor);

        String body = raw(get("/api/sales/my-work"), salesActor);
        JsonNode work = json.readTree(body);

        assertThat(ids(work.get("board"))).contains(mine).doesNotContain(theirs);
        assertThat(ids(work.get("followUp"))).doesNotContain(theirs);
        assertThat(body).doesNotContain(theirQuotation.number()).doesNotContain("ผู้ซื้อของคนอื่น");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void setBuyer(long ticketId, String buyer) {
        jdbc.update("UPDATE sales.ticket SET buyer_name = :b WHERE ticket_id = :id", Map.of("b", buyer, "id", ticketId));
    }

    /** An ACCEPTED quotation (payable) on CREDIT 30 days, the deal FULLY_DELIVERED {@code daysAgo} Bangkok days ago, at
     * DELIVERED (column 5), nothing paid. Due date = delivery + 30. */
    private long creditDealDelivered(int daysAgo, String paymentStatus) {
        long id = deal(salesActor);
        DealQuotationDto q = approved(id, salesActor, "DESIGNER");
        setDocStatus(q.id(), "ACCEPTED");
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = 30 WHERE quotation_id = :q",
            Map.of("q", q.id()));
        jdbc.update("""
            UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED', sales_stage = 'DELIVERED'
             WHERE ticket_id = :id""", Map.of("id", id));
        tickets.updatePaymentStatusUnchecked(id, paymentStatus);
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
            VALUES (:id, 'WAREHOUSE', :at, :by)""", new MapSqlParameterSource("id", id)
            .addValue("at", Timestamp.from(today().minusDays(daysAgo).atTime(12, 0).atZone(BKK).toInstant()))
            .addValue("by", salesActor.id()));
        return id;
    }

    private void expected(long ticketId, String kind, LocalDate week, String status) {
        Long receiptId = null;
        if ("RECEIVED".equals(status)) {
            receiptId = jdbc.queryForObject("""
                INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref)
                VALUES (:t, 'DEPOSIT', 100, :by, :ref) RETURNING receipt_id""",
                new MapSqlParameterSource("t", ticketId).addValue("by", salesActor.id())
                    .addValue("ref", "S6E-" + ticketId + "-" + System.nanoTime()), Long.class);
        }
        jdbc.update("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, status, receipt_id, created_by)
            VALUES (:t, :k, 100, :w, :s, :r, :by)""", new MapSqlParameterSource("t", ticketId).addValue("k", kind)
            .addValue("w", week).addValue("s", status).addValue("r", receiptId).addValue("by", salesActor.id()));
    }

    private static LocalDate today() {
        return LocalDate.now(BKK);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        return json.readTree(raw(request, actor));
    }

    private String raw(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MvcResult result = perform(request, actor);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return body;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, UserPrincipal actor) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(request.session(session)).andReturn();
    }

    private static List<Long> ids(JsonNode deals) {
        List<Long> out = new java.util.ArrayList<>();
        deals.forEach(d -> out.add(d.get("ticketId").asLong()));
        return out;
    }

    private static JsonNode byId(JsonNode deals, long id) {
        for (JsonNode d : deals) {
            if (d.get("ticketId").asLong() == id) {
                return d;
            }
        }
        throw new AssertionError("deal " + id + " not on the board");
    }
}
