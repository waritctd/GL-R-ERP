package th.co.glr.hr.salesboard;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.salesboard.VisitDtos.VisitDto;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * sales.visit (V211). Scope lives in the WHERE clause: {@link #findOwn} adds {@code v.owner_id = :ownerId};
 * {@link #findAll} has no owner predicate and is reached only through {@code VisitService}'s sales_manager/ceo gate.
 * Both take an inclusive {@code planned_date} range (the service caps it at 31 days) and order by
 * {@code planned_date, planned_time NULLS LAST, visit_id} — mockApi.salesBoard.visits mirrors this ORDER BY.
 */
@Repository
public class VisitRepository {
    private static final String SELECT = """
        SELECT v.visit_id, v.owner_id,
               NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name,
               v.ticket_id, COALESCE(NULLIF(TRIM(t.title), ''), t.customer_name) AS ticket_title,
               v.contact_name, v.planned_date, v.planned_time, v.status, v.done_at, v.note,
               CASE WHEN t.ticket_id IS NOT NULL AND v.owner_id <> t.created_by
                    THEN NULLIF(TRIM(CONCAT_WS(' ', de.first_name_th, de.last_name_th)), '') END
                 AS deal_moved_to_name,
               -- Why the deal is someone else's: it was reassigned at or after this visit was created (the EDITED
               -- event DealQuotationService#moveOwnerToSalesRep writes), versus the visit's owner never holding it
               -- (a keyer's or a manager's own visit). Review LOW: only the first is "the deal moved".
               -- LIKE prefix% on the stored message: editing the Thai prefix would break matching for stored events.
               (t.ticket_id IS NOT NULL AND v.owner_id <> t.created_by AND EXISTS (
                   SELECT 1 FROM sales.ticket_event te
                    WHERE te.ticket_id = t.ticket_id AND te.kind = 'EDITED'
                      AND te.message LIKE '{OWNER_CHANGED_PREFIX}%'
                      AND te.created_at >= v.created_at)) AS deal_moved
          FROM sales.visit v
          JOIN hr.employee e ON e.employee_id = v.owner_id
          LEFT JOIN sales.ticket t ON t.ticket_id = v.ticket_id
          LEFT JOIN hr.employee de ON de.employee_id = t.created_by
        """.replace("{OWNER_CHANGED_PREFIX}", TicketRepository.OWNER_CHANGED_MESSAGE_PREFIX);
    private static final String ORDER = " ORDER BY v.planned_date, v.planned_time NULLS LAST, v.visit_id";

    private final NamedParameterJdbcTemplate jdbc;

    public VisitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The owner's own visits planned in [from, to]. */
    public List<VisitDto> findOwn(long ownerId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + " WHERE v.owner_id = :ownerId AND v.planned_date BETWEEN :from AND :to" + ORDER,
            Map.of("ownerId", ownerId, "from", from, "to", to), VisitRepository::map);
    }

    /** Every owner's visits planned in [from, to]. Only for the sales_manager/ceo team view. */
    public List<VisitDto> findAll(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + " WHERE v.planned_date BETWEEN :from AND :to" + ORDER,
            Map.of("from", from, "to", to), VisitRepository::map);
    }

    public Optional<VisitDto> findById(long visitId) {
        return jdbc.query(SELECT + " WHERE v.visit_id = :id", Map.of("id", visitId), VisitRepository::map)
            .stream().findFirst();
    }

    /**
     * The owner's DONE visit with the same deal, contact and date, created in the last {@code seconds} seconds.
     *
     * <p>Known limitation (review NEW-3, accepted): this is check-then-insert with no lock or unique key, so two
     * identical requests that arrive at the same instant can both miss each other and insert twice. The UI disables
     * the save button while a log is pending, which covers the double tap; a true race needs two in-flight requests
     * from one rep within milliseconds. Closing it fully would need an advisory lock or a partial unique index.
     */
    public Optional<VisitDto> findRecentDoneLog(long ownerId, Long ticketId, String contactName, LocalDate plannedDate,
                                                int seconds) {
        return jdbc.query(SELECT + """
             WHERE v.owner_id = :ownerId AND v.ticket_id IS NOT DISTINCT FROM :ticketId
               AND v.contact_name = :contactName AND v.planned_date = :plannedDate AND v.status = 'DONE'
               AND v.created_at > now() - make_interval(secs => :seconds)
             ORDER BY v.visit_id DESC LIMIT 1
            """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId).addValue("ticketId", ticketId, java.sql.Types.BIGINT)
                .addValue("contactName", contactName).addValue("plannedDate", plannedDate)
                .addValue("seconds", seconds), VisitRepository::map).stream().findFirst();
    }

    /** Inserts a visit; {@code done} creates it DONE with {@code done_at = now()}. */
    public long insert(long ownerId, Long ticketId, String contactName, LocalDate plannedDate, LocalTime plannedTime,
                       String note, boolean done) {
        return jdbc.queryForObject("""
            INSERT INTO sales.visit (owner_id, ticket_id, contact_name, planned_date, planned_time, note, status,
                                     done_at)
            VALUES (:ownerId, :ticketId, :contactName, :plannedDate, :plannedTime, :note, :status,
                    CASE WHEN :done THEN now() END)
            RETURNING visit_id
            """,
            new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("ticketId", ticketId)
                .addValue("contactName", contactName)
                .addValue("plannedDate", plannedDate)
                .addValue("plannedTime", plannedTime == null ? null : Time.valueOf(plannedTime))
                .addValue("note", note)
                .addValue("status", done ? VisitDtos.DONE : VisitDtos.PLANNED)
                .addValue("done", done),
            Long.class);
    }

    /**
     * PLANNED → DONE, only from PLANNED (the WHERE is the race guard: a second tick updates zero rows). A non-null
     * {@code note} replaces the planned note. Returns the rows updated.
     */
    public int markDone(long visitId, String note) {
        return jdbc.update("""
            UPDATE sales.visit
               SET status = 'DONE', done_at = now(), note = COALESCE(:note, note), updated_at = now()
             WHERE visit_id = :id AND status = 'PLANNED'
            """, new MapSqlParameterSource().addValue("id", visitId).addValue("note", note));
    }

    /** PLANNED → CANCELLED, only from PLANNED. Returns the rows updated. */
    public int cancel(long visitId) {
        return jdbc.update("""
            UPDATE sales.visit SET status = 'CANCELLED', updated_at = now()
             WHERE visit_id = :id AND status = 'PLANNED'
            """, Map.of("id", visitId));
    }

    private static VisitDto map(ResultSet rs, int rowNum) throws SQLException {
        long ticketId = rs.getLong("ticket_id");
        Long ticket = rs.wasNull() ? null : ticketId;
        Time time = rs.getTime("planned_time");
        Timestamp doneAt = rs.getTimestamp("done_at");
        return new VisitDto(rs.getLong("visit_id"), rs.getLong("owner_id"), rs.getString("owner_name"), ticket,
            rs.getString("ticket_title"), rs.getString("contact_name"), rs.getObject("planned_date", LocalDate.class),
            time == null ? null : time.toLocalTime(), rs.getString("status"),
            doneAt == null ? null : doneAt.toInstant(), rs.getString("note"), rs.getString("deal_moved_to_name"),
            rs.getBoolean("deal_moved"));
    }
}
