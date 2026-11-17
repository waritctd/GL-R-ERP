package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.support.PostgresTestSupport;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Slice 6b fix round 2: the deal row lock behind ตามต่อ, through the REAL Spring proxies, two real transactions at once.
 *
 * <p>Ordering is forced, not timed: a test-only trigger makes a transaction stop at a chosen point by asking for a shared
 * advisory lock this test holds exclusively, and the test waits until Postgres itself reports the expected backends as
 * waiting on a lock (pg_stat_activity) before letting go. No sleeps decide the order.
 * <ol>
 *   <li>Two identical one-step logs with a date on one deal (a double submit). Inserting a visit takes KEY SHARE on the
 *       deal row; if each then asks for the deal's FOR UPDATE they deadlock. With the deal locked BEFORE the visit is
 *       inserted (one order: deal, then visit) the second waits, then de-duplicates: one visit, one activity, the date
 *       once, no error.</li>
 *   <li>The lock in TicketService#recordVisitActivity (review R4): while a tick with a date is between its owner re-check
 *       and its activity write, a concurrent reassignment of the deal must WAIT for it — otherwise the activity and the
 *       date land on a deal that has just moved to someone else.</li>
 * </ol>
 * Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class VisitFollowUpConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    @Autowired VisitService visits;
    @Autowired TicketRepository tickets;
    @Autowired EmployeeRepository employees;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void twoIdenticalLogsWithADateAtOnce_doNotDeadlock_andDeDuplicateToOneVisitWithTheDateOnce() throws Exception {
        String tag = Long.toString(System.nanoTime());
        UserPrincipal rep = rep("พนักงานขาย พร้อมกัน " + tag, "visit-fu-cc-" + tag + "@glr.co.th");
        long dealId = deal(rep);
        LocalDate today = LocalDate.now(VisitService.BANGKOK);
        LocalDate date = today.plusDays(7);
        long key = 6_200_000_000L + Math.floorMod(tag.hashCode(), 1_000_000);
        CreateVisitRequest log = new CreateVisitRequest(dealId, "คุณอรุณ", today, null, "ไปแล้ว", true, date);

        String fn = "test_hold_visit_" + tag;
        String trigger = "trg_test_hold_visit_" + tag;
        try (Connection holder = dataSource.getConnection()) {
            advisory(holder, "SELECT pg_advisory_lock(?)", key);
            jdbc.update("CREATE FUNCTION sales." + fn + "() RETURNS trigger AS $$ BEGIN "
                + "PERFORM pg_advisory_xact_lock_shared(" + key + "); RETURN NEW; END $$ LANGUAGE plpgsql", Map.of());
            jdbc.update("CREATE TRIGGER " + trigger + " AFTER INSERT ON sales.visit FOR EACH ROW "
                + "WHEN (NEW.ticket_id = " + dealId + ") EXECUTE FUNCTION sales." + fn + "()", Map.of());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<VisitDtos.VisitDto>> calls = List.of(
                    pool.submit(() -> { start.await(); return visits.create(log, rep); }),
                    pool.submit(() -> { start.await(); return visits.create(log, rep); }));
                start.countDown();
                // Both requests are parked: before the fix, each after its visit insert (holding KEY SHARE on the
                // deal); after it, one on the advisory lock and the other on the deal's row lock.
                assertThat(awaitWaiting(2)).as("both requests in flight and parked").isTrue();
                advisory(holder, "SELECT pg_advisory_unlock(?)", key);

                long first = calls.get(0).get(30, TimeUnit.SECONDS).id();
                long second = calls.get(1).get(30, TimeUnit.SECONDS).id();
                assertThat(second).as("the 60-second rule: the second is the same visit").isEqualTo(first);
            } finally {
                advisory(holder, "SELECT pg_advisory_unlock_all() WHERE ? IS NOT NULL", key);
                pool.shutdownNow();
                jdbc.update("DROP TRIGGER IF EXISTS " + trigger + " ON sales.visit", Map.of());
                jdbc.update("DROP FUNCTION IF EXISTS sales." + fn + "()", Map.of());
            }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.visit WHERE ticket_id = :id", Map.of("id", dealId),
            Integer.class)).isEqualTo(1);
        assertThat(siteVisits(dealId)).isEqualTo(1);
        assertThat(nextFollowUp(dealId)).isEqualTo(date);
    }

    @Test
    void aReassignmentWaitsForATickThatIsWritingItsActivityAndDate() throws Exception {
        String tag = Long.toString(System.nanoTime());
        UserPrincipal rep = rep("พนักงานขาย ล็อก " + tag, "visit-fu-lock-" + tag + "@glr.co.th");
        UserPrincipal other = rep("พนักงานขาย รับต่อ " + tag, "visit-fu-lock-other-" + tag + "@glr.co.th");
        long dealId = deal(rep);
        LocalDate today = LocalDate.now(VisitService.BANGKOK);
        long visitId = visits.create(new CreateVisitRequest(dealId, "คุณอรุณ", today, null, null, false), rep).id();
        long key = 6_300_000_000L + Math.floorMod(tag.hashCode(), 1_000_000);

        String fn = "test_hold_activity_" + tag;
        String trigger = "trg_test_hold_activity_" + tag;
        try (Connection holder = dataSource.getConnection()) {
            advisory(holder, "SELECT pg_advisory_lock(?)", key);
            jdbc.update("CREATE FUNCTION sales." + fn + "() RETURNS trigger AS $$ BEGIN "
                + "PERFORM pg_advisory_xact_lock_shared(" + key + "); RETURN NEW; END $$ LANGUAGE plpgsql", Map.of());
            jdbc.update("CREATE TRIGGER " + trigger + " BEFORE INSERT ON sales.deal_activity FOR EACH ROW "
                + "WHEN (NEW.ticket_id = " + dealId + ") EXECUTE FUNCTION sales." + fn + "()", Map.of());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<VisitDtos.VisitDto> tick = pool.submit(
                    () -> visits.markDone(visitId, "ไปแล้ว", today.plusDays(7), rep));
                assertThat(awaitWaiting(1)).as("the tick is parked at its activity write").isTrue();

                Future<Integer> reassign = pool.submit(() -> tickets.moveOwnerToSalesRep(dealId, other.id()));
                assertThat(awaitWaiting(2))
                    .as("the reassignment must wait on the deal's row lock the tick holds (R4)").isTrue();
                assertThat(reassign.isDone()).isFalse();

                advisory(holder, "SELECT pg_advisory_unlock(?)", key);
                assertThat(tick.get(30, TimeUnit.SECONDS).status()).isEqualTo("DONE");
                assertThat(reassign.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                advisory(holder, "SELECT pg_advisory_unlock_all() WHERE ? IS NOT NULL", key);
                pool.shutdownNow();
                jdbc.update("DROP TRIGGER IF EXISTS " + trigger + " ON sales.deal_activity", Map.of());
                jdbc.update("DROP FUNCTION IF EXISTS sales." + fn + "()", Map.of());
            }
        }

        // The tick wrote while its owner held the deal; the reassignment came after it.
        assertThat(siteVisits(dealId)).isEqualTo(1);
        assertThat(nextFollowUp(dealId)).isEqualTo(today.plusDays(7));
    }

    /** True once at least {@code n} other backends of this database are waiting on a lock (deadline 15 s). */
    private boolean awaitWaiting(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()
                """, Map.of(), Integer.class);
            if (waiting != null && waiting >= n) {
                return true;
            }
            Thread.sleep(20); // polling a condition Postgres reports; the order itself is forced by the locks
        }
        return false;
    }

    private static void advisory(Connection connection, String sql, long key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private UserPrincipal rep(String name, String email) {
        long id = employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        return new UserPrincipal(id, email, name, "sales", id, true, LocalDate.now(), false, null, false);
    }

    private long deal(UserPrincipal owner) {
        return tickets.create(new CreateTicketRequest("งานพร้อมกัน ตามต่อ", "NORMAL", "ลูกค้าทดสอบ",
            null, null, null, null, null, List.of()), tickets.nextTicketCode(), owner.id(), owner.name());
    }

    private LocalDate nextFollowUp(long dealId) {
        return jdbc.queryForObject("SELECT next_follow_up_at FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", dealId), LocalDate.class);
    }

    private int siteVisits(long dealId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'SITE_VISIT'",
            Map.of("id", dealId), Integer.class);
    }
}
