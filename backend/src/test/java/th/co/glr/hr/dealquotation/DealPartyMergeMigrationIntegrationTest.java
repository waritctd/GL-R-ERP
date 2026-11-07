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
 * V205 (docs/decisions/deal-party-model.md, slice 4): the sibling-bid backlog folds into one deal per rep and
 * project, but ONLY when every ticket in the group is a pure quotation-only container. Runs the file verbatim,
 * twice, against data built through the real services.
 */
class DealPartyMergeMigrationIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {
    private static final String V205 = "db/migration/V205__merge_sibling_bid_deals.sql";
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
        return quotationService.create(ticketId, draft().withRecipientType("BUYER"), salesActor).id();
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
        ClassPathResource resource = new ClassPathResource(V205);
        assertThat(resource.exists()).as("migration file %s must exist on the classpath", V205).isTrue();
        try {
            // Flyway runs each file in a transaction, which is what drops the ON COMMIT DROP temp tables; do the same.
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            transactionTemplate.executeWithoutResult(s -> jdbc.getJdbcOperations().execute(sql));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
