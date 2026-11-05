package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;
import th.co.glr.hr.ticket.TicketController;
import tools.jackson.databind.json.JsonMapper;

/**
 * V203 data migration: deals keyed by admin staff before V202 get re-owned to the sales rep their latest live
 * quotation names (sales_rep_display_id), the admin becoming keyed_by. Flyway already ran at startup, so each test
 * seeds data and then executes the V203 file's SQL from the classpath.
 */
class DealOwnerReconcileMigrationIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private static final String V203 = "db/migration/V203__reconcile_deal_owner_from_quotation_rep.sql";

    private final ObjectMapper json = new ObjectMapper();
    /** admin = salesActor (creates + keys every deal), B = otherSalesActor (eligible), C = repC (eligible). */
    private UserPrincipal repC;
    private MockMvc mvc;

    @BeforeEach
    void seedEligibility() {
        repC = actor(createEmployee("พนักงานขาย ซี", "rep-c-v203@glr.co.th", "SALES", "แผนกขาย", null), "sales");
        // Base fixtures label the sales division "SALES"; the real code the eligibility rule reads is "SA".
        jdbc.update("UPDATE hr.division SET source_code = 'SA' WHERE source_code = 'SALES'", Map.of());
        SessionContext sessions = new SessionContext();
        mvc = MockMvcBuilders.standaloneSetup(new TicketController(ticketService, sessions))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** 1. Admin-keyed deal whose latest quotation names eligible B -> owner B, keyer admin. */
    @Test
    void adminKeyedDeal_latestQuotationNamesEligibleRep_reownsToThatRep() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");

        migrate();

        assertOwnerKeyer(ticket, otherSalesActor.id(), salesActor.id());
    }

    /** 2. Self-keyed deal: display rep null, or equal to the creator -> unchanged. */
    @Test
    void selfKeyedDeal_displayRepNullOrCreator_isUnchanged() {
        long nullRep = legacyDeal(salesActor);
        quote(nullRep, salesActor, null, "DRAFT");
        long selfRep = legacyDeal(salesActor);
        quote(selfRep, salesActor, salesActor.id(), "DRAFT");

        migrate();

        assertUnchanged(nullRep);
        assertUnchanged(selfRep);
    }

    /** 3. Latest quotation names an ineligible employee (inactive / account division) -> unchanged. */
    @Test
    void ineligibleDisplayRep_inactiveOrAccountDivision_isUnchanged() {
        long inactive = createEmployee("พนักงานขาย ลาออก v203", "resigned-v203@glr.co.th", "SALES", "แผนกขาย", null);
        jdbc.update("UPDATE hr.employee SET is_active = FALSE WHERE employee_id = :id", Map.of("id", inactive));
        long account = createEmployee("บัญชี v203", "acct-v203@glr.co.th", "ACCT", "ฝ่ายบัญชี", null);
        long t1 = legacyDeal(salesActor);
        quote(t1, salesActor, inactive, "DRAFT");
        long t2 = legacyDeal(salesActor);
        quote(t2, salesActor, account, "DRAFT");

        migrate();

        assertUnchanged(t1);
        assertUnchanged(t2);
    }

    /** 4. Older quotation names C, latest names B -> owner B. */
    @Test
    void mixedRevisions_latestQuotationWins() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, repC.id(), "SUPERSEDED");
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");

        migrate();

        assertOwnerKeyer(ticket, otherSalesActor.id(), salesActor.id());
    }

    /** 5. Latest quotation CANCELLED -> the previous live one (names B) decides. */
    @Test
    void cancelledLatestQuotation_isSkipped_previousOneDecides() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "SUPERSEDED");
        quote(ticket, salesActor, repC.id(), "CANCELLED");

        migrate();

        assertOwnerKeyer(ticket, otherSalesActor.id(), salesActor.id());
    }

    /** 6. Running it twice leaves exactly what running it once did (keyed_by is not overwritten by a second pass). */
    @Test
    void runningTwice_changesNothingTheSecondTime() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");

        migrate();
        migrate();

        assertOwnerKeyer(ticket, otherSalesActor.id(), salesActor.id());
    }

    /** 8. A deal already keyed through the V202 owner picker (keyed_by set) is never re-owned. */
    @Test
    void dealAlreadyKeyedViaOwnerPicker_isUnchanged() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");
        jdbc.update("UPDATE sales.ticket SET keyed_by = created_by WHERE ticket_id = :id", Map.of("id", ticket));

        migrate();

        assertOwnerKeyer(ticket, salesActor.id(), salesActor.id());
    }

    /** 9. A deal that already has a commission record keeps its owner, so commission and deal stay aligned. */
    @Test
    void dealWithCommissionRecord_isUnchanged() {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");
        Number invoiceId = jdbc.queryForObject("""
            INSERT INTO sales.invoice_details (invoice_number, invoice_date, gross_amount, evidence_provenance)
            VALUES ('INV-RECONCILE', CURRENT_DATE, 1000, 'PRE_ERP_PAPER_APPROVAL') RETURNING invoice_id
            """, Map.of(), Number.class);
        jdbc.update("""
            INSERT INTO sales.commission_record (invoice_id, source_ticket_id, sales_rep_id, submitted_by_id,
                payroll_month, actual_received, commissionable_base)
            VALUES (:inv, :ticket, :rep, :rep, date_trunc('month', CURRENT_DATE)::date, 1000, 1000)
            """, Map.of("inv", invoiceId.longValue(), "ticket", ticket, "rep", salesActor.id()));

        migrate();

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT created_by, keyed_by FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticket));
        assertThat(((Number) row.get("created_by")).longValue()).as("created_by stays the commissioned rep")
            .isEqualTo(salesActor.id());
        assertThat(row.get("keyed_by")).as("keyed_by untouched").isNull();
    }

    /** 7. After reconcile B lists + opens the deal over HTTP, and the admin (keyer) can still open it. */
    @Test
    void afterReconcile_ownerSeesIt_andKeyerStillOpensIt() throws Exception {
        long ticket = legacyDeal(salesActor);
        quote(ticket, salesActor, otherSalesActor.id(), "DRAFT");

        migrate();

        JsonNode list = json.readTree(body(send(otherSalesActor, "/api/tickets?size=200")));
        List<Long> ids = new ArrayList<>();
        list.get("tickets").forEach(t -> ids.add(t.get("id").asLong()));
        assertThat(ids).contains(ticket);
        assertThat(send(salesActor, "/api/tickets/" + ticket).getResponse().getStatus()).isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A deal shaped like prod's pre-V202 rows: created by the admin, keyed_by NULL. */
    private long legacyDeal(UserPrincipal creator) {
        long id = deal(creator);
        jdbc.update("UPDATE sales.ticket SET keyed_by = NULL WHERE ticket_id = :id", Map.of("id", id));
        return id;
    }

    /** Creates a quotation on the deal through the service, then pins its status and display rep. */
    private void quote(long ticketId, UserPrincipal creator, Long salesRepDisplayId, String docStatus) {
        long id = quotationService.create(ticketId, draft("OWNER"), creator).id();
        MapSqlParams.update(jdbc, id, salesRepDisplayId, docStatus);
    }

    private void migrate() {
        ClassPathResource resource = new ClassPathResource(V203);
        assertThat(resource.exists()).as("migration file %s must exist on the classpath", V203).isTrue();
        try {
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.getJdbcOperations().execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Untouched legacy row: still owned by the admin, keyed_by still NULL. */
    private void assertUnchanged(long ticketId) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT created_by, keyed_by FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId));
        assertThat(((Number) row.get("created_by")).longValue()).as("created_by unchanged").isEqualTo(salesActor.id());
        assertThat(row.get("keyed_by")).as("keyed_by untouched").isNull();
    }

    private void assertOwnerKeyer(long ticketId, long owner, long keyer) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT created_by, keyed_by FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId));
        assertThat(((Number) row.get("created_by")).longValue()).as("created_by (owner)").isEqualTo(owner);
        assertThat(((Number) row.get("keyed_by")).longValue()).as("keyed_by (keyer)").isEqualTo(keyer);
    }

    private MvcResult send(UserPrincipal actor, String url) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY, actor);
        return mvc.perform(get(url).session(session)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static final class MapSqlParams {
        static void update(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc, long id,
                           Long rep, String status) {
            jdbc.update("UPDATE sales.quotation SET sales_rep_display_id = :rep, doc_status = :s WHERE quotation_id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("rep", rep).addValue("s", status).addValue("id", id));
        }
    }
}
