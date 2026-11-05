package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.ticket.TicketController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deal OWNER vs KEYER (owner decision, Ploy 2026-11-05). Admin staff key deals for other reps, so
 * {@code POST /api/tickets} gains an optional {@code ownerEmployeeId}: {@code sales.ticket.created_by}
 * becomes the chosen owner (the rep who sells it, earns the commission and is notified) and the new
 * nullable {@code sales.ticket.keyed_by} records the actor who typed it in. The keyer ALSO passes every
 * owner check (open, list, quotations, stage moves); a third rep still does not.
 *
 * <p>Same wiring as {@link DealQuotationSlice2HttpIntegrationTest}: real controllers + services + Postgres
 * over MockMvc, requests are the JSON the frontend sends. {@code ownerEmployeeId} is sent as RAW JSON so
 * that, before the feature exists, every failure here is an assertion (the field is ignored, the deal
 * lands on the keyer) and not a compile error. {@code keyed_by} is read straight from the table, so the
 * missing column surfaces as a plain SQL error until the migration exists.
 *
 * <p>Every test that needs a keyed deal builds it through {@link #keyedDeal()}, which first pins the
 * precondition {@code created_by = owner}; without it the keyer-side tests would pass vacuously today
 * (the keyer IS the creator on current code).
 */
class DealOwnerKeyerHttpIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    /** keyer = salesActor (A), owner = otherSalesActor (B), thirdRep = C. */
    private UserPrincipal thirdRep;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        mvc = MockMvcBuilders
            .standaloneSetup(new TicketController(ticketService, sessions),
                new DealQuotationController(quotationService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        thirdRep = actor(createEmployee("พนักงานขาย S2 ที่สาม", "sales-s2-third@glr.co.th", "SALES",
            "แผนกขาย", null), "sales");
        // The base fixtures label the sales division "SALES", which is not the real sales-division code
        // ("SA", DivisionAccessPolicy.SALES_DIVISION_CODE) the owner-eligibility rule reads. Relabel it.
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
    }

    // ── 1/2: create ──────────────────────────────────────────────────────────────────────────────

    /** Scenario 1a: A keys the deal and names B as owner -> created_by = B. */
    @Test
    void create_withOwner_storesTheOwnerAsCreatedBy() throws Exception {
        long ticketId = createDeal(salesActor, otherSalesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(otherSalesActor.id());
    }

    /** Scenario 1b: ...and keyed_by = A, the actor who typed it in (red = SQL error until the column exists). */
    @Test
    void create_withOwnerDifferentFromActor_recordsTheActorAsKeyedBy() throws Exception {
        long ticketId = createDeal(salesActor, otherSalesActor.id());

        assertThat(keyedBy(ticketId)).isEqualTo(salesActor.id());
    }

    /** Scenario 2 (regression, green today and must stay green): no ownerEmployeeId -> created_by = actor. */
    @Test
    void create_withoutOwner_ownsTheDealItself() throws Exception {
        long ticketId = createDeal(salesActor, null);

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
    }

    /** Scenario 2b: an explicit owner equal to the actor behaves like no owner (keyed_by is not pinned). */
    @Test
    void create_withOwnerEqualToActor_ownsTheDealItself() throws Exception {
        long ticketId = createDeal(salesActor, salesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
    }

    // ── 3: owner validation ──────────────────────────────────────────────────────────────────────

    /** Scenario 3: owner must be an ACTIVE employee who can enter deals; else 400 and NOTHING inserted. */
    @Test
    void create_withAnOwnerWhoCannotOwnADeal_is400_andInsertsNothing() throws Exception {
        long inactiveSales = createEmployee("พนักงานขาย ลาออก", "sales-resigned@glr.co.th", "SALES", "แผนกขาย", null);
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id", Map.of("id", inactiveSales));
        long accountEmployee = createEmployee("บัญชี เจ้าของไม่ได้", "acct-owner@glr.co.th", "ACCT", "ฝ่ายบัญชี", null);
        long hrEmployee = createEmployee("ฝ่ายบุคคล เจ้าของไม่ได้", "hr-owner@glr.co.th", "OTHER", "ฝ่ายอื่น", null);
        long nonexistent = 987_654_321L;

        for (long badOwner : List.of(inactiveSales, accountEmployee, hrEmployee, nonexistent)) {
            int before = ticketCount();

            MvcResult result = send(salesActor, post("/api/tickets"), createBody(badOwner));

            assertThat(result.getResponse().getStatus()).as("ownerEmployeeId=%d: %s", badOwner, body(result))
                .isEqualTo(400);
            assertThat(read(result).get("message").asText()).as("a Thai refusal message").isNotBlank();
            assertThat(ticketCount()).as("no ticket inserted for ownerEmployeeId=%d", badOwner).isEqualTo(before);
        }
    }

    /** Scenario 3b (positive side of the rule): a non-sales employee holding the can_create_quotation grant is a valid owner. */
    @Test
    void create_withAGrantedQcOwner_isAccepted() throws Exception {
        long qc = createEmployee("QC มีสิทธิ์", "qc-owner@glr.co.th", "QC", "ฝ่าย QC", null);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qc));

        long ticketId = createDeal(salesActor, qc);

        assertThat(createdBy(ticketId)).isEqualTo(qc);
    }

    // ── 4: owner B ───────────────────────────────────────────────────────────────────────────────

    /** Scenario 4: the owner sees the deal in the deal list AND the quotation list, and can open it. */
    @Test
    void owner_seesTheDealInBothLists_andCanOpenIt() throws Exception {
        long ticketId = keyedDeal();
        long quotationId = createQuotation(otherSalesActor, ticketId);

        assertThat(dealListIds(otherSalesActor)).contains(ticketId);
        assertThat(quotationListIds(otherSalesActor)).contains(quotationId);
        assertThat(send(otherSalesActor, get("/api/tickets/" + ticketId), null).getResponse().getStatus())
            .isEqualTo(200);
    }

    /** Scenario 4b: the owner (not only the keyer) moves stages. */
    @Test
    void owner_canMoveTheDealStage() throws Exception {
        long ticketId = keyedDeal();

        MvcResult result = send(otherSalesActor, post("/api/tickets/" + ticketId + "/stage"),
            "{\"stage\":\"PRESENTATION\"}");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    // ── 5: keyer A ───────────────────────────────────────────────────────────────────────────────

    /** Scenario 5a: the keyer still sees the deal in the deal list and can open it. */
    @Test
    void keyer_seesTheDealInTheDealList_andCanOpenIt() throws Exception {
        long ticketId = keyedDeal();

        assertThat(dealListIds(salesActor)).contains(ticketId);
        assertThat(send(salesActor, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(200);
    }

    /** Scenario 5b: the keyer creates, edits and submits a deal quotation on it, and sees it in the quotation list. */
    @Test
    void keyer_canCreateEditSubmitAQuotation_andSeesItInTheQuotationList() throws Exception {
        long ticketId = keyedDeal();

        long quotationId = createQuotation(salesActor, ticketId);
        MvcResult edit = send(salesActor, put("/api/deal-quotations/" + quotationId), quotationBody());
        MvcResult submit = send(salesActor, post("/api/deal-quotations/" + quotationId + "/submit"), null);

        assertThat(edit.getResponse().getStatus()).as(body(edit)).isEqualTo(200);
        assertThat(submit.getResponse().getStatus()).as(body(submit)).isEqualTo(200);
        assertThat(quotationListIds(salesActor)).contains(quotationId);
    }

    /** Scenario 5c: the keyer moves stages. */
    @Test
    void keyer_canMoveTheDealStage() throws Exception {
        long ticketId = keyedDeal();

        MvcResult result = send(salesActor, post("/api/tickets/" + ticketId + "/stage"),
            "{\"stage\":\"PRESENTATION\"}");

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    // ── 6: third rep C ───────────────────────────────────────────────────────────────────────────

    /** Scenario 6: neither owner nor keyer -> not listed, 403 on GET, quotation create, and stage move. */
    @Test
    void thirdRep_neverSeesNorTouchesTheDeal() throws Exception {
        long ticketId = keyedDeal();
        long quotationId = createQuotation(otherSalesActor, ticketId);

        assertThat(dealListIds(thirdRep)).doesNotContain(ticketId);
        assertThat(quotationListIds(thirdRep)).doesNotContain(quotationId);
        assertThat(send(thirdRep, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(thirdRep, get("/api/deal-quotations/" + quotationId), null).getResponse().getStatus())
            .isEqualTo(403);
        assertThat(send(thirdRep, post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("OWNER"))
            .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(thirdRep, post("/api/tickets/" + ticketId + "/stage"), "{\"stage\":\"PRESENTATION\"}")
            .getResponse().getStatus()).isEqualTo(403);
    }

    // ── 7: attribution follows the owner ─────────────────────────────────────────────────────────

    /**
     * Scenario 7. Cheapest seam: {@code ticket.summary.createdById} is the single value
     * {@code CommissionService} (salesRepId) and every {@code notifyEmployee(s.createdById(), ...)} read, so
     * pinning it to the OWNER (and not the keyer) pins commission + notification attribution without driving
     * a deal to CLOSED_PAID. Read the way the frontend reads it: {@code r.ticket.summary.*}.
     */
    @Test
    void ticketSummary_attributesTheDealToTheOwner_notTheKeyer() throws Exception {
        long ticketId = keyedDeal();

        JsonNode summary = read(send(otherSalesActor, get("/api/tickets/" + ticketId), null))
            .get("ticket").get("summary");

        assertThat(summary.get("createdById").asLong()).isEqualTo(otherSalesActor.id());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A keys a deal owned by B, after pinning that B really is the owner (see class Javadoc). */
    private long keyedDeal() throws Exception {
        long ticketId = createDeal(salesActor, otherSalesActor.id());
        assertThat(createdBy(ticketId)).as("precondition: created_by = the chosen owner").isEqualTo(otherSalesActor.id());
        return ticketId;
    }

    private long createDeal(UserPrincipal keyer, Long ownerEmployeeId) throws Exception {
        MvcResult result = send(keyer, post("/api/tickets"), createBody(ownerEmployeeId));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return read(result).get("ticket").get("summary").get("id").asLong();
    }

    private String createBody(Long ownerEmployeeId) {
        String owner = ownerEmployeeId == null ? "" : ",\"ownerEmployeeId\":" + ownerEmployeeId;
        return """
            {"title":"%s","customerName":"%s","customerId":%d,"projectId":%d,"contactId":null,
             "entryChannel":"OWNER_DIRECT","priority":"NORMAL","items":[],"nextFollowUpAt":"2026-12-31",
             "quotationOnly":true%s}
            """.formatted(customer.name(), customer.name(), customer.id(), projectId, owner);
    }

    private long createQuotation(UserPrincipal actor, long ticketId) throws Exception {
        MvcResult result = send(actor, post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("OWNER"));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return read(result).get("quotation").get("id").asLong();
    }

    private List<Long> dealListIds(UserPrincipal actor) throws Exception {
        List<Long> ids = new ArrayList<>();
        read(send(actor, get("/api/tickets?size=200"), null)).get("tickets")
            .forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    private List<Long> quotationListIds(UserPrincipal actor) throws Exception {
        List<Long> ids = new ArrayList<>();
        read(send(actor, get("/api/deal-quotations"), null)).get("items").forEach(q -> ids.add(q.get("id").asLong()));
        return ids;
    }

    private long createdBy(long ticketId) {
        return jdbc.queryForObject("SELECT created_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
    }

    private Long keyedBy(long ticketId) {
        return jdbc.queryForObject("SELECT keyed_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), Long.class);
    }

    private int ticketCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales.ticket", Map.of(), Integer.class);
    }

    private static String quotationBody() {
        return quotationBody("OWNER");
    }

    /** Same body {@code DealQuotationSlice2HttpIntegrationTest} sends (QuotationEditorPage#buildUpsertPayload). */
    private static String quotationBody(String recipientType) {
        return """
            {"contactId":null,"deptCode":"P003","unitCode":"D002","offerDate":"2026-09-30","depositPercent":50,
             "remainderMode":"ON_DELIVERY","creditDays":null,"validityDays":30,"validityMode":"DAYS",
             "validityUntil":null,"customerNotes":null,"priceMode":"NET","documentLanguage":"TH","currency":"THB",
             "printedByDisplayId":null,"salesRepDisplayId":null,"projectName":null,"omitContactHonorific":false,
             "orderedByName":null,
             "items":[{"lineType":"TILE","brand":"Pietre Di Sardegna","model":"Pietre Di Sardegna",
                       "color":"Punta Molara","texture":"R11","sizeText":"60x120","thicknessMm":2,
                       "sqmPerPiece":0.72,"quantityMode":"AREA","areaSqm":120,"wastageMode":"PERCENT",
                       "wastageValue":10,"piecesPerBox":2,"unitPrice":1748.13,"discountPct":0,
                       "originCountry":"อิตาลี","leadTimeMinDays":75,"leadTimeMaxDays":90,"locationLabel":null}],
             "recipientType":"%s"}
            """.formatted(recipientType);
    }

    private MvcResult send(UserPrincipal actor, MockHttpServletRequestBuilder request, String jsonBody)
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        request.session(session);
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(body(result));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
