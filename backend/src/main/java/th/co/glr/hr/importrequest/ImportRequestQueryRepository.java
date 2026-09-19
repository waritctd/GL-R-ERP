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

    /**
     * One deal line's factory-resolution INPUT (V184) — the raw candidates {@link
     * ImportRequestService}'s cascade needs, still unresolved. Read-only; the resolve-or-create
     * decision (including the factory-master WRITE for an unresolved name — owner decision 2) is the
     * service's job, not this class's, matching this repository's own "writes nothing" discipline.
     *
     * @param remainingQty {@code qty - qty_from_stock}, already floored at zero. A line fully covered
     *                     from stock needs no import at all (owner decision 4: "zero-qty lines
     *                     omitted") and is filtered out by {@link #factoryResolutionCandidates}
     *                     before this record is ever built for it.
     * @param directFactoryId a factory ID already resolved with no name-matching needed — either
     *                     {@code pricing_request_item.resolved_factory_id} (the catalog-picked path)
     *                     or {@code product_prices.factory_id} via {@code ticket_item.catalog_price_id}.
     *                     Takes priority over {@code candidateFactoryName} whenever both are absent-or-
     *                     present is ambiguous; see the cascade order in this class's Javadoc on
     *                     {@link #factoryResolutionCandidates}.
     * @param candidateFactoryName a hand-typed factory NAME with no direct ID — needs matching against
     *                     the factory master by normalized name, or creating on the fly if it matches
     *                     nothing (owner decision 2). Null when a direct ID was already found, or when
     *                     no factory information exists anywhere for this line (in which case the
     *                     service refuses rather than deriving one from {@code brand}).
     * @param code         the F-SM-001 line's prefilled "Code" (owner decision 09-18 #3 §A), already
     *                     resolved by {@link ImportRequestLinePrefill#firstNonBlank}: the catalog
     *                     product code via {@code ticket_item.catalog_price_id} →
     *                     {@code price_catalog.product_prices.product_code}, else the order-confirmed
     *                     pricing_request_item's {@code catalog_product_code}, else the hand-typed
     *                     {@code ticket_item.model}. Never blank — {@code model} is the floor of the
     *                     cascade and {@code ticket_item.model} has no NOT NULL constraint of its own,
     *                     but every deal line is created with one.
     * @param model        (REVIEW ROUND 2, S-A) the RAW {@code ticket_item.model} text, kept SEPARATE
     *                     from {@code code} — {@code code} prefers a catalog product code and only
     *                     falls back to this same text when no catalog code exists anywhere, so the
     *                     two are frequently different values. Feeds the order-email draft's per-line
     *                     header ("<Brand> <Model>  (Code: X)") alongside {@link #brand}.
     * @param color        resolved colour, preferring {@code ticket_item.color} over the
     *                     order-confirmed pricing_request_item's, for the printed note sub-row and the
     *                     order-email's Colour/Surface/Size detail line. Null when neither has one.
     * @param texture      resolved surface/texture — see {@link #color}.
     */
    public record LineFactoryCandidate(
        long ticketItemId, String brand, String code, String model, String size, BigDecimal remainingQty,
        String unit, Long directFactoryId, String candidateFactoryName, String color, String texture
    ) {}

    /**
     * Per-line factory resolution inputs for the STORED (factory-grained) ใบขอซื้อ path — replaces
     * {@link #brandLinesForTicket} for {@code createDrafts}/the rollup, which are per-FACTORY as of
     * V184. {@link #brandLinesForTicket} itself is UNCHANGED and still serves the singular PREVIEW
     * routes ({@code render}/{@code brands}/{@code pageCount}), which stay per-brand: those routes are
     * being demoted to {@code SERVER_ONLY} (owner decision 4, superseded by the stored aggregate's
     * {@code /file} route in a later PR) rather than rewritten, so this migration does not touch their
     * grain or their existing tests.
     *
     * <p><strong>Cascade, per {@code ticket_item}, in order</strong> (import-request-per-factory-PLAN.md
     * §2, amended 2026-09-18 after the pricing session's answer that catalog-picked lines get {@code
     * resolved_factory_id} via {@code snapshotCatalogSelections} while hand-typed lines get only a
     * {@code factory} NAME once import runs {@code setItemFactory} — before that both are NULL):
     * <ol>
     *   <li>The line's {@code pricing_request_item} on the deal's ORDER-CONFIRMED ({@code
     *       QUOTATION_ACCEPTED}) pricing request, found via {@code source_ticket_item_id}: its {@code
     *       resolved_factory_id} if set, else its {@code factory} name if non-blank.
     *   <li>{@code ticket_item.catalog_price_id} → {@code price_catalog.product_prices.factory_id}.
     *   <li>{@code ticket_item.factory} (hand-typed name).
     * </ol>
     * The first step that yields EITHER a direct ID or a name stops the cascade for that line — a
     * name found at step 1 is not overridden by a direct ID a later step might have produced. Steps
     * 4/5 of the plan (match-by-normalized-name-or-create, or refuse) are the service's job once it
     * has these candidates; this method supplies the inputs only.
     *
     * <p><strong>{@code brand} is NEVER a factory-resolution input</strong> — it is carried here only
     * as a display/filename snapshot. One factory makes several brands, and the pricing-flow redesign
     * (Phase 1, unmerged as of this writing) already has sales no longer picking a factory at all — the
     * "โรงงาน" label the CEO/import UI shows for a pricing-request line is cosmetic on the {@code brand}
     * column, not a real factory selection. Deriving a factory from brand would therefore invent a
     * relationship this codebase's own data does not assert.
     */
    public List<LineFactoryCandidate> factoryResolutionCandidates(long ticketId) {
        return jdbc.query("""
            SELECT ti.item_id, ti.brand, ti.model, ti.size, ti.unit,
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

                // Owner decision 09-18 #3 §A: code prefers the catalog link, then the order-confirmed
                // pricing-request-item's own catalog code, then the hand-typed model — see
                // ImportRequestLinePrefill's Javadoc for why this and the color/texture cascade below
                // share one helper.
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
