package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Optional;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentFacts;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentDocumentDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentLotDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentWaitingDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentWaitingLine;
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

    /** Ids of the rounds that carry a lot of any ISSUED import request of the deal, ascending. */
    public List<Long> roundIdsOfTicket(long ticketId) {
        return jdbc.queryForList("""
            SELECT DISTINCT l.shipment_round_id FROM sales.shipment_lot l
              JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED' ORDER BY l.shipment_round_id
            """, Map.of("t", ticketId), Long.class);
    }

    public List<Long> issuedIrIdsOfTicket(long ticketId) {
        return jdbc.queryForList("SELECT import_request_id FROM sales.import_request WHERE ticket_id = :t AND status = 'ISSUED' ORDER BY import_request_id",
            Map.of("t", ticketId), Long.class);
    }

    /** Of these IRs, the ones that have a lot or carry this deal line. */
    public java.util.Set<Long> irsWithLotsOrLine(List<Long> irIds, long ticketItemId) {
        if (irIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.queryForList("""
            SELECT DISTINCT i.import_request_id FROM sales.import_request_item i
             WHERE i.import_request_id IN (:ids)
               AND (i.ticket_item_id = :item
                    OR EXISTS (SELECT 1 FROM sales.shipment_lot l WHERE l.import_request_id = i.import_request_id))
            """, Map.of("ids", irIds, "item", ticketItemId), Long.class));
    }

    /** Per ISSUED import-request line of the deal: how much is still wanted (the quantity marked not delivered is taken off). */
    public Map<Long, BigDecimal> wantedByItem(long ticketId) {
        Map<Long, BigDecimal> out = new java.util.HashMap<>();
        jdbc.query("""
            SELECT w.import_request_item_id, w.wanted_qty FROM sales.import_item_wanted w
              JOIN sales.import_request_item i ON i.import_request_item_id = w.import_request_item_id
              JOIN sales.import_request ir ON ir.import_request_id = i.import_request_id
             WHERE ir.ticket_id = :t
            """, Map.of("t", ticketId), rs -> {
                out.put(rs.getLong(1), rs.getBigDecimal(2));
            });
        return out;
    }

    public String stepOfRound(long roundId) {
        return jdbc.queryForObject("SELECT import_step FROM sales.shipment_round WHERE shipment_round_id = :r",
            Map.of("r", roundId), String.class);
    }

    /** What a deal's lots planned and what reached the warehouse (received_qty, else the planned qty) over its ISSUED IRs' lots on
     * RECEIVED rounds; {@code unit} is null when the units differ. */
    public record ReceiptTotals(BigDecimal planned, BigDecimal received, String unit, boolean oneUnit) {
        public boolean short_() {
            return planned != null && received != null && received.compareTo(planned) < 0;
        }
    }

    /** What the all-received notice reports: the IR lines as ordered against what physically arrived. A line with no lot on a
     * RECEIVED round (tracked before rounds) counts as arrived, less what was marked not delivered. */
    public ReceiptTotals receiptTotals(long ticketId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(i.qty), 0) AS planned,
                   COALESCE(SUM(COALESCE(g.got, GREATEST(i.qty - COALESCE(t.qty_not_delivered, 0), 0))), 0) AS received,
                   COUNT(DISTINCT COALESCE(NULLIF(btrim(i.unit), ''), '')) AS units, -- NULL and blank are the same "no unit"
                   MIN(NULLIF(btrim(i.unit), '')) AS unit
              FROM sales.import_request_item i
              JOIN sales.import_request ir ON ir.import_request_id = i.import_request_id
              LEFT JOIN sales.ticket_item t ON t.item_id = i.ticket_item_id
              LEFT JOIN LATERAL (SELECT SUM(COALESCE(l.received_qty, l.qty)) AS got
                                   FROM sales.shipment_lot l
                                   JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
                                  WHERE l.import_request_item_id = i.import_request_item_id AND r.import_step = 'RECEIVED') g ON true
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED'
            """, Map.of("t", ticketId), (rs, n) -> new ReceiptTotals(rs.getBigDecimal("planned"), rs.getBigDecimal("received"), rs.getString("unit"),
                rs.getInt("units") <= 1));
    }

    /** Any lot of the deal's ISSUED import requests on a RECEIVED round recorded as exactly 0. */
    public boolean hasZeroReceivedLot(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.shipment_lot l
                             JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
                             JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
                            WHERE ir.ticket_id = :t AND ir.status = 'ISSUED' AND r.import_step = 'RECEIVED' AND l.received_qty = 0)
            """, Map.of("t", ticketId), Boolean.class));
    }

    /** ISSUED import requests of the deal that have lots whose lots together received nothing: on a RECEIVED round, but
     * nothing physically arrived, so they must not count as goods received. */
    public List<Long> nothingArrivedIrIds(long ticketId) {
        return jdbc.queryForList("""
            SELECT ir.import_request_id
              FROM sales.import_request ir
              JOIN sales.shipment_lot l ON l.import_request_id = ir.import_request_id
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED'
             GROUP BY ir.import_request_id
            HAVING SUM(COALESCE(l.received_qty, l.qty)) = 0
            """, Map.of("t", ticketId), Long.class);
    }

    /** The id of the round's latest advance (null when none is undoable). */
    public String advanceIdOf(long roundId) {
        return jdbc.queryForObject("SELECT CAST(step_advance_id AS VARCHAR) FROM sales.shipment_round WHERE shipment_round_id = :r",
            Map.of("r", roundId), String.class);
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

    private static final String STEP_COLUMNS = """
        import_step, contacted_on, ordered_on, picked_up_on, in_transit_on, awaiting_customs_on, received_on,
        step_by_id, step_by_name, step_note, label, transport_mode, vessel, voyage, forwarder, container_nos, port,
        goods_ready_on, etd_planned_on, etd_actual_on, eta_on, arrived_on, customs_released_on, gr_number,
        complete_per_invoice, complete_note, note""";

    private static MapSqlParameterSource factParams(MapSqlParameterSource p, ShipmentFacts f) {
        ShipmentFacts x = f == null ? new ShipmentFacts(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null) : f;
        return p.addValue("label", x.label()).addValue("transportMode", x.transportMode()).addValue("vessel", x.vessel())
            .addValue("voyage", x.voyage()).addValue("forwarder", x.forwarder()).addValue("containerNos", x.containerNos())
            .addValue("port", x.port()).addValue("goodsReadyOn", x.goodsReadyOn()).addValue("etdPlannedOn", x.etdPlannedOn())
            .addValue("etdActualOn", x.etdActualOn()).addValue("etaOn", x.etaOn()).addValue("arrivedOn", x.arrivedOn())
            .addValue("customsReleasedOn", x.customsReleasedOn()).addValue("grNumber", x.grNumber())
            .addValue("completePerInvoice", x.completePerInvoice()).addValue("completeNote", x.completeNote())
            .addValue("factNote", x.note());
    }

    /** {@code all}: write every fact as sent (the facts editor); otherwise only the non-null ones (an advance). The
     * transport mode is never cleared (it is NOT NULL). */
    private static String factAssignments(boolean all) {
        String[][] cols = {{"label", "label", "VARCHAR"}, {"vessel", "vessel", "VARCHAR"}, {"voyage", "voyage", "VARCHAR"},
            {"forwarder", "forwarder", "VARCHAR"}, {"container_nos", "containerNos", "VARCHAR"},
            {"goods_ready_on", "goodsReadyOn", "DATE"}, {"etd_planned_on", "etdPlannedOn", "DATE"},
            {"etd_actual_on", "etdActualOn", "DATE"}, {"eta_on", "etaOn", "DATE"}, {"port", "port", "VARCHAR"},
            {"arrived_on", "arrivedOn", "DATE"}, {"customs_released_on", "customsReleasedOn", "DATE"},
            {"gr_number", "grNumber", "VARCHAR"}, {"complete_per_invoice", "completePerInvoice", "BOOLEAN"},
            {"complete_note", "completeNote", "VARCHAR"}, {"note", "factNote", "VARCHAR"}};
        StringBuilder sb = new StringBuilder("transport_mode = COALESCE(CAST(:transportMode AS VARCHAR), transport_mode), ");
        for (String[] c : cols) {
            String v = "CAST(:" + c[1] + " AS " + c[2] + ")";
            sb.append(c[0]).append(" = ").append(all ? v : "COALESCE(" + v + ", " + c[0] + ")").append(", ");
        }
        return sb.toString();
    }

    public void touch(long roundId) {
        jdbc.update("UPDATE sales.shipment_round SET updated_at = clock_timestamp() WHERE shipment_round_id = :r", Map.of("r", roundId));
    }

    /** Writes the facts (see {@link #factAssignments}). */
    public void applyFacts(long roundId, ShipmentFacts facts, boolean all) {
        jdbc.update("UPDATE sales.shipment_round SET " + factAssignments(all) + "updated_at = now() WHERE shipment_round_id = :r",
            factParams(new MapSqlParameterSource("r", roundId), facts));
    }

    /** Moves a round to {@code target} and dates that step; writes the step's facts; keeps the row as it was (the
     * snapshot) for the 30-second undo. */
    public void advanceRound(long roundId, String target, LocalDate on, long actorId, String actorName, String note,
                             ShipmentFacts facts) {
        jdbc.update("""
            UPDATE sales.shipment_round r
               SET step_undo_snapshot = to_jsonb(r) - 'step_undo_snapshot',
                   step_undo_from = r.import_step, step_undo_by_id = :actor, step_advanced_at = clock_timestamp(), step_advance_id = gen_random_uuid(),
                   import_step = :target,
                   contacted_on = CASE WHEN :target = 'CONTACTED' THEN :on ELSE contacted_on END,
                   ordered_on = CASE WHEN :target = 'ORDERED' THEN :on ELSE ordered_on END,
                   picked_up_on = CASE WHEN :target = 'PICKED_UP' THEN :on ELSE picked_up_on END,
                   in_transit_on = CASE WHEN :target = 'IN_TRANSIT' THEN :on ELSE in_transit_on END,
                   awaiting_customs_on = CASE WHEN :target = 'AWAITING_CUSTOMS' THEN :on ELSE awaiting_customs_on END,
                   received_on = CASE WHEN :target = 'RECEIVED' THEN :on ELSE received_on END,
                   """ + factAssignments(false) + """
                   step_by_id = :actor, step_by_name = :actorName, step_note = :note, updated_at = now()
             WHERE shipment_round_id = :r
            """, factParams(new MapSqlParameterSource("r", roundId).addValue("target", target).addValue("on", on)
                .addValue("actor", actorId).addValue("actorName", actorName).addValue("note", note), facts));
    }

    /** Restores the row from its snapshot when the advance is still the latest, by {@code actorId}, inside the
     * window (database clock). 0 rows = refused. */
    public int undoRound(long roundId, String expectedStep, long actorId, String advanceId, int windowSeconds) {
        String sets = String.join(", ", java.util.Arrays.stream(STEP_COLUMNS.split(","))
            .map(String::trim).map(c -> c + " = x." + c).toList());
        return jdbc.update("""
            UPDATE sales.shipment_round r
               SET\s""" + sets + """
                   , step_undo_from = NULL, step_undo_by_id = NULL, step_advanced_at = NULL, step_advance_id = NULL,
                   step_undo_snapshot = NULL, updated_at = now()
              FROM jsonb_populate_record(NULL::sales.shipment_round,
                       (SELECT step_undo_snapshot FROM sales.shipment_round WHERE shipment_round_id = :r)) x
             WHERE r.shipment_round_id = :r AND r.step_undo_snapshot IS NOT NULL AND r.import_step = :expected
               AND r.step_undo_by_id = :actor AND r.step_advance_id = CAST(:adv AS UUID)
               AND r.step_advanced_at >= clock_timestamp() - make_interval(secs => :w)
            """, new MapSqlParameterSource("r", roundId).addValue("expected", expectedStep)
                .addValue("actor", actorId).addValue("adv", advanceId).addValue("w", windowSeconds));
    }

    /** One lot with the line it sits on, for remapping onto a revision. */
    public record LotRow(long roundId, BigDecimal qty, Long ticketItemId, String code, String size, BigDecimal receivedQty) {}

    public List<LotRow> lotsOf(long importRequestId) {
        return jdbc.query("""
            SELECT l.shipment_round_id, l.qty, i.ticket_item_id, i.code, i.size, l.received_qty
              FROM sales.shipment_lot l JOIN sales.import_request_item i USING (import_request_item_id)
             WHERE l.import_request_id = :ir ORDER BY l.shipment_lot_id
            """, Map.of("ir", importRequestId), (rs, n) -> new LotRow(rs.getLong("shipment_round_id"),
                rs.getBigDecimal("qty"), (Long) rs.getObject("ticket_item_id"), rs.getString("code"), rs.getString("size"),
                rs.getBigDecimal("received_qty")));
    }

    public void deleteLotsOf(long importRequestId) {
        jdbc.update("DELETE FROM sales.shipment_lot WHERE import_request_id = :ir", Map.of("ir", importRequestId));
    }

    public void insertLot(long roundId, long importRequestId, long itemId, BigDecimal qty) {
        insertLot(roundId, importRequestId, itemId, qty, null);
    }

    /** {@code receivedQty}: null = arrived in full (short arrival otherwise). */
    public void insertLot(long roundId, long importRequestId, long itemId, BigDecimal qty, BigDecimal receivedQty) {
        jdbc.update("""
            INSERT INTO sales.shipment_lot (shipment_round_id, import_request_id, import_request_item_id, qty, received_qty)
            VALUES (:r, :ir, :item, :qty, :recv)
            """, new MapSqlParameterSource("r", roundId).addValue("ir", importRequestId)
                .addValue("item", itemId).addValue("qty", qty).addValue("recv", receivedQty));
    }

    /** The currently ISSUED import request of a deal's factory, read WITHOUT a lock. */
    public OptionalLong issuedIdOf(long ticketId, long factoryId) {
        List<Long> ids = jdbc.queryForList("""
            SELECT import_request_id FROM sales.import_request
             WHERE ticket_id = :t AND factory_id = :f AND status = 'ISSUED'
            """, Map.of("t", ticketId, "f", factoryId), Long.class);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.get(0));
    }

    // ── slice 2: the import team's queue and round page ───────────────────────────────────────────────────

    /** Rounds that carry at least one lot (one only when {@code onlyId} is given), newest first. */
    public List<ShipmentRoundDto> roundsWithLots(Long onlyId) {
        return jdbc.query("""
            SELECT r.*, (SELECT s.step_undo_by_id FROM sales.shipment_round s WHERE s.shipment_round_id = r.shipment_round_id) AS undo_by
              FROM sales.shipment_round r
             -- a round whose lots are ALL on finished deals (CLOSED_LOST, CANCELLED, COMPLETED) is hidden; a no-project lot is live
             WHERE (EXISTS (SELECT 1 FROM sales.shipment_lot l
                            JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
                            LEFT JOIN sales.ticket t ON t.ticket_id = ir.ticket_id
                      WHERE l.shipment_round_id = r.shipment_round_id
                        AND (ir.ticket_id IS NULL OR t.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED', 'COMPLETED')))
                 -- a received round whose only lot went back to waiting stays reachable for its documents and facts
                 OR (r.import_step = 'RECEIVED' AND NOT EXISTS (SELECT 1 FROM sales.shipment_lot e WHERE e.shipment_round_id = r.shipment_round_id)))
               AND (CAST(:only AS BIGINT) IS NULL OR r.shipment_round_id = :only)
             ORDER BY (r.import_step = 'RECEIVED'), r.shipment_round_id DESC
            """, new MapSqlParameterSource("only", onlyId), (rs, n) -> {
                java.sql.Timestamp adv = rs.getTimestamp("step_advanced_at");
                return new ShipmentRoundDto(rs.getLong("shipment_round_id"), (Long) rs.getObject("factory_id"),
                    rs.getString("factory_name"), rs.getString("source"), rs.getString("import_step"),
                    date(rs, "contacted_on"), date(rs, "ordered_on"), date(rs, "picked_up_on"),
                    date(rs, "in_transit_on"), date(rs, "awaiting_customs_on"), date(rs, "received_on"),
                    rs.getString("step_by_name"), rs.getString("step_note"),
                    new ShipmentFacts(rs.getString("label"), rs.getString("transport_mode"), rs.getString("vessel"),
                        rs.getString("voyage"), rs.getString("forwarder"), rs.getString("container_nos"),
                        rs.getString("port"), date(rs, "goods_ready_on"), date(rs, "etd_planned_on"),
                        date(rs, "etd_actual_on"), date(rs, "eta_on"), date(rs, "arrived_on"),
                        date(rs, "customs_released_on"), rs.getString("gr_number"),
                        (Boolean) rs.getObject("complete_per_invoice"), rs.getString("complete_note"),
                        rs.getString("note")),
                    List.of(), List.of(), adv == null ? null : adv.toInstant(), (Long) rs.getObject("step_undo_by_id"),
                    rs.getString("step_undo_from"),
                    rs.getString("step_advance_id"), rs.getTimestamp("updated_at").toInstant());
            });
    }

    private static LocalDate date(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    /** Every lot of the given rounds with its line, IR and deal (no price column is selected), keyed by round. */
    public Map<Long, List<ShipmentLotDto>> lotsByRound(List<Long> roundIds) {
        Map<Long, List<ShipmentLotDto>> out = new java.util.LinkedHashMap<>();
        if (roundIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
            WITH x AS (
              SELECT l.shipment_lot_id, l.shipment_round_id, l.import_request_id, l.import_request_item_id, l.qty,
                     i.code, i.brand, i.model, i.size, i.unit, COALESCE(w.wanted_qty, i.qty) AS item_qty,
                     row_number() OVER (PARTITION BY l.import_request_item_id ORDER BY l.shipment_lot_id) AS lot_no,
                     count(*) OVER (PARTITION BY l.import_request_item_id) AS lots,
                     COALESCE(w.wanted_qty, i.qty) - sum(l.qty) OVER (PARTITION BY l.import_request_item_id) AS remaining, l.received_qty
                FROM sales.shipment_lot l JOIN sales.import_request_item i USING (import_request_item_id)
                LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = l.import_request_item_id
               -- windows run over ALL lots of an item, but only of the items on the requested rounds
               WHERE l.import_request_item_id IN (SELECT import_request_item_id FROM sales.shipment_lot
                                                   WHERE shipment_round_id IN (:ids)))
            SELECT x.*, r.doc_number, r.ticket_id, t.code AS ticket_code, t.customer_name, p.name AS project_name,
                   r.order_purpose, r.purpose_note,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS requester_name
              FROM x JOIN sales.import_request r USING (import_request_id)
              LEFT JOIN sales.ticket t ON t.ticket_id = r.ticket_id
              LEFT JOIN customers.project p ON p.project_id = t.project_id
              LEFT JOIN hr.employee e ON e.employee_id = r.requester_id
             WHERE x.shipment_round_id IN (:ids)
             ORDER BY x.shipment_round_id, r.doc_number, x.shipment_lot_id
            """, Map.of("ids", roundIds), rs -> {
                BigDecimal remaining = rs.getBigDecimal("remaining");
                int lots = rs.getInt("lots");
                out.computeIfAbsent(rs.getLong("shipment_round_id"), k -> new java.util.ArrayList<>()).add(new ShipmentLotDto(
                    rs.getLong("shipment_lot_id"), rs.getLong("import_request_id"), rs.getString("doc_number"),
                    (Long) rs.getObject("ticket_id"), rs.getString("ticket_code"), rs.getString("customer_name"),
                    rs.getString("project_name"), rs.getLong("import_request_item_id"), rs.getString("code"),
                    rs.getString("brand"), rs.getString("model"), rs.getString("size"), rs.getString("unit"),
                    rs.getBigDecimal("qty"), rs.getBigDecimal("item_qty"), rs.getInt("lot_no"),
                    remaining.signum() > 0 ? lots + 1 : lots, remaining.max(BigDecimal.ZERO), rs.getBigDecimal("received_qty"),
                    rs.getString("order_purpose"), rs.getString("purpose_note"), rs.getString("requester_name")));
            });
        return out;
    }

    public Map<Long, List<ShipmentDocumentDto>> documentsByRound(List<Long> roundIds) {
        Map<Long, List<ShipmentDocumentDto>> out = new java.util.LinkedHashMap<>();
        if (roundIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
            SELECT shipment_round_id, shipment_round_document_id, kind, doc_no, doc_date, note, file_name, file_size,
                   mime_type, created_at
              FROM sales.shipment_round_document WHERE shipment_round_id IN (:ids)
             ORDER BY shipment_round_id, array_position(
                 ARRAY['PROFORMA','INVOICE','PACKING_LIST','BILL_OF_LADING','CERT_ORIGIN','IMPORT_ENTRY','OTHER'], kind),
                 shipment_round_document_id
            """, Map.of("ids", roundIds), rs -> {
                java.sql.Date d = rs.getDate("doc_date");
                out.computeIfAbsent(rs.getLong("shipment_round_id"), k -> new java.util.ArrayList<>()).add(
                    new ShipmentDocumentDto(rs.getLong("shipment_round_document_id"), rs.getString("kind"),
                        rs.getString("doc_no"), d == null ? null : d.toLocalDate(), rs.getString("note"),
                        rs.getString("file_name"), (Long) rs.getObject("file_size"), rs.getString("mime_type"),
                        rs.getTimestamp("created_at").toInstant()));
            });
        return out;
    }

    /** A document row with its stored file location, for the file endpoints. */
    public record DocumentRow(long id, String kind, String docNo, String fileName, String filePath, String mimeType) {}

    public Optional<DocumentRow> findDocument(long roundId, long docId) {
        return jdbc.query("""
            SELECT shipment_round_document_id, kind, doc_no, file_name, file_path, mime_type
              FROM sales.shipment_round_document WHERE shipment_round_id = :r AND shipment_round_document_id = :d
            """, Map.of("r", roundId, "d", docId), (rs, n) -> new DocumentRow(rs.getLong(1), rs.getString("kind"),
                rs.getString("doc_no"), rs.getString("file_name"), rs.getString("file_path"),
                rs.getString("mime_type"))).stream().findFirst();
    }

    public long insertDocument(long roundId, String kind, String docNo, LocalDate docDate, String note, long by,
                               boolean byAdvance) {
        return jdbc.queryForObject("""
            INSERT INTO sales.shipment_round_document (shipment_round_id, kind, doc_no, doc_date, note, uploaded_by, created_by_advance)
            VALUES (:r, :k, :n, :dd, :note, :by, :adv) RETURNING shipment_round_document_id
            """, new MapSqlParameterSource("r", roundId).addValue("k", kind).addValue("n", docNo)
                .addValue("dd", docDate).addValue("note", note).addValue("by", by).addValue("adv", byAdvance), Long.class);
    }

    public void updateDocument(long docId, String kind, String docNo, LocalDate docDate, String note) {
        jdbc.update("""
            UPDATE sales.shipment_round_document SET kind = :k, doc_no = :n, doc_date = :dd, note = :note
             WHERE shipment_round_document_id = :d
            """, new MapSqlParameterSource("d", docId).addValue("k", kind).addValue("n", docNo)
                .addValue("dd", docDate).addValue("note", note));
    }

    public void setDocumentFile(long docId, String name, String path, long size, String mime) {
        jdbc.update("""
            UPDATE sales.shipment_round_document SET file_name = :n, file_path = :p, file_size = :s, mime_type = :m
             WHERE shipment_round_document_id = :d
            """, new MapSqlParameterSource("d", docId).addValue("n", name).addValue("p", path)
                .addValue("s", size).addValue("m", mime));
    }

    public void clearDocumentFile(long docId) {
        jdbc.update("""
            UPDATE sales.shipment_round_document SET file_name = NULL, file_path = NULL, file_size = NULL, mime_type = NULL
             WHERE shipment_round_document_id = :d
            """, Map.of("d", docId));
    }

    public void deleteDocument(long docId) {
        jdbc.update("DELETE FROM sales.shipment_round_document WHERE shipment_round_document_id = :d", Map.of("d", docId));
    }

    /** How many documents of this kind carry a number. */
    public int numberedDocuments(long roundId, String kind) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM sales.shipment_round_document
             WHERE shipment_round_id = :r AND kind = :k AND doc_no IS NOT NULL
            """, Map.of("r", roundId, "k", kind), Integer.class);
    }

    /** An advance starts afresh: documents of earlier advances are final. */
    public void finalizeDocuments(long roundId) {
        jdbc.update("UPDATE sales.shipment_round_document SET created_by_advance = false WHERE shipment_round_id = :r AND created_by_advance",
            Map.of("r", roundId));
    }

    /** The undo of an advance removes the documents that advance typed. */
    public void deleteDocumentsCreatedByAdvance(long roundId) {
        jdbc.update("DELETE FROM sales.shipment_round_document WHERE shipment_round_id = :r AND created_by_advance",
            Map.of("r", roundId));
    }

    /** Corrects the real date of steps (key = DATE column prefix of the step); only the keys present are written. */
    public void setStepDates(long roundId, Map<String, LocalDate> dates) {
        String[][] cols = {{"CONTACTED", "contacted_on"}, {"ORDERED", "ordered_on"}, {"PICKED_UP", "picked_up_on"},
            {"IN_TRANSIT", "in_transit_on"}, {"AWAITING_CUSTOMS", "awaiting_customs_on"}, {"RECEIVED", "received_on"}};
        MapSqlParameterSource p = new MapSqlParameterSource("r", roundId);
        StringBuilder sb = new StringBuilder();
        for (String[] c : cols) {
            p.addValue("has" + c[0], dates.containsKey(c[0])).addValue("v" + c[0], dates.get(c[0]));
            sb.append(c[1]).append(" = CASE WHEN :has").append(c[0]).append(" THEN CAST(:v").append(c[0])
                .append(" AS DATE) ELSE ").append(c[1]).append(" END, ");
        }
        jdbc.update("UPDATE sales.shipment_round SET " + sb + "updated_at = now() WHERE shipment_round_id = :r", p);
    }

    /** ISSUED import requests with a line that has quantity on no round, with what remains per line. */
    public List<ShipmentWaitingDto> waiting() {
        Map<Long, ShipmentWaitingDto> byIr = new java.util.LinkedHashMap<>();
        Map<Long, List<ShipmentWaitingLine>> lines = new java.util.HashMap<>();
        jdbc.query("""
            SELECT r.import_request_id, r.doc_number, r.factory_id, r.factory_name, r.ticket_id, t.code AS ticket_code,
                   t.customer_name, p.name AS project_name, r.order_purpose, r.purpose_note,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS requester_name,
                   i.import_request_item_id AS item_id, i.code, i.brand, i.model, i.size, i.unit,
                   COALESCE(w.wanted_qty, i.qty) - COALESCE((SELECT sum(l.qty) FROM sales.shipment_lot l
                                      WHERE l.import_request_item_id = i.import_request_item_id), 0) AS remaining
              FROM sales.import_request r
              JOIN sales.import_request_item i ON i.import_request_id = r.import_request_id
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.ticket t ON t.ticket_id = r.ticket_id
              LEFT JOIN customers.project p ON p.project_id = t.project_id
              LEFT JOIN hr.employee e ON e.employee_id = r.requester_id
             WHERE r.status = 'ISSUED' AND (r.ticket_id IS NULL OR t.lifecycle = 'ACTIVE')
               AND COALESCE(w.wanted_qty, i.qty) > COALESCE((SELECT sum(l.qty) FROM sales.shipment_lot l
                                      WHERE l.import_request_item_id = i.import_request_item_id), 0)
             ORDER BY r.import_request_id, i.seq
            """, new MapSqlParameterSource(), rs -> {
                BigDecimal remaining = rs.getBigDecimal("remaining");
                if (remaining.signum() <= 0) {
                    return;
                }
                long id = rs.getLong("import_request_id");
                lines.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(new ShipmentWaitingLine(rs.getLong("item_id"),
                    rs.getString("code"), rs.getString("brand"), rs.getString("model"), rs.getString("size"),
                    rs.getString("unit"), remaining));
                byIr.computeIfAbsent(id, k -> {
                    try {
                        return new ShipmentWaitingDto(id, rs.getString("doc_number"), (Long) rs.getObject("factory_id"),
                            rs.getString("factory_name"), (Long) rs.getObject("ticket_id"), rs.getString("ticket_code"),
                            rs.getString("customer_name"), rs.getString("project_name"), null,
                            rs.getString("order_purpose"), rs.getString("purpose_note"), rs.getString("requester_name"));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
            });
        return byIr.values().stream().map(w -> new ShipmentWaitingDto(w.importRequestId(), w.docNumber(), w.factoryId(),
            w.factoryName(), w.ticketId(), w.ticketCode(), w.customerName(), w.projectName(),
            lines.get(w.importRequestId()), w.orderPurpose(), w.purposeNote(), w.requesterName())).toList();
    }

    /** Import requests carrying lots on these rounds, locked FOR UPDATE in ascending id. */
    /** {@code ticketId} is null for a no-project order (which counts as active and has no deal side effects). */
    public record IrLock(long id, Long ticketId, String docNumber, String factoryName, String importStep,
                         LocalDate importStepAt, Long importStepById, String importStepByName, String importStepNote,
                         LocalDate firstIssuedDate, boolean ticketActive, Long factoryId, Long requesterId) {}

    public List<IrLock> lockImportRequestsOnRounds(List<Long> roundIds) {
        return lockImportRequests(roundIds, List.of());
    }

    /** ISSUED import requests carrying a lot on these rounds, plus these ids, FOR UPDATE in ascending id. */
    public List<IrLock> lockImportRequests(List<Long> roundIds, List<Long> extraIrIds) {
        return jdbc.query("""
            SELECT import_request_id, ticket_id, doc_number, factory_name, import_step, import_step_at,
                   import_step_by_id, import_step_by_name, import_step_note, first_issued_date, factory_id, requester_id,
                   COALESCE((SELECT t.lifecycle = 'ACTIVE' FROM sales.ticket t WHERE t.ticket_id = sales.import_request.ticket_id), TRUE) AS ticket_active
              FROM sales.import_request
             WHERE status = 'ISSUED' AND (import_request_id IN (
                   SELECT import_request_id FROM sales.shipment_lot WHERE shipment_round_id IN (:ids))
                   OR import_request_id IN (:extra))
             ORDER BY import_request_id FOR UPDATE
            """, Map.of("ids", roundIds.isEmpty() ? List.of(-1L) : roundIds,
                "extra", extraIrIds.isEmpty() ? List.of(-1L) : extraIrIds), (rs, n) -> new IrLock(rs.getLong("import_request_id"),
                (Long) rs.getObject("ticket_id"), rs.getString("doc_number"), rs.getString("factory_name"),
                rs.getString("import_step"), date(rs, "import_step_at"), (Long) rs.getObject("import_step_by_id"),
                rs.getString("import_step_by_name"), rs.getString("import_step_note"), date(rs, "first_issued_date"),
                rs.getBoolean("ticket_active"), (Long) rs.getObject("factory_id"), (Long) rs.getObject("requester_id")));
    }

    /** What an IR's derived step is made of: per line, its quantity and every lot with its round's step and date. */
    public record DerivedInput(long importRequestId, long itemId, BigDecimal itemQty, BigDecimal lotQty,
                               String roundStep, LocalDate roundStepOn, Long stepById, String stepByName, String stepNote,
                               Long roundId) {}

    public List<DerivedInput> derivedInputs(List<Long> importRequestIds) {
        if (importRequestIds.isEmpty()) { // a received round whose only lot went back to waiting carries no IR
            return List.of();
        }
        return jdbc.query("""
            SELECT i.import_request_id, i.import_request_item_id, COALESCE(w.wanted_qty, i.qty) AS qty, l.qty AS lot_qty, r.import_step,
                   CASE r.import_step WHEN 'CONTACTED' THEN r.contacted_on WHEN 'ORDERED' THEN r.ordered_on
                        WHEN 'PICKED_UP' THEN r.picked_up_on WHEN 'IN_TRANSIT' THEN r.in_transit_on
                        WHEN 'AWAITING_CUSTOMS' THEN r.awaiting_customs_on WHEN 'RECEIVED' THEN r.received_on END AS step_on,
                   r.step_by_id, r.step_by_name, r.step_note, r.shipment_round_id
              FROM sales.import_request_item i
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.shipment_lot l ON l.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE i.import_request_id IN (:ids)
            """, Map.of("ids", importRequestIds), (rs, n) -> new DerivedInput(rs.getLong("import_request_id"),
                rs.getLong("import_request_item_id"), rs.getBigDecimal("qty"), rs.getBigDecimal("lot_qty"),
                rs.getString("import_step"), date(rs, "step_on"), (Long) rs.getObject("step_by_id"),
                rs.getString("step_by_name"), rs.getString("step_note"), (Long) rs.getObject("shipment_round_id")));
    }

    public boolean roundExists(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM sales.shipment_round WHERE shipment_round_id = :r)", Map.of("r", roundId),
            Boolean.class));
    }

    /** Ends the 30-second undo window of a round (and of the IRs on it): called by every write that an undo would
     * otherwise discard, the facts editor and the file upload/delete. The IR rows must already be locked. */
    public void clearUndo(long roundId) {
        jdbc.update("""
            UPDATE sales.shipment_round
               SET step_undo_from = NULL, step_undo_by_id = NULL, step_advanced_at = NULL, step_advance_id = NULL, step_undo_snapshot = NULL
             WHERE shipment_round_id = :r
            """, Map.of("r", roundId));
        jdbc.update("""
            UPDATE sales.import_request
               SET step_undo_from = NULL, step_undo_from_at = NULL, step_undo_from_by_id = NULL,
                   step_undo_from_by_name = NULL, step_undo_from_note = NULL, step_advanced_at = NULL
             WHERE import_request_id IN (SELECT import_request_id FROM sales.shipment_lot WHERE shipment_round_id = :r)
            """, Map.of("r", roundId));
    }

    /** Whether any round carrying a lot of this import request was made in the portal (source PORTAL). */
    public boolean hasPortalRound(long importRequestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.shipment_round r
                            WHERE r.source = 'PORTAL' AND r.shipment_round_id IN (
                                  SELECT shipment_round_id FROM sales.shipment_lot WHERE import_request_id = :ir))
            """, Map.of("ir", importRequestId), Boolean.class));
    }

    // ── slice 3: planning rounds and moving lots ──────────────────────────────────────────────────────────

    /** An IR line with how much of it already rides on rounds. */
    public record ItemQty(long importRequestId, BigDecimal qty, BigDecimal placed) {}

    public Optional<ItemQty> itemQty(long itemId) {
        return jdbc.query("""
            SELECT i.import_request_id, COALESCE(w.wanted_qty, i.qty) AS qty, -- what the factory is still to send: not what was marked not delivered
                   COALESCE((SELECT sum(l.qty) FROM sales.shipment_lot l WHERE l.import_request_item_id = i.import_request_item_id), 0) AS placed
              FROM sales.import_request_item i
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = i.import_request_item_id
             WHERE i.import_request_item_id = :i
            """, Map.of("i", itemId), (rs, n) -> new ItemQty(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
            .stream().findFirst();
    }

    /** A lot's place: its round, import request, line and quantity. */
    public record LotPlace(long lotId, long roundId, long importRequestId, long itemId, BigDecimal qty) {}

    public Optional<LotPlace> findLot(long lotId) {
        return jdbc.query("SELECT shipment_lot_id, shipment_round_id, import_request_id, import_request_item_id, qty"
            + " FROM sales.shipment_lot WHERE shipment_lot_id = :l", Map.of("l", lotId), (rs, n) -> new LotPlace(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBigDecimal(5))).stream().findFirst();
    }

    public Optional<LotPlace> lotOnRound(long roundId, long itemId) {
        return jdbc.query("SELECT shipment_lot_id, shipment_round_id, import_request_id, import_request_item_id, qty"
            + " FROM sales.shipment_lot WHERE shipment_round_id = :r AND import_request_item_id = :i",
            Map.of("r", roundId, "i", itemId), (rs, n) -> new LotPlace(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getBigDecimal(5))).stream().findFirst();
    }

    public void setLotQty(long lotId, BigDecimal qty) {
        jdbc.update("UPDATE sales.shipment_lot SET qty = :q WHERE shipment_lot_id = :l", Map.of("q", qty, "l", lotId));
    }

    public void deleteLot(long lotId) {
        jdbc.update("DELETE FROM sales.shipment_lot WHERE shipment_lot_id = :l", Map.of("l", lotId));
    }

    public int lotCount(long roundId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.shipment_lot WHERE shipment_round_id = :r",
            Map.of("r", roundId), Integer.class);
    }

    public int documentCount(long roundId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.shipment_round_document WHERE shipment_round_id = :r",
            Map.of("r", roundId), Integer.class);
    }

    /** A new PORTAL round at CONTACTED dated today (Bangkok), before any lot rides on it. */
    public long createPlannedRound(Long factoryId, String factoryName, String label, String mode, LocalDate today,
                                   long actorId, String actorName) {
        return jdbc.queryForObject("""
            INSERT INTO sales.shipment_round (factory_id, factory_name, label, transport_mode, source, import_step,
                   contacted_on, step_by_id, step_by_name, created_by_id)
            VALUES (:f, :fn, :label, COALESCE(:mode, 'SEA'), 'PORTAL', 'CONTACTED', :today, :actor, :actorName, :actor)
            RETURNING shipment_round_id
            """, new MapSqlParameterSource("f", factoryId).addValue("fn", factoryName).addValue("label", label)
                .addValue("mode", mode).addValue("today", today).addValue("actor", actorId)
                .addValue("actorName", actorName), Long.class);
    }

    /** Ends the IR-level undo of these IRs (their lots changed, so an old advance can no longer be undone). */
    public void clearIrUndo(List<Long> importRequestIds) {
        if (importRequestIds.isEmpty()) {
            return;
        }
        jdbc.update("""
            UPDATE sales.import_request
               SET step_undo_from = NULL, step_undo_from_at = NULL, step_undo_from_by_id = NULL,
                   step_undo_from_by_name = NULL, step_undo_from_note = NULL, step_advanced_at = NULL
             WHERE import_request_id IN (:ids)
            """, Map.of("ids", importRequestIds));
    }

    public void moveLotTo(long lotId, long toRoundId) {
        jdbc.update("UPDATE sales.shipment_lot SET shipment_round_id = :to WHERE shipment_lot_id = :l",
            Map.of("to", toRoundId, "l", lotId));
    }

    /** The lot's deal line had a lot sent back to waiting (see V240 had_returned_lot). */
    public void flagLineLotReturned(long lotId) {
        jdbc.update("""
            UPDATE sales.ticket_item ti SET had_returned_lot = true
              FROM sales.shipment_lot l
              JOIN sales.import_request_item i ON i.import_request_item_id = l.import_request_item_id
             WHERE l.shipment_lot_id = :lot AND ti.item_id = i.ticket_item_id
            """, Map.of("lot", lotId));
    }

    /** Whether the round holds anything of its own: a document, or a typed fact (the label alone does not count). */
    public boolean hasOwnData(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.shipment_round_document WHERE shipment_round_id = :r)
                OR EXISTS (SELECT 1 FROM sales.shipment_round
                            WHERE shipment_round_id = :r
                              AND (NULLIF(btrim(vessel), '') IS NOT NULL OR NULLIF(btrim(voyage), '') IS NOT NULL
                                   OR NULLIF(btrim(forwarder), '') IS NOT NULL OR NULLIF(btrim(container_nos), '') IS NOT NULL
                                   OR NULLIF(btrim(port), '') IS NOT NULL OR NULLIF(btrim(gr_number), '') IS NOT NULL
                                   OR NULLIF(btrim(note), '') IS NOT NULL
                                   OR complete_per_invoice IS FALSE OR NULLIF(btrim(complete_note), '') IS NOT NULL
                                   OR goods_ready_on IS NOT NULL OR etd_planned_on IS NOT NULL OR etd_actual_on IS NOT NULL
                                   OR eta_on IS NOT NULL OR arrived_on IS NOT NULL OR customs_released_on IS NOT NULL))
            """, Map.of("r", roundId), Boolean.class));
    }

    /** Short arrival: what actually reached the warehouse of a lot; null = the lot arrived in full. */
    public void setLotReceived(long lotId, BigDecimal receivedQty) {
        jdbc.update("UPDATE sales.shipment_lot SET received_qty = :q WHERE shipment_lot_id = :l",
            new MapSqlParameterSource("l", lotId).addValue("q", receivedQty));
    }

    /** A lot of a deal's ISSUED IR with its round, for the sales view (no price, no factory documents). */
    public record TicketLot(long lotId, long importRequestId, long itemId, BigDecimal qty, BigDecimal receivedQty,
                            long roundId, String roundLabel, String roundStep, String transportMode, String vessel,
                            String voyage, String port, LocalDate etdActualOn, LocalDate etaOn, LocalDate arrivedOn,
                            LocalDate customsReleasedOn, LocalDate contactedOn, LocalDate orderedOn, LocalDate pickedUpOn,
                            LocalDate inTransitOn, LocalDate awaitingCustomsOn, LocalDate receivedOn) {}

    public List<TicketLot> lotsOfTicket(long ticketId) {
        return jdbc.query("""
            SELECT l.shipment_lot_id, l.import_request_id, l.import_request_item_id, l.qty, l.received_qty,
                   r.shipment_round_id, r.label, r.import_step, r.transport_mode, r.vessel, r.voyage, r.port,
                   r.etd_actual_on, r.eta_on, r.arrived_on, r.customs_released_on, r.contacted_on, r.ordered_on,
                   r.picked_up_on, r.in_transit_on, r.awaiting_customs_on, r.received_on
              FROM sales.shipment_lot l
              JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
              JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE ir.ticket_id = :t AND ir.status = 'ISSUED'
             ORDER BY l.import_request_id, l.import_request_item_id, l.shipment_lot_id
            """, Map.of("t", ticketId), (rs, n) -> new TicketLot(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), date(rs, "etd_actual_on"),
                date(rs, "eta_on"), date(rs, "arrived_on"), date(rs, "customs_released_on"), date(rs, "contacted_on"),
                date(rs, "ordered_on"), date(rs, "picked_up_on"), date(rs, "in_transit_on"),
                date(rs, "awaiting_customs_on"), date(rs, "received_on")));
    }

    // ── ของที่ฉันสั่ง ──────────────────────────────────────────────────────────────────────────────────

    /** One IR line, with one of its lots when it has any (lot columns are null for quantity on no round). */
    public record MyLine(long importRequestId, String docNumber, String factoryName, Long ticketId, String ticketCode,
                         String customerName, String projectName, String orderPurpose, String purposeNote,
                         String requesterName, Long requesterId, LocalDate wantedBy, String requiredByNote, LocalDate expectedTo,
                         long itemId, String brand, String model, String code, String size, String unit,
                         BigDecimal itemQty, TicketLot lot) {}

    /**
     * Every ISSUED IR line of the caller's scope with its lots, in ONE query: project deals (ACTIVE; {@code allDeals} or
     * the owner/keyer of the deal) and no-project orders ({@code allOrders}, the requester, or SHOWROOM orders for the
     * showroom grant). Columns of the lot are only what the sales view may see: no price, no factory document.
     */
    public List<MyLine> myLines(long actorId, boolean allDeals, boolean allOrders, boolean showroom, LocalDate cutoff) {
        return jdbc.query("""
            SELECT ir.import_request_id, ir.doc_number, ir.factory_name, ir.ticket_id, t.code AS ticket_code,
                   t.customer_name, p.name AS project_name, ir.order_purpose, ir.purpose_note,
                   NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS requester_name, ir.requester_id, ir.wanted_by,
                   ir.required_by_note, ir.first_issued_date + ir.lead_time_max_days AS expected_to,
                   i.import_request_item_id, i.brand, i.model, i.code, i.size, i.unit, COALESCE(w.wanted_qty, i.qty) AS item_qty,
                   l.shipment_lot_id, l.qty AS lot_qty, l.received_qty,
                   r.shipment_round_id, r.label, r.import_step, r.transport_mode, r.vessel, r.voyage, r.port,
                   r.etd_actual_on, r.eta_on, r.arrived_on, r.customs_released_on, r.contacted_on, r.ordered_on,
                   r.picked_up_on, r.in_transit_on, r.awaiting_customs_on, r.received_on
              FROM sales.import_request ir
              JOIN sales.import_request_item i ON i.import_request_id = ir.import_request_id
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.ticket t ON t.ticket_id = ir.ticket_id
              LEFT JOIN customers.project p ON p.project_id = t.project_id
              LEFT JOIN hr.employee e ON e.employee_id = ir.requester_id
              LEFT JOIN sales.shipment_lot l ON l.import_request_item_id = i.import_request_item_id
              LEFT JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE ir.status = 'ISSUED'
               AND ((ir.ticket_id IS NOT NULL AND t.lifecycle = 'ACTIVE'
                     AND (:allDeals OR t.created_by = :actor OR t.keyed_by = :actor))
                 OR (ir.ticket_id IS NULL
                     AND (:allOrders OR ir.requester_id = :actor OR (:showroom AND ir.order_purpose = 'SHOWROOM'))))
               -- delivered long ago and nothing left to place: off the page (in SQL, so the list never grows without bound)
               AND NOT (EXISTS (SELECT 1 FROM sales.shipment_lot x WHERE x.import_request_id = ir.import_request_id)
                        AND NOT EXISTS (SELECT 1 FROM sales.shipment_lot x JOIN sales.shipment_round xr ON xr.shipment_round_id = x.shipment_round_id
                                         WHERE x.import_request_id = ir.import_request_id
                                           AND (xr.import_step <> 'RECEIVED' OR xr.received_on IS NULL OR xr.received_on >= :cutoff))
                        AND NOT EXISTS (SELECT 1 FROM sales.import_request_item xi
                                          LEFT JOIN sales.import_item_wanted xw ON xw.import_request_item_id = xi.import_request_item_id
                                         WHERE xi.import_request_id = ir.import_request_id
                                           AND COALESCE(xw.wanted_qty, xi.qty) > COALESCE((SELECT SUM(xl.qty) FROM sales.shipment_lot xl
                                                                   WHERE xl.import_request_item_id = xi.import_request_item_id), 0)))
             ORDER BY ir.import_request_id, i.seq, l.shipment_lot_id
            """, new MapSqlParameterSource("actor", actorId).addValue("allDeals", allDeals)
                .addValue("allOrders", allOrders).addValue("showroom", showroom).addValue("cutoff", cutoff), (rs, n) -> {
                Long lotId = (Long) rs.getObject("shipment_lot_id");
                TicketLot lot = lotId == null ? null : new TicketLot(lotId, rs.getLong("import_request_id"),
                    rs.getLong("import_request_item_id"), rs.getBigDecimal("lot_qty"), rs.getBigDecimal("received_qty"),
                    rs.getLong("shipment_round_id"), rs.getString("label"), rs.getString("import_step"),
                    rs.getString("transport_mode"), rs.getString("vessel"), rs.getString("voyage"), rs.getString("port"),
                    date(rs, "etd_actual_on"), date(rs, "eta_on"), date(rs, "arrived_on"), date(rs, "customs_released_on"),
                    date(rs, "contacted_on"), date(rs, "ordered_on"), date(rs, "picked_up_on"), date(rs, "in_transit_on"),
                    date(rs, "awaiting_customs_on"), date(rs, "received_on"));
                return new MyLine(rs.getLong("import_request_id"), rs.getString("doc_number"), rs.getString("factory_name"),
                    (Long) rs.getObject("ticket_id"), rs.getString("ticket_code"), rs.getString("customer_name"),
                    rs.getString("project_name"), rs.getString("order_purpose"), rs.getString("purpose_note"),
                    rs.getString("requester_name"), (Long) rs.getObject("requester_id"), date(rs, "wanted_by"), rs.getString("required_by_note"),
                    date(rs, "expected_to"), rs.getLong("import_request_item_id"), rs.getString("brand"),
                    rs.getString("model"), rs.getString("code"), rs.getString("size"), rs.getString("unit"),
                    rs.getBigDecimal("item_qty"), lot);
            });
    }

    /** A round that has lots, every one of them on a finished deal (so {@link #roundsWithLots} hides it). */
    public boolean onlyFinishedDeals(long roundId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.shipment_lot WHERE shipment_round_id = :r)
               AND NOT EXISTS (SELECT 1 FROM sales.shipment_lot l
                                 JOIN sales.import_request ir ON ir.import_request_id = l.import_request_id
                                 LEFT JOIN sales.ticket t ON t.ticket_id = ir.ticket_id
                                WHERE l.shipment_round_id = :r
                                  AND (ir.ticket_id IS NULL OR t.lifecycle NOT IN ('CLOSED_LOST', 'CANCELLED', 'COMPLETED')))
            """, Map.of("r", roundId), Boolean.class));
    }
}
