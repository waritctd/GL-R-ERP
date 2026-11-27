package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.importrequest.ImportJobDtos.JobHistory;
import th.co.glr.hr.importrequest.ImportJobDtos.JobLot;
import th.co.glr.hr.importrequest.ImportJobDtos.JobRound;

/** SQL of งานนำเข้า: an issued import request with no sales job. Every write here is guarded on {@code ticket_id IS NULL}. */
@Repository
class ImportJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    ImportJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record JobHeader(String purpose, String purposeLabel, String purposeNote, Long requesterId, String requesterName,
                     LocalDate receivedOn, LocalDate depositReceivedOn, LocalDate wantedBy, String requiredByNote,
                     String docNumber, long factoryId, String factoryName) {}

    int updateHeader(long id, JobHeader h) {
        return jdbc.update("""
            UPDATE sales.import_request
               SET order_purpose = :purpose, project_name = :label, purpose_note = :note, customer_name = :customer,
                   requester_id = :requester, requested_by_name = :requesterName, issue_date = :received,
                   first_issued_date = :received, deposit_received_date = :deposit, wanted_by = :wanted,
                   required_by_note = :wantedText, doc_number = :doc, factory_id = :factory, factory_name = :factoryName,
                   brand = :brand,
                   import_step_at = CASE WHEN import_step = 'CONTACTED' THEN :received ELSE import_step_at END,
                   updated_at = now()
             WHERE import_request_id = :id AND ticket_id IS NULL AND status = 'ISSUED'
            """, new MapSqlParameterSource("id", id).addValue("purpose", h.purpose()).addValue("label", h.purposeLabel())
                .addValue("note", h.purposeNote()).addValue("customer", ImportSheetParser.cut(h.purposeNote(), 200))
                .addValue("requester", h.requesterId()).addValue("requesterName", ImportSheetParser.cut(h.requesterName(), 200))
                .addValue("received", h.receivedOn()).addValue("deposit", h.depositReceivedOn()).addValue("wanted", h.wantedBy())
                .addValue("wantedText", h.requiredByNote()).addValue("doc", h.docNumber()).addValue("factory", h.factoryId())
                .addValue("factoryName", h.factoryName()).addValue("brand", ImportSheetParser.cut(h.factoryName(), 120)));
    }

    int updateItem(long importRequestId, long itemId, String code, String size, BigDecimal qty, String unit) {
        return jdbc.update("""
            UPDATE sales.import_request_item SET code = :code, size = :size, qty = :qty, unit = :unit
             WHERE import_request_item_id = :item AND import_request_id = :ir
            """, new MapSqlParameterSource("item", itemId).addValue("ir", importRequestId).addValue("code", code)
                .addValue("size", size).addValue("qty", qty).addValue("unit", unit));
    }

    void deleteItem(long importRequestId, long itemId) {
        jdbc.update("DELETE FROM sales.import_request_item WHERE import_request_item_id = :item AND import_request_id = :ir",
            Map.of("item", itemId, "ir", importRequestId));
    }

    int itemCount(long importRequestId) {
        return jdbc.queryForObject("SELECT count(*) FROM sales.import_request_item WHERE import_request_id = :ir",
            Map.of("ir", importRequestId), Integer.class);
    }

    String sourceOf(long importRequestId) {
        return jdbc.queryForObject("SELECT source FROM sales.import_request WHERE import_request_id = :ir",
            Map.of("ir", importRequestId), String.class);
    }

    Long requesterOf(long importRequestId) {
        return jdbc.queryForObject("SELECT requester_id FROM sales.import_request WHERE import_request_id = :ir",
            Map.of("ir", importRequestId), Long.class);
    }

    /** Lots of the job keyed by line, with what the sales view of a lot may show. */
    Map<Long, List<JobLot>> lotsByItem(long importRequestId) {
        Map<Long, List<JobLot>> out = new java.util.HashMap<>();
        jdbc.query("""
            SELECT l.import_request_item_id, l.shipment_lot_id, r.shipment_round_id, r.label, l.qty, l.received_qty, r.import_step
              FROM sales.shipment_lot l JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
             WHERE l.import_request_id = :ir ORDER BY l.shipment_lot_id
            """, Map.of("ir", importRequestId), rs -> {
                out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new JobLot(rs.getLong(2), rs.getLong(3),
                    rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7)));
            });
        return out;
    }

    /** The rounds the job rides on: no document and no round note. */
    List<JobRound> roundsOf(long importRequestId) {
        return jdbc.query("""
            SELECT DISTINCT r.shipment_round_id, r.factory_name, r.label, r.source, r.import_step, r.transport_mode, r.vessel, r.port,
                   r.ordered_on, r.picked_up_on, r.etd_actual_on, r.eta_on, r.transship_eta_on, r.insured_on, r.arrived_on, r.received_on
              FROM sales.shipment_round r JOIN sales.shipment_lot l ON l.shipment_round_id = r.shipment_round_id
             WHERE l.import_request_id = :ir ORDER BY r.shipment_round_id
            """, Map.of("ir", importRequestId), (rs, n) -> new JobRound(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), date(rs, 9), date(rs, 10),
                date(rs, 11), date(rs, 12), date(rs, 13), date(rs, 14), date(rs, 15), date(rs, 16)));
    }

    private static LocalDate date(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    /** The earliest real step date on any round of the job: its received date may not come after it. */
    LocalDate earliestStepDate(long importRequestId) {
        return jdbc.queryForObject("""
            SELECT min(d) FROM sales.shipment_lot l
              JOIN sales.shipment_round r ON r.shipment_round_id = l.shipment_round_id
              CROSS JOIN LATERAL (VALUES (r.contacted_on), (r.ordered_on), (r.picked_up_on), (r.in_transit_on),
                                         (r.awaiting_customs_on), (r.received_on)) AS v(d)
             WHERE l.import_request_id = :ir
            """, Map.of("ir", importRequestId), LocalDate.class);
    }

    List<JobHistory> history(long importRequestId) {
        return jdbc.query("""
            SELECT a.at, NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), '') AS actor_name, a.actor_email, a.action,
                   CAST(a.before_json AS TEXT), CAST(a.after_json AS TEXT)
              FROM hr.audit_log a LEFT JOIN hr.employee e ON e.employee_id = a.actor_user_id
             WHERE a.entity = 'import_request' AND a.entity_id = :ir
             ORDER BY a.at DESC, a.id DESC
            """, Map.of("ir", importRequestId), (rs, n) -> new JobHistory(rs.getTimestamp(1).toInstant(),
                rs.getString(2) != null ? rs.getString(2) : rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    java.time.Instant createdAt(long importRequestId) {
        return jdbc.queryForObject("SELECT created_at FROM sales.import_request WHERE import_request_id = :ir",
            Map.of("ir", importRequestId), java.sql.Timestamp.class).toInstant();
    }
}
