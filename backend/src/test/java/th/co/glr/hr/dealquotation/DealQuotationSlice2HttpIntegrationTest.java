package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import th.co.glr.hr.pricingrequest.PricingRequestController;
import th.co.glr.hr.ticket.DealStageMetaController;
import th.co.glr.hr.ticket.TicketController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quotation ↔ deal linking slice 2 — the API wiring PR #1090 codes against, proven over HTTP: the
 * REAL {@link TicketController}, {@link DealQuotationController}, {@link PricingRequestController} and
 * {@link DealStageMetaController}, over the REAL services and real Postgres, through the SAME
 * Jackson-3 HTTP converter production uses and the real {@link ApiExceptionHandler}. Requests are the
 * JSON the frontend sends ({@code QuotationEditorPage#buildUpsertPayload} /
 * {@code #handleInlineCreate}); assertions read the JSON back the way the frontend reads it
 * ({@code r.quotation.*}, {@code r.ticket.summary.*}, {@code r.tickets[*]}, {@code r.items[*]}, and
 * for an error {@code ApiError.details} = the whole body, {@code frontend/src/api/client.js}).
 *
 * <p>The live pricing request is created through {@code POST /api/tickets/{id}/pricing-requests} with
 * the same body {@code frontend/e2e-real/write-quotation-deal-link.spec.js} sends, so a green run here
 * is also evidence that the e2e spec's fixture is accepted by the real service.
 */
class DealQuotationSlice2HttpIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private MockHttpSession salesSession;

    @BeforeEach
    void wireHttp() {
        SessionContext sessions = new SessionContext();
        mvc = MockMvcBuilders
            .standaloneSetup(new TicketController(ticketService, sessions),
                new DealQuotationController(quotationService, sessions),
                new PricingRequestController(pricingRequestService, sessions),
                new DealStageMetaController(sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
                .build()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        salesSession = sessionFor(salesActor);
    }

    // ── create ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Owner rule of 2026-10-05 (M2): creating a direct quotation for a recipient puts the deal on that
     * recipient's stage (DESIGNER → ขั้น 4). The body the editor reads carries the deal's CURRENT stage,
     * so the {@code dealStage} it shows right after the create is QUOTE_DESIGN_SIDE — it used to be
     * LEAD_APPROACH, the stage the deal was on before the quotation existed — and the deal itself, read
     * back over {@code GET /api/tickets/{id}}, is on the same stage. The stage assertions come last so the
     * rest of what the editor reads is checked whatever the stage does.
     */
    @Test
    void create_withARecipient_is201_andTheBodyCarriesWhatTheEditorReads() throws Exception {
        long ticketId = createDealOverHttp("DESIGNER_LED");

        MvcResult result = send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("DESIGNER"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode q = read(result).get("quotation");
        assertThat(q.get("recipientType").asText()).isEqualTo("DESIGNER");
        assertThat(q.get("recipientLabel").asText()).isEqualTo("ผู้ออกแบบ");
        assertThat(q.get("origin").asText()).isEqualTo("DEAL_DIRECT");
        assertThat(q.get("docStatus").asText()).isEqualTo("DRAFT");
        assertThat(q.get("ticketId").asLong()).isEqualTo(ticketId);
        assertThat(q.get("ticketCode").asText()).startsWith("PR-");
        assertThat(q.get("id").isIntegralNumber()).isTrue();
        assertThat(q.get("number").asText()).startsWith("QT-");
        JsonNode dealAfter = read(send(get("/api/tickets/" + ticketId), null)).get("ticket").get("summary");
        assertAll(
            () -> assertThat(q.get("dealStage").asText())
                .as("dealStage in the create response, once the quotation document exists").isEqualTo("QUOTE_DESIGN_SIDE"),
            () -> assertThat(dealAfter.get("salesStage").asText())
                .as("the deal's own stage, read back over GET /api/tickets/{id}").isEqualTo("QUOTE_DESIGN_SIDE"));
    }

    @Test
    void create_withoutARecipient_is400_withExactlyMessageAndStatus() throws Exception {
        long ticketId = createDealOverHttp("OWNER_DIRECT");

        MvcResult result = send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody(null));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).isEqualTo("{\"message\":\"ต้องระบุผู้รับใบเสนอราคา\",\"status\":400}");
    }

    @Test
    void create_withUnspecified_is400() throws Exception {
        long ticketId = createDealOverHttp("OWNER_DIRECT");

        MvcResult result = send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("UNSPECIFIED"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result))
            .isEqualTo("{\"message\":\"ไม่รองรับผู้รับใบเสนอราคา 'UNSPECIFIED'\",\"status\":400}");
    }

    /** N6 over the wire: the three fields sit at the TOP level, beside message/status — which is where
     * {@code error.details.liveQuotationId} (details = the whole body) finds them. */
    @Test
    void n6_is409_andTheBodyCarriesLiveQuotationIdNumberAndDocStatusAtTopLevel() throws Exception {
        long ticketId = createDealOverHttp("OWNER_DIRECT");
        JsonNode first = read(send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("OWNER")))
            .get("quotation");

        MvcResult result = send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("OWNER"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        JsonNode error = read(result);
        assertThat(fieldNames(error)).containsExactly("message", "status", "liveQuotationId", "number", "docStatus");
        assertThat(error.get("status").asInt()).isEqualTo(409);
        assertThat(error.get("liveQuotationId").isIntegralNumber()).as("a number, not a string").isTrue();
        assertThat(error.get("liveQuotationId").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(error.get("number").asText()).isEqualTo(first.get("number").asText());
        assertThat(error.get("docStatus").asText()).isEqualTo("DRAFT");
        assertThat(error.get("message").asText()).isEqualTo("ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ ("
            + first.get("number").asText() + ") — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่");
    }

    @Test
    void aLivePricingRequestCreatedOverHttp_isListed_andRefusesADirectQuotation409() throws Exception {
        long ticketId = createDealOverHttp("DESIGNER_LED");
        MvcResult pr = send(post("/api/tickets/" + ticketId + "/pricing-requests"), pricingRequestBody());
        assertThat(pr.getResponse().getStatus()).as(body(pr)).isEqualTo(201);

        // What DealPicker / TicketDetailPage read: r.items[*].ticketId + .status.
        JsonNode items = read(send(get("/api/tickets/" + ticketId + "/pricing-requests"), null)).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("ticketId").asLong()).isEqualTo(ticketId);
        assertThat(items.get(0).get("status").asText()).isEqualTo("DRAFT");

        MvcResult refused = send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("DESIGNER"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused)).isEqualTo("{\"message\":\"ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — "
            + "ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน\",\"status\":409}");
    }

    // ── update ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void update_recipientOnADraft_is200_andOnAPendingApprovalQuotation_is409() throws Exception {
        long ticketId = createDealOverHttp("OWNER_DIRECT");
        long id = read(send(post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBody("OWNER")))
            .get("quotation").get("id").asLong();

        MvcResult changed = send(put("/api/deal-quotations/" + id), quotationBody("BUYER"));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(changed).get("quotation").get("recipientType").asText()).isEqualTo("BUYER");

        assertThat(send(post("/api/deal-quotations/" + id + "/submit"), "{}").getResponse().getStatus()).isEqualTo(200);
        MvcResult refused = send(put("/api/deal-quotations/" + id), quotationBody("DESIGNER"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(fieldNames(read(refused))).containsExactly("message", "status");
        assertThat(read(send(get("/api/deal-quotations/" + id), null)).get("quotation").get("recipientType").asText())
            .isEqualTo("BUYER");
    }

    // ── tickets: get / list ──────────────────────────────────────────────────────────────────────

    @Test
    void ticketGetAndList_carryLiveDirectQuotation_andEveryOtherFieldTheUiReads() throws Exception {
        long withQuotation = createDealOverHttp("BUYER_DIRECT");
        long without = createDealOverHttp("OWNER_DIRECT");
        JsonNode q = read(send(post("/api/tickets/" + withQuotation + "/deal-quotations"), quotationBody("BUYER")))
            .get("quotation");

        JsonNode summary = read(send(get("/api/tickets/" + withQuotation), null)).get("ticket").get("summary");
        JsonNode live = summary.get("liveDirectQuotation");
        assertThat(fieldNames(live)).containsExactlyInAnyOrder("id", "number", "docStatus", "recipientType");
        assertThat(live.get("id").asLong()).isEqualTo(q.get("id").asLong());
        assertThat(live.get("number").asText()).isEqualTo(q.get("number").asText());
        assertThat(live.get("docStatus").asText()).isEqualTo("DRAFT");
        assertThat(live.get("recipientType").asText()).isEqualTo("BUYER");
        // S2-B5: the summary fields the slice-2 UI reads are all served.
        for (String field : List.of("status", "entryChannel", "salesStage", "lifecycle", "createdById", "code",
                "customerName")) {
            assertThat(summary.hasNonNull(field)).as(field).isTrue();
        }
        assertThat(summary.get("entryChannel").asText()).isEqualTo("BUYER_DIRECT");
        assertThat(summary.get("createdById").asLong()).isEqualTo(salesActor.id());

        JsonNode noneSummary = read(send(get("/api/tickets/" + without), null)).get("ticket").get("summary");
        assertThat(noneSummary.has("liveDirectQuotation")).as("served as an explicit null").isTrue();
        assertThat(noneSummary.get("liveDirectQuotation").isNull()).isTrue();

        JsonNode rows = read(send(get("/api/tickets"), null)).get("tickets");
        JsonNode listed = null;
        JsonNode listedNone = null;
        for (JsonNode row : rows) {
            if (row.get("id").asLong() == withQuotation) listed = row;
            if (row.get("id").asLong() == without) listedNone = row;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("liveDirectQuotation")).isEqualTo(live);
        assertThat(listedNone.get("liveDirectQuotation").isNull()).isTrue();
    }

    @Test
    void metaDealStages_stillServesRoutes() throws Exception {
        JsonNode meta = read(send(get("/api/meta/deal-stages"), null));
        assertThat(meta.has("routes")).isTrue();
        assertThat(fieldNames(meta.get("routes"))).contains("DESIGNER_LED", "OWNER_DIRECT", "BUYER_DIRECT");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** {@code QuotationEditorPage#handleInlineCreate}'s own {@code api.tickets.create} body. */
    private long createDealOverHttp(String entryChannel) throws Exception {
        MvcResult result = send(post("/api/tickets"), """
            {"title":"%s","customerName":"%s","customerId":%d,"projectId":%d,"contactId":null,
             "entryChannel":"%s","priority":"NORMAL","items":[],"nextFollowUpAt":"2026-12-31","quotationOnly":true}
            """.formatted(customer.name(), customer.name(), customer.id(), projectId, entryChannel));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return read(result).get("ticket").get("summary").get("id").asLong();
    }

    /** {@code QuotationEditorPage#buildUpsertPayload}'s shape; {@code recipientType} OMITTED (never
     * null) when there is none, exactly as that method spreads it. */
    private static String quotationBody(String recipientType) {
        String recipient = recipientType == null ? "" : ",\"recipientType\":\"" + recipientType + "\"";
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
                       "originCountry":"อิตาลี","leadTimeMinDays":75,"leadTimeMaxDays":90,"locationLabel":null}]
            """ + recipient + "}";
    }

    /** The body frontend/e2e-real/write-quotation-deal-link.spec.js sends to open a live คำขอราคา. */
    private static String pricingRequestBody() {
        return """
            {"recipientType":"DESIGNER","recipientLabel":"คุณออกแบบ (e2e)","targetCurrency":"THB",
             "clientRequestId":"%s",
             "items":[{"brand":"E2E","model":"Slice2 Model","productDescription":"slice 2 one-route e2e",
                       "color":"White","texture":"Matte","size":"60x60","thicknessMm":9,"sqmPerPiece":0.36,
                       "piecesPerBox":4,"quantityMode":"AREA","areaSqm":20,"quantityType":"CONFIRMED",
                       "originCountry":"อิตาลี","leadTimeMinDays":30,"leadTimeMaxDays":45}]}
            """.formatted(UUID.randomUUID());
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String jsonBody) throws Exception {
        request.session(salesSession);
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

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static MockHttpSession sessionFor(UserPrincipal user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, user);
        return session;
    }
}
