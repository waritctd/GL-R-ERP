package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * READ-ONLY source data for the ใบขอซื้อ (F-SM-001).
 *
 * <p><strong>This class writes nothing, and that is the point.</strong> The import request is
 * generated on demand from the deal rather than stored: no {@code sales.import_request} table, no
 * migration, no lifecycle. The stored-aggregate version (draft → issue → supersede, a minted
 * {@code IR<yy><nnn>}, superseding revisions) is designed and its migration written, but it is a
 * schema change against a production Flyway history with six known checksum mismatches, so it
 * ships separately and deliberately later. Everything here is a SELECT against columns that
 * already exist in production.
 *
 * <p>The consequence a reader should know: the document number and "กำหนดวันที่ต้องการของ" are
 * supplied by the caller, not persisted. That matches how the business already works — in the
 * owner's own IR69068 the number is a pasted overlay, not part of the document.
 */
@Repository
public class ImportRequestQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportRequestQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The deal-level values the form prints, plus the state fields the service gates on. */
    public record TicketSnapshot(
        long ticketId,
        String ticketCode,
        String customerName,
        String projectName,
        String requestedByName,
        String requiredByNote,
        LocalDate depositReceivedDate,
        String status,
        String lifecycle
    ) {}

    /** One brand's deal lines — one printed form per entry. */
    public record BrandLines(String brand, List<Line> lines) {}

    public record Line(long ticketItemId, String code, String size, BigDecimal qty, String unit) {}

    public Optional<TicketSnapshot> loadTicketSnapshot(long ticketId) {
        return jdbc.query("""
            SELECT t.ticket_id, t.code, t.customer_name, p.name AS project_name,
                   COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name_th, e.last_name_th)), ''),
                            e.nickname, e.employee_code) AS rep_name,
                   t.required_by_note, t.status, t.lifecycle,
                   (SELECT MIN(pr.received_at)::date
                      FROM sales.payment_receipt pr
                     WHERE pr.ticket_id = t.ticket_id AND pr.kind = 'DEPOSIT') AS deposit_date
              FROM sales.ticket t
              LEFT JOIN customers.project p ON p.project_id = t.project_id
              LEFT JOIN hr.employee e ON e.employee_id = t.created_by
             WHERE t.ticket_id = :id
            """, Map.of("id", ticketId), rs -> {
                if (!rs.next()) {
                    return Optional.<TicketSnapshot>empty();
                }
                java.sql.Date d = rs.getDate("deposit_date");
                return Optional.of(new TicketSnapshot(
                    rs.getLong("ticket_id"), rs.getString("code"), rs.getString("customer_name"),
                    rs.getString("project_name"), rs.getString("rep_name"),
                    // Now really persisted: V154 added sales.ticket.required_by_note, set by Sales
                    // from ORDER_RECEIVED onward. Preview callers may still override it per request;
                    // the STORED path snapshots this value at issue.
                    rs.getString("required_by_note"),
                    d == null ? null : d.toLocalDate(),
                    rs.getString("status"), rs.getString("lifecycle")));
            });
    }

    /**
     * Does {@code employeeId} own this deal? Expressed as {@code createdById}, the same way
     * {@code TicketService.requireDealOwnership} and {@code isFulfilmentOrOwningRep} express it.
     *
     * <p>Used only by {@code setRequiredByNote} — the one write here that belongs to SALES rather than
     * import, so it cannot lean on {@code IR_ROLES}.
     */
    public boolean isDealOwner(long ticketId, long employeeId) {
        Boolean owns = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.ticket WHERE ticket_id = :id AND created_by = :emp)",
            Map.of("id", ticketId, "emp", employeeId), Boolean.class);
        return Boolean.TRUE.equals(owns);
    }

    /**
     * Has the deal reached ORDER_RECEIVED — the floor the owner set for Sales supplying
     * "กำหนดวันที่ต้องการของ" ("when the order is already confirmed")?
     *
     * <p>Compared by POSITION in {@code DealStage.ORDER}, not by a hardcoded list of stage names, so
     * inserting a stage (as V143 did with QUOTE_OWNER) cannot silently change which deals qualify.
     * Fails closed on an unknown stage: {@code indexOf} returns -1, which is below the floor.
     */
    public boolean stageAtLeastOrderReceived(long ticketId) {
        String stage = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
        return th.co.glr.hr.ticket.DealStage.indexOf(stage)
            >= th.co.glr.hr.ticket.DealStage.indexOf(th.co.glr.hr.ticket.DealStage.ORDER_RECEIVED);
    }

    /**
     * The deal's lines grouped by brand, in printed order — one entry per form to raise.
     *
     * <p>Lines with no usable brand come back under a {@code null} brand rather than being bucketed
     * with anything else, so the service can refuse rather than print a form whose header does not
     * say what was ordered. F-SM-001 has no unbranded variant and the owner confirmed the field is
     * necessary.
     *
     * <p><strong>"No usable brand" means BLANK, not NULL.</strong> {@code sales.ticket_item.brand}
     * has been {@code NOT NULL} since V8, so a null can never arrive — the null branch below is
     * belt-and-braces against a future schema change, not a live case. An empty or whitespace-only
     * string IS reachable, which is why the check is {@code isBlank()} and not a null test. Verified
     * against real Postgres by {@code ImportRequestServiceIntegrationTest}, which had to be written
     * with a blank brand because inserting a null one is rejected by the column itself.
     *
     * <p>Ordering is {@code sort_order, item_id} within a brand — the deal's own printed order, the
     * same one {@code TicketRepository} uses for the items table. Do not reorder here: the Item
     * column is a positional sequence a reader matches against the deal.
     */
    public List<BrandLines> brandLinesForTicket(long ticketId) {
        Map<String, List<Line>> byBrand = new LinkedHashMap<>();
        jdbc.query("""
            SELECT item_id, brand, model, size, qty, unit
              FROM sales.ticket_item
             WHERE ticket_id = :id
             ORDER BY brand NULLS FIRST, sort_order, item_id
            """, Map.of("id", ticketId), rs -> {
                String brand = rs.getString("brand");
                byBrand.computeIfAbsent(brand == null || brand.isBlank() ? "" : brand,
                        k -> new ArrayList<>())
                    .add(new Line(rs.getLong("item_id"), rs.getString("model"),
                                  rs.getString("size"), rs.getBigDecimal("qty"),
                                  rs.getString("unit")));
            });
        return byBrand.entrySet().stream()
            .map(e -> new BrandLines(e.getKey().isEmpty() ? null : e.getKey(), e.getValue()))
            .toList();
    }
}
