package th.co.glr.hr.salesboard;

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
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealActivityKind;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;

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
    void oldActivityDoesNotClearQuiet() {
        long id = dealCreatedDaysAgo(60);
        activityDaysAgo(id, 20);

        assertThat(quietRule.isQuiet(id)).isTrue();
    }

    @Test
    void stageChangeEventClearsQuiet() {
        long id = dealCreatedDaysAgo(60);
        eventDaysAgo(id, "STAGE_CHANGED", 3);

        assertThat(quietRule.isQuiet(id)).isFalse();
    }

    @Test
    void quotationIssuedEventClearsQuiet() {
        long id = dealCreatedDaysAgo(60);
        eventDaysAgo(id, "QUOTATION_ISSUED", 3);

        assertThat(quietRule.isQuiet(id)).isFalse();
    }

    @Test
    void otherEventKindsDoNotClearQuiet() {
        long id = dealCreatedDaysAgo(60);
        eventDaysAgo(id, "COMMENTED", 3);

        assertThat(quietRule.isQuiet(id)).isTrue();
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

    @Test
    void deliverAndCollectColumnIsNeverQuiet() {
        for (String stage : List.of(DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT,
                DealStage.DELIVERY_SCHEDULING, DealStage.DELIVERED)) {
            long id = dealCreatedDaysAgo(60);
            setStage(id, stage);
            assertThat(quietRule.isQuiet(id)).as("stage %s", stage).isFalse();
        }
        long negotiating = dealCreatedDaysAgo(60);
        setStage(negotiating, DealStage.NEGOTIATION);
        assertThat(quietRule.isQuiet(negotiating)).isTrue();
    }

    /** The deal list's TicketSummaryDto.stale is the same rule — the app has one meaning of "quiet". */
    @Test
    void ticketRepositoryIsStaleUsesTheSameRule() {
        long untouched = dealCreatedDaysAgo(60);
        long recentActivity = dealCreatedDaysAgo(60);
        activityDaysAgo(recentActivity, 3);
        long brandNew = dealCreatedDaysAgo(0);
        long delivered = dealCreatedDaysAgo(60);
        setStage(delivered, DealStage.DELIVERED);

        for (long id : List.of(untouched, recentActivity, brandNew, delivered)) {
            boolean stale = tickets.findSummaryById(id).orElseThrow().stale();
            assertThat(stale).as("ticket %d", id).isEqualTo(quietRule.isQuiet(id));
        }
        assertThat(tickets.findSummaryById(untouched).orElseThrow().stale()).isTrue();
        assertThat(tickets.findSummaryById(brandNew).orElseThrow().stale()).isFalse();
    }

    @Test
    void thresholdComesFromConfiguration() {
        QuietRule sevenDays = new QuietRule(jdbc, 7);
        long id = dealCreatedDaysAgo(10);

        assertThat(sevenDays.quietDays()).isEqualTo(7);
        assertThat(sevenDays.isQuiet(id)).isTrue();
        assertThat(quietRule.isQuiet(id)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private void eventDaysAgo(long ticketId, String kind, int days) {
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, created_at)
            VALUES (:id, :rep, 'rep', :kind, now() - make_interval(days => :d))
            """, Map.of("id", ticketId, "rep", repId, "kind", kind, "d", days));
    }

    private void setStage(long ticketId, String stage) {
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :id",
            Map.of("s", stage, "id", ticketId));
    }
}
