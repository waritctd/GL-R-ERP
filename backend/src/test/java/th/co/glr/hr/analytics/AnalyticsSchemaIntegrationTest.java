package th.co.glr.hr.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.yaml.snakeyaml.Yaml;
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

    // ---- 1. norm_brand ------------------------------------------------------------------------------------------

    @Test
    void normBrand_trimsCasesAliasesAndStripsLeadingThaiMarks() {
        assertThat(norm("REFIN")).isEqualTo("Refin");
        assertThat(norm(" refin")).isEqualTo("Refin");
        assertThat(norm("Rquipe")).isEqualTo("Equipe");
        assertThat(norm("Daygres")).isEqualTo("Daugres");
        assertThat(norm("่Refin")).isEqualTo("Refin");
        assertThat(norm("Cotto D' Este")).isEqualTo("Cotto d'Este");
        assertThat(norm("\u0E31\u0E34 Refin")).isEqualTo("Refin");
        assertThat(norm("โกลเด้น")).isEqualTo("โกลเด้น");
        assertThat(norm("แอลเอ")).isEqualTo("แอลเอ");
        assertThat(norm(null)).isNull();
        assertThat(norm("   ")).isNull();
    }

    // ---- 2. dim_stage -------------------------------------------------------------------------------------------

    @Test
    void dimStage_coversEveryJavaStageAndMarksWonFromOrderReceived() {
        List<String> seeded = jdbc.queryForList("SELECT raw_stage FROM analytics.dim_stage", Map.of(), String.class);
        assertThat(seeded).containsExactlyInAnyOrderElementsOf(DealStage.ORDER);
        for (String stage : DealStage.ORDER) {
            boolean won = DealStage.ORDER.indexOf(stage) >= DealStage.ORDER.indexOf(DealStage.ORDER_RECEIVED);
            assertThat(jdbc.queryForObject("SELECT is_won FROM analytics.dim_stage WHERE raw_stage = :s",
                Map.of("s", stage), Boolean.class)).as(stage).isEqualTo(won);
        }
        assertThat(jdbc.queryForObject("SELECT stage_no FROM analytics.dim_stage WHERE raw_stage = 'CLOSED_PAID'",
            Map.of(), Integer.class)).isEqualTo(15);
    }

    // ---- 3. stg_deal --------------------------------------------------------------------------------------------

    @Test
    void stgDeal_flagsTestDealsAndOwnerAndLifecycle() {
        long real = ticket("D-REAL", "Hotel lobby", repA, "NEGOTIATION", "ACTIVE", null);
        long flagged = ticket("D-FLAG", "Hotel lobby 2", repA, "NEGOTIATION", "ACTIVE", null);
        long named = ticket("D-NAME", "ทดสอบ ระบบ", repA, "NEGOTIATION", "ACTIVE", null);
        long cancelled = ticket("D-CAN", "Cancelled job", repA, "NEGOTIATION", "CANCELLED", null);
        jdbc.update("INSERT INTO analytics.test_deal (ticket_id, reason) VALUES (:t, 'manual')", Map.of("t", flagged));

        long overridden = ticket("D-OVR", "test unit", repA, "NEGOTIATION", "ACTIVE", null);
        jdbc.update("INSERT INTO analytics.test_deal (ticket_id, reason, is_test) VALUES (:t, 'real', false)", Map.of("t", overridden));

        assertThat(deal(overridden, "is_test")).isEqualTo(false);
        assertThat(deal(real, "is_test")).isEqualTo(false);
        assertThat(deal(flagged, "is_test")).isEqualTo(true);
        assertThat(deal(named, "is_test")).isEqualTo(true);
        assertThat(deal(real, "rep_id")).isEqualTo(repA);
        assertThat(deal(real, "rep_name")).isEqualTo("Alpha");
        assertThat(deal(real, "stage_no")).isEqualTo(9);
        assertThat(deal(real, "is_open")).isEqualTo(true);
        assertThat(deal(cancelled, "is_open")).isEqualTo(false);
    }

    @Test
    void stgDeal_testRegexMatchesWholeWordsOnly() {
        assertThat(isTestTitle("เทสโก้ โลตัส")).isFalse();
        assertThat(isTestTitle("The Latest Condo")).isFalse();
        assertThat(isTestTitle("Demolition & rebuild")).isFalse();
        assertThat(isTestTitle("TEST deal")).isTrue();
        assertThat(isTestTitle("ทดสอบระบบ")).isTrue();
        assertThat(isTestTitle("my demo")).isTrue();
    }

    @Test
    void stgDeal_ownerIsCreatedByNotKeyedBy() {
        long t = ticket("D-OWN", "Owner rule", repA, "NEGOTIATION", "ACTIVE", null);
        jdbc.update("UPDATE sales.ticket SET keyed_by = :k WHERE ticket_id = :t", Map.of("k", repB, "t", t));
        assertThat(deal(t, "rep_id")).isEqualTo(repA);
    }

    @Test
    void stgDeal_daysQuietMirrorsQuietRule() {
        long t = ticket("D-QUIET", "Quiet job", repA, "NEGOTIATION", "ACTIVE", null);
        jdbc.update("UPDATE sales.ticket SET created_at = now() - interval '60 days' WHERE ticket_id = :t", Map.of("t", t));
        assertThat(deal(t, "days_quiet")).isEqualTo(60);
        event(t, "STAGE_CHANGED", "now() - interval '40 days'");
        assertThat(deal(t, "days_quiet")).isEqualTo(40);
        event(t, "EDITED", "now() - interval '1 day'");
        event(t, "COMMENTED", "now() - interval '1 day'");
        assertThat(deal(t, "days_quiet")).as("EDITED / COMMENTED do not reset the clock").isEqualTo(40);
        event(t, "QUOTATION_ISSUED", "now() - interval '20 days'");
        assertThat(deal(t, "days_quiet")).isEqualTo(20);
        activity(t, "now() - interval '10 days'", weekStart);
        assertThat(deal(t, "days_quiet")).isEqualTo(10);
        assertThat(deal(t, "is_stale_30d")).isEqualTo(false);
        long old = ticket("D-OLD", "Old job", repA, "NEGOTIATION", "ACTIVE", null);
        jdbc.update("UPDATE sales.ticket SET created_at = now() - interval '45 days' WHERE ticket_id = :t", Map.of("t", old));
        assertThat(deal(old, "is_stale_30d")).isEqualTo(true);
    }

    @Test
    void stgDeal_isQuietHonoursVisitsAndDeliverCollectStages() {
        long quiet = ticket("Q-1", "Quiet", repA, "NEGOTIATION", "ACTIVE", null);
        long visit = ticket("Q-2", "Visit booked", repA, "NEGOTIATION", "ACTIVE", null);
        long farVisit = ticket("Q-3", "Visit far out", repA, "NEGOTIATION", "ACTIVE", null);
        long keyerVisit = ticket("Q-4", "Keyer visit", repA, "NEGOTIATION", "ACTIVE", null);
        long delivery = ticket("Q-5", "Delivery", repA, DealStage.DELIVERY_SCHEDULING, "ACTIVE", null);
        long fresh = ticket("Q-6", "Fresh", repA, "NEGOTIATION", "ACTIVE", null);
        jdbc.update("UPDATE sales.ticket SET created_at = now() - interval '30 days' WHERE ticket_id <> :f", Map.of("f", fresh));
        visitPlanned(visit, repA, "current_date + 3");
        visitPlanned(farVisit, repA, "current_date + 60");
        visitPlanned(keyerVisit, repB, "current_date + 3");
        assertThat(deal(quiet, "is_quiet")).isEqualTo(true);
        assertThat(deal(visit, "is_quiet")).isEqualTo(false);
        assertThat(deal(farVisit, "is_quiet")).isEqualTo(true);
        assertThat(deal(keyerVisit, "is_quiet")).isEqualTo(true);
        assertThat(deal(delivery, "is_quiet")).isEqualTo(false);
        assertThat(deal(fresh, "is_quiet")).isEqualTo(false);
    }

    @Test
    void stgDeal_deliverCollectFlagMatchesJavaBoardColumns() {
        for (String stage : DealStage.ORDER) {
            assertThat(jdbc.queryForObject("SELECT is_deliver_collect FROM analytics.dim_stage WHERE raw_stage = :s",
                Map.of("s", stage), Boolean.class)).as(stage)
                .isEqualTo(DealStage.boardColumnOf(stage) == DealStage.DELIVER_AND_COLLECT_COLUMN);
        }
    }

    @Test
    void stgDeal_waitBucketAndHygieneFlags() {
        long order = ticket("D-ORD", "Ordered", repA, DealStage.ORDER_RECEIVED, "ACTIVE", null);
        long accepted = ticket("D-ACC", "Accepted", repA, DealStage.NEGOTIATION, "ACTIVE", null);
        long plain = ticket("D-PLN", "Plain", repA, DealStage.NEGOTIATION, "ACTIVE", null);
        long done = ticket("D-DNE", "Done", repA, DealStage.CLOSED_PAID, "ACTIVE", null);
        quotation(accepted, "Q-ACC", "APPROVED", "1000.00");
        quotation(done, "Q-DNE", "APPROVED", "1000.00");
        assertThat(deal(order, "wait_bucket")).isEqualTo("ORDER_NO_DEPOSIT");
        assertThat(deal(accepted, "wait_bucket")).isEqualTo("QUOTE_ACCEPTED");
        assertThat(deal(plain, "wait_bucket")).isNull();
        assertThat(deal(done, "wait_bucket")).isNull();
        assertThat(deal(plain, "has_designer")).isEqualTo(false);
        jdbc.update("UPDATE sales.ticket SET designer_name = 'D', win_probability = 50, expected_quarter = '2026-Q4',"
            + " next_follow_up_at = current_date WHERE ticket_id = :t", Map.of("t", plain));
        assertThat(deal(plain, "has_designer")).isEqualTo(true);
        assertThat(deal(plain, "has_win_probability")).isEqualTo(true);
        assertThat(deal(plain, "has_expected_quarter")).isEqualTo(true);
        assertThat(deal(plain, "has_next_follow_up")).isEqualTo(true);
    }

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

    // ---- 4. fct_project_pipeline --------------------------------------------------------------------------------

    @Test
    void fctProjectPipeline_countsAProjectOnceAtItsLargestDeal() {
        long project = project("Synthetic tower");
        long d1 = ticket("P-1", "Bid one", repA, "NEGOTIATION", "ACTIVE", project);
        long d2 = ticket("P-2", "Bid two", repB, "NEGOTIATION", "ACTIVE", project);
        long testDeal = ticket("P-3", "demo bid", repA, "NEGOTIATION", "ACTIVE", project);
        long cancelled = ticket("P-4", "Dead bid", repA, "NEGOTIATION", "CANCELLED", project);
        long solo = ticket("P-5", "No project", repA, "NEGOTIATION", "ACTIVE", null);
        quotation(d1, "Q-P1", "APPROVED", "1000.00");
        quotation(d2, "Q-P2", "APPROVED", "1500.00");
        quotation(testDeal, "Q-P3", "APPROVED", "99999.00");
        quotation(cancelled, "Q-P4", "APPROVED", "88888.00");
        quotation(solo, "Q-P5", "APPROVED", "200.00");

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM analytics.fct_project_pipeline ORDER BY value DESC", Map.of());
        assertThat(rows).hasSize(2);
        assertThat(num(rows.get(0).get("value"))).isEqualByComparingTo("1500");
        assertThat(rows.get(0).get("deals")).isEqualTo(2L);
        assertThat(num(rows.get(1).get("value"))).isEqualByComparingTo("200");
    }

    // ---- 5. stg_quote_line --------------------------------------------------------------------------------------

    @Test
    void stgQuoteLine_onlyLiveTileLinesWithNormalisedBrandAndSqm() {
        long t = ticket("L-1", "Lines", repA, DealStage.ORDER_RECEIVED, "ACTIVE", null);
        long open = ticket("L-2", "Lines open", repA, "NEGOTIATION", "ACTIVE", null);
        long cancelled = ticket("L-3", "Lines cancelled", repA, "NEGOTIATION", "CANCELLED", null);
        long testDeal = ticket("L-4", "demo lines", repA, "NEGOTIATION", "ACTIVE", null);
        long q1 = quotation(t, "QL-1", "APPROVED", "100.00");
        tile(q1, 1, "REFIN", "M1", "100.00", "12.50", null, null);
        tile(q1, 2, "Rquipe", "M2", "50.00", null, 10, "0.36");
        long plain = jdbc.queryForObject("INSERT INTO sales.quotation_item (quotation_id, seq, line_type, unit_price, amount)"
            + " VALUES (:q, 3, 'PLAIN', 1, 1) RETURNING quotation_item_id", Map.of("q", q1), Long.class);
        assertThat(plain).isPositive();
        long superseded = quotation(t, "QL-S", "SUPERSEDED", "1.00");
        tile(superseded, 1, "Refin", "X", "1.00", "1", null, null);
        long draft = quotation(open, "QL-D", "DRAFT", "1.00");
        tile(draft, 1, "Refin", "X", "1.00", "1", null, null);
        long qc = quotation(cancelled, "QL-C", "APPROVED", "1.00");
        tile(qc, 1, "Refin", "X", "1.00", "1", null, null);
        long qt = quotation(testDeal, "QL-T", "APPROVED", "1.00");
        tile(qt, 1, "Refin", "X", "1.00", "1", null, null);
        long qo = quotation(open, "QL-O", "APPROVED", "20.00");
        tile(qo, 1, "refin", "M3", "20.00", "2", null, null);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM analytics.stg_quote_line ORDER BY quotation_id, model", Map.of());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("brand")).isEqualTo("Refin");
        assertThat(num(rows.get(0).get("sqm"))).isEqualByComparingTo("12.50");
        assertThat(rows.get(0).get("is_ordered")).isEqualTo(true);
        assertThat(rows.get(1).get("brand")).isEqualTo("Equipe");
        assertThat(num(rows.get(1).get("sqm"))).isEqualByComparingTo("3.60");
        assertThat(rows.get(2).get("is_ordered")).isEqualTo(false);
        assertThat(rows.get(0).get("rep_id")).isEqualTo(repA);
    }

    @Test
    void stgQuoteLine_onlyTheDealsValueQuotationAndBrandMetricsCountHeadlineDealsOnce() {
        long project = project("Synthetic plaza");
        long big = ticket("V-1", "Bid big", repA, "NEGOTIATION", "ACTIVE", project);
        long small = ticket("V-2", "Bid small", repB, "NEGOTIATION", "ACTIVE", project);
        long oldQ = quotation(big, "QV-OLD", "APPROVED", "100.00");
        long newQ = quotation(big, "QV-NEW", "APPROVED", "400.00");
        tile(oldQ, 1, "Refin", "M1", "100.00", "10", null, null);
        tile(newQ, 1, "Refin", "M1", "400.00", "40", null, null);
        long q2 = quotation(small, "QV-S", "APPROVED", "50.00");
        tile(q2, 1, "Refin", "M1", "50.00", "5", null, null);

        assertThat(jdbc.queryForList("SELECT quotation_id FROM analytics.stg_quote_line WHERE ticket_id = :t",
            Map.of("t", big), Long.class)).containsExactly(newQ);
        assertThat(deal(big, "is_project_headline")).isEqualTo(true);
        assertThat(deal(small, "is_project_headline")).isEqualTo(false);
        compute();
        assertThat(metric("quoted_value", "brand=Refin")).isEqualByComparingTo("400");
        assertThat(metric("quoted_sqm", "brand=Refin")).isEqualByComparingTo("40");
    }

    @Test
    void projectHeadline_tieBreaksByTicketIdAndIgnoresTestAndClosedDeals() {
        long project = project("Tie tower");
        long a = ticket("H-1", "Bid a", repA, "NEGOTIATION", "ACTIVE", project);
        long b = ticket("H-2", "Bid b", repB, "NEGOTIATION", "ACTIVE", project);
        long t = ticket("H-3", "demo bid", repA, "NEGOTIATION", "ACTIVE", project);
        quotation(a, "QH-1", "APPROVED", "100.00");
        quotation(b, "QH-2", "APPROVED", "100.00");
        quotation(t, "QH-3", "APPROVED", "900.00");
        assertThat(deal(a, "is_project_headline")).isEqualTo(true);
        assertThat(deal(b, "is_project_headline")).isEqualTo(false);
        assertThat(deal(t, "is_project_headline")).isEqualTo(false);
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

    @Test
    void computeWeek_oldWeekKeepsItsSnapshotButActivityIsRecomputed() {
        seedMetricFixture();
        LocalDate old = weekStart.minusDays(21);
        jdbc.update("INSERT INTO analytics.metric_weekly (week_start, metric_key, dimension, value)"
            + " VALUES (:w, 'pipeline_value_raw', '', 12345), (:w, 'rep_activities_week', 'rep=' || CAST(:a AS text), 99)",
            Map.of("w", old, "a", repA));
        long t = jdbc.queryForObject("SELECT ticket_id FROM sales.ticket WHERE code = 'M-3'", Map.of(), Long.class);
        activity(t, "now()", old.plusDays(1));
        jdbc.queryForObject("SELECT analytics.compute_week(:w)", Map.of("w", old), Object.class);

        assertThat(metricAt(old, "pipeline_value_raw", "")).isEqualByComparingTo("12345");
        assertThat(metricAt(old, "rep_activities_week", "rep=" + repA)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics.metric_weekly WHERE week_start = :w"
            + " AND metric_key NOT IN ('rep_stage_moves_week', 'rep_activities_week', 'pipeline_value_raw')",
            Map.of("w", old), Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics.run_log WHERE week_start = :w",
            Map.of("w", old), Long.class)).isZero();
    }

    @Test
    void computeWeek_justEndedWeekStillGetsSnapshot() {
        seedMetricFixture();
        LocalDate previous = weekStart.minusDays(7);
        jdbc.queryForObject("SELECT analytics.compute_week(:w)", Map.of("w", previous), Object.class);
        assertThat(metricAt(previous, "pipeline_value_raw", "")).isEqualByComparingTo("2700");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analytics.run_log WHERE week_start = :w",
            Map.of("w", previous), Long.class)).isPositive();
    }

    @Test
    void computeWeek_weekWindowIsBangkokTime() {
        long t = ticket("W-1", "Window", repA, "NEGOTIATION", "ACTIVE", null);
        // Sunday 18:00 UTC = Monday 01:00 Bangkok -> new week; Sunday 16:00 UTC = Sunday 23:00 Bangkok -> old week.
        event(t, "STAGE_CHANGED", "(CAST('" + weekStart + "' AS timestamp) AT TIME ZONE 'UTC') - interval '6 hours'");
        event(t, "STAGE_CHANGED", "(CAST('" + weekStart + "' AS timestamp) AT TIME ZONE 'UTC') - interval '8 hours'");
        compute();
        LocalDate previous = weekStart.minusDays(7);
        jdbc.queryForObject("SELECT analytics.compute_week(:w)", Map.of("w", previous), Object.class);
        assertThat(metric("rep_stage_moves_week", "rep=" + repA)).isEqualByComparingTo("1");
        assertThat(metricAt(previous, "rep_stage_moves_week", "rep=" + repA)).isEqualByComparingTo("1");
    }

    // ---- 7. dq_check --------------------------------------------------------------------------------------------

    @Test
    void dqCheck_failsWhenATicketStageIsMissingFromDimStage() {
        ticket("DQ-1", "Dq", repA, "NEGOTIATION", "ACTIVE", null);
        assertThat(jdbc.queryForList("SELECT check_key FROM analytics.dq_check WHERE NOT passed", Map.of(), String.class)).isEmpty();
        jdbc.update("DELETE FROM analytics.dim_stage WHERE raw_stage = 'NEGOTIATION'", Map.of());
        assertThat(jdbc.queryForList("SELECT check_key FROM analytics.dq_check WHERE NOT passed", Map.of(), String.class))
            .containsExactly("stage_mapped");
    }

    @Test
    void dqCheck_wonDealWithoutValueFails() {
        long t = ticket("DQ-W", "Won", repA, DealStage.ORDER_RECEIVED, "ACTIVE", null);
        assertThat(failedChecks()).containsExactly("won_deal_has_value");
        jdbc.update("UPDATE sales.ticket SET imported_value = 10 WHERE ticket_id = :t", Map.of("t", t));
        assertThat(failedChecks()).isEmpty();
    }

    @Test
    void dqCheck_regexFlaggedTestDealNeedsAnExplicitReview() {
        long t = ticket("DQ-T", "test deal", repA, "NEGOTIATION", "ACTIVE", null);
        assertThat(failedChecks()).containsExactly("test_flag_reviewed");
        assertThat(jdbc.queryForObject("SELECT detail FROM analytics.dq_check WHERE check_key = 'test_flag_reviewed'",
            Map.of(), String.class)).contains("1");
        jdbc.update("INSERT INTO analytics.test_deal (ticket_id, reason, is_test) VALUES (:t, 'checked', false)", Map.of("t", t));
        assertThat(failedChecks()).isEmpty();
    }

    @Test
    void dqCheck_hasNoTautologicalChecks() {
        assertThat(jdbc.queryForList("SELECT check_key FROM analytics.dq_check", Map.of(), String.class))
            .containsExactlyInAnyOrder("stage_mapped", "quote_line_brand_present", "won_deal_has_value", "test_flag_reviewed");
    }

    // ---- 8. metrics.yml -----------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void metricsYml_listsExactlyTheKeysComputeWeekWrites() throws IOException {
        seedMetricFixture();
        compute();
        Set<String> written = new TreeSet<>(jdbc.queryForList(
            "SELECT DISTINCT metric_key FROM analytics.metric_weekly", Map.of(), String.class));
        Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("..", "analytics", "metrics.yml")));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("metrics");
        Set<String> documented = new TreeSet<>();
        for (Map<String, Object> e : entries) {
            documented.add((String) e.get("key"));
            assertThat(e.keySet()).contains("label_th", "definition", "source", "grain", "audience");
        }
        assertThat(documented).isEqualTo(written);
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

    private boolean isTestTitle(String title) {
        long t = ticket("TT-" + Math.abs(title.hashCode()), title, repA, "NEGOTIATION", "ACTIVE", null);
        return (Boolean) deal(t, "is_test");
    }

    private void visitPlanned(long ticketId, long owner, String dateSql) {
        jdbc.update("INSERT INTO sales.visit (owner_id, ticket_id, contact_name, planned_date) VALUES (:o, :t, 'c', "
            + dateSql + ")", Map.of("o", owner, "t", ticketId));
    }

    private BigDecimal metricAt(LocalDate week, String key, String dimension) {
        return jdbc.queryForObject("SELECT value FROM analytics.metric_weekly WHERE week_start = :w AND metric_key = :k"
            + " AND dimension = :d", Map.of("w", week, "k", key, "d", dimension), BigDecimal.class);
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

    private String norm(String raw) {
        return jdbc.queryForObject("SELECT analytics.norm_brand(:r)", new MapSqlParameterSource("r", raw), String.class);
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

    private void event(long ticketId, String kind, String createdAtSql) {
        jdbc.update("INSERT INTO sales.ticket_event (ticket_id, actor_id, kind, created_at) VALUES (:t, :a, :k, "
            + createdAtSql + ")", Map.of("t", ticketId, "a", repA, "k", kind));
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
