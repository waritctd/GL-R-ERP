package th.co.glr.hr.salesanalytics;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SalesAnalyticsRepository {
    /** The stored document is returned as raw JSON text so the service never re-shapes it. */
    public record Row(String payloadJson, String asOf, OffsetDateTime uploadedAt) {}

    public record MetricDto(String key, String dimension, String label, Double value, Double previous) {}
    public record CheckDto(String key, boolean passed, String detail) {}
    public record MetricsDto(LocalDate weekStart, OffsetDateTime computedAt, List<MetricDto> metrics, List<CheckDto> checks) {}

    private final NamedParameterJdbcTemplate jdbc;

    public SalesAnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String payloadJson, String asOf, long uploadedBy) {
        jdbc.update("""
            INSERT INTO sales.sales_analytics_snapshot (payload, as_of, uploaded_by)
            VALUES (CAST(:payload AS jsonb), :asOf, :by)
            """, new MapSqlParameterSource().addValue("payload", payloadJson).addValue("asOf", asOf, Types.VARCHAR)
                .addValue("by", uploadedBy));
    }

    public Optional<Row> findLatest() {
        List<Row> rows = jdbc.query("""
            SELECT payload::text AS payload, as_of, uploaded_at
              FROM sales.sales_analytics_snapshot
             ORDER BY uploaded_at DESC, snapshot_id DESC
             LIMIT 1
            """, Map.of(), (rs, i) -> new Row(rs.getString("payload"), rs.getString("as_of"),
                rs.getObject("uploaded_at", OffsetDateTime.class)));
        return rows.stream().findFirst();
    }

    /**
     * The headline week is the latest ENDED Bangkok week (week_start + 7 <= today) carrying the pipeline snapshot: the week-based rep metrics exist for later
     * weeks too, but a week without the snapshot would show a page of blanks. A rep's label is the name the sales board
     * shows (Thai first + last name), else the raw dimension, so an unknown id is visible rather than hidden.
     */
    public MetricsDto metrics(LocalDate today) {
        LocalDate week = jdbc.queryForObject(
            "SELECT max(week_start) FROM analytics.metric_weekly WHERE metric_key = 'pipeline_value_dedup' AND week_start + 7 <= :today",
            new MapSqlParameterSource().addValue("today", today), LocalDate.class);
        if (week == null) return new MetricsDto(null, null, List.of(), List.of());
        var p = new MapSqlParameterSource().addValue("w", week).addValue("prev", week.minusDays(7));
        List<MetricDto> metrics = jdbc.query("""
            SELECT m.metric_key, m.dimension, m.value, p.value AS previous,
                   CASE WHEN m.dimension LIKE 'rep=%'
                        THEN COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''), m.dimension)
                        WHEN m.dimension LIKE 'brand=%' THEN substr(m.dimension, 7)
                        ELSE m.dimension END AS label
              FROM analytics.metric_weekly m
              LEFT JOIN analytics.metric_weekly p
                     ON p.week_start = :prev AND p.metric_key = m.metric_key AND p.dimension = m.dimension
              LEFT JOIN hr.employee e
                     ON e.employee_id = CASE WHEN m.dimension ~ '^rep=[0-9]+$' THEN substr(m.dimension, 5)::bigint END
             WHERE m.week_start = :w
             ORDER BY m.metric_key, m.dimension
            """, p, (rs, i) -> new MetricDto(rs.getString("metric_key"), rs.getString("dimension"), rs.getString("label"),
                dbl(rs, "value"), dbl(rs, "previous")));
        OffsetDateTime computedAt = jdbc.queryForObject(
            "SELECT max(computed_at) FROM analytics.metric_weekly WHERE week_start = :w", p, OffsetDateTime.class);
        // run_log keeps one row per check per run: the latest run of each check is the current verdict.
        List<CheckDto> checks = jdbc.query("""
            SELECT DISTINCT ON (check_key) check_key, passed, detail
              FROM analytics.run_log WHERE week_start = :w
             ORDER BY check_key, created_at DESC, run_id DESC
            """, p, (rs, i) -> new CheckDto(rs.getString("check_key"), rs.getBoolean("passed"), rs.getString("detail")));
        return new MetricsDto(week, computedAt, metrics, checks);
    }

    private static Double dbl(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }
}
