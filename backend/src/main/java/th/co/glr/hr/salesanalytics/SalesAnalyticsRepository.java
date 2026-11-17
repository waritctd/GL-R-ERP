package th.co.glr.hr.salesanalytics;

import java.sql.Types;
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
}
