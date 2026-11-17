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

    // ── 8: QT พนักงานขาย moves the deal owner (Ploy 2026-11-13) ──────────────────────────────────

    /** 8a: rep B on a deal A owns (no keyer) -> owner B, A becomes keyer; B reaches the deal, A still does. */
    @Test
    void create_withSalesRep_movesOwner_andOldOwnerBecomesKeyer() throws Exception {
        long ticketId = createDeal(salesActor, null);
        // A legacy deal (pre-V202 / keyed_by NULL): the old owner must become the keyer so A keeps access.
        jdbc.update("UPDATE sales.ticket SET keyed_by = NULL WHERE ticket_id = :id", Map.of("id", ticketId));

        createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(otherSalesActor.id());
        assertThat(keyedBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(send(otherSalesActor, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(salesActor, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(200);
    }

    /** 8b: draft update B -> C on a deal with keyer A: owner C, keyer A kept, B loses access. */
    @Test
    void draftUpdate_changingRep_movesOwner_keepsExistingKeyer_oldOwnerLosesAccess() throws Exception {
        long ticketId = keyedDeal(); // owner B, keyer A
        long quotationId = createQuotation(otherSalesActor, ticketId);

        MvcResult put = send(otherSalesActor, put("/api/deal-quotations/" + quotationId),
            quotationBodyWithRep(thirdRep.id()));
        assertThat(put.getResponse().getStatus()).as(body(put)).isEqualTo(200);

        assertThat(createdBy(ticketId)).isEqualTo(thirdRep.id());
        assertThat(keyedBy(ticketId)).as("existing keyer kept, B not promoted").isEqualTo(salesActor.id());
        assertThat(send(otherSalesActor, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(thirdRep, get("/api/tickets/" + ticketId), null).getResponse().getStatus()).isEqualTo(200);
    }

    /** 8c: rep already the owner -> nothing changes, nobody is notified. */
    @Test
    void salesRep_equalToOwner_changesNothing_noNotification() throws Exception {
        long ticketId = createDeal(salesActor, null);

        createQuotationWithRep(salesActor, ticketId, salesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(keyedBy(ticketId)).as("keyer untouched (a self-keyed deal has keyed_by = created_by)")
            .isEqualTo(salesActor.id());
        assertThat(ownerMoveNotifications(ticketId)).isZero();
        assertThat(employeeMails).isEmpty();
    }

    /** 8d: no rep (null/cleared) -> no move, also on a draft update. */
    @Test
    void salesRep_null_changesNothing() throws Exception {
        long ticketId = createDeal(salesActor, null);
        long quotationId = createQuotation(salesActor, ticketId);

        MvcResult put = send(salesActor, put("/api/deal-quotations/" + quotationId), quotationBody());
        assertThat(put.getResponse().getStatus()).as(body(put)).isEqualTo(200);

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(keyedBy(ticketId)).as("keyer untouched (a self-keyed deal has keyed_by = created_by)")
            .isEqualTo(salesActor.id());
    }

    /** 8e: a commission_record already exists for the deal -> owner frozen. */
    @Test
    void salesRep_withExistingCommissionRecord_changesNothing() throws Exception {
        long ticketId = createDeal(salesActor, null);
        long invoice = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES (:n, CURRENT_DATE, 1000.00, 'PRE_ERP_PAPER_APPROVAL') RETURNING invoice_id""",
            Map.of("n", "OM-INV-" + ticketId), Long.class);
        jdbc.update("""
            INSERT INTO sales.commission_record
                (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id, kind, status,
                 payroll_month, actual_received, commissionable_base)
            VALUES (:inv, :id, :rep, :rep, 'SALE', 'SUBMITTED',
                    date_trunc('month', CURRENT_DATE)::date, 1000.00, 1000.00)""",
            Map.of("inv", invoice, "id", ticketId, "rep", salesActor.id()));

        createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(ownerMoveNotifications(ticketId)).isZero();
    }

    /**
     * 8f: finished deal -> no move. Predicate: lifecycle NOT ACTIVE (CLOSED_LOST/CANCELLED/COMPLETED,
     * DealLifecycle) or status closed/cancelled (TicketStatus). The draft QT is created while the deal is live,
     * then the deal is finished, then the rep is changed on the draft. The save itself may or may not be refused;
     * only the owner is asserted.
     */
    @Test
    void salesRep_onFinishedDeal_changesNothing() throws Exception {
        String[][] finished = {{"cancelled", "CANCELLED"}, {"closed", "COMPLETED"}, {"cancelled", "CLOSED_LOST"}};
        for (String[] f : finished) {
            long ticketId = createDeal(salesActor, null);
            long quotationId = createQuotation(salesActor, ticketId);
            jdbc.update("UPDATE sales.ticket SET status = :s, lifecycle = :l WHERE ticket_id = :id",
                Map.of("s", f[0], "l", f[1], "id", ticketId));

            send(salesActor, put("/api/deal-quotations/" + quotationId), quotationBodyWithRep(otherSalesActor.id()));

            assertThat(createdBy(ticketId)).as("%s/%s", f[0], f[1]).isEqualTo(salesActor.id());
        }
    }

    /** 8g: the PCR header's salesRepDisplayId never moves the owner. */
    @Test
    void pricingRequestHeader_salesRep_doesNotMoveOwner() throws Exception {
        long ticketId = createDeal(salesActor, null);
        var item = new th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest(null, null, null,
            "SCG", "Tile Rep", "SCG Tile Rep", "White", "Matte", "60x60", "Factory Rep", null, null, null, null,
            th.co.glr.hr.pricingrequest.QuantityType.CONFIRMED, null, null, null,
            null, new java.math.BigDecimal("10"), new java.math.BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
        var request = new th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest(
            "DESIGNER", null, "Designer Co.", java.time.LocalDate.now().plusDays(14), null, "THB", "rep header",
            java.util.UUID.randomUUID().toString(), null, null, null, null, otherSalesActor.id(), null, null, null,
            List.of(item));

        pricingRequestService.createDraft(ticketId, request, salesActor);

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(keyedBy(ticketId)).as("keyer untouched (a self-keyed deal has keyed_by = created_by)")
            .isEqualTo(salesActor.id());
    }

    /** 8h: a move notifies the NEW owner (bell row + email, link to the deal); nothing before the move. */
    @Test
    void ownerMove_notifiesTheNewOwner_bellAndEmail() throws Exception {
        long ticketId = createDeal(salesActor, null);
        assertThat(ownerMoveNotifications(ticketId)).isZero();

        createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT employee_id, link FROM hr.notification WHERE type = 'DEAL_OWNER_CHANGED' AND link = :link""",
            Map.of("link", "/tickets/" + ticketId));
        assertThat(((Number) row.get("employee_id")).longValue()).isEqualTo(otherSalesActor.id());
        assertThat(employeeMails).contains(otherSalesActor.id() + "|/tickets/" + ticketId);
    }

    /** 9 (A-i): a draft that already carries the override gets an unrelated edit with the SAME rep -> no move, no notice. */
    @Test
    void draftUpdate_withUnchangedRep_doesNotMoveOwner() throws Exception {
        long ticketId = createDeal(salesActor, null);
        long quotationId = createQuotation(salesActor, ticketId); // override-free: nothing moved
        jdbc.update("UPDATE sales.quotation SET sales_rep_display_id = :r WHERE quotation_id = :id",
            Map.of("r", otherSalesActor.id(), "id", quotationId));

        MvcResult put = send(salesActor, put("/api/deal-quotations/" + quotationId),
            quotationBodyWithRep(otherSalesActor.id()));

        assertThat(put.getResponse().getStatus()).as(body(put)).isEqualTo(200);
        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(ownerMoveNotifications(ticketId)).isZero();
    }

    /** 9 (A-ii): explicitly changing the rep back to the old owner moves the deal back. */
    @Test
    void draftUpdate_changingRepBackToOldOwner_movesItBack() throws Exception {
        long ticketId = createDeal(salesActor, null);
        long quotationId = createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());
        assertThat(createdBy(ticketId)).as("precondition: moved to B").isEqualTo(otherSalesActor.id());

        MvcResult put = send(salesActor, put("/api/deal-quotations/" + quotationId),
            quotationBodyWithRep(salesActor.id()));

        assertThat(put.getResponse().getStatus()).as(body(put)).isEqualTo(200);
        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
    }

    /** 10 (C): on a move THIS quotation's sales_rep_id becomes the new owner. */
    @Test
    void ownerMove_setsTheQuotationsSalesRepToTheNewOwner() throws Exception {
        long ticketId = createDeal(salesActor, null);

        long quotationId = createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());

        assertThat(jdbc.queryForObject("SELECT sales_rep_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class)).isEqualTo(otherSalesActor.id());
    }

    /** 10b (C): the deal's OTHER live quotations follow the new owner too; dead (CANCELLED/REJECTED) ones keep the old rep. */
    @Test
    void ownerMove_carriesTheDealsOtherLiveQuotations_notTheDeadOnes() throws Exception {
        long ticketId = createDeal(salesActor, null);
        long cancelled = createQuotation(salesActor, ticketId);
        setDocStatus(cancelled, "CANCELLED");
        long rejected = createQuotation(salesActor, ticketId);
        setDocStatus(rejected, "REJECTED");
        long awaiting = createQuotation(salesActor, ticketId);
        setDocStatus(awaiting, "REJECTED"); // only so the draft below may be created; made live again next
        long draft = createQuotation(salesActor, ticketId);
        setDocStatus(awaiting, "PENDING_APPROVAL");
        // A legacy (pre-V203) document still attributed to the admin who keyed the deal, not its owner, follows too.
        jdbc.update("UPDATE sales.quotation SET sales_rep_id = :m WHERE quotation_id = :q",
            Map.of("m", salesManagerActor.id(), "q", awaiting));

        MvcResult put = send(salesActor, put("/api/deal-quotations/" + draft), quotationBodyWithRep(otherSalesActor.id()));
        assertThat(put.getResponse().getStatus()).as(body(put)).isEqualTo(200);

        assertThat(salesRepOf(draft)).isEqualTo(otherSalesActor.id());
        assertThat(salesRepOf(awaiting)).as("live QT-1 follows the owner").isEqualTo(otherSalesActor.id());
        assertThat(salesRepOf(cancelled)).as("cancelled keeps the old rep").isEqualTo(salesActor.id());
        assertThat(salesRepOf(rejected)).as("rejected keeps the old rep").isEqualTo(salesActor.id());
    }

    /** 11 (D): a move leaves an audit row in the deal's timeline naming the actor and the new owner. */
    @Test
    void ownerMove_writesATicketEvent() throws Exception {
        long ticketId = createDeal(salesActor, null);

        createQuotationWithRep(salesActor, ticketId, otherSalesActor.id());

        Map<String, Object> ev = jdbc.queryForMap("""
            SELECT actor_id, message FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'EDITED' AND message LIKE '%เจ้าของงาน%'""", Map.of("id", ticketId));
        assertThat(((Number) ev.get("actor_id")).longValue()).isEqualTo(salesActor.id());
        assertThat((String) ev.get("message")).contains("พนักงานขาย S2 →").contains("→ พนักงานขาย S2 อื่น");
    }

    /** 12 (E): the keyer naming THEMSELVES moves the deal to them but they are not notified of their own action. */
    @Test
    void ownerMove_toTheActor_doesNotNotifyThem() throws Exception {
        long ticketId = keyedDeal(); // owner B, keyer A

        createQuotationWithRep(salesActor, ticketId, salesActor.id());

        assertThat(createdBy(ticketId)).isEqualTo(salesActor.id());
        assertThat(ownerMoveNotifications(ticketId)).isZero();
        assertThat(employeeMails).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private long salesRepOf(long quotationId) {
        return jdbc.queryForObject("SELECT sales_rep_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
    }

    private int ownerMoveNotifications(long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE type = 'DEAL_OWNER_CHANGED' AND link = :l",
            Map.of("l", "/tickets/" + ticketId), Integer.class);
    }

    private long createQuotationWithRep(UserPrincipal actor, long ticketId, long repId) throws Exception {
        MvcResult result = send(actor, post("/api/tickets/" + ticketId + "/deal-quotations"), quotationBodyWithRep(repId));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return read(result).get("quotation").get("id").asLong();
    }

    private static String quotationBodyWithRep(long repId) {
        return quotationBody("OWNER").replace("\"salesRepDisplayId\":null", "\"salesRepDisplayId\":" + repId);
    }

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
