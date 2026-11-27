package th.co.glr.hr.deliverytrip;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.RoundItem;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.Trip;

@Repository
public class DeliveryTripRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryTripRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Who hears about a round's plan. */
    public record Recipients(Long ownerId, Long keyerId, Long requesterId, String displayCode, String customerName) {}

    private static final String DISPLAY_CODE = "CASE WHEN t.child_kind = 'ADD_ON' THEN t.code ELSE t.code || '-1' END";

    /** Pieces a round may still deliver: what it covers less what left against it, never more than the item still needs. */
    private static final String REMAINING = """
        COALESCE((SELECT SUM(GREATEST(0, LEAST(
                    l.qty - COALESCE((SELECT SUM(dri.qty) FROM sales.delivery_record_item dri
                                        JOIN sales.delivery_record dr ON dr.delivery_id = dri.delivery_id
                                       WHERE dr.delivery_request_id = l.delivery_request_id AND dri.item_id = l.item_id), 0),
                    ti.qty - ti.qty_not_delivered - COALESCE(ti.qty_delivered, 0))))
                    FROM sales.delivery_request_line l JOIN sales.ticket_item ti ON ti.item_id = l.item_id
                   WHERE l.delivery_request_id = r.delivery_request_id), 0)""";

    private static final String ITEM_COLUMNS = """
        r.delivery_request_id, r.ticket_id, r.round_no, r.status, %s AS display_code, t.customer_name,
        p.name AS project_name, NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS owner_name,
        r.note, r.sent_at, r.forwarded_at, r.requested_date, r.customer_pickup, %s AS remaining""".formatted(DISPLAY_CODE, REMAINING);

    private static final String ITEM_FROM = """
        FROM sales.delivery_request r
        JOIN sales.ticket t ON t.ticket_id = r.ticket_id
        LEFT JOIN customers.project p ON p.project_id = t.project_id
        LEFT JOIN hr.employee e ON e.employee_id = t.created_by""";

    /** Every SENT, FORWARDED and INVOICED round of a live deal that is on no open trip, by requested date. */
    public List<RoundItem> unplanned() {
        return jdbc.query("SELECT " + ITEM_COLUMNS + """
            , EXISTS (SELECT 1 FROM sales.delivery_trip_round x
                       WHERE x.delivery_request_id = r.delivery_request_id AND x.state = 'DELAYED') AS delayed,
              CAST(NULL AS text) AS state
            """ + ITEM_FROM + """
             WHERE r.status IN ('SENT','FORWARDED','INVOICED') AND t.lifecycle = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM sales.delivery_trip_round x
                                WHERE x.delivery_request_id = r.delivery_request_id AND x.state = 'PLANNED'
                                  AND r.status = 'INVOICED')
               AND (r.status <> 'INVOICED' OR
            """ + REMAINING + """
             > 0)
             ORDER BY r.requested_date NULLS LAST, r.sent_at NULLS LAST, r.delivery_request_id
            """, Map.of(), (rs, n) -> item(rs));
    }

    /** Every open trip, plus the closed (not cancelled) ones dated inside {@code from}..{@code to}. */
    public List<Trip> trips(LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT * FROM sales.delivery_trip
             WHERE status = 'OPEN' OR (status = 'DONE' AND delivery_date BETWEEN :from AND :to)
             ORDER BY delivery_date, delivery_trip_id
            """, Map.of("from", from, "to", to), (rs, n) -> trip(rs, List.of()))
            .stream().map(this::withRounds).toList();
    }

    public Optional<Trip> findTrip(long id) {
        return jdbc.query("SELECT * FROM sales.delivery_trip WHERE delivery_trip_id = :id", Map.of("id", id),
            (rs, n) -> trip(rs, List.of())).stream().findFirst().map(this::withRounds);
    }

    /** Serializes every change to one trip. */
    public void lockTrip(long id) {
        jdbc.queryForList("SELECT 1 FROM sales.delivery_trip WHERE delivery_trip_id = :id FOR UPDATE", Map.of("id", id));
    }

    private Trip withRounds(Trip t) {
        List<RoundItem> items = jdbc.query("SELECT " + ITEM_COLUMNS + ", FALSE AS delayed, x.state " + ITEM_FROM + """
             JOIN sales.delivery_trip_round x ON x.delivery_request_id = r.delivery_request_id
             WHERE x.delivery_trip_id = :id AND x.state <> 'REMOVED'
             ORDER BY r.delivery_request_id
            """, Map.of("id", t.id()), (rs, n) -> item(rs));
        return new Trip(t.id(), t.deliveryDate(), t.route(), t.timeSlot(), t.vehicle(), t.note(), t.status(), items);
    }

    /** The routes used before, newest first (one-tap chips). */
    public List<String> recentRoutes() {
        return new ArrayList<>(jdbc.queryForList(
            "SELECT route FROM sales.delivery_trip GROUP BY route ORDER BY MAX(delivery_trip_id) DESC LIMIT 6",
            Map.of(), String.class));
    }

    public boolean isPickup(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT customer_pickup FROM sales.delivery_request WHERE delivery_request_id = :id", Map.of("id", roundId), Boolean.class));
    }

    /** INVOICED, on a live deal, with goods left, not a customer pickup: the only rounds a trip may take. */
    public boolean plannable(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 " + ITEM_FROM
            + " WHERE r.delivery_request_id = :id AND r.status = 'INVOICED' AND NOT r.customer_pickup AND t.lifecycle = 'ACTIVE' AND " + REMAINING
            + " > 0)", Map.of("id", roundId), Boolean.class));
    }

    public long insertTrip(LocalDate date, String route, String slot, String vehicle, String note, long by) {
        return jdbc.queryForObject("""
            INSERT INTO sales.delivery_trip (delivery_date, route, time_slot, vehicle, note, created_by)
            VALUES (:d, :r, :s, :v, :n, :by) RETURNING delivery_trip_id
            """, params(date, route, slot, vehicle, note).addValue("by", by), Long.class);
    }

    public void updateTrip(long id, LocalDate date, String route, String slot, String vehicle, String note) {
        jdbc.update("""
            UPDATE sales.delivery_trip SET delivery_date = :d, route = :r, time_slot = :s, vehicle = :v, note = :n
             WHERE delivery_trip_id = :id
            """, params(date, route, slot, vehicle, note).addValue("id", id));
    }

    public void setStatus(long id, String status) {
        jdbc.update("UPDATE sales.delivery_trip SET status = :s WHERE delivery_trip_id = :id", Map.of("id", id, "s", status));
    }

    /** Adds the round to the trip, or puts a delayed/removed row of the same trip back. */
    public void plan(long tripId, long roundId) {
        jdbc.update("""
            INSERT INTO sales.delivery_trip_round (delivery_trip_id, delivery_request_id) VALUES (:t, :r)
            ON CONFLICT (delivery_trip_id, delivery_request_id) DO UPDATE SET state = 'PLANNED', updated_at = clock_timestamp()
            """, Map.of("t", tripId, "r", roundId));
    }

    public void setState(long tripId, long roundId, String state) {
        jdbc.update("""
            UPDATE sales.delivery_trip_round SET state = :s, updated_at = clock_timestamp()
             WHERE delivery_trip_id = :t AND delivery_request_id = :r
            """, Map.of("t", tripId, "r", roundId, "s", state));
    }

    public boolean plannedElsewhere(long tripId, long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.delivery_trip_round
                            WHERE delivery_request_id = :r AND state = 'PLANNED' AND delivery_trip_id <> :t)
            """, Map.of("t", tripId, "r", roundId), Boolean.class));
    }

    public Recipients recipients(long roundId) {
        return jdbc.queryForObject("SELECT t.created_by, t.keyed_by, r.requested_by, " + DISPLAY_CODE
            + " AS display_code, t.customer_name " + ITEM_FROM + " WHERE r.delivery_request_id = :id",
            Map.of("id", roundId), (rs, n) -> new Recipients(rs.getObject("created_by", Long.class),
                rs.getObject("keyed_by", Long.class), rs.getObject("requested_by", Long.class),
                rs.getString("display_code") + " รอบส่งที่ " + roundNo(roundId), rs.getString("customer_name")));
    }

    public long ticketIdOf(long roundId) {
        return jdbc.queryForObject("SELECT ticket_id FROM sales.delivery_request WHERE delivery_request_id = :id",
            Map.of("id", roundId), Long.class);
    }

    private int roundNo(long roundId) {
        return jdbc.queryForObject("SELECT round_no FROM sales.delivery_request WHERE delivery_request_id = :id",
            Map.of("id", roundId), Integer.class);
    }

    private static MapSqlParameterSource params(LocalDate date, String route, String slot, String vehicle, String note) {
        return new MapSqlParameterSource().addValue("d", date).addValue("r", route).addValue("s", slot)
            .addValue("v", vehicle).addValue("n", note);
    }

    private static Trip trip(ResultSet rs, List<RoundItem> items) throws SQLException {
        return new Trip(rs.getLong("delivery_trip_id"), rs.getObject("delivery_date", LocalDate.class), rs.getString("route"),
            rs.getString("time_slot"), rs.getString("vehicle"), rs.getString("note"), rs.getString("status"), items);
    }

    private static RoundItem item(ResultSet rs) throws SQLException {
        BigDecimal remaining = rs.getBigDecimal("remaining");
        return new RoundItem(rs.getLong("delivery_request_id"), rs.getLong("ticket_id"), rs.getInt("round_no"),
            rs.getString("status"), rs.getString("display_code"), rs.getString("customer_name"), rs.getString("project_name"),
            rs.getString("owner_name"), rs.getString("note"), instant(rs.getTimestamp("sent_at")),
            instant(rs.getTimestamp("forwarded_at")), remaining, rs.getBoolean("delayed"), rs.getString("state"),
            rs.getObject("requested_date", LocalDate.class), rs.getBoolean("customer_pickup"));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
