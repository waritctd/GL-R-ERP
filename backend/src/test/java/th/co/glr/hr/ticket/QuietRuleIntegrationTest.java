package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** The one "quiet" rule (spec 2026-11-15 §7.5) against real SQL: last touched = latest of created_at, any
 * deal_activity, and a STAGE_CHANGED / QUOTATION_ISSUED event; quiet = ACTIVE, not in deliver &amp; collect, and
 * last touched older than 14 days. Annotated itself — @EnabledIf is not inherited. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class QuietRuleIntegrationTest extends AbstractPostgresIntegrationTest {

    private TicketRepository tickets;
    private QuietRule quietRule;
    private long repId;

    @BeforeEach
    void wire() {
        quietRule = new QuietRule(jdbc, 14);
        tickets = new TicketRepository(jdbc, quietRule);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย เงียบ", null, null, null, null, null, null, null,
            "quiet-rule@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    @Test
    void brandNewDealIsNotQuiet_untilDay15() {
        long day13 = dealCreatedDaysAgo(13);
        long day15 = dealCreatedDaysAgo(15);

        assertThat(quietRule.isQuiet(day13)).isFalse();
        assertThat(quietRule.isQuiet(day15)).isTrue();
    }

    @Test
    void activityWithin14DaysClearsQuiet() {
        long id = dealCreatedDaysAgo(60);
        activityDaysAgo(id, 3);

        assertThat(quietRule.isQuiet(id)).isFalse();
    }

    @Test
    void onlyActiveDealsCanBeQuiet() {
        for (String lifecycle : List.of(DealLifecycle.CLOSED_LOST, DealLifecycle.ON_HOLD, DealLifecycle.DORMANT,
                DealLifecycle.CANCELLED)) {
            long id = dealCreatedDaysAgo(60);
            jdbc.update("UPDATE sales.ticket SET lifecycle = :l WHERE ticket_id = :id",
                Map.of("l", lifecycle, "id", id));
            assertThat(quietRule.isQuiet(id)).as("lifecycle %s", lifecycle).isFalse();
        }
    }

    /** A visit on the deal, {@code dayOffset} days from today's Bangkok date. */
    private void visit(long ticketId, String status, int dayOffset) {
        jdbc.update("""
            INSERT INTO sales.visit (owner_id, ticket_id, contact_name, planned_date, status, done_at)
            VALUES (:rep, :id, 'ลูกค้า', (now() AT TIME ZONE 'Asia/Bangkok')::date + :d, :status,
                    CASE WHEN :status = 'DONE' THEN now() END)
            """, Map.of("rep", repId, "id", ticketId, "d", dayOffset, "status", status));
    }

    private long dealCreatedDaysAgo(int days) {
        long id = tickets.create(new CreateTicketRequest("ดีลเงียบ", "NORMAL", "ลูกค้าทดสอบ",
            null, null, null, null, null, List.of()), tickets.nextTicketCode(), repId, "พนักงานขาย เงียบ");
        jdbc.update("UPDATE sales.ticket SET created_at = now() - make_interval(days => :d) WHERE ticket_id = :id",
            Map.of("d", days, "id", id));
        // Any event create() wrote is stamped now(); push it back with the deal so only the test's own rows count.
        jdbc.update("UPDATE sales.ticket_event SET created_at = now() - make_interval(days => :d) WHERE ticket_id = :id",
            Map.of("d", days, "id", id));
        return id;
    }

    private void activityDaysAgo(long ticketId, int days) {
        jdbc.update("""
            INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, note, created_by_id, created_at)
            VALUES (:id, CURRENT_DATE - :d, :kind, NULL, :rep, now() - make_interval(days => :d))
            """, Map.of("id", ticketId, "d", days, "kind", DealActivityKind.CALL, "rep", repId));
    }

}
