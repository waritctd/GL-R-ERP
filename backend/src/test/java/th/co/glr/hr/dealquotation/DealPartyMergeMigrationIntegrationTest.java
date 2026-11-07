package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * V205 and V206 (docs/decisions/deal-party-model.md, slice 4): the sibling-bid backlog folds into one deal per rep
 * and project, but ONLY when every ticket in the group is a pure quotation-only container. V205 needs one owner,
 * one keyer and two customers; V206 relaxes that to "one rep on every quotation" (an admin-typed deal, a second
 * keyer, a customer quoted twice). Runs each file verbatim, twice, against data built through the real services.
 */
class DealPartyMergeMigrationIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {
    private static final String V205 = "db/migration/V205__merge_sibling_bid_deals.sql";
    private static final String V206 = "db/migration/V206__merge_sibling_deals_same_rep.sql";
    private static final String SITE = "โครงการ ประมูลหลายราย";

    private TicketRepository tickets;
    private CustomerRepository customers;
    private ProjectRepository projects;
    private UserPrincipal otherRep;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        customers = new CustomerRepository(jdbc);
        projects = new ProjectRepository(jdbc);
        otherRep = actor(createEmployee(new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc)), "พนักงานขาย อีกทีม", "sales-merge-other@glr.co.th", "SALES", "แผนกขาย",
            null), "sales");
    }

    @Test
    void containersOfOneRepAndProject_foldIntoTheFirstBid_andARerunChangesNothing() {
        CustomerDto a = customer("บจก.ผู้รับเหมา เอ");
        CustomerDto b = customer("บจก.ผู้รับเหมา บี");
        long t1 = container(a, "  " + SITE.toUpperCase(), salesActor);   // same name_key, different spelling
        long t2 = container(b, SITE, salesActor);
        long t3 = container(a, SITE, salesActor);                       // A bid twice: folds into A's party
        long q1 = quote(t1);
        long q2 = quote(t2);
        long q3 = quote(t3);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'QUOTE_BUYER' WHERE ticket_id = :id", Map.of("id", t2));

        migrate();
        assertMerged(t1, t2, t3, q1, q2, q3, a, b);
        migrate();
        assertMerged(t1, t2, t3, q1, q2, q3, a, b);
    }

    @Test
    void aGroupWithAnOrdinaryDeal_isLeftWhole() {
        CustomerDto a = customer("บจก.ผู้รับเหมา ซี");
        CustomerDto b = customer("บจก.ผู้รับเหมา ดี");
        long t1 = container(a, SITE, salesActor);
        long ordinary = container(b, SITE, salesActor);
        quote(t1);
        quote(ordinary);
        jdbc.update("UPDATE sales.ticket SET quotation_only = FALSE WHERE ticket_id = :id", Map.of("id", ordinary));

        migrate();
        assertThat(status(t1)).isEqualTo("draft");
        assertThat(status(ordinary)).isEqualTo("draft");
        assertThat(ticketOf(quoteIdsOn(ordinary).get(0))).isEqualTo(ordinary);
    }

    @Test
    void groupsWithDifferentKeyerOrPolicy_areLeftWhole() {
        CustomerDto a = customer("บจก.ผู้รับเหมา จี");
        CustomerDto b = customer("บจก.ผู้รับเหมา เอช");
        CustomerDto c = customer("บจก.ผู้รับเหมา ไอ");
        CustomerDto d = customer("บจก.ผู้รับเหมา เจ");
        long keyerA = container(a, SITE + " keyer", salesActor);
        long keyerB = container(b, SITE + " keyer", salesActor);
        long policyA = container(c, SITE + " policy", salesActor);
        long policyB = container(d, SITE + " policy", salesActor);
        for (long t : new long[] {keyerA, keyerB, policyA, policyB}) {
            quote(t);
        }
        jdbc.update("UPDATE sales.ticket SET keyed_by = :k WHERE ticket_id = :id", Map.of("k", otherRep.id(), "id", keyerB));
        jdbc.update("UPDATE sales.ticket SET deposit_policy = 'WAIVED' WHERE ticket_id = :id", Map.of("id", policyB));

        migrate();
        for (long t : new long[] {keyerA, keyerB, policyA, policyB}) {
            assertThat(status(t)).isEqualTo("draft");
            assertThat(ticketOf(quoteIdsOn(t).get(0))).isEqualTo(t);
        }
    }

    @Test
    void differentReps_areNotMerged() {
        CustomerDto a = customer("บจก.ผู้รับเหมา อี");
        CustomerDto b = customer("บจก.ผู้รับเหมา เอฟ");
        long mine = container(a, SITE, salesActor);
        long theirs = container(b, SITE, otherRep);
        quote(mine);
        jdbc.update("UPDATE sales.ticket SET created_by = :rep WHERE ticket_id = :id",
            Map.of("rep", otherRep.id(), "id", theirs));
        quotationService.create(theirs, draft().withRecipientType("BUYER"), otherRep);

        migrate();
        assertThat(status(mine)).isEqualTo("draft");
        assertThat(status(theirs)).isEqualTo("draft");
    }

    // ---- V206 ----

    @Test
    void v206_adminTypedDealAndRepsDeal_foldIntoTheRepsDeal_evenWhenTheAdminsHasTheLowerId() {
        CustomerDto a = customer("บจก.ผู้รับเหมา เค");
        CustomerDto b = customer("บจก.ผู้รับเหมา แอล");
        long typed = container(a, SITE + " v206a", otherRep);   // an admin owns it (created_by) but the rep quoted it
        long repsDeal = container(b, SITE + " v206a", salesActor);
        long q1 = quote(typed, otherRep);
        long q2 = quote(repsDeal);
        nameRep(salesActor, q1, q2);

        migrate(V206);
        assertThat(status(repsDeal)).isEqualTo("draft");
        assertThat(status(typed)).isEqualTo("cancelled");
        assertThat(List.of(ticketOf(q1), ticketOf(q2))).containsOnly(repsDeal);
        assertThat(count("SELECT COUNT(*) FROM sales.deal_party WHERE ticket_id = :id", repsDeal)).isEqualTo(2);
        long ownerAfter = jdbc.queryForObject("SELECT created_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", repsDeal), Long.class);
        assertThat(ownerAfter).isEqualTo(salesActor.id());
        migrate(V206);
        assertThat(ticketOf(q1)).isEqualTo(repsDeal);
        assertThat(count("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'DEAL_MERGED_INTO'", typed)).isEqualTo(1);
    }

    @Test
    void v206_aCustomerQuotedTwiceOnTheProject_endsAsOnePartyWithBothQuotations() {
        CustomerDto a = customer("บจก.ผู้รับเหมา เอ็ม");
        long t1 = container(a, SITE + " v206b", salesActor);
        long t2 = container(a, SITE + " v206b", salesActor);
        long q1 = quote(t1);
        long q2 = quote(t2);
        nameRep(salesActor, q1, q2);

        migrate(V206);
        assertThat(status(t2)).isEqualTo("cancelled");
        assertThat(List.of(ticketOf(q1), ticketOf(q2))).containsOnly(t1);
        assertThat(count("SELECT COUNT(*) FROM sales.deal_party WHERE ticket_id = :id", t1)).isEqualTo(1);
        assertThat(partyOf(q1)).isEqualTo(partyOf(q2));
        assertThat(count("SELECT COUNT(DISTINCT quotation_version) FROM sales.quotation WHERE ticket_id = :id", t1)).isEqualTo(2);
    }

    @Test
    void v206_differentRepsOnTheProject_areLeftWhole() {
        CustomerDto a = customer("บจก.ผู้รับเหมา เอ็น");
        CustomerDto b = customer("บจก.ผู้รับเหมา โอ");
        long mine = container(a, SITE + " v206c", salesActor);
        long theirs = container(b, SITE + " v206c", otherRep);
        long q1 = quote(mine);
        long q2 = quote(theirs, otherRep);
        nameRep(salesActor, q1);
        nameRep(otherRep, q2);

        migrate(V206);
        assertThat(status(mine)).isEqualTo("draft");
        assertThat(status(theirs)).isEqualTo("draft");
        assertThat(ticketOf(q2)).isEqualTo(theirs);
    }

    @Test
    void v206_whenTheQuotingRepOwnsNoneOfTheDeals_theGroupIsLeftWhole() {
        CustomerDto a = customer("บจก.ผู้รับเหมา พี");
        CustomerDto b = customer("บจก.ผู้รับเหมา คิว");
        long t1 = container(a, SITE + " v206d", otherRep);
        long t2 = container(b, SITE + " v206d", otherRep);
        long q1 = quote(t1, otherRep);
        long q2 = quote(t2, otherRep);
        nameRep(salesActor, q1, q2);

        migrate(V206);
        assertThat(status(t1)).isEqualTo("draft");
        assertThat(status(t2)).isEqualTo("draft");
    }

    @Test
    void v206_aGroupWithAnOrdinaryDeal_isLeftWhole() {
        CustomerDto a = customer("บจก.ผู้รับเหมา อาร์");
        CustomerDto b = customer("บจก.ผู้รับเหมา เอส");
        long t1 = container(a, SITE + " v206e", salesActor);
        long ordinary = container(b, SITE + " v206e", salesActor);
        long q1 = quote(t1);
        long q2 = quote(ordinary);
        nameRep(salesActor, q1, q2);
        jdbc.update("UPDATE sales.ticket SET quotation_only = FALSE WHERE ticket_id = :id", Map.of("id", ordinary));

        migrate(V206);
        assertThat(status(t1)).isEqualTo("draft");
        assertThat(status(ordinary)).isEqualTo("draft");
    }

    /** The rep a quotation names (sales_rep_display_id wins over sales_rep_id), set explicitly so the test says who. */
    private void nameRep(UserPrincipal rep, long... quotationIds) {
        for (long id : quotationIds) {
            jdbc.update("UPDATE sales.quotation SET sales_rep_display_id = :rep WHERE quotation_id = :id",
                Map.of("rep", rep.id(), "id", id));
        }
    }

    private void assertMerged(long t1, long t2, long t3, long q1, long q2, long q3, CustomerDto a, CustomerDto b) {
        assertThat(status(t1)).isEqualTo("draft");
        assertThat(status(t2)).isEqualTo("cancelled");
        assertThat(status(t3)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM sales.ticket WHERE ticket_id = :id", Map.of("id", t2),
            String.class)).isEqualTo("CANCELLED");
        assertThat(List.of(ticketOf(q1), ticketOf(q2), ticketOf(q3))).containsOnly(t1);
        List<Map<String, Object>> parties = jdbc.queryForList(
            "SELECT party_id, customer_id, role, status, is_headline FROM sales.deal_party WHERE ticket_id = :id ORDER BY party_id",
            Map.of("id", t1));
        assertThat(parties).hasSize(2);
        assertThat(parties).extracting(p -> ((Number) p.get("customer_id")).longValue()).containsExactly(a.id(), b.id());
        assertThat(parties).extracting(p -> p.get("status")).containsOnly("QUOTED");
        assertThat(parties).extracting(p -> p.get("is_headline")).containsExactly(true, false);
        long partyA = ((Number) parties.get(0).get("party_id")).longValue();
        assertThat(partyOf(q1)).isEqualTo(partyA);
        assertThat(partyOf(q3)).isEqualTo(partyA);
        assertThat(jdbc.queryForObject("SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id", Map.of("id", t1),
            String.class)).isEqualTo("QUOTE_BUYER");
        assertThat(count("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'DEAL_MERGED_INTO'", t2)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'DEAL_PARTY_ADDED'", t1)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales.deal_party WHERE ticket_id IN (:id)", t2)).isZero();
    }

    private CustomerDto customer(String name) {
        return customers.create(name, null, "1 ถนนประมูล", "สนญ.", "02-111-0000");
    }

    /** A quotation-only container owned by {@code rep}, under a project of its own customer named {@code site}. */
    private long container(CustomerDto customer, String site, UserPrincipal rep) {
        long projectId = projects.create(customer.id(), site).id();
        long id = tickets.create(new CreateTicketRequest(customer.name(), "NORMAL", customer.name(), customer.id(),
            projectId, null, null, "DESIGNER_LED", List.of()), tickets.nextTicketCode(), rep.id(), rep.id(), rep.name());
        jdbc.update("UPDATE sales.ticket SET quotation_only = TRUE WHERE ticket_id = :id", Map.of("id", id));
        return id;
    }

    private long quote(long ticketId) {
        return quote(ticketId, salesActor);
    }

    private long quote(long ticketId, UserPrincipal actor) {
        return quotationService.create(ticketId, draft().withRecipientType("BUYER"), actor).id();
    }

    private String status(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM sales.ticket WHERE ticket_id = :id", Map.of("id", ticketId), String.class);
    }

    private long ticketOf(long quotationId) {
        return jdbc.queryForObject("SELECT ticket_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
    }

    private long partyOf(long quotationId) {
        return jdbc.queryForObject("SELECT party_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
    }

    private List<Long> quoteIdsOn(long ticketId) {
        return jdbc.queryForList("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id ORDER BY quotation_id",
            Map.of("id", ticketId), Long.class);
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Map.of("id", id), Integer.class);
    }

    private void migrate() {
        migrate(V205);
    }

    private void migrate(String file) {
        ClassPathResource resource = new ClassPathResource(file);
        assertThat(resource.exists()).as("migration file %s must exist on the classpath", file).isTrue();
        try {
            // Flyway runs each file in a transaction, which is what drops the ON COMMIT DROP temp tables; do the same.
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            transactionTemplate.executeWithoutResult(s -> jdbc.getJdbcOperations().execute(sql));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
