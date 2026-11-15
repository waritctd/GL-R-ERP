package th.co.glr.hr.ticket;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one "quiet" (stale) rule for a deal — spec 2026-11-15 §7.5. A deal is last touched at the latest of its
 * {@code created_at}, any {@code deal_activity} row, and a {@code STAGE_CHANGED} / {@code QUOTATION_ISSUED} event.
 * It is quiet when it is {@code ACTIVE}, not in the deliver &amp; collect board column, and last touched more than
 * {@link #quietDays()} days ago. The threshold is {@code app.sales.quiet-days} (default 14).
 *
 * <p>The SQL fragments need a {@code :quietDays} bind parameter; pass {@link #quietDays()}.
 */
@Component
public class QuietRule {
    public static final int DEFAULT_QUIET_DAYS = 14;

    private final NamedParameterJdbcTemplate jdbc;
    private final int quietDays;

    @Autowired
    public QuietRule(NamedParameterJdbcTemplate jdbc,
                     @Value("${app.sales.quiet-days:" + DEFAULT_QUIET_DAYS + "}") int quietDays) {
        this.jdbc = jdbc;
        this.quietDays = quietDays;
    }

    public int quietDays() { return quietDays; }

    /** SQL {@code timestamptz} expression: when the deal aliased {@code t} was last touched. */
    public static String lastTouchedSql(String t) {
        return """
            GREATEST(%1$s.created_at,
                (SELECT max(a.created_at) FROM sales.deal_activity a WHERE a.ticket_id = %1$s.ticket_id),
                (SELECT max(e.created_at) FROM sales.ticket_event e
                  WHERE e.ticket_id = %1$s.ticket_id AND e.kind IN ('%2$s', '%3$s')))
            """.formatted(t, TicketEventKind.STAGE_CHANGED, TicketEventKind.QUOTATION_ISSUED);
    }

    /** SQL boolean expression: the deal aliased {@code t} is quiet. Needs {@code :quietDays} bound. */
    public static String quietSql(String t) {
        return """
            (%1$s.lifecycle = '%2$s'
             AND COALESCE(%1$s.sales_stage, '') NOT IN (%3$s)
             AND %4$s < now() - make_interval(days => :quietDays))
            """.formatted(t, DealLifecycle.ACTIVE, deliverAndCollectStagesSql(), lastTouchedSql(t));
    }

    public boolean isQuiet(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT " + quietSql("t") + " FROM sales.ticket t WHERE t.ticket_id = :id",
            Map.of("id", ticketId, "quietDays", quietDays), Boolean.class));
    }

    private static String deliverAndCollectStagesSql() {
        return DealStage.ORDER.stream()
            .filter(s -> DealStage.boardColumnOf(s) == DealStage.DELIVER_AND_COLLECT_COLUMN)
            .map(s -> "'" + s + "'")
            .collect(Collectors.joining(", "));
    }
}
