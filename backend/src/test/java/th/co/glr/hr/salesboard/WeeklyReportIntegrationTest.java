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
    void anUndeliveredOrdersRowsStayOutOfTheDeliveryLines() {
        // CashRepository's stage/kind rules, as on the cash cards: at ORDER_RECEIVED nothing is delivered or scheduled.
        long id = confirmedDeal("2026-11-17T03:00:00Z");
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :t", Map.of("t", id));
        expected(id, "ON_DELIVERY", "400", "2026-11-23");
        expected(id, "BALANCE", "50", "2026-11-23");

        var ledger = service.report(salesActor, WEEK, null).ledger();

        assertThat(ledger.deliveriesScheduled()).isEqualByComparingTo("0");
        assertThat(ledger.deliveredAwaiting()).isEqualByComparingTo("0");
    }

    @Test
    void estimatesFillLines3To5ForJobsWithoutARepRow_andARepRowWins() {
        long deposit = stagedDeal("ORDER_RECEIVED");
        notice(deposit, "2026-11-18", "107");
        long onDelivery = stagedDeal("PROCUREMENT");
        item(onDelivery, "1000");
        round(onDelivery, "2026-11-12"); // ETA + 7 days is before the current week: lands in it, but is no slip
        long delivered = stagedDeal("DELIVERED");
        item(delivered, "1000");
        jdbc.update("INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by) VALUES (:t, 'WAREHOUSE',"
            + " '2026-11-24T05:00:00Z', :by)", Map.of("t", delivered, "by", salesActor.id()));
        receipt(delivered, "70", "2026-11-24T03:00:00Z", "RE-EST");
        long ownRow = stagedDeal("PROCUREMENT");
        item(ownRow, "1000");
        round(ownRow, "2026-11-26");
        expected(ownRow, "ON_DELIVERY", "50", "2026-11-23");
        long addOn = deliveredDeal();
        jdbc.update("UPDATE sales.ticket SET parent_ticket_id = :p, child_kind = 'ADD_ON' WHERE ticket_id = :t",
            Map.of("p", ownRow, "t", addOn)); // the root's own row may already cover it
        long usd = deliveredDeal();
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, total_amount, recipient_type, currency, issued_at, accepted_at)
            VALUES (:t, 'QT-USD-' || :t, :by, 'ACCEPTED', 1000, 'BUYER', 'USD', now(), now())
            """, Map.of("t", usd, "by", salesActor.id()));

        WeeklyReport r = service.report(salesActor, WEEK, null);

        var byId = new java.util.HashMap<Long, WeeklyOrderRow>();
        r.orders().forEach(o -> byId.put(o.ticketId(), o));
        assertThat(byId.get(deposit).estimate().kind()).isEqualTo("DEPOSIT");
        assertThat(byId.get(deposit).estimate().amount()).isEqualByComparingTo("107");
        assertThat(byId.get(onDelivery).estimate().week()).as("a past date is due this week").isEqualTo(WEEK);
        assertThat(byId.get(addOn).estimate()).isNull();
        assertThat(byId.get(usd).estimate()).as("THB only").isNull();
        assertThat(byId.get(onDelivery).slipped()).as("the rep never gave that date").isFalse();
        assertThat(byId.get(delivered).estimate().amount()).as("payable 1070 less 70 received").isEqualByComparingTo("1000");
        assertThat(byId.get(ownRow).estimate()).isNull();
        var l = r.ledger();
        assertThat(l.depositsExpected()).isEqualByComparingTo("107");
        assertThat(l.deliveriesScheduled()).as("1070 estimated + 50 of the rep's own").isEqualByComparingTo("1120");
        assertThat(l.deliveredAwaiting()).isEqualByComparingTo("1000");
        assertThat(l.estimated()).isEqualByComparingTo("2177");
        assertThat(l.weeks().stream().filter(w -> w.isCurrent()).findFirst().orElseThrow().slipped())
            .isEqualByComparingTo("0");
        assertThat(r.reps().get(0).ordersWithDate()).isEqualTo(4);
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

    @Test
    void aVisitDoneBeforeItsTimeCountsAsDueWithResult() {
        VisitRepository visits = new VisitRepository(jdbc);
        visits.insert(salesActor.id(), null, "early", LocalDate.of(2026, 11, 25), java.time.LocalTime.of(18, 0), null, true);
        visits.insert(salesActor.id(), null, "later", LocalDate.of(2026, 11, 25), java.time.LocalTime.of(18, 0), null, false);

        WeeklyRepRow rep = service.report(salesActor, WEEK, null).reps().get(0);

        assertThat(rep.visitsDue()).isEqualTo(1);
        assertThat(rep.visitsDueWithResult()).isEqualTo(1);
    }

    private long deliveredDeal() {
        long id = stagedDeal("DELIVERED");
        item(id, "1000");
        jdbc.update("INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by) VALUES (:t, 'WAREHOUSE',"
            + " '2026-11-24T05:00:00Z', :by)", Map.of("t", id, "by", salesActor.id()));
        return id;
    }

    private long stagedDeal(String stage) {
        long id = confirmedDeal("2026-11-17T03:00:00Z");
        jdbc.update("UPDATE sales.ticket SET sales_stage = :s WHERE ticket_id = :t", Map.of("s", stage, "t", id));
        return id;
    }

    private void notice(long ticketId, String issueDate, String total) {
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, issue_date, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:t, 1, 'ISSUED', :d, 'x', 0.3, 100, 100, 0.07, 7, :total)
            """, Map.of("t", ticketId, "d", LocalDate.parse(issueDate), "total", new BigDecimal(total)));
    }

    private void item(long ticketId, String price) {
        jdbc.update("INSERT INTO sales.ticket_item (ticket_id, brand, qty, qty_from_stock, approved_price) VALUES (:t, 'B', 1, 0, :p)",
            Map.of("t", ticketId, "p", new BigDecimal(price)));
    }

    /** An import request with one line and a shipment round carrying it, ETA as given. */
    private void round(long ticketId, String eta) {
        long itemId = jdbc.queryForObject("SELECT item_id FROM sales.ticket_item WHERE ticket_id = :t", Map.of("t", ticketId), Long.class);
        long factory = jdbc.queryForObject("INSERT INTO price_catalog.factories (name, country, default_currency) VALUES ('F' || :t, 'IT', 'EUR')"
            + " RETURNING factory_id", Map.of("t", ticketId), Long.class);
        long req = jdbc.queryForObject("""
            INSERT INTO sales.import_request (ticket_id, factory_id, brand, factory_name, version, status, doc_number, issue_date, import_step,
                import_step_at, first_issued_date)
            VALUES (:t, :f, 'B', 'F', 1, 'ISSUED', :n, CURRENT_DATE, 'ORDERED', CURRENT_DATE, CURRENT_DATE) RETURNING import_request_id
            """, Map.of("t", ticketId, "f", factory, "n", "IR-" + ticketId), Long.class);
        long line = jdbc.queryForObject("""
            INSERT INTO sales.import_request_item (import_request_id, ticket_item_id, seq, code, qty)
            VALUES (:r, :i, 1, 'c', 1) RETURNING import_request_item_id
            """, Map.of("r", req, "i", itemId), Long.class);
        long sr = jdbc.queryForObject("INSERT INTO sales.shipment_round (source, import_step, eta_on) VALUES ('PORTAL', 'ORDERED', :e)"
            + " RETURNING shipment_round_id", Map.of("e", LocalDate.parse(eta)), Long.class);
        jdbc.update("INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty) VALUES (:s, :r, :l, 1)",
            Map.of("s", sr, "r", req, "l", line));
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
        expected(ticketId, "DEPOSIT", amount, week);
    }

    private void expected(long ticketId, String kind, String amount, String week) {
        jdbc.update("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, status, created_by)
            VALUES (:t, :k, :a, :w, 'OPEN', :by)
            """, Map.of("t", ticketId, "k", kind, "a", new BigDecimal(amount), "w", LocalDate.parse(week),
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
