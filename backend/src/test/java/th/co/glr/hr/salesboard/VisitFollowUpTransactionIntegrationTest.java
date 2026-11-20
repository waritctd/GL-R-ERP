package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.support.PostgresTestSupport;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Slice 6b fix round 1 (review item 5 + the reassignment race): ตามต่อ through the REAL Spring transaction proxy, as
 * {@link VisitTransactionIntegrationTest} does for the tick. The other follow-up tests build the services by hand, so
 * {@code @Transactional} is never in play there.
 *
 * <p>The race: the deal is reassigned AFTER {@code requireVisitFollowUpWritable} passed and the visit row was updated,
 * but before the activity and the date are written. A test-only trigger on {@code sales.visit} plays the concurrent
 * reassignment inside the same transaction, right after the tick's UPDATE. The fix re-checks the owner under the
 * deal's row lock inside {@code recordVisitActivity}; a requested date that can no longer land is refused, and the
 * proxy rolls the whole request back — the visit stays PLANNED, no activity, no date, and the reassignment itself
 * (part of the same transaction here) is undone too. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class VisitFollowUpTransactionIntegrationTest {

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

    @Test
    void aReassignmentBetweenTheCheckAndTheWrite_refusesTheDate_andRollsEverythingBack() {
        String tag = Long.toString(System.nanoTime());
        UserPrincipal rep = rep("พนักงานขาย ตามต่อ " + tag, "visit-fu-tx-" + tag + "@glr.co.th");
        UserPrincipal other = rep("พนักงานขาย อีกคน " + tag, "visit-fu-tx-other-" + tag + "@glr.co.th");
        long dealId = deal(rep);
        LocalDate today = LocalDate.now(VisitService.BANGKOK);
        long visitId = visits.create(new CreateVisitRequest(dealId, "คุณอรุณ", today, null, null, false), rep).id();
        LocalDate before = nextFollowUp(dealId);

        String fn = "test_reassign_fu_" + tag;
        String trigger = "trg_test_reassign_fu_" + tag;
        jdbc.update("CREATE FUNCTION sales." + fn + "() RETURNS trigger AS $$ BEGIN "
            + "UPDATE sales.ticket SET created_by = " + other.id() + ", keyed_by = " + rep.id()
            + " WHERE ticket_id = " + dealId + "; RETURN NEW; END $$ LANGUAGE plpgsql", Map.of());
        jdbc.update("CREATE TRIGGER " + trigger + " AFTER UPDATE OF status ON sales.visit FOR EACH ROW "
            + "WHEN (NEW.visit_id = " + visitId + " AND NEW.status = 'DONE') EXECUTE FUNCTION sales." + fn + "()",
            Map.of());
        try {
            assertThatThrownBy(() -> visits.markDone(visitId, "ไปแล้ว", today.plusDays(7), rep))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            jdbc.update("DROP TRIGGER " + trigger + " ON sales.visit", Map.of());
            jdbc.update("DROP FUNCTION sales." + fn + "()", Map.of());
        }

        assertThat(jdbc.queryForObject("SELECT status FROM sales.visit WHERE visit_id = :id", Map.of("id", visitId),
            String.class)).isEqualTo("PLANNED");
        assertThat(siteVisits(dealId)).isZero();
        assertThat(nextFollowUp(dealId)).isEqualTo(before);
        assertThat(((Number) jdbc.queryForObject("SELECT created_by FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", dealId), Long.class)).longValue()).as("the in-transaction reassignment rolled back too")
            .isEqualTo(rep.id());
    }

    private UserPrincipal rep(String name, String email) {
        long id = employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        return new UserPrincipal(id, email, name, "sales", id, true, LocalDate.now(), false, null, false);
    }

    private long deal(UserPrincipal owner) {
        return tickets.create(new CreateTicketRequest("งานทรานแซกชัน ตามต่อ", "NORMAL", "ลูกค้าทดสอบ",
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
