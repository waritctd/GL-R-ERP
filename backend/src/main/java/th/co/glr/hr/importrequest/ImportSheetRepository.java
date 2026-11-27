package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetFacts;

/** SQL of the one time import sheet backfill. Column names written here come from the fixed list below, never from the file. */
@Repository
class ImportSheetRepository {

    /** One key for every backfill transaction: two runs can never interleave. */
    private static final long ADVISORY_KEY = 7_242_001L;

    /** Every sheet fact a round can hold, with its SQL type. */
    private static final Map<String, String> FACT_COLUMNS = factColumns();
    private static final List<String> STEP_ORDER = List.of(ImportRequestStep.CONTACTED, ImportRequestStep.ORDERED,
        ImportRequestStep.PICKED_UP, ImportRequestStep.IN_TRANSIT, ImportRequestStep.AWAITING_CUSTOMS, ImportRequestStep.RECEIVED);

    private final NamedParameterJdbcTemplate jdbc;

    ImportSheetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, String> factColumns() {
        Map<String, String> c = new LinkedHashMap<>();
        for (String text : List.of("vessel", "port", "gr_number", "complete_note", "note")) {
            c.put(text, "VARCHAR");
        }
        for (String date : List.of("goods_ready_on", "etd_planned_on", "etd_actual_on", "transship_eta_on", "insured_on",
            "eta_on", "arrived_on", "customs_released_on", "contacted_on", "ordered_on", "in_transit_on",
            "awaiting_customs_on", "received_on")) {
            c.put(date, "DATE");
        }
        c.put("complete_per_invoice", "BOOLEAN");
        return c;
    }

    record PortalIr(long id, String docNumber, String status, Long ticketId, String ticketCode, Long factoryId, String source,
                    Long ownerId, Long keyedById, String importStep) {}

    record PortalLot(long lotId, long importRequestId, long itemId, BigDecimal qty, BigDecimal receivedQty, long roundId,
                     String roundSource, String roundStep, boolean sharedWithAnotherIr) {}

    record WantedItem(long itemId, BigDecimal wantedQty) {}

    record TicketState(long ticketId, String code, String stage, String fulfilment, String payment, String lifecycle) {}

    void lockRun() {
        jdbc.query("SELECT pg_advisory_xact_lock(:k)", Map.of("k", ADVISORY_KEY), (rs, n) -> 0);
    }

    Map<String, PortalIr> findIrs(Collection<String> docNumbers) {
        Map<String, PortalIr> out = new HashMap<>();
        if (docNumbers.isEmpty()) {
            return out;
        }
        jdbc.query("""
            SELECT r.import_request_id, r.doc_number, r.status, r.ticket_id, t.code, r.factory_id, r.source,
                   t.created_by, t.keyed_by, r.import_step
              FROM sales.import_request r LEFT JOIN sales.ticket t ON t.ticket_id = r.ticket_id
             WHERE r.doc_number IN (:docs)
            """, Map.of("docs", docNumbers), rs -> {
                out.put(rs.getString("doc_number"), new PortalIr(rs.getLong("import_request_id"), rs.getString("doc_number"),
                    rs.getString("status"), (Long) rs.getObject("ticket_id"), rs.getString("code"),
                    (Long) rs.getObject("factory_id"), rs.getString("source"), (Long) rs.getObject("created_by"),
                    (Long) rs.getObject("keyed_by"), rs.getString("import_step")));
            });
        return out;
    }

