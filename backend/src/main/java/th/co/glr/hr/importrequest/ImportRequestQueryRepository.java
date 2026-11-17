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

/** READ-ONLY source data for the ใบขอซื้อ (F-SM-001). This class writes nothing, and that is the point. The
 * import request is generated on demand from the deal rather than stored: no sales.import_request table, no
 * migration, no lifecycle. */
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

    /** One deal line's factory-resolution INPUT (V184) — the raw candidates ImportRequestService's cascade needs,
     * still unresolved. */
    public record LineFactoryCandidate(
        long ticketItemId, String brand, String code, String model, String size, BigDecimal remainingQty,
        String unit, Long directFactoryId, String candidateFactoryName, String color, String texture
    ) {}

    /** CR-1 (GLA-167), owner rulings B-R4/B-R5: the lead times on the deal's CURRENT QUOTATION items -- never the
     * pricing-request lines. "Current" is route-specific: PRICING_REQUEST route: the ACCEPTED quotation
     * (DealQuotationRepository #findAcceptedItemQuantitiesByPricingRequest's own definition). */
    public List<QuotationLeadTimeRow> currentQuotationLeadTimes(long ticketId) {
        return jdbc.query("""
            SELECT pri.source_ticket_item_id AS ticket_item_id, NULL::bigint AS factory_id,
                   qi.lead_time_min_days, qi.lead_time_max_days
              FROM sales.quotation q
              JOIN sales.quotation_item qi ON qi.quotation_id = q.quotation_id
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = qi.pricing_request_item_id
             WHERE q.ticket_id = :id AND q.origin = 'PRICING_REQUEST' AND q.doc_status = 'ACCEPTED'
               AND pri.source_ticket_item_id IS NOT NULL
               AND qi.lead_time_min_days IS NOT NULL AND qi.lead_time_max_days IS NOT NULL
            UNION ALL
            SELECT NULL::bigint, pp.factory_id, qi.lead_time_min_days, qi.lead_time_max_days
              FROM sales.quotation q
              JOIN sales.quotation_item qi ON qi.quotation_id = q.quotation_id
              JOIN price_catalog.product_prices pp ON pp.price_id = qi.catalog_price_id
             WHERE q.ticket_id = :id AND q.origin = 'DEAL_DIRECT' AND q.doc_status = 'APPROVED'
               AND qi.lead_time_min_days IS NOT NULL AND qi.lead_time_max_days IS NOT NULL
            """, Map.of("id", ticketId), (rs, n) -> new QuotationLeadTimeRow(
                (Long) rs.getObject("ticket_item_id"), (Long) rs.getObject("factory_id"),
                rs.getInt("lead_time_min_days"), rs.getInt("lead_time_max_days")));
    }

    /** One quotation item's lead time, attributed to a ticket item (PR route) or a factory (direct route). */
    public record QuotationLeadTimeRow(Long ticketItemId, Long factoryId, int minDays, int maxDays) {}

    /** Per-line factory resolution inputs for the STORED ใบขอซื้อ path. Steps 4/5 of the plan
     * (match-by-normalized-name-or-create, or refuse) are the service's job once it has these candidates; this
     * method supplies the inputs only. */
    public boolean ticketHasItem(long ticketId, long ticketItemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.ticket_item WHERE item_id = :item AND ticket_id = :ticket)",
            java.util.Map.of("item", ticketItemId, "ticket", ticketId), Boolean.class));
    }

    /** Records the factory sales chose for a line that had none; never overwrites an existing name. */
    public void setItemFactoryIfBlank(long ticketId, long ticketItemId, String factoryName) {
        jdbc.update("""
            UPDATE sales.ticket_item SET factory = :name
             WHERE item_id = :item AND ticket_id = :ticket AND (factory IS NULL OR BTRIM(factory) = '')
            """, java.util.Map.of("name", factoryName, "item", ticketItemId, "ticket", ticketId));
    }

    public List<LineFactoryCandidate> factoryResolutionCandidates(long ticketId) {
        return jdbc.query("""
            SELECT ti.item_id, ti.brand, ti.model,
                   -- cm ruling 2026-10-30: the line's own catalogue row (ti.catalog_price_id) wins
                   COALESCE(pp.size_cm, ti.size)            AS size,
                   ti.unit,
                   ti.qty - COALESCE(ti.qty_from_stock, 0) AS remaining_qty,
                   pri.resolved_factory_id AS pri_factory_id,
                   NULLIF(BTRIM(pri.factory), '')          AS pri_factory_name,
                   pp.factory_id                            AS catalog_factory_id,
                   NULLIF(BTRIM(ti.factory), '')           AS ti_factory_name,
                   pp.product_code                          AS catalog_product_code,
                   NULLIF(BTRIM(pri.catalog_product_code), '') AS pri_catalog_product_code,
                   NULLIF(BTRIM(ti.color), '')              AS ti_color,
                   NULLIF(BTRIM(ti.texture), '')            AS ti_texture,
                   NULLIF(BTRIM(pri.color), '')             AS pri_color,
                   NULLIF(BTRIM(pri.texture), '')           AS pri_texture
              FROM sales.ticket_item ti
              LEFT JOIN LATERAL (
                  SELECT pri2.resolved_factory_id, pri2.factory, pri2.catalog_product_code,
                         pri2.color, pri2.texture
                    FROM sales.pricing_request_item pri2
                    JOIN sales.pricing_request pr2
                      ON pr2.pricing_request_id = pri2.pricing_request_id
                   WHERE pri2.source_ticket_item_id = ti.item_id
                     AND pr2.ticket_id = ti.ticket_id
                     AND pr2.status = 'QUOTATION_ACCEPTED'
                   ORDER BY pri2.pricing_request_item_id DESC
                   LIMIT 1
              ) pri ON true
              LEFT JOIN price_catalog.product_prices pp ON pp.price_id = ti.catalog_price_id
             WHERE ti.ticket_id = :id
               AND ti.qty - COALESCE(ti.qty_from_stock, 0) > 0
             ORDER BY ti.sort_order, ti.item_id
            """, Map.of("id", ticketId), (rs, n) -> {
                Long priFactoryId = (Long) rs.getObject("pri_factory_id");
                String priFactoryName = rs.getString("pri_factory_name");
                Long catalogFactoryId = (Long) rs.getObject("catalog_factory_id");
                String tiFactoryName = rs.getString("ti_factory_name");

                Long directId;
                String candidateName;
                if (priFactoryId != null) {
                    directId = priFactoryId;
                    candidateName = null;
                } else if (priFactoryName != null) {
                    directId = null;
                    candidateName = priFactoryName;
                } else if (catalogFactoryId != null) {
                    directId = catalogFactoryId;
                    candidateName = null;
                } else {
                    directId = null;
                    candidateName = tiFactoryName; // null when truly nothing resolves
                }

                // Owner decision 09-18 #3 §A: code prefers the catalog link, then the order-confirmed pricing-request-item's
                // own catalog code, then the hand-typed model — see ImportRequestLinePrefill's Javadoc for why this and the
                // color/texture cascade below share one helper.
                String code = ImportRequestLinePrefill.firstNonBlank(
                    rs.getString("catalog_product_code"), rs.getString("pri_catalog_product_code"),
                    rs.getString("model"));
                String color = ImportRequestLinePrefill.firstNonBlank(
                    rs.getString("ti_color"), rs.getString("pri_color"));
                String texture = ImportRequestLinePrefill.firstNonBlank(
                    rs.getString("ti_texture"), rs.getString("pri_texture"));

                return new LineFactoryCandidate(
                    rs.getLong("item_id"), rs.getString("brand"), code, rs.getString("model"),
                    rs.getString("size"), rs.getBigDecimal("remaining_qty"), rs.getString("unit"),
                    directId, candidateName, color, texture);
            });
    }

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
                    // Now really persisted: V154 added sales.ticket.required_by_note, set by Sales from ORDER_RECEIVED onward.
                    // Preview callers may still override it per request; the STORED path snapshots this value at issue.
                    rs.getString("required_by_note"),
                    d == null ? null : d.toLocalDate(),
                    rs.getString("status"), rs.getString("lifecycle")));
            });
    }

    /** Does employeeId own this deal? Expressed as createdById, the same way TicketService.requireDealOwnership and
     * canDeclareStockCoverage express it. Used only by setRequiredByNote — the one write here that belongs to
     * SALES rather than import, so it cannot lean on IR_ROLES. */
    public boolean isDealOwner(long ticketId, long employeeId) {
        Boolean owns = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM sales.ticket WHERE ticket_id = :id AND (created_by = :emp OR keyed_by = :emp))",
            Map.of("id", ticketId, "emp", employeeId), Boolean.class);
        return Boolean.TRUE.equals(owns);
    }

    /** Has the deal reached ORDER_RECEIVED — the floor the owner set for Sales supplying "กำหนดวันที่ต้องการของ"
     * ("when the order is already confirmed")? Fails closed on an unknown stage: indexOf returns -1, which is
     * below the floor. */
    public boolean stageAtLeastOrderReceived(long ticketId) {
        String stage = jdbc.queryForObject(
            "SELECT sales_stage FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
        return th.co.glr.hr.ticket.DealStage.indexOf(stage)
            >= th.co.glr.hr.ticket.DealStage.indexOf(th.co.glr.hr.ticket.DealStage.ORDER_RECEIVED);
    }

    /** The deal's lines grouped by brand, in printed order — one entry per form to raise. sales.ticket_item.brand
     * has been NOT NULL since V8, so a null can never arrive — the null branch below is belt-and-braces against a
     * future schema change, not a live case. */
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
