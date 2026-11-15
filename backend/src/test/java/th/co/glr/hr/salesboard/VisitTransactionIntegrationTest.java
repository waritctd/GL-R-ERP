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
 * VisitService through the REAL Spring proxy (review M5): the other visit tests build the service by hand, so
 * {@code @Transactional} is never in play there. Here a failure after the visit's status update — the SITE_VISIT
 * insert, forced by a test-only constraint on this test's deal — must roll the visit back to PLANNED, so a visit is
 * never DONE without its activity. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
@ActiveProfiles("test")
@SpringBootTest
class VisitTransactionIntegrationTest {

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
    void aFailedActivityInsertRollsTheVisitBackToPlanned() {
        String tag = Long.toString(System.nanoTime());
        long repId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย ทรานแซกชัน", null, null, null, null, null, null, null,
            "visit-tx-" + tag + "@glr.co.th", null, "SALES", "Sales Division", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        UserPrincipal rep = new UserPrincipal(repId, "visit-tx@glr.co.th", "พนักงานขาย", "sales", repId, true,
            LocalDate.now(), false, null, false);
        long dealId = tickets.create(new CreateTicketRequest("ดีลทรานแซกชัน", "NORMAL", "ลูกค้าทดสอบ",
            null, null, null, null, null, List.of()), tickets.nextTicketCode(), repId, "พนักงานขาย");
        long visitId = visits.create(new CreateVisitRequest(dealId, "คุณอรุณ", LocalDate.now(VisitService.BANGKOK), null, null,
            false), rep)
            .id();

        String constraint = "chk_test_visit_tx_" + tag;
        jdbc.update("ALTER TABLE sales.deal_activity ADD CONSTRAINT " + constraint
            + " CHECK (ticket_id <> " + dealId + ") NOT VALID", Map.of());
        try {
            assertThatThrownBy(() -> visits.markDone(visitId, "ไปแล้ว", rep)).isNotNull();
        } finally {
            jdbc.update("ALTER TABLE sales.deal_activity DROP CONSTRAINT " + constraint, Map.of());
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT status, done_at, note FROM sales.visit WHERE visit_id = :id",
            Map.of("id", visitId));
        assertThat(row.get("status")).isEqualTo("PLANNED");
        assertThat(row.get("done_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.deal_activity WHERE ticket_id = :id AND kind = 'SITE_VISIT'",
            Map.of("id", dealId), Integer.class)).isZero();
    }
}
