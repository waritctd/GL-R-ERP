package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for sales.shipment_round / sales.shipment_lot (V239). Slice 1: the step of a round follows its import
 * request's step. Lock order everywhere: rounds (ascending id), then import_request rows, then the ticket. */
@Repository
public class ShipmentRoundRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ShipmentRoundRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids of every round carrying a lot of this import request, ascending. */
    public List<Long> roundIdsOf(long importRequestId) {
        return jdbc.queryForList("""
            SELECT DISTINCT shipment_round_id FROM sales.shipment_lot
             WHERE import_request_id = :ir ORDER BY shipment_round_id
            """, Map.of("ir", importRequestId), Long.class);
    }

    /** FOR UPDATE, ascending id, for the rest of the caller's transaction. */
    public void lockRounds(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.queryForList("""
            SELECT shipment_round_id FROM sales.shipment_round
             WHERE shipment_round_id IN (:ids) ORDER BY shipment_round_id FOR UPDATE
            """, Map.of("ids", ids), Long.class);
    }

    /** The round carries ONLY this import request, with every line at its full quantity and no lot elsewhere. */
    public boolean isSoleWhole(long importRequestId, long roundId) {
        return wholeOn(importRequestId, roundId) && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS (SELECT 1 FROM sales.shipment_lot
                                WHERE shipment_round_id = :r AND import_request_id <> :ir)
            """, Map.of("r", roundId, "ir", importRequestId), Boolean.class));
    }

    /** A PORTAL round that no advance has touched since it was created (so undoing its creating advance deletes it). */
    public boolean createdByAdvance(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT source = 'PORTAL' AND step_undo_from IS NULL FROM sales.shipment_round WHERE shipment_round_id = :r
            """, Map.of("r", roundId), Boolean.class));
    }

    public void deleteRound(long roundId) {
        jdbc.update("DELETE FROM sales.shipment_round WHERE shipment_round_id = :r", Map.of("r", roundId));
    }

    /** Every line of the import request has a lot of its full quantity on this round, and no lot elsewhere. */
    public boolean wholeOn(long importRequestId, long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS (SELECT 1 FROM sales.import_request_item i
                                WHERE i.import_request_id = :ir
                                  AND NOT EXISTS (SELECT 1 FROM sales.shipment_lot l
                                                   WHERE l.import_request_item_id = i.import_request_item_id
                                                     AND l.shipment_round_id = :r AND l.qty = i.qty))
               AND NOT EXISTS (SELECT 1 FROM sales.shipment_lot
                                WHERE import_request_id = :ir AND shipment_round_id <> :r)
            """, new MapSqlParameterSource("ir", importRequestId).addValue("r", roundId), Boolean.class));
    }

    /** A PORTAL round at {@code step} (also keeping the date of the step the IR was on) carrying every line of the import request at its full quantity. */
    public long createPortalRound(long importRequestId, Long factoryId, String factoryName, String step,
                                  LocalDate stepOn, String fromStep, LocalDate fromOn, long actorId, String actorName, String note) {
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (factory_id, factory_name, source, import_step,
                   contacted_on, ordered_on, picked_up_on, in_transit_on, awaiting_customs_on, received_on,
                   step_by_id, step_by_name, step_note, created_by_id)
            VALUES (:f, :fn, 'PORTAL', :step,
                   CASE WHEN :step = 'CONTACTED' THEN :on WHEN :fromStep = 'CONTACTED' THEN :fromOn END, CASE WHEN :step = 'ORDERED' THEN :on WHEN :fromStep = 'ORDERED' THEN :fromOn END,
                   CASE WHEN :step = 'PICKED_UP' THEN :on WHEN :fromStep = 'PICKED_UP' THEN :fromOn END, CASE WHEN :step = 'IN_TRANSIT' THEN :on WHEN :fromStep = 'IN_TRANSIT' THEN :fromOn END,
                   CASE WHEN :step = 'AWAITING_CUSTOMS' THEN :on WHEN :fromStep = 'AWAITING_CUSTOMS' THEN :fromOn END, CASE WHEN :step = 'RECEIVED' THEN :on WHEN :fromStep = 'RECEIVED' THEN :fromOn END,
                   :actor, :actorName, :note, :actor)
            RETURNING shipment_round_id
            """, new MapSqlParameterSource("f", factoryId).addValue("fn", factoryName).addValue("step", step)
                .addValue("on", stepOn).addValue("fromStep", fromStep).addValue("fromOn", fromOn)
                .addValue("actor", actorId).addValue("actorName", actorName)
                .addValue("note", note), Long.class);
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            SELECT :r, import_request_id, import_request_item_id, qty
              FROM sales.import_request_item WHERE import_request_id = :ir
            """, Map.of("r", id, "ir", importRequestId));
        return id;
    }

    /** Moves a round to {@code target} and dates that step; remembers the step it left, for the 30-second undo. */
    public void advanceRound(long roundId, String target, LocalDate on, long actorId, String actorName, String note) {
        jdbc.update("""
            UPDATE sales.shipment_round
               SET step_undo_from = import_step, step_undo_by_id = :actor, step_advanced_at = now(),
                   import_step = :target,
                   contacted_on = CASE WHEN :target = 'CONTACTED' THEN :on ELSE contacted_on END,
                   ordered_on = CASE WHEN :target = 'ORDERED' THEN :on ELSE ordered_on END,
                   picked_up_on = CASE WHEN :target = 'PICKED_UP' THEN :on ELSE picked_up_on END,
                   in_transit_on = CASE WHEN :target = 'IN_TRANSIT' THEN :on ELSE in_transit_on END,
                   awaiting_customs_on = CASE WHEN :target = 'AWAITING_CUSTOMS' THEN :on ELSE awaiting_customs_on END,
                   received_on = CASE WHEN :target = 'RECEIVED' THEN :on ELSE received_on END,
                   step_by_id = :actor, step_by_name = :actorName, step_note = :note, updated_at = now()
             WHERE shipment_round_id = :r
            """, new MapSqlParameterSource("r", roundId).addValue("target", target).addValue("on", on)
                .addValue("actor", actorId).addValue("actorName", actorName).addValue("note", note));
    }

    /** Puts a round back to {@code step}, clearing the date of the step it undoes. */
    public void undoRound(long roundId, String step, Long byId, String byName, String note) {
        jdbc.update("""
            UPDATE sales.shipment_round
               SET contacted_on = CASE WHEN import_step = 'CONTACTED' THEN NULL ELSE contacted_on END,
                   ordered_on = CASE WHEN import_step = 'ORDERED' THEN NULL ELSE ordered_on END,
                   picked_up_on = CASE WHEN import_step = 'PICKED_UP' THEN NULL ELSE picked_up_on END,
                   in_transit_on = CASE WHEN import_step = 'IN_TRANSIT' THEN NULL ELSE in_transit_on END,
                   awaiting_customs_on = CASE WHEN import_step = 'AWAITING_CUSTOMS' THEN NULL ELSE awaiting_customs_on END,
                   received_on = CASE WHEN import_step = 'RECEIVED' THEN NULL ELSE received_on END,
                   import_step = :step, step_by_id = :by, step_by_name = :byName, step_note = :note,
                   step_undo_from = NULL, step_undo_by_id = NULL, step_advanced_at = NULL, updated_at = now()
             WHERE shipment_round_id = :r
            """, new MapSqlParameterSource("r", roundId).addValue("step", step).addValue("by", byId)
                .addValue("byName", byName).addValue("note", note));
    }

    /** One lot with the line it sits on, for remapping onto a revision. */
    public record LotRow(long roundId, BigDecimal qty, Long ticketItemId, String code, String size) {}

    public List<LotRow> lotsOf(long importRequestId) {
        return jdbc.query("""
            SELECT l.shipment_round_id, l.qty, i.ticket_item_id, i.code, i.size
              FROM sales.shipment_lot l JOIN sales.import_request_item i USING (import_request_item_id)
             WHERE l.import_request_id = :ir ORDER BY l.shipment_lot_id
            """, Map.of("ir", importRequestId), (rs, n) -> new LotRow(rs.getLong("shipment_round_id"),
                rs.getBigDecimal("qty"), (Long) rs.getObject("ticket_item_id"), rs.getString("code"), rs.getString("size")));
    }

    public void deleteLotsOf(long importRequestId) {
        jdbc.update("DELETE FROM sales.shipment_lot WHERE import_request_id = :ir", Map.of("ir", importRequestId));
    }

    public void insertLot(long roundId, long importRequestId, long itemId, BigDecimal qty) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty)
            VALUES (:r, :ir, :item, :qty)
            """, new MapSqlParameterSource("r", roundId).addValue("ir", importRequestId)
                .addValue("item", itemId).addValue("qty", qty));
    }

    /** The currently ISSUED import request of a deal's factory, read WITHOUT a lock. */
    public OptionalLong issuedIdOf(long ticketId, long factoryId) {
        List<Long> ids = jdbc.queryForList("""
            SELECT import_request_id FROM sales.import_request
             WHERE ticket_id = :t AND factory_id = :f AND status = 'ISSUED'
            """, Map.of("t", ticketId, "f", factoryId), Long.class);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }
}
