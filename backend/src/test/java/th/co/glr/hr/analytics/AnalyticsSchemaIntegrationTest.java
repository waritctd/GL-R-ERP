package th.co.glr.hr.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;

/** Phase 1 of the analytics schema (V223): dimension seeds, the staging / fact views, compute_week and the data-quality
 * checks. Every row is a synthetic fixture this test inserts itself. */
class AnalyticsSchemaIntegrationTest extends AbstractPostgresIntegrationTest {
    private LocalDate weekStart;
    private long repA;
    private long repB;

    @BeforeEach
    void fixtures() {
        weekStart = jdbc.queryForObject(
            "SELECT date_trunc('week', now() AT TIME ZONE 'Asia/Bangkok')::date", Map.of(), LocalDate.class);
        repA = employee("A001", "Alpha");
        repB = employee("B001", "Beta");
    }

    // ---- 3. stg_deal --------------------------------------------------------------------------------------------

    @Test
    void stgDeal_dealValueFollowsTheBoardRule() {
        long t = ticket("D-VAL", "Value rule", repA, "NEGOTIATION", "ACTIVE", null);
        assertThat(deal(t, "deal_value")).isNull();
        jdbc.update("UPDATE sales.ticket SET estimated_value = 500 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("500");
        jdbc.update("UPDATE sales.ticket SET imported_value = 700 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("700");
        quotation(t, "Q-OLD", "DRAFT", "9999.00");
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("700");
        quotation(t, "Q-APP", "APPROVED", "1000.00");
        assertThat(num(deal(t, "deal_value"))).isEqualByComparingTo("1000");
    }

    // ---- 6. compute_week ----------------------------------------------------------------------------------------

    @Test
    void computeWeek_writesMetricsIdempotentlyAndLogsEveryCheck() {
        seedMetricFixture();
        compute();
        List<Map<String, Object>> first = metrics();

        assertThat(metric("pipeline_value_raw", "")).isEqualByComparingTo("2700");
        assertThat(metric("pipeline_value_dedup", "")).isEqualByComparingTo("1700");
        assertThat(metric("pipeline_stale_share", "")).isEqualByComparingTo("0.3529");
        assertThat(metric("rep_stage_moves_week", "rep=" + repA)).isEqualByComparingTo("2");
        assertThat(metric("rep_activities_week", "rep=" + repA)).isEqualByComparingTo("1");
        assertThat(metric("rep_quiet_deal_share", "rep=" + repA)).isEqualByComparingTo("0.25");
        assertThat(metric("hygiene_score", "rep=" + repB)).isEqualByComparingTo("0");
        assertThat(metric("quoted_value", "brand=Refin")).isEqualByComparingTo("300");
        assertThat(metric("quoted_sqm", "brand=Refin")).isEqualByComparingTo("30");
        assertThat(metric("ordered_value", "brand=Refin")).isEqualByComparingTo("100");
        assertThat(metric("pipeline_value_dedup", "")).isLessThanOrEqualTo(metric("pipeline_value_raw", ""));

        long checks = jdbc.queryForObject("SELECT count(*) FROM analytics.dq_check", Map.of(), Long.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics.run_log WHERE week_start = :w",
            Map.of("w", weekStart), Long.class)).isEqualTo(checks);

        compute();
        assertThat(metrics()).isEqualTo(first);
    }

    // ---- 7. dq_check --------------------------------------------------------------------------------------------

    @Test
    void dqCheck_wonDealWithoutValueFails() {
        long t = ticket("DQ-W", "Won", repA, DealStage.ORDER_RECEIVED, "ACTIVE", null);
        assertThat(failedChecks()).containsExactly("won_deal_has_value");
        jdbc.update("UPDATE sales.ticket SET imported_value = 10 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(failedChecks()).isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void seedMetricFixture() {
        long project = project("Synthetic mall");
        long a1 = ticket("M-1", "Mall A", repA, DealStage.ORDER_RECEIVED, "ACTIVE", project);
        long a2 = ticket("M-2", "Mall B", repA, "NEGOTIATION", "ACTIVE", project);
        long a3 = ticket("M-3", "Villa", repA, "NEGOTIATION", "ACTIVE", null);
        long a4 = ticket("M-4", "Shop", repA, "NEGOTIATION", "ACTIVE", null);
        long b1 = ticket("M-5", "Clinic", repB, "NEGOTIATION", "ACTIVE", null);
        ticket("M-T", "test deal", repB, "NEGOTIATION", "ACTIVE", null);
        quotation(a1, "QM-1", "APPROVED", "1000.00");
        quotation(a2, "QM-2", "APPROVED", "1000.00");
        quotation(a3, "QM-3", "APPROVED", "500.00");
        quotation(a4, "QM-4", "APPROVED", "100.00");
        quotation(b1, "QM-5", "APPROVED", "100.00");
        // Fresh deals except a3 (20 days quiet) and b1 (20 days quiet): stale value = 500 + 100 over 1700 deduped.
        jdbc.update("UPDATE sales.ticket SET created_at = now() - interval '20 days' WHERE ticket_id IN (:a, :b)",
            Map.of("a", a3, "b", b1));
        // Two stage moves + one activity for repA inside the week.
        jdbc.update("INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, created_at) VALUES"
            + " (:t, :a, 'STAGE_CHANGED', :w::date + interval '1 day' + interval '9 hours'),"
            + " (:t, :a, 'STAGE_CHANGED', :w::date + interval '2 days' + interval '9 hours')",
            new MapSqlParameterSource("t", a2).addValue("a", repA).addValue("w", weekStart));
        activity(a2, "now() - interval '1 day'", weekStart.plusDays(1));
        long q1 = jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE number = 'QM-1'", Map.of(), Long.class);
        long q3 = jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE number = 'QM-3'", Map.of(), Long.class);
        tile(q1, 1, "REFIN", "M1", "100.00", "10", null, null);
        tile(q3, 1, "refin", "M2", "200.00", "20", null, null);
    }

    private List<String> failedChecks() {
        return jdbc.queryForList("SELECT check_key FROM analytics.dq_check WHERE NOT passed", Map.of(), String.class);
    }

    private void compute() {
        jdbc.queryForObject("SELECT analytics.compute_week(:w)", Map.of("w", weekStart), Object.class);
    }

    private List<Map<String, Object>> metrics() {
        return jdbc.queryForList(
            "SELECT week_start, metric_key, dimension, value FROM analytics.metric_weekly ORDER BY metric_key, dimension", Map.of());
    }

    private BigDecimal metric(String key, String dimension) {
        return jdbc.queryForObject("SELECT value FROM analytics.metric_weekly WHERE week_start = :w AND metric_key = :k"
            + " AND dimension = :d", Map.of("w", weekStart, "k", key, "d", dimension), BigDecimal.class);
    }

    private static BigDecimal num(Object o) {
        return (BigDecimal) o;
    }

    private Object deal(long ticketId, String column) {
        return jdbc.queryForMap("SELECT " + column + " AS v FROM analytics.stg_deal WHERE ticket_id = :t",
            Map.of("t", ticketId)).get("v");
    }

    private long employee(String code, String nick) {
        return jdbc.queryForObject("INSERT INTO hr.employee (employee_code, nickname) VALUES (:c, :n) RETURNING employee_id",
            Map.of("c", code, "n", nick), Long.class);
    }

    private long project(String name) {
        return jdbc.queryForObject("INSERT INTO customers.project (name) VALUES (:n) RETURNING project_id",
            Map.of("n", name), Long.class);
    }

    private long ticket(String code, String title, long owner, String stage, String lifecycle, Long projectId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, sales_stage, lifecycle, project_id)
            VALUES (:c, :t, :o, :s, :l, :p) RETURNING ticket_id
            """, new MapSqlParameterSource("c", code).addValue("t", title).addValue("o", owner)
            .addValue("s", stage).addValue("l", lifecycle).addValue("p", projectId), Long.class);
    }

    private void activity(long ticketId, String createdAtSql, LocalDate date) {
        jdbc.update("INSERT INTO sales.deal_activity (ticket_id, activity_date, kind, created_by_id, created_at)"
            + " VALUES (:t, :d, 'CALL', :a, " + createdAtSql + ")", Map.of("t", ticketId, "d", date, "a", repA));
    }

    private long quotation(long ticketId, String number, String status, String total) {
        return jdbc.queryForObject("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, total_amount, document_language,
                                         quotation_version)
            VALUES (:t, :n, :i, :s, :a, 'TH',
                    (SELECT count(*) + 1 FROM sales.quotation WHERE ticket_id = :t)) RETURNING quotation_id
            """, new MapSqlParameterSource("t", ticketId).addValue("n", number).addValue("i", repA)
            .addValue("s", status).addValue("a", new BigDecimal(total)), Long.class);
    }

    private void tile(long quotationId, int seq, String brand, String model, String amount, String areaSqm,
                      Integer pieces, String sqmPerPiece) {
        jdbc.update("""
            INSERT INTO sales.quotation_item (quotation_id, seq, line_type, brand, model, unit_price, amount, area_sqm,
                                              pieces_after_wastage, sqm_per_piece)
            VALUES (:q, :s, 'TILE', :b, :m, 1, :a, :area, :p, :spp)
            """, new MapSqlParameterSource("q", quotationId).addValue("s", seq).addValue("b", brand)
            .addValue("m", model).addValue("a", new BigDecimal(amount))
            .addValue("area", areaSqm == null ? null : new BigDecimal(areaSqm)).addValue("p", pieces)
            .addValue("spp", sqmPerPiece == null ? null : new BigDecimal(sqmPerPiece)));
    }
}
