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
 * It is quiet when it is {@code ACTIVE}, not in the deliver &amp; collect board column, last touched more than
 * {@link #quietDays()} days ago, and has no booked visit ahead: a {@code sales.visit} that is PLANNED, booked by the
 * deal's current owner ({@code created_by}; not the keyer — review NEW-A), with {@code planned_date} from today to today + {@link #quietDays()}
 * (Asia/Bangkok) holds the deal out of ควรตามต่อ (spec §7.3 — booking a visit is the follow-up). A plan further out
 * does not (review NEW-1: a 2099 plan must not hide a deal forever), nor does an ex-owner's plan after the deal was
 * reassigned (review NEW-2). Once that date passes unticked the deal can go quiet again; a DONE visit resets the clock through
 * its SITE_VISIT activity instead. The threshold is {@code app.sales.quiet-days} (default 14).
 *
 * <p>The SQL fragments need a {@code :quietDays} bind parameter; pass {@link #quietDays()}.
 */
@Component
public class QuietRule {
    public static final int DEFAULT_QUIET_DAYS = 14;

    private static final java.time.ZoneId BANGKOK = java.time.ZoneId.of("Asia/Bangkok");

    private final NamedParameterJdbcTemplate jdbc;
    private final int quietDays;
    private final java.time.Clock clock;

    @Autowired
    public QuietRule(NamedParameterJdbcTemplate jdbc,
                     @Value("${app.sales.quiet-days:" + DEFAULT_QUIET_DAYS + "}") int quietDays) {
        this(jdbc, quietDays, java.time.Clock.systemUTC());
    }

    /** Tests fix the clock to an instant at which the Bangkok and UTC calendar dates differ. */
    public QuietRule(NamedParameterJdbcTemplate jdbc, int quietDays, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.quietDays = quietDays;
        this.clock = clock;
    }

    public int quietDays() { return quietDays; }

    /** Today on the Asia/Bangkok calendar, whatever zone the clock carries — the {@code :returnToday} bind parameter
     * {@link #returningSql} needs. Computed here (not in SQL) so the day boundary is testable. */
    public java.time.LocalDate bangkokToday() {
        return java.time.LocalDate.ofInstant(clock.instant(), BANGKOK);
    }

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
             AND %4$s < now() - make_interval(days => :quietDays)
             AND NOT %5$s)
            """.formatted(t, DealLifecycle.ACTIVE, deliverAndCollectStagesSql(), lastTouchedSql(t),
                upcomingVisitSql(t));
    }

    /**
     * SQL boolean: the deal aliased {@code t} is a PAUSED deal coming back — {@code ON_HOLD} or {@code DORMANT} with a
     * วันที่จะกลับมาดู ({@code pause_return_on}, V220) on or before today (Asia/Bangkok). Owner ruling 2026-11-16:
     * such a deal shows in its owner's ควรตามต่อ and counts in the manager's quiet figures the way a quiet deal does,
     * until the rep resumes it (which clears the date) or re-pauses it with a later one. A pause with no date never
     * matches, exactly as before V220. Deliberately NOT part of {@link #quietSql}: the board, the deal summary's
     * {@code stale} flag and every other quiet reader stay ACTIVE-only. Needs {@code :returnToday} bound to
     * {@link #bangkokToday()}.
     */
    public static String returningSql(String t) {
        return """
            (%1$s.lifecycle IN ('%2$s', '%3$s')
             AND %1$s.pause_return_on IS NOT NULL
             AND %1$s.pause_return_on <= CAST(:returnToday AS date))
            """.formatted(t, DealLifecycle.ON_HOLD, DealLifecycle.DORMANT);
    }

    /**
     * SQL boolean: the deal aliased {@code t} has a PLANNED visit by its current owner ({@code created_by} only: a
     * reassignment through TicketRepository#moveOwnerToSalesRep leaves the ex-owner as the keyer, so the keyer must
     * not count — review NEW-A), dated from today to
     * today + {@code :quietDays} (Asia/Bangkok). Needs {@code :quietDays} bound, as {@link #quietSql} already does.
     */
    public static String upcomingVisitSql(String t) {
        return """
            EXISTS (SELECT 1 FROM sales.visit uv
                     WHERE uv.ticket_id = %1$s.ticket_id AND uv.status = 'PLANNED'
                       AND uv.owner_id = %1$s.created_by
                       AND uv.planned_date BETWEEN (now() AT TIME ZONE 'Asia/Bangkok')::date
                                               AND (now() AT TIME ZONE 'Asia/Bangkok')::date + :quietDays)
            """.formatted(t);
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
