package th.co.glr.hr.salesboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyOrderRow;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyRepRow;
import th.co.glr.hr.salesboard.WeeklyReportDtos.WeeklyReport;

/**
 * GET /api/sales/weekly-report through the real service, repositories and Postgres: the money cutoff, a slipped OPEN
 * payment counted once in the current week, who may read whom, and one counts check. The clock is fixed on Wed
 * 25 Nov 2026; the cutoff is Mon 16 Nov. Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class WeeklyReportIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {
    private static final LocalDate WEEK = LocalDate.of(2026, 11, 23);
    private WeeklyReportService service;

    @BeforeEach
    void wire() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T05:00:00Z"), ZoneOffset.UTC);
        service = new WeeklyReportService(new WeeklyReportRepository(jdbc),
            new CashRepository(jdbc, LocalDate.of(2026, 11, 16)), new VisitRepository(jdbc),
            new EmployeeAuthRepository(jdbc), clock);
    }

    @Test
    void cutoffExcludesOldOrderAndASlipCountsOnceInTheCurrentWeek() {
        long old = confirmedDeal("2026-11-10T03:00:00Z");
        receipt(old, "100", "2026-11-24T03:00:00Z", "RE-OLD");
        expected(old, "900", "2026-11-23");
        long fresh = confirmedDeal("2026-11-17T03:00:00Z");
        receipt(fresh, "200", "2026-11-24T03:00:00Z", "RE-NEW");
        expected(fresh, "300", "2026-11-09"); // two weeks late: a slip
        event(fresh, "STAGE_CHANGED", "LEAD_APPROACH", "PRESENTATION", "2026-11-25T02:00:00Z");

        WeeklyReport mine = service.report(salesActor, WEEK, null);

        assertThat(mine.scope()).isEqualTo("OWN");
        assertThat(mine.moneyFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(mine.orders()).extracting(WeeklyOrderRow::ticketId).containsExactly(fresh);
        WeeklyOrderRow order = mine.orders().get(0);
        assertThat(order.slipped()).isTrue();
        assertThat(order.nextPayment().amount()).isEqualByComparingTo("300");
        assertThat(mine.ledger().received()).isEqualByComparingTo("200");
        assertThat(mine.ledger().depositsExpected()).as("the old order's 900 is out, the slip is in once")
            .isEqualByComparingTo("300");
        assertThat(mine.ledger().total()).isEqualByComparingTo("500");
        var current = mine.ledger().weeks().stream().filter(w -> w.isCurrent()).findFirst().orElseThrow();
        assertThat(current.weekStart()).isEqualTo(WEEK);
        assertThat(current.slipped()).isEqualByComparingTo("300");

        WeeklyRepRow rep = mine.reps().get(0);
        assertThat(rep.ownerId()).isEqualTo(salesActor.id());
        assertThat(rep.stageMoves()).isEqualTo(1);
        assertThat(rep.entriesToday()).isEqualTo(1);
        assertThat(rep.ordersOpen()).isEqualTo(0); // payable is 0 with no quotation: nothing outstanding
        assertThat(mine.today()).hasSize(1);
        assertThat(mine.today().get(0).label()).isEqualTo("เลื่อนขั้น 1 เป็น 2");
    }

    @Test
    void scope_salesReadsOnlyOwn_managerReadsTeamOrOneRep() {
        assertThat(refusal(() -> service.report(salesActor, WEEK, otherSalesActor.id()), HttpStatus.FORBIDDEN))
            .isNotNull();
        assertThat(service.report(salesActor, WEEK, salesActor.id()).scope()).isEqualTo("OWN");

        WeeklyReport team = service.report(salesManagerActor, WEEK, null);
        assertThat(team.scope()).isEqualTo("TEAM");
        assertThat(team.today()).isEmpty();
        WeeklyReport one = service.report(salesManagerActor, WEEK, otherSalesActor.id());
        assertThat(one.scope()).isEqualTo("OWN");
        assertThat(one.reps()).extracting(WeeklyRepRow::ownerId).containsExactly(otherSalesActor.id());
        assertThat(one.today()).isEmpty();
        refusal(() -> service.report(salesManagerActor, WEEK, 987654321L), HttpStatus.NOT_FOUND);
    }

    private long confirmedDeal(String confirmedAt) {
        long id = deal(salesActor);
        event(id, "DEAL_PROMOTED_FROM_QUOTATION", null, null, confirmedAt);
        return id;
    }

    private void event(long ticketId, String kind, String from, String to, String at) {
        jdbc.update("""
            INSERT INTO sales.ticket_event (ticket_id, actor_id, actor_name, kind, from_status, to_status, created_at)
            VALUES (:t, :by, 'x', :k, :f, :to, :at)
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", ticketId)
                .addValue("by", salesManagerActor.id()).addValue("k", kind).addValue("f", from).addValue("to", to)
                .addValue("at", Timestamp.from(Instant.parse(at))));
    }

    private void expected(long ticketId, String amount, String week) {
        jdbc.update("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, status, created_by)
            VALUES (:t, 'DEPOSIT', :a, :w, 'OPEN', :by)
            """, Map.of("t", ticketId, "a", new BigDecimal(amount), "w", LocalDate.parse(week),
                "by", salesManagerActor.id()));
    }

    private void receipt(long ticketId, String amount, String receivedAt, String ref) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, received_at, recorded_by, receipt_ref)
            VALUES (:t, 'DEPOSIT', :a, :at, :by, :ref)
            """, Map.of("t", ticketId, "a", new BigDecimal(amount), "at", Timestamp.from(Instant.parse(receivedAt)),
                "by", salesManagerActor.id(), "ref", ref));
    }
}
