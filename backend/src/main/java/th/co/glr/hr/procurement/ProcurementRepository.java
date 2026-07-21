package th.co.glr.hr.procurement;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderDto;
import th.co.glr.hr.procurement.ProcurementDtos.FactoryPurchaseOrderItemDto;

/**
 * Persistence for the Step 7 aggregate (V77): {@code sales.factory_purchase_order}, {@code
 * sales.factory_purchase_order_item}. Persistence only, mirroring every prior step's own
 * repository/service split — no permission checks, no workflow validation; see {@link
 * ProcurementService} for all of that.
 */
@Repository
public class ProcurementRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProcurementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextPoNumber() {
        Long seq = jdbc.queryForObject("SELECT nextval('sales.factory_purchase_order_no_seq')", Map.of(), Long.class);
        return "FPO-" + Year.now() + "-" + String.format("%04d", seq == null ? 0 : seq);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Source-of-truth read: the APPROVED decision's own costing items, grouped by factory.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** The pricing_costing_id the pricing request's currently-APPROVED decision references —
     * this and only this costing version's items may ever become a PO line (see this class's
     * own Javadoc and V77's header comment). Empty if no decision has been approved yet. */
    public Optional<Long> findApprovedPricingCostingId(long pricingRequestId) {
        try {
            Long id = jdbc.queryForObject("""
                SELECT pricing_costing_id
                  FROM sales.pricing_decision
                 WHERE pricing_request_id = :pricingRequestId AND status = 'APPROVED'
                """, Map.of("pricingRequestId", pricingRequestId), Long.class);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record CostingItemForPo(
        long pricingCostingItemId, long pricingRequestItemId, Long factoryId, String factoryName,
        BigDecimal rawUnitPrice, String rawCurrency, BigDecimal requestedQuantity
    ) {}

    /** Every line of the APPROVED costing version, ordered by factory so the caller can group
     * them into one PO per factory without a second pass. */
    public List<CostingItemForPo> findCostingItemsForPo(long pricingCostingId) {
        return jdbc.query("""
            SELECT pricing_costing_item_id, pricing_request_item_id, factory_id, factory_name,
                   raw_unit_price, raw_currency, requested_quantity
              FROM sales.pricing_costing_item
             WHERE pricing_costing_id = :pricingCostingId
             ORDER BY factory_name, pricing_costing_item_id
            """, Map.of("pricingCostingId", pricingCostingId), (rs, rowNum) -> {
                long factoryIdRaw = rs.getLong("factory_id");
                Long factoryId = rs.wasNull() ? null : factoryIdRaw;
                return new CostingItemForPo(
                    rs.getLong("pricing_costing_item_id"), rs.getLong("pricing_request_item_id"),
                    factoryId, rs.getString("factory_name"),
                    rs.getBigDecimal("raw_unit_price"), rs.getString("raw_currency"),
                    rs.getBigDecimal("requested_quantity"));
            });
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Create.
    // ─────────────────────────────────────────────────────────────────────────────────────

    public Optional<Long> findOpenIdByFactory(long pricingRequestId, String factoryName) {
        try {
            Long id = jdbc.queryForObject("""
                SELECT factory_purchase_order_id
                  FROM sales.factory_purchase_order
                 WHERE pricing_request_id = :pricingRequestId AND factory_name = :factoryName
                """, new MapSqlParameterSource()
                    .addValue("pricingRequestId", pricingRequestId)
                    .addValue("factoryName", factoryName),
                Long.class);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long insertPo(long pricingRequestId, long ticketId, Long factoryId, String factoryName,
                         String currency, String clientRequestId, long actorId) {
        Long id = jdbc.queryForObject("""
            INSERT INTO sales.factory_purchase_order
                (po_number, pricing_request_id, ticket_id, factory_id, factory_name, status,
                 currency, client_request_id, created_by)
            VALUES
                (:poNumber, :pricingRequestId, :ticketId, :factoryId, :factoryName, 'OPEN',
                 :currency, CAST(:clientRequestId AS uuid), :createdBy)
            RETURNING factory_purchase_order_id
            """,
            new MapSqlParameterSource()
                .addValue("poNumber", nextPoNumber())
                .addValue("pricingRequestId", pricingRequestId)
                .addValue("ticketId", ticketId)
                .addValue("factoryId", factoryId)
                .addValue("factoryName", factoryName)
                .addValue("currency", currency)
                .addValue("clientRequestId", clientRequestId)
                .addValue("createdBy", actorId),
            Long.class);
        return id;
    }

    /**
     * Inserts one PO line per costing item, but ONLY for costing items that actually belong to
     * {@code pricingRequestId} (the {@code pc.pricing_request_id = :pricingRequestId} join
     * condition) — the cross-tenant/cross-request reference guard. A caller that (by bug or
     * malicious input) passes a costing-item id belonging to a DIFFERENT pricing request gets it
     * silently filtered out of the {@code SELECT}, not inserted; the returned count then falls
     * short of {@code pricingCostingItemIds.size()}, which {@link ProcurementService} treats as a
     * hard failure. Also silently skips any id already consumed by an earlier PO (the {@code
     * uq_factory_po_item_costing_item} unique constraint backs this with {@code ON CONFLICT DO
     * NOTHING}, defense-in-depth against a concurrent double-insert of the same line).
     *
     * @return the number of rows actually inserted — the caller must compare this against the
     *         requested id list, not assume success.
     */
    public int insertItems(long factoryPurchaseOrderId, long pricingRequestId, List<Long> pricingCostingItemIds) {
        if (pricingCostingItemIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
            INSERT INTO sales.factory_purchase_order_item
                (factory_purchase_order_id, pricing_costing_item_id, pricing_request_item_id,
                 quantity, unit_price, currency, line_total)
            SELECT :factoryPurchaseOrderId, pci.pricing_costing_item_id, pci.pricing_request_item_id,
                   pci.requested_quantity, pci.raw_unit_price, pci.raw_currency,
                   pci.requested_quantity * pci.raw_unit_price
              FROM sales.pricing_costing_item pci
              JOIN sales.pricing_costing pc ON pc.pricing_costing_id = pci.pricing_costing_id
             WHERE pci.pricing_costing_item_id IN (:ids)
               AND pc.pricing_request_id = :pricingRequestId
            ON CONFLICT (pricing_costing_item_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("factoryPurchaseOrderId", factoryPurchaseOrderId)
                .addValue("ids", pricingCostingItemIds)
                .addValue("pricingRequestId", pricingRequestId));
    }

    public void recalcTotal(long factoryPurchaseOrderId) {
        jdbc.update("""
            UPDATE sales.factory_purchase_order
               SET total_amount = COALESCE((
                       SELECT SUM(line_total) FROM sales.factory_purchase_order_item
                        WHERE factory_purchase_order_id = :id
                   ), 0),
                   updated_at = now()
             WHERE factory_purchase_order_id = :id
            """, Map.of("id", factoryPurchaseOrderId));
    }

    public List<FactoryPurchaseOrderDto> findByClientRequestId(long createdBy, String clientRequestId) {
        List<Long> ids = jdbc.query("""
            SELECT factory_purchase_order_id FROM sales.factory_purchase_order
             WHERE created_by = :createdBy AND client_request_id = CAST(:clientRequestId AS uuid)
            """,
            new MapSqlParameterSource().addValue("createdBy", createdBy).addValue("clientRequestId", clientRequestId),
            (rs, rowNum) -> rs.getLong("factory_purchase_order_id"));
        List<FactoryPurchaseOrderDto> result = new ArrayList<>();
        for (Long id : ids) {
            findById(id).ifPresent(result::add);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Status updates.
    // ─────────────────────────────────────────────────────────────────────────────────────

    /** Compare-and-set guard: only a PO NOT already CLOSED (RECEIVED/CANCELLED) may be touched.
     * Returns the affected row count — 0 means the PO was already closed. */
    public int recordSupplierProforma(long id, String supplierProformaRef, String supplierPaymentScheduleNote) {
        return jdbc.update("""
            UPDATE sales.factory_purchase_order
               SET supplier_proforma_ref = :ref, supplier_payment_schedule_note = :note, updated_at = now()
             WHERE factory_purchase_order_id = :id AND status NOT IN ('RECEIVED', 'CANCELLED')
            """,
            new MapSqlParameterSource().addValue("id", id).addValue("ref", supplierProformaRef).addValue("note", supplierPaymentScheduleNote));
    }

    /** Records whichever shipping fields are supplied (each nullable — see {@code
     * RecordShippingDetailRequest}'s own Javadoc) and advances OPEN -&gt; SHIPPING. Idempotent:
     * calling this again while already SHIPPING just updates the fields, no error. */
    public int recordShippingDetail(long id, String containerRef, LocalDate etd, LocalDate eta, String customsStatus) {
        return jdbc.update("""
            UPDATE sales.factory_purchase_order
               SET container_ref = COALESCE(:containerRef, container_ref),
                   etd = COALESCE(:etd, etd),
                   eta = COALESCE(:eta, eta),
                   customs_status = COALESCE(:customsStatus, customs_status),
                   status = CASE WHEN status = 'OPEN' THEN 'SHIPPING' ELSE status END,
                   updated_at = now()
             WHERE factory_purchase_order_id = :id AND status NOT IN ('RECEIVED', 'CANCELLED')
            """,
            new MapSqlParameterSource()
                .addValue("id", id).addValue("containerRef", containerRef)
                .addValue("etd", etd).addValue("eta", eta).addValue("customsStatus", customsStatus));
    }

    public int recordGoodsReceived(long id, BigDecimal actualLandedCostThb) {
        return jdbc.update("""
            UPDATE sales.factory_purchase_order
               SET actual_landed_cost_thb = :cost, status = 'RECEIVED', received_at = now(), updated_at = now()
             WHERE factory_purchase_order_id = :id AND status NOT IN ('RECEIVED', 'CANCELLED')
            """, new MapSqlParameterSource().addValue("id", id).addValue("cost", actualLandedCostThb));
    }

    /**
     * Step 8: records the ACTUAL quantity Import counted for one PO line, plus any QC/damage
     * note. Scoped to {@code factoryPurchaseOrderId} — an itemId belonging to a DIFFERENT PO is
     * silently not updated (0 rows), which {@link ProcurementService#recordGoodsReceived} treats
     * as a hard failure (the caller must not have been able to reach this at all in practice,
     * since it validates every itemId against THIS PO's own item set first — this is
     * defense-in-depth, mirroring Step 7's own cross-tenant guard on
     * {@code insertItems}).
     */
    public int recordItemReceipt(long factoryPurchaseOrderId, long itemId, BigDecimal qtyReceived, String qcNote) {
        return jdbc.update("""
            UPDATE sales.factory_purchase_order_item
               SET qty_received = :qtyReceived, qc_note = :qcNote
             WHERE factory_purchase_order_item_id = :itemId AND factory_purchase_order_id = :poId
            """,
            new MapSqlParameterSource()
                .addValue("poId", factoryPurchaseOrderId)
                .addValue("itemId", itemId)
                .addValue("qtyReceived", qtyReceived)
                .addValue("qcNote", qcNote));
    }

    public int cancel(long id, String reason) {
        return jdbc.update("""
            UPDATE sales.factory_purchase_order
               SET status = 'CANCELLED', cancel_reason = :reason, cancelled_at = now(), updated_at = now()
             WHERE factory_purchase_order_id = :id AND status NOT IN ('RECEIVED', 'CANCELLED')
            """, new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Reads.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final String SELECT_PO = """
        SELECT po.*, pr.request_code AS pricing_request_code, t.code AS ticket_code,
               cb.first_name_th AS created_by_first_name_th, cb.last_name_th AS created_by_last_name_th
          FROM sales.factory_purchase_order po
          JOIN sales.pricing_request pr ON pr.pricing_request_id = po.pricing_request_id
          JOIN sales.ticket t ON t.ticket_id = po.ticket_id
          LEFT JOIN hr.employee cb ON cb.employee_id = po.created_by
        """;

    public Optional<FactoryPurchaseOrderDto> findById(long id) {
        List<FactoryPurchaseOrderDto> rows = jdbc.query(SELECT_PO + " WHERE po.factory_purchase_order_id = :id",
            Map.of("id", id), (rs, rowNum) -> mapPo(rs));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        FactoryPurchaseOrderDto po = rows.get(0);
        return Optional.of(withItems(po));
    }

    public List<FactoryPurchaseOrderDto> findByPricingRequest(long pricingRequestId) {
        List<FactoryPurchaseOrderDto> pos = jdbc.query(SELECT_PO + " WHERE po.pricing_request_id = :pricingRequestId ORDER BY po.created_at",
            Map.of("pricingRequestId", pricingRequestId), (rs, rowNum) -> mapPo(rs));
        return pos.stream().map(this::withItems).toList();
    }

    /** Import/CEO's own list view (no ticket/pricing-request scoping — every PO is theirs to
     * see, matching {@code FactoryQuoteService}'s own list-everything shape for that role pair). */
    public List<FactoryPurchaseOrderDto> findAll(String status) {
        String sql = SELECT_PO + (status != null && !status.isBlank() ? " WHERE po.status = :status" : "") + " ORDER BY po.created_at DESC";
        List<FactoryPurchaseOrderDto> pos = jdbc.query(sql,
            status != null && !status.isBlank() ? Map.of("status", status) : Map.of(),
            (rs, rowNum) -> mapPo(rs));
        return pos.stream().map(this::withItems).toList();
    }

    private FactoryPurchaseOrderDto withItems(FactoryPurchaseOrderDto po) {
        List<FactoryPurchaseOrderItemDto> items = jdbc.query("""
            SELECT poi.*, pri.brand, pri.model, pri.product_description,
                   pci.landed_cost_per_unit_thb AS est_landed_cost_per_unit_thb,
                   pci.total_landed_cost_thb AS est_total_landed_cost_thb
              FROM sales.factory_purchase_order_item poi
              JOIN sales.pricing_request_item pri ON pri.pricing_request_item_id = poi.pricing_request_item_id
              JOIN sales.pricing_costing_item pci ON pci.pricing_costing_item_id = poi.pricing_costing_item_id
             WHERE poi.factory_purchase_order_id = :id
             ORDER BY poi.factory_purchase_order_item_id
            """, Map.of("id", po.id()), (rs, rowNum) -> {
                BigDecimal quantity = rs.getBigDecimal("quantity");
                BigDecimal qtyReceived = rs.getBigDecimal("qty_received");
                return new FactoryPurchaseOrderItemDto(
                    rs.getLong("factory_purchase_order_item_id"), rs.getLong("factory_purchase_order_id"),
                    rs.getLong("pricing_costing_item_id"), rs.getLong("pricing_request_item_id"),
                    rs.getString("brand"), rs.getString("model"), rs.getString("product_description"),
                    quantity, rs.getBigDecimal("unit_price"), rs.getString("currency"),
                    rs.getBigDecimal("line_total"),
                    rs.getBigDecimal("est_landed_cost_per_unit_thb"), rs.getBigDecimal("est_total_landed_cost_thb"),
                    qtyReceived, rs.getString("qc_note"),
                    qtyReceived == null ? null : qtyReceived.subtract(quantity));
            });
        return new FactoryPurchaseOrderDto(
            po.id(), po.poNumber(), po.pricingRequestId(), po.pricingRequestCode(), po.ticketId(), po.ticketCode(),
            po.factoryId(), po.factoryName(), po.status(), po.supplierProformaRef(), po.supplierPaymentScheduleNote(),
            po.currency(), po.totalAmount(), po.etd(), po.eta(), po.containerRef(), po.customsStatus(),
            po.actualLandedCostThb(), po.cancelReason(), po.createdBy(), po.createdByName(),
            po.createdAt(), po.updatedAt(), po.receivedAt(), po.cancelledAt(), items);
    }

    private FactoryPurchaseOrderDto mapPo(ResultSet rs) throws SQLException {
        long factoryIdRaw = rs.getLong("factory_id");
        Long factoryId = rs.wasNull() ? null : factoryIdRaw;
        long createdByRaw = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : createdByRaw;
        String createdByName = joinName(rs.getString("created_by_first_name_th"), rs.getString("created_by_last_name_th"));
        return new FactoryPurchaseOrderDto(
            rs.getLong("factory_purchase_order_id"), rs.getString("po_number"),
            rs.getLong("pricing_request_id"), rs.getString("pricing_request_code"),
            rs.getLong("ticket_id"), rs.getString("ticket_code"),
            factoryId, rs.getString("factory_name"), rs.getString("status"),
            rs.getString("supplier_proforma_ref"), rs.getString("supplier_payment_schedule_note"),
            rs.getString("currency"), rs.getBigDecimal("total_amount"),
            rs.getObject("etd", LocalDate.class), rs.getObject("eta", LocalDate.class),
            rs.getString("container_ref"), rs.getString("customs_status"),
            rs.getBigDecimal("actual_landed_cost_thb"), rs.getString("cancel_reason"),
            createdBy, createdByName,
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("received_at") != null ? rs.getTimestamp("received_at").toInstant() : null,
            rs.getTimestamp("cancelled_at") != null ? rs.getTimestamp("cancelled_at").toInstant() : null,
            List.of());
    }

    private String joinName(String firstNameTh, String lastNameTh) {
        String joined = ((firstNameTh == null ? "" : firstNameTh) + " " + (lastNameTh == null ? "" : lastNameTh)).trim();
        return joined.isEmpty() ? null : joined;
    }
}