    Map<Long, List<PortalLot>> lotsOf(Collection<Long> importRequestIds) {
        Map<Long, List<PortalLot>> out = new HashMap<>();
        if (importRequestIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
            SELECT l.shipment_lot_id, l.import_request_id, l.import_request_item_id, l.qty, l.received_qty,
                   r.shipment_round_id, r.source, r.import_step,
                   EXISTS (SELECT 1 FROM sales.shipment_lot o
                            WHERE o.shipment_round_id = r.shipment_round_id AND o.import_request_id <> l.import_request_id) AS shared
              FROM sales.shipment_lot l JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE l.import_request_id IN (:ids)
             ORDER BY l.shipment_lot_id
            """, Map.of("ids", importRequestIds), rs -> {
                out.computeIfAbsent(rs.getLong("import_request_id"), k -> new ArrayList<>()).add(new PortalLot(
                    rs.getLong("shipment_lot_id"), rs.getLong("import_request_id"), rs.getLong("import_request_item_id"),
                    rs.getBigDecimal("qty"), rs.getBigDecimal("received_qty"), rs.getLong("shipment_round_id"),
                    rs.getString("source"), rs.getString("import_step"), rs.getBoolean("shared")));
            });
        return out;
    }

    /** The factory a round belongs to, or null when there is no such round. */
    Long roundFactoryId(long roundId) {
        List<Long> found = jdbc.query("SELECT factory_id FROM sales.shipment_round WHERE shipment_round_id = :id", Map.of("id", roundId),
            (rs, n) -> rs.getLong(1));
        return found.isEmpty() ? null : found.get(0);
    }

    Map<String, Long> roundIdsByKey(Collection<String> keys) {
        Map<String, Long> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT backfill_key, shipment_round_id FROM sales.shipment_round WHERE backfill_key IN (:keys)",
            Map.of("keys", keys), rs -> {
                out.put(rs.getString(1), rs.getLong(2));
            });
        return out;
    }

    /** The current value of every sheet fact of a round, as text. */
    Map<String, String> roundFacts(long roundId) {
        StringBuilder select = new StringBuilder();
        FACT_COLUMNS.keySet().forEach(c -> select.append("CAST(").append(c).append(" AS TEXT) AS ").append(c).append(", "));
        select.append("import_step");
        return jdbc.query("SELECT " + select + " FROM sales.shipment_round WHERE shipment_round_id = :r", Map.of("r", roundId),
            rs -> {
                Map<String, String> out = new LinkedHashMap<>();
                if (rs.next()) {
                    for (String c : FACT_COLUMNS.keySet()) {
                        out.put(c, rs.getString(c));
                    }
                    out.put("import_step", rs.getString("import_step"));
                }
                return out;
            });
    }

    String roundSnapshot(long roundId) {
        return jdbc.queryForObject("SELECT CAST(to_jsonb(r) AS TEXT) FROM sales.shipment_round r WHERE shipment_round_id = :r",
            Map.of("r", roundId), String.class);
    }

    long insertRound(String backfillKey, Long factoryId, String factoryName, SheetFacts facts, long actorId, String actorName) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        MapSqlParameterSource p = new MapSqlParameterSource("key", backfillKey).addValue("f", factoryId)
            .addValue("fn", factoryName).addValue("step", facts.step()).addValue("mode", facts.transportMode())
            .addValue("actor", actorId).addValue("actorName", actorName);
        for (Map.Entry<String, String> c : FACT_COLUMNS.entrySet()) {
            cols.append(c.getKey()).append(", ");
            vals.append("CAST(:").append(c.getKey()).append(" AS ").append(c.getValue()).append("), ");
            p.addValue(c.getKey(), facts.columns().get(c.getKey()));
        }
        return jdbc.queryForObject("INSERT INTO sales.shipment_round (" + cols
            + "backfill_key, factory_id, factory_name, source, import_step, transport_mode, step_by_id, step_by_name, created_by_id) VALUES ("
            + vals + ":key, :f, :fn, 'BACKFILL', :step, :mode, :actor, :actorName, :actor) RETURNING shipment_round_id", p, Long.class);
    }

    /** Fills only what is blank, and moves the step only forward. */
    void fillRound(long roundId, SheetFacts facts, long actorId, String actorName) {
        StringBuilder sets = new StringBuilder();
        MapSqlParameterSource p = new MapSqlParameterSource("r", roundId).addValue("target", facts.step())
            .addValue("order", String.join(",", STEP_ORDER)).addValue("actor", actorId).addValue("actorName", actorName);
        for (Map.Entry<String, String> c : FACT_COLUMNS.entrySet()) {
            String column = c.getKey();
            String current = "VARCHAR".equals(c.getValue()) ? "NULLIF(btrim(" + column + "), '')" : column;
            sets.append(column).append(" = COALESCE(").append(current).append(", CAST(:").append(column).append(" AS ")
                .append(c.getValue()).append(")), ");
            p.addValue(column, facts.columns().get(column));
        }
        String forward = "array_position(string_to_array(:order, ','), CAST(:target AS TEXT)) > array_position(string_to_array(:order, ','), CAST(import_step AS TEXT))";
        jdbc.update("UPDATE sales.shipment_round SET " + sets
            + "step_by_id = CASE WHEN " + forward + " THEN :actor ELSE step_by_id END, "
            + "step_by_name = CASE WHEN " + forward + " THEN :actorName ELSE step_by_name END, "
            + "import_step = CASE WHEN " + forward + " THEN :target ELSE import_step END, updated_at = now() "
            + "WHERE shipment_round_id = :r", p);
    }

    boolean documentExists(long roundId, String kind, String docNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM sales.shipment_round_document
                            WHERE shipment_round_id = :r AND kind = :k AND upper(btrim(doc_no)) = upper(btrim(:n)))
            """, Map.of("r", roundId, "k", kind, "n", docNumber), Boolean.class));
    }

    List<WantedItem> wantedItems(long importRequestId) {
        return jdbc.query("""
            SELECT i.import_request_item_id, COALESCE(w.wanted_qty, i.qty)
              FROM sales.import_request_item i
              LEFT JOIN sales.import_item_wanted w ON w.import_request_item_id = i.import_request_item_id
             WHERE i.import_request_id = :ir ORDER BY i.seq
            """, Map.of("ir", importRequestId), (rs, n) -> new WantedItem(rs.getLong(1), rs.getBigDecimal(2)));
    }

    /** Per job, every issued IR as {@code doc=step}. */
    Map<Long, List<String>> issuedStepsOfTickets(Collection<Long> ticketIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (ticketIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT ticket_id, doc_number, import_step FROM sales.import_request WHERE status = 'ISSUED' AND ticket_id IN (:ids)",
            Map.of("ids", ticketIds), rs -> {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2) + "=" + rs.getString(3));
            });
        return out;
    }

    Map<Long, TicketState> ticketStates(Collection<Long> ticketIds) {
        Map<Long, TicketState> out = new HashMap<>();
        if (ticketIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
            SELECT ticket_id, code, sales_stage, fulfillment_status, payment_status, lifecycle FROM sales.ticket WHERE ticket_id IN (:ids)
            """, Map.of("ids", ticketIds), rs -> {
                out.put(rs.getLong(1), new TicketState(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6)));
            });
        return out;
    }

    // ── the run log ─────────────────────────────────────────────────────────────────────────────────────

    long insertRun(long actorId, String actorName, String fileName, long fileSize, String sha256, String summaryJson) {
        return jdbc.queryForObject("""
            INSERT INTO sales.import_sheet_backfill_run (actor_id, actor_name, file_name, file_size, file_sha256, summary)
            VALUES (:a, :an, :f, :s, :h, CAST(:j AS JSONB)) RETURNING run_id
            """, new MapSqlParameterSource("a", actorId).addValue("an", actorName)
                .addValue("f", ImportSheetParser.cut(fileName, 255)).addValue("s", fileSize).addValue("h", sha256)
                .addValue("j", summaryJson), Long.class);
    }

    void updateRun(long runId, int roundsCreated, int roundsUpdated, int irsCreated, int irsAdopted, int irsSkipped,
                   int blocksExcluded, int dealsAdvanced, String summaryJson) {
        jdbc.update("""
            UPDATE sales.import_sheet_backfill_run
               SET rounds_created = :rc, rounds_updated = :ru, irs_created = :ic, irs_adopted = :ia, irs_skipped = :is,
                   blocks_excluded = :be, deals_advanced = :da, summary = CAST(:j AS JSONB)
             WHERE run_id = :id
            """, new MapSqlParameterSource("id", runId).addValue("rc", roundsCreated).addValue("ru", roundsUpdated)
                .addValue("ic", irsCreated).addValue("ia", irsAdopted).addValue("is", irsSkipped)
                .addValue("be", blocksExcluded).addValue("da", dealsAdvanced).addValue("j", summaryJson));
    }

    void finishRun(long runId) {
        jdbc.update("UPDATE sales.import_sheet_backfill_run SET finished_at = now() WHERE run_id = :id", Map.of("id", runId));
    }

    record LatestRun(long runId, java.time.Instant finishedAt, int roundsCreated, int irsCreated, int irsAdopted, int irsSkipped,
                     int blocksExcluded, int dealsAdvanced, String summaryJson) {}

    Optional<LatestRun> latestFinishedRun() {
        return jdbc.query("""
            SELECT run_id, finished_at, rounds_created, irs_created, irs_adopted, irs_skipped, blocks_excluded, deals_advanced,
                   CAST(summary AS TEXT)
              FROM sales.import_sheet_backfill_run WHERE finished_at IS NOT NULL ORDER BY run_id DESC LIMIT 1
            """, Map.of(), (rs, n) -> new LatestRun(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9))).stream().findFirst();
    }
}
