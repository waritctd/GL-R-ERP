package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationCountsDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;

/**
 * Persistence for Quotation v2 (direct deal quotation, V165) — extends the SAME {@code
 * sales.quotation}/{@code sales.quotation_item} aggregate the pricing chain
 * ({@code customerquotation/CustomerQuotationRepository}) already uses, tagged {@code origin =
 * 'DEAL_DIRECT'}. Every read here filters on that column so the two flows' rows never see each
 * other — mirrors {@code CustomerQuotationRepository}'s own {@code pricing_request_id IS NOT
 * NULL} filter exactly.
 *
 * <p>Persistence and mapping only — no permission checks, no arithmetic (see {@link
 * WastageCalculator}), no status-machine rules; see {@link DealQuotationService} for all of that.
 */
@Repository
public class DealQuotationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public DealQuotationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same sequence, same format as {@code CustomerQuotationRepository.nextQuotationCode()} —
     * one quotation number space across both flows (the {@code number} column is UNIQUE). */
    public String nextQuotationCode() {
        long seq = jdbc.queryForObject("SELECT nextval('sales.quotation_code_seq')", Map.of(), Long.class);
        return "QT-" + Year.now() + "-" + String.format("%04d", seq);
    }

    /**
     * A revision child's number is {@code {base}-{revisionNo}}. Since every number this class
     * ever produces for revisionNo &gt; 1 was itself built by this exact method, the trailing
     * {@code "-" + sourceRevisionNo} suffix on a source number always exactly identifies the base
     * — so stripping it recovers the ORIGINAL first-revision number regardless of how many times
     * the chain has already been revised, without needing to walk {@code parent_quotation_id} all
     * the way to the root.
     */
    static String baseNumber(String sourceNumber, int sourceRevisionNo) {
        if (sourceRevisionNo <= 1) {
            return sourceNumber;
        }
        String suffix = "-" + sourceRevisionNo;
        return sourceNumber.endsWith(suffix)
            ? sourceNumber.substring(0, sourceNumber.length() - suffix.length())
            : sourceNumber;
    }

    static String revisionNumber(String baseNumber, int revisionNo) {
        return revisionNo <= 1 ? baseNumber : baseNumber + "-" + revisionNo;
    }

    /** Serializes every mutating operation this feature performs against a given deal, against
     * each other. Mirrors {@code CustomerQuotationRepository.lockPricingRequest}'s idiom, keyed on
     * the ticket id instead — this feature has no pricing-request row to lock on. */
    public void lockTicket(long ticketId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:id)", Map.of("id", ticketId), (rs, rowNum) -> 0);
    }

    /** M3: whether {@code parentQuotationId} already has a child revision sitting open
     * (DRAFT/PENDING_APPROVAL) — {@link DealQuotationService#createRevision} calls this, under
     * {@link #lockTicket}, to refuse a second revision with a 409 rather than racing a second
     * caller into the {@code sales.quotation.number} UNIQUE constraint (V6). */
    public boolean hasOpenRevision(long parentQuotationId) {
        Boolean found = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM sales.quotation
                 WHERE parent_quotation_id = :parentId
                   AND origin = 'DEAL_DIRECT'
                   AND doc_status IN ('DRAFT', 'PENDING_APPROVAL')
            )
            """, Map.of("parentId", parentQuotationId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /**
     * M3 (found while testing the fix above): the next unused revision number for the chain
     * {@code baseNumber} belongs to — MAX(quotation_revision_no) across every row that has EVER
     * existed under this base (found by exact match on {@code baseNumber} itself, or the {@code
     * {base}-N} suffix pattern {@link #revisionNumber} produces) plus one, rather than the naive
     * {@code source.revisionNo() + 1}.
     *
     * <p>The naive version has its own latent duplicate-key crash: {@code source.revisionNo()}
     * belongs to whatever row {@code createRevision} was CALLED ON, and that row's own
     * {@code revisionNo} never changes just because a PRIOR attempt at revising it was cancelled.
     * Cancel a freshly created revision and call {@code createRevision} on the same (still
     * APPROVED) parent again — a legitimate, expected flow, not a race — and the naive formula
     * recomputes the SAME {@code {base}-N} number the cancelled row still holds (cancelling never
     * frees the UNIQUE {@code number} slot), hitting the identical DuplicateKeyException M3 was
     * about. This method is called under the SAME {@link #lockTicket} advisory lock {@link
     * #hasOpenRevision} already relies on, so it is race-safe against concurrent callers too.
     */
    public int nextRevisionNo(long ticketId, String baseNumber) {
        Integer max = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_revision_no), 0) FROM sales.quotation
             WHERE ticket_id = :ticketId AND origin = 'DEAL_DIRECT'
               AND (number = :baseNumber OR number LIKE :basePrefix)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("baseNumber", baseNumber)
                .addValue("basePrefix", baseNumber + "-%"),
            Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * One row about to be written to {@code sales.quotation_item}, with every derived number
     * already computed by {@code DealQuotationService}.
     *
     * <p>Quotation v3 (2026-09-11) added {@code lineType} and the fields the two non-tile row
     * types need. Note what is NOT here: {@code piecesBeforeWastage}/{@code piecesAfterWastage}/
     * {@code piecesFinal}/{@code boxes} stay {@code int}/{@code Integer} because they are TILE
     * concepts — a PLAIN or ADJUSTMENT row carries 0/null in them and prints its {@code quantity}
     * instead. {@code quantity} is the ONE printed จำนวน for every row type (a TILE row sets it to
     * its own {@code piecesFinal}, so the two can never disagree).
     */
    public record NewItem(
        String locationLabel, Long catalogPriceId, String productCode,
        String brand, String model, String color, String texture, String sizeText,
        BigDecimal thicknessMm, BigDecimal sqmPerPiece,
        String quantityMode, BigDecimal areaSqm, Integer piecesInput,
        String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
        int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal, Integer boxes,
        BigDecimal unitPrice, BigDecimal discountPct, BigDecimal netUnitPrice, BigDecimal lineAmount,
        BigDecimal vat, BigDecimal lineTotal,
        String originCountry, Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
        String descriptionLine,
        // ── quotation v3 ──────────────────────────────────────────────────────────────────────
        String lineType, BigDecimal quantity, String unit,
        BigDecimal specialPriceSqm, BigDecimal adjustmentPct, java.time.LocalDate adjustmentDeadline
    ) {}

    /** The ผู้สั่งซื้อ snapshot written onto {@code sales.quotation} (V167) — see {@link
     * DealQuotationService#resolveContact}; a revision copies its parent's verbatim. */
    public record ContactSnapshot(Long contactId, String name, String phone, String email) {}

    /** The ลูกค้า snapshot on {@code sales.quotation} — written at insert AND re-written on every
     * DRAFT save (see {@link #updateHeader}), so a tax id / phone the rep corrects on the deal card
     * reaches the document instead of being frozen at the value the deal happened to carry when the
     * draft was first created. Built in ONE place, {@code DealQuotationService#customerSnapshot}. */
    public record CustomerSnapshot(String name, String address, String taxId, String phone) {}

    public record InsertDraftParams(
        long ticketId, String number, long createdById, long salesRepId,
        String customerName, String customerAddress, String customerTaxId, String customerPhone,
        ContactSnapshot contact,
        String projectName, String deptCode, String unitCode, LocalDate offerDate,
        Integer depositPercent, String remainderMode, Integer creditDays, Integer validityDays,
        String customerNotes, String priceMode, String documentLanguage, String currency,
        BigDecimal subtotal, Long parentQuotationId, int revisionNo,
        List<NewItem> items) {}

    @Transactional
    public long insertDraft(InsertDraftParams p) {
        Integer maxVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(quotation_version), 0) FROM sales.quotation WHERE ticket_id = :ticketId
            """, Map.of("ticketId", p.ticketId()), Integer.class);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.quotation
                (ticket_id, number, issued_by, issued_at, total_amount, currency, quotation_version,
                 doc_status, recipient_type, quotation_revision_no, origin, created_by, sales_rep_id,
                 customer_name, customer_address, customer_tax_id, customer_phone,
                 contact_id, contact_name, contact_phone, contact_email, project_name,
                 dept_code, unit_code, offer_date, deposit_percent, remainder_mode, credit_days,
                 validity_days, customer_notes, price_mode, document_language,
                 parent_quotation_id, updated_at)
            VALUES
                (:ticketId, :number, :salesRepId, now(), :totalAmount, :currency, :version,
                 'DRAFT', 'UNSPECIFIED', :revisionNo, 'DEAL_DIRECT', :createdById, :salesRepId,
                 :customerName, :customerAddress, :customerTaxId, :customerPhone,
                 :contactId, :contactName, :contactPhone, :contactEmail, :projectName,
                 :deptCode, :unitCode, :offerDate, :depositPercent, :remainderMode, :creditDays,
                 :validityDays, :customerNotes, :priceMode, :documentLanguage,
                 :parentQuotationId, now())
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", p.ticketId())
                .addValue("number", p.number())
                .addValue("salesRepId", p.salesRepId())
                .addValue("totalAmount", p.subtotal())
                .addValue("version", nextVersion)
                .addValue("revisionNo", p.revisionNo())
                .addValue("createdById", p.createdById())
                .addValue("customerName", p.customerName())
                .addValue("customerAddress", p.customerAddress())
                .addValue("customerTaxId", p.customerTaxId())
                .addValue("customerPhone", p.customerPhone())
                .addValue("contactId", p.contact() != null ? p.contact().contactId() : null)
                .addValue("contactName", p.contact() != null ? p.contact().name() : null)
                .addValue("contactPhone", p.contact() != null ? p.contact().phone() : null)
                .addValue("contactEmail", p.contact() != null ? p.contact().email() : null)
                .addValue("projectName", p.projectName())
                .addValue("deptCode", p.deptCode())
                .addValue("unitCode", p.unitCode())
                .addValue("offerDate", p.offerDate())
                .addValue("depositPercent", p.depositPercent())
                .addValue("remainderMode", p.remainderMode())
                .addValue("creditDays", p.creditDays())
                .addValue("validityDays", p.validityDays())
                .addValue("customerNotes", p.customerNotes())
                .addValue("priceMode", p.priceMode())
                // v3b: currency was a hardcoded 'THB' LITERAL in the VALUES list until V169 — it
                // is a real parameter now, defaulted from the document language by
                // DealQuotationService#resolveCurrency (TH->THB, EN->USD).
                .addValue("documentLanguage", p.documentLanguage())
                .addValue("currency", p.currency())
                .addValue("parentQuotationId", p.parentQuotationId()),
            keyHolder, new String[]{"quotation_id"});
        long quotationId = keyHolder.getKey().longValue();
        insertItems(quotationId, p.items());
        return quotationId;
    }

    public void insertItems(long quotationId, List<NewItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = new MapSqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            NewItem item = items.get(i);
            batch[i] = new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", i + 1)
                .addValue("brand", item.brand())
                .addValue("model", item.model())
                .addValue("color", item.color())
                .addValue("texture", item.texture())
                .addValue("size", item.sizeText())
                // v3: the printed หน่วย is now per-row, not the hardcoded "แผ่น" it used to be —
                // a PLAIN row carries JOB/Bags/Barrels/ชุด and an ADJUSTMENT row carries none at
                // all (NULL, which the renderer prints as an EMPTY cell rather than falling back).
                .addValue("rawUnit", item.unit())
                // v3: quantity, not piecesFinal. For a TILE row the service sets quantity =
                // piecesFinal so this is byte-identical to the previous expression; for PLAIN it
                // is the rep's own จำนวน, and for ADJUSTMENT it is −1 (which is precisely what
                // makes amount = quantity × netPrice come out negative).
                .addValue("qty", item.quantity())
                .addValue("unitPrice", item.unitPrice())
                .addValue("amount", item.lineAmount())
                .addValue("salesDiscount", item.unitPrice().subtract(item.netUnitPrice()))
                .addValue("finalUnitPrice", item.netUnitPrice())
                .addValue("lineSubtotal", item.lineAmount())
                .addValue("vat", item.vat())
                .addValue("lineTotal", item.lineTotal())
                .addValue("description", item.descriptionLine())
                .addValue("locationLabel", item.locationLabel())
                .addValue("catalogPriceId", item.catalogPriceId())
                .addValue("productCode", item.productCode())
                .addValue("thicknessMm", item.thicknessMm())
                .addValue("sqmPerPiece", item.sqmPerPiece())
                .addValue("quantityMode", item.quantityMode())
                .addValue("areaSqm", item.areaSqm())
                .addValue("piecesInput", item.piecesInput())
                .addValue("wastageMode", item.wastageMode())
                .addValue("wastageValue", item.wastageValue())
                .addValue("piecesPerBox", item.piecesPerBox())
                .addValue("piecesBeforeWastage", item.piecesBeforeWastage())
                .addValue("piecesAfterWastage", item.piecesAfterWastage())
                .addValue("boxes", item.boxes())
                .addValue("discountPct", item.discountPct())
                .addValue("originCountry", item.originCountry())
                .addValue("leadTimeMinDays", item.leadTimeMinDays())
                .addValue("leadTimeMaxDays", item.leadTimeMaxDays())
                .addValue("itemNotes", item.itemNotes())
                // ── quotation v3 (V168) ──────────────────────────────────────────────────────
                .addValue("lineType", item.lineType())
                .addValue("specialPriceSqm", item.specialPriceSqm())
                .addValue("adjustmentPct", item.adjustmentPct())
                .addValue("adjustmentDeadline", item.adjustmentDeadline());
        }
        jdbc.batchUpdate("""
            INSERT INTO sales.quotation_item
                (quotation_id, seq, brand, model, color, texture, size, raw_unit, qty, unit_price, amount,
                 sales_discount, final_unit_price, line_subtotal, vat, line_total, description,
                 location_label, catalog_price_id, product_code, thickness_mm, sqm_per_piece,
                 quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
                 pieces_before_wastage, pieces_after_wastage, boxes, discount_pct, origin_country,
                 lead_time_min_days, lead_time_max_days, item_notes,
                 line_type, special_price_sqm, adjustment_pct, adjustment_deadline)
            VALUES
                (:quotationId, :seq, :brand, :model, :color, :texture, :size, :rawUnit, :qty, :unitPrice, :amount,
                 :salesDiscount, :finalUnitPrice, :lineSubtotal, :vat, :lineTotal, :description,
                 :locationLabel, :catalogPriceId, :productCode, :thicknessMm, :sqmPerPiece,
                 :quantityMode, :areaSqm, :piecesInput, :wastageMode, :wastageValue, :piecesPerBox,
                 :piecesBeforeWastage, :piecesAfterWastage, :boxes, :discountPct, :originCountry,
                 :leadTimeMinDays, :leadTimeMaxDays, :itemNotes,
                 :lineType, :specialPriceSqm, :adjustmentPct, :adjustmentDeadline)
            """, batch);
    }

    public void deleteItems(long quotationId) {
        jdbc.update("DELETE FROM sales.quotation_item WHERE quotation_id = :id", Map.of("id", quotationId));
    }

    /** DRAFT-only via the WHERE clause — the enforcement, not a service-layer check the caller
     * could forget (mirrors {@code CustomerQuotationRepository.updateHeader}'s contract).
     *
     * <p>The four {@code customer_*} columns are RE-SNAPSHOTTED here, not only at insert. Owner
     * feedback F7 (2026-09-10) puts เลขที่ผู้เสียภาษี / โทร. on screen as editable fields on the
     * SELECTED customer, and promised "the values on screen at save time" — which was false while
     * this statement left the columns alone: correcting a wrong tax id and re-saving the draft
     * still printed the old one forever. Re-snapshotting on every DRAFT save is safe precisely
     * because of the {@code doc_status = 'DRAFT'} predicate below: an issued/approved/superseded
     * document is not matched by this UPDATE at all, so the freeze that makes a sent document
     * trustworthy still holds. A revision is its own DRAFT row, so it picks up the correction the
     * first time the rep saves it — which is the point of taking a revision. */
    public int updateHeader(long quotationId, ContactSnapshot contact, CustomerSnapshot customer,
                            String deptCode, String unitCode,
                            LocalDate offerDate, Integer depositPercent, String remainderMode, Integer creditDays,
                            Integer validityDays, String customerNotes, String priceMode,
                            String documentLanguage, String currency, BigDecimal subtotal) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET contact_id = :contactId, contact_name = :contactName,
                   contact_phone = :contactPhone, contact_email = :contactEmail,
                   customer_name = :customerName, customer_address = :customerAddress,
                   customer_tax_id = :customerTaxId, customer_phone = :customerPhone,
                   dept_code = :deptCode, unit_code = :unitCode, offer_date = :offerDate,
                   deposit_percent = :depositPercent, remainder_mode = :remainderMode,
                   credit_days = :creditDays, validity_days = :validityDays,
                   customer_notes = :customerNotes, price_mode = :priceMode,
                   document_language = :documentLanguage, currency = :currency,
                   total_amount = :subtotal, updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'DRAFT'
            """,
            new MapSqlParameterSource()
                .addValue("id", quotationId)
                .addValue("contactId", contact.contactId())
                .addValue("contactName", contact.name())
                .addValue("contactPhone", contact.phone())
                .addValue("contactEmail", contact.email())
                .addValue("customerName", customer.name())
                .addValue("customerAddress", customer.address())
                .addValue("customerTaxId", customer.taxId())
                .addValue("customerPhone", customer.phone())
                .addValue("deptCode", deptCode)
                .addValue("unitCode", unitCode)
                .addValue("offerDate", offerDate)
                .addValue("depositPercent", depositPercent)
                .addValue("remainderMode", remainderMode)
                .addValue("creditDays", creditDays)
                .addValue("validityDays", validityDays)
                .addValue("customerNotes", customerNotes)
                .addValue("priceMode", priceMode)
                .addValue("documentLanguage", documentLanguage)
                .addValue("currency", currency)
                .addValue("subtotal", subtotal));
    }

    /** Compare-and-set DRAFT -> PENDING_APPROVAL. Rowcount 0 means not open for submit. Clears any
     * stale rejection reason from a prior cycle (V165's own header comment: "cleared on the next
     * submit"). */
    public int submit(long quotationId, long actorId) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'PENDING_APPROVAL', submitted_at = now(), submitted_by = :actorId,
                   approval_note = NULL, updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'DRAFT'
            """, new MapSqlParameterSource().addValue("id", quotationId).addValue("actorId", actorId));
    }

    /** Compare-and-set PENDING_APPROVAL -> APPROVED. */
    public int approve(long quotationId, long approverId, String note, LocalDate validityDate) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'APPROVED', approved_at = now(), approved_by = :approverId,
                   approval_decided_at = now(), approval_decided_by = :approverId,
                   approval_note = :note, validity_date = :validityDate, updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'PENDING_APPROVAL'
            """,
            new MapSqlParameterSource().addValue("id", quotationId).addValue("approverId", approverId)
                .addValue("note", note).addValue("validityDate", validityDate));
    }

    /** Compare-and-set PENDING_APPROVAL -> DRAFT, with the reason recorded for the rep to see. */
    public int reject(long quotationId, long approverId, String reason) {
        return jdbc.update("""
            UPDATE sales.quotation
               SET doc_status = 'DRAFT', approval_decided_at = now(), approval_decided_by = :approverId,
                   approval_note = :reason, updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'PENDING_APPROVAL'
            """, new MapSqlParameterSource().addValue("id", quotationId).addValue("approverId", approverId)
                .addValue("reason", reason));
    }

    /** Compare-and-set DRAFT -> CANCELLED. */
    public int cancel(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'CANCELLED', updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'DRAFT'
            """, Map.of("id", quotationId));
    }

    /** Compare-and-set APPROVED -> SUPERSEDED — only called once the CHILD revision itself reaches
     * APPROVED (see {@code DealQuotationService#approve}); the parent stays a valid, live APPROVED
     * document until then. */
    public int supersede(long quotationId) {
        return jdbc.update("""
            UPDATE sales.quotation SET doc_status = 'SUPERSEDED', updated_at = now()
             WHERE quotation_id = :id AND origin = 'DEAL_DIRECT' AND doc_status = 'APPROVED'
            """, Map.of("id", quotationId));
    }

    public record RepInfo(String name, String phone) {}

    /** Nothing existing on the employee-lookup surface returns phone — added here rather than
     * widening a shared DTO for one caller. */
    public Optional<RepInfo> findEmployeeNameAndPhone(long employeeId) {
        try {
            RepInfo info = jdbc.queryForObject("""
                SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '') AS name, phone
                  FROM hr.employee WHERE employee_id = :id
                """, Map.of("id", employeeId), (rs, rowNum) -> new RepInfo(rs.getString("name"), rs.getString("phone")));
            return Optional.ofNullable(info);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<DealQuotationDto> findById(long quotationId) {
        try {
            DealQuotationDto dto = jdbc.queryForObject(
                baseSelect() + " WHERE q.quotation_id = :id AND q.origin = 'DEAL_DIRECT'",
                Map.of("id", quotationId), (rs, rowNum) -> mapQuotation(rs, findItems(quotationId)));
            return Optional.ofNullable(dto);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** All revisions of a deal's direct quotations, newest first. */
    public List<DealQuotationDto> findByTicket(long ticketId) {
        List<Long> ids = jdbc.query("""
            SELECT quotation_id FROM sales.quotation
             WHERE ticket_id = :id AND origin = 'DEAL_DIRECT'
             ORDER BY quotation_id DESC
            """, Map.of("id", ticketId), (rs, rowNum) -> rs.getLong("quotation_id"));
        return hydrate(ids);
    }

    /**
     * The "แก้" bucket (owner feedback F5, 2026-09-10): a DRAFT that was sent back with a reason
     * ({@code approval_note}, cleared again on the next submit) OR a revision still in progress
     * ({@code parent_quotation_id}). ONE definition, shared by {@link #search}'s
     * {@code needsRework} filter and {@link #counts} so the tab's count can never disagree with
     * the rows the tab lists.
     */
    private static final String NEEDS_REWORK_PREDICATE =
        "(q.doc_status = 'DRAFT' AND (q.approval_note IS NOT NULL OR q.parent_quotation_id IS NOT NULL))";

    /**
     * Approver-queue / own-list search. {@code statuses} null or empty means "any status".
     * {@code ownerTicketCreatedById} non-null scopes to deals created by that employee (the "own
     * deals only" rule for the {@code sales} role — see {@code DealQuotationService}); null means
     * no owner restriction (the approver queue). {@code needsRework} narrows to
     * {@link #NEEDS_REWORK_PREDICATE} — server-side, so the list page's "แก้" tab is never a
     * client-side post-filter over a truncated list; it composes with {@code statuses} (AND).
     */
    public List<DealQuotationDto> search(List<String> statuses, Long ownerTicketCreatedById, boolean needsRework) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder("q.origin = 'DEAL_DIRECT'");
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" AND q.doc_status IN (:statuses)");
            params.addValue("statuses", statuses);
        }
        if (needsRework) {
            where.append(" AND ").append(NEEDS_REWORK_PREDICATE);
        }
        if (ownerTicketCreatedById != null) {
            where.append(" AND t.created_by = :ownerId");
            params.addValue("ownerId", ownerTicketCreatedById);
        }
        List<Long> ids = jdbc.query("""
            SELECT q.quotation_id FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
             WHERE %s
             ORDER BY q.quotation_id DESC
            """.formatted(where), params, (rs, rowNum) -> rs.getLong("quotation_id"));
        return hydrate(ids);
    }

    /**
     * Per-status counts over the SAME scope as {@link #search} with no status filter — the same
     * {@code t.created_by} owner clause (null = unrestricted), the same origin filter, the same
     * {@link #NEEDS_REWORK_PREDICATE} — in one statement with conditional aggregates. {@code all}
     * counts every row in scope regardless of status (SUPERSEDED etc. included), matching what
     * {@code search(null, owner, false)} returns.
     */
    public DealQuotationCountsDto counts(Long ownerTicketCreatedById) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String ownerClause = "";
        if (ownerTicketCreatedById != null) {
            ownerClause = " AND t.created_by = :ownerId";
            params.addValue("ownerId", ownerTicketCreatedById);
        }
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS all_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'PENDING_APPROVAL') AS pending_count,
                   COUNT(*) FILTER (WHERE %s) AS rework_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'CANCELLED') AS cancelled_count,
                   COUNT(*) FILTER (WHERE q.doc_status = 'APPROVED') AS approved_count
              FROM sales.quotation q
              JOIN sales.ticket t ON t.ticket_id = q.ticket_id
             WHERE q.origin = 'DEAL_DIRECT'%s
            """.formatted(NEEDS_REWORK_PREDICATE, ownerClause), params,
            (rs, rowNum) -> new DealQuotationCountsDto(rs.getLong("all_count"), rs.getLong("pending_count"),
                rs.getLong("rework_count"), rs.getLong("cancelled_count"), rs.getLong("approved_count")));
    }

    /**
     * Batches {@link #findByTicket}/{@link #search}'s per-row hydration into exactly TWO queries
     * total, regardless of how many ids are requested — one for the quotation rows, one for every
     * one of their items. The previous version called {@link #findById} per id, and that method
     * itself issues two queries (header + items), so a list of N quotations cost 2N round trips
     * (N+1 twice over). Order is preserved to match the callers' own {@code ORDER BY
     * quotation_id DESC}.
     */
    private List<DealQuotationDto> hydrate(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DealQuotationItemDto>> itemsByQuotation = findItemsForQuotations(ids);
        List<DealQuotationDto> rows = jdbc.query(
            baseSelect() + " WHERE q.quotation_id IN (:ids) AND q.origin = 'DEAL_DIRECT'",
            Map.of("ids", ids),
            (rs, rowNum) -> mapQuotation(rs,
                itemsByQuotation.getOrDefault(rs.getLong("quotation_id"), List.of())));
        Map<Long, DealQuotationDto> byId = new HashMap<>();
        for (DealQuotationDto dto : rows) {
            byId.put(dto.id(), dto);
        }
        List<DealQuotationDto> result = new ArrayList<>();
        for (Long id : ids) {
            DealQuotationDto dto = byId.get(id);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    private List<DealQuotationItemDto> findItems(long quotationId) {
        return jdbc.query("""
            SELECT quotation_id, quotation_item_id, seq, location_label, catalog_price_id, product_code,
                   brand, model, color, texture, size, thickness_mm, sqm_per_piece,
                   quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
                   unit_price, discount_pct, origin_country, lead_time_min_days, lead_time_max_days,
                   item_notes, pieces_before_wastage, pieces_after_wastage, qty AS pieces_final, boxes,
                   final_unit_price, amount,
                   raw_unit, description, line_type, special_price_sqm, adjustment_pct, adjustment_deadline,
                   picture_placement
              FROM sales.quotation_item
             WHERE quotation_id = :id
             ORDER BY seq
            """, Map.of("id", quotationId), (rs, rowNum) -> mapItem(rs));
    }

    /** Same row shape as {@link #findItems}, batched across every id in one query — see
     * {@link #hydrate}. */
    private Map<Long, List<DealQuotationItemDto>> findItemsForQuotations(List<Long> ids) {
        Map<Long, List<DealQuotationItemDto>> byQuotation = new LinkedHashMap<>();
        jdbc.query("""
            SELECT quotation_id, quotation_item_id, seq, location_label, catalog_price_id, product_code,
                   brand, model, color, texture, size, thickness_mm, sqm_per_piece,
                   quantity_mode, area_sqm, pieces_input, wastage_mode, wastage_value, pieces_per_box,
                   unit_price, discount_pct, origin_country, lead_time_min_days, lead_time_max_days,
                   item_notes, pieces_before_wastage, pieces_after_wastage, qty AS pieces_final, boxes,
                   final_unit_price, amount,
                   raw_unit, description, line_type, special_price_sqm, adjustment_pct, adjustment_deadline,
                   picture_placement
              FROM sales.quotation_item
             WHERE quotation_id IN (:ids)
             ORDER BY quotation_id, seq
            """, Map.of("ids", ids), (ResultSet rs) -> {
                while (rs.next()) {
                    long quotationId = rs.getLong("quotation_id");
                    byQuotation.computeIfAbsent(quotationId, k -> new ArrayList<>()).add(mapItem(rs));
                }
            });
        return byQuotation;
    }

    private String baseSelect() {
        return """
            SELECT q.quotation_id, q.number, q.ticket_id, q.doc_status, q.quotation_revision_no,
                   q.parent_quotation_id, q.created_by,
                   NULLIF(TRIM(CONCAT_WS(' ', cb.first_name_th, cb.last_name_th)), '') AS created_by_name,
                   -- v3b: the ENGLISH document's signature names. NULLIF(TRIM(...)) so an employee
                   -- with blank English names reads as SQL NULL here rather than an empty string,
                   -- which is what lets DealQuotationRenderAdapter fall back to the Thai name with
                   -- a plain null check instead of a blank test at every call site.
                   NULLIF(TRIM(CONCAT_WS(' ', cb.first_name_en, cb.last_name_en)), '') AS created_by_name_en,
                   q.sales_rep_id,
                   NULLIF(TRIM(CONCAT_WS(' ', rep.first_name_th, rep.last_name_th)), '') AS sales_rep_name,
                   NULLIF(TRIM(CONCAT_WS(' ', rep.first_name_en, rep.last_name_en)), '') AS sales_rep_name_en,
                   rep.phone AS sales_rep_phone,
                   q.submitted_at, q.approved_by,
                   NULLIF(TRIM(CONCAT_WS(' ', ap.first_name_th, ap.last_name_th)), '') AS approved_by_name,
                   NULLIF(TRIM(CONCAT_WS(' ', ap.first_name_en, ap.last_name_en)), '') AS approved_by_name_en,
                   q.approved_at, q.approval_note,
                   q.customer_name, q.customer_address, q.customer_tax_id, q.customer_phone, q.project_name,
                   q.contact_id, q.contact_name, q.contact_phone, q.contact_email,
                   q.dept_code, q.unit_code, q.offer_date, q.deposit_percent, q.remainder_mode,
                   q.credit_days, q.validity_days, q.validity_date, q.customer_notes, q.price_mode,
                   q.document_language,
                   q.total_amount, q.currency, q.issued_at AS created_at, q.updated_at,
                   EXISTS (SELECT 1 FROM hr.employee_signature es WHERE es.employee_id = q.approved_by)
                       AS approver_has_signature
              FROM sales.quotation q
              LEFT JOIN hr.employee cb  ON cb.employee_id = q.created_by
              LEFT JOIN hr.employee rep ON rep.employee_id = q.sales_rep_id
              LEFT JOIN hr.employee ap  ON ap.employee_id = q.approved_by
            """;
    }

    private DealQuotationDto mapQuotation(ResultSet rs, List<DealQuotationItemDto> items) throws SQLException {
        BigDecimal subtotal = rs.getBigDecimal("total_amount") != null ? rs.getBigDecimal("total_amount") : BigDecimal.ZERO;
        // v3b: NULL document_language (every pre-V169 row) reads as TH — see V169's own comment.
        String documentLanguage = rs.getString("document_language") == null
            ? WastageCalculator.DOCUMENT_LANGUAGE_TH : rs.getString("document_language");
        // ⚠️ The VAT rate is a function of the LANGUAGE, not a constant: an EN/F-SM-008 document
        // has no VAT row at all, so vatTotal is ZERO and grandTotal == subtotal there. Routed
        // through WastageCalculator#vat(subtotal, language) so the DTO, the stored per-item vat
        // column and the printed footer can never disagree about it.
        BigDecimal vatTotal = WastageCalculator.vat(subtotal, documentLanguage);
        // total_amount is the persisted subtotal (server-recomputed on every write — see
        // DealQuotationService); vat/grand are always DERIVED from it here, never stored
        // separately, so they can never drift from the subtotal they belong to.
        BigDecimal grandTotal = WastageCalculator.grandTotal(subtotal, vatTotal);
        Instant approvedAt = instant(rs, "approved_at");
        Instant createdAt = instant(rs, "created_at");
        // Owner feedback F8 (2026-09-10): the header date is the date the sales rep CREATED the
        // quotation, for EVERY status -- it used to be the approved date once approved, else
        // today. DealQuotationRenderAdapter#toRenderModel derives the printed B4 cell the same
        // way, so the list/detail UI and the printed document can never disagree. offerDate
        // (remark 1) is a separate, rep-editable date and is unaffected.
        LocalDate quotationDate = createdAt != null
            ? createdAt.atZone(java.time.ZoneId.of("Asia/Bangkok")).toLocalDate()
            : LocalDate.now(java.time.ZoneId.of("Asia/Bangkok"));
        long approvedByRaw = rs.getLong("approved_by");
        Long approvedById = rs.wasNull() ? null : approvedByRaw;
        return new DealQuotationDto(
            rs.getLong("quotation_id"),
            rs.getString("number"),
            rs.getLong("ticket_id"),
            rs.getString("doc_status"),
            rs.getInt("quotation_revision_no"),
            nullableLong(rs, "parent_quotation_id"),
            rs.getLong("created_by"),
            rs.getString("created_by_name"),
            rs.getString("created_by_name_en"),
            rs.getLong("sales_rep_id"),
            rs.getString("sales_rep_name"),
            rs.getString("sales_rep_name_en"),
            rs.getString("sales_rep_phone"),
            instant(rs, "submitted_at"),
            approvedById,
            rs.getString("approved_by_name"),
            rs.getString("approved_by_name_en"),
            approvedAt,
            rs.getString("approval_note"),
            quotationDate,
            rs.getString("customer_name"),
            rs.getString("customer_address"),
            rs.getString("customer_tax_id"),
            rs.getString("customer_phone"),
            nullableLong(rs, "contact_id"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("contact_email"),
            rs.getString("project_name"),
            rs.getString("dept_code"),
            rs.getString("unit_code"),
            rs.getObject("offer_date", LocalDate.class),
            nullableInt(rs, "deposit_percent"),
            rs.getString("remainder_mode"),
            nullableInt(rs, "credit_days"),
            nullableInt(rs, "validity_days"),
            rs.getObject("validity_date", LocalDate.class),
            rs.getString("customer_notes"),
            // NULL price_mode (every pre-V168 row) reads as NET — see V168's own comment.
            rs.getString("price_mode") == null
                ? WastageCalculator.PRICE_MODE_NET : rs.getString("price_mode"),
            documentLanguage,
            subtotal,
            vatTotal,
            grandTotal,
            rs.getString("currency"),
            rs.getBoolean("approver_has_signature"),
            items,
            createdAt,
            instant(rs, "updated_at")
        );
    }

    /** GLA-75: the stored row, plus its picture link (V170) — only the placement is read here;
     * the bytes are served separately by {@link #findItemPicture}. An item without a picture
     * keeps the legacy constructor's {@code hasPicture=false} shape exactly. */
    private DealQuotationItemDto mapItem(ResultSet rs) throws SQLException {
        DealQuotationItemDto item = mapItemColumns(rs);
        String placement = rs.getString("picture_placement");
        return placement == null ? item : item.withPicture(rs.getLong("quotation_id"), placement);
    }

    private DealQuotationItemDto mapItemColumns(ResultSet rs) throws SQLException {
        String quantityMode = rs.getString("quantity_mode");
        BigDecimal areaSqm = rs.getBigDecimal("area_sqm");
        Integer piecesPerBox = nullableInt(rs, "pieces_per_box");
        int piecesBeforeWastage = rs.getInt("pieces_before_wastage");
        int piecesAfterWastage = rs.getInt("pieces_after_wastage");
        BigDecimal sqmPerPiece = rs.getBigDecimal("sqm_per_piece");
        String wastageMode = rs.getString("wastage_mode");
        BigDecimal wastageValue = rs.getBigDecimal("wastage_value");
        // v3: piecesPerSqm now comes from WastageCalculator rather than an inline reciprocal here.
        // It is a DIVISOR in the SPECIAL_SQM money math, so the printed ตร.ม./แผ่น and the price
        // computed from it must be the same number — see WastageCalculator#piecesPerSqm's Javadoc.
        BigDecimal piecesPerSqm = sqmPerPiece != null && sqmPerPiece.signum() > 0
            ? WastageCalculator.piecesPerSqm(sqmPerPiece) : null;
        String model = rs.getString("model");
        String color = rs.getString("color");
        String texture = rs.getString("texture");
        String productCode = rs.getString("product_code");
        String sizeText = rs.getString("size");
        BigDecimal thicknessMm = rs.getBigDecimal("thickness_mm");

        // ── quotation v3 (V168) ──────────────────────────────────────────────────────────────
        // NULL line_type reads as TILE: every pre-V168 row, and every row written by a build that
        // predates this feature, keeps behaving exactly as it does today with no data rewrite.
        String lineType = rs.getString("line_type");
        if (lineType == null) {
            lineType = WastageCalculator.LINE_TYPE_TILE;
        }
        boolean tile = WastageCalculator.LINE_TYPE_TILE.equals(lineType);
        BigDecimal quantity = rs.getBigDecimal("pieces_final"); // the `qty` column, aliased
        // piecesFinal stays an int for TILE rows (it is a piece COUNT and always whole). For a
        // PLAIN row the quantity may legitimately be fractional and for an ADJUSTMENT it is −1, so
        // intValueExact() — which THROWS on a non-integral value — must not be reached for those.
        // The printed จำนวน for every row type is `quantity`; piecesFinal is TILE-only.
        int piecesFinal = tile && quantity != null ? quantity.intValueExact() : 0;
        String storedDescription = rs.getString("description");
        BigDecimal specialPriceSqm = rs.getBigDecimal("special_price_sqm");
        BigDecimal adjustmentPct = rs.getBigDecimal("adjustment_pct");
        LocalDate adjustmentDeadline = rs.getObject("adjustment_deadline", LocalDate.class);

        if (!tile) {
            // A non-tile row has no รุ่น/สี/ผิว/ขนาด and no wastage arithmetic: its description was
            // composed once at write time (rep-typed for PLAIN, derived for ADJUSTMENT) and stored,
            // and there is no size line or calculation line to print at all.
            return new DealQuotationItemDto(
                rs.getLong("quotation_item_id"), rs.getInt("seq"), rs.getString("location_label"),
                nullableLong(rs, "catalog_price_id"), productCode, rs.getString("brand"), model, color,
                texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm,
                nullableInt(rs, "pieces_input"), wastageMode, wastageValue, piecesPerBox,
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_pct"),
                rs.getString("origin_country"), nullableInt(rs, "lead_time_min_days"),
                nullableInt(rs, "lead_time_max_days"), rs.getString("item_notes"),
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                nullableInt(rs, "boxes"), rs.getBigDecimal("final_unit_price"), rs.getBigDecimal("amount"),
                storedDescription, null, null,
                lineType, quantity, rs.getString("raw_unit"), specialPriceSqm, adjustmentPct,
                adjustmentDeadline, null,
                // Review fix F2: a FLAT adjustment's amount has no column of its own, so echo it
                // back off unit_price or a GET→PUT round-trip of the row cannot be saved.
                DealQuotationLines.flatAdjustmentAmount(lineType, adjustmentPct,
                    rs.getBigDecimal("unit_price")));
        }
        return new DealQuotationItemDto(
            rs.getLong("quotation_item_id"),
            rs.getInt("seq"),
            rs.getString("location_label"),
            nullableLong(rs, "catalog_price_id"),
            productCode,
            rs.getString("brand"),
            model,
            color,
            texture,
            sizeText,
            thicknessMm,
            sqmPerPiece,
            quantityMode,
            areaSqm,
            nullableInt(rs, "pieces_input"),
            wastageMode,
            wastageValue,
            piecesPerBox,
            rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("discount_pct"),
            rs.getString("origin_country"),
            nullableInt(rs, "lead_time_min_days"),
            nullableInt(rs, "lead_time_max_days"),
            rs.getString("item_notes"),
            piecesPerSqm,
            piecesBeforeWastage,
            piecesAfterWastage,
            piecesFinal,
            nullableInt(rs, "boxes"),
            rs.getBigDecimal("final_unit_price"),
            rs.getBigDecimal("amount"),
            DealQuotationLines.descriptionLine(model, color, texture, productCode, sizeText, thicknessMm),
            DealQuotationLines.sizeLine(sizeText, thicknessMm),
            DealQuotationLines.calculationLine(quantityMode, areaSqm, piecesPerSqm, piecesBeforeWastage,
                wastageMode, wastageValue, piecesFinal, piecesPerBox),
            lineType, quantity, rs.getString("raw_unit"), specialPriceSqm, adjustmentPct,
            adjustmentDeadline,
            DealQuotationLines.specialPriceLine(specialPriceSqm),
            // Always null on this branch — it is the TILE branch, and only an ADJUSTMENT row can
            // carry a flat amount. Routed through the same helper anyway so the two branches can
            // never disagree about the rule.
            DealQuotationLines.flatAdjustmentAmount(lineType, adjustmentPct, null)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GLA-75 item pictures (V170). Bytes live once, immutable, in sales.quotation_item_picture;
    // an item references one by picture_id + picture_placement. See V170's header for why.
    // Every item-scoped statement below is keyed by BOTH quotation_id and quotation_item_id, so an
    // item id from another quotation can never be reached through this quotation's URL.
    // ─────────────────────────────────────────────────────────────────────────────────────

    public record PictureLink(long pictureId, String placement) {}

    public record PictureImage(String mimeType, byte[] image) {}

    /** item id → its picture link, for this quotation's items that have one. */
    public Map<Long, PictureLink> findPictureLinks(long quotationId) {
        Map<Long, PictureLink> links = new HashMap<>();
        jdbc.query("""
            SELECT quotation_item_id, picture_id, picture_placement
              FROM sales.quotation_item
             WHERE quotation_id = :id AND picture_id IS NOT NULL
            """, Map.of("id", quotationId), (ResultSet rs) -> {
                // A RowCallbackHandler: Spring has ALREADY advanced to this row. An inner
                // `while (rs.next())` here silently drops the first row — which, for a quotation
                // with one picture, is every picture.
                links.put(rs.getLong("quotation_item_id"),
                    new PictureLink(rs.getLong("picture_id"), rs.getString("picture_placement")));
            });
        return links;
    }

    /** Points this quotation's items, addressed by {@code seq}, at existing picture rows — the
     * draft-save carry-forward (see {@code DealQuotationService#update}). */
    public void linkPictures(long quotationId, Map<Integer, PictureLink> bySeq) {
        if (bySeq.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = bySeq.entrySet().stream()
            .map(e -> new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("seq", e.getKey())
                .addValue("pictureId", e.getValue().pictureId())
                .addValue("placement", e.getValue().placement()))
            .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
            UPDATE sales.quotation_item
               SET picture_id = :pictureId, picture_placement = :placement
             WHERE quotation_id = :quotationId AND seq = :seq
            """, batch);
    }

    /** A revision copies its parent's items verbatim and in the same order, so {@code seq} lines
     * the two sets up exactly; the child SHARES the parent's picture rows (immutable). */
    public int copyPictureLinks(long fromQuotationId, long toQuotationId) {
        return jdbc.update("""
            UPDATE sales.quotation_item child
               SET picture_id = parent.picture_id, picture_placement = parent.picture_placement
              FROM sales.quotation_item parent
             WHERE parent.quotation_id = :fromId
               AND parent.picture_id IS NOT NULL
               AND child.quotation_id = :toId
               AND child.seq = parent.seq
            """, Map.of("fromId", fromQuotationId, "toId", toQuotationId));
    }

    public long insertPicture(String mimeType, byte[] image, long uploadedBy) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sales.quotation_item_picture (mime_type, image, uploaded_by, uploaded_at)
            VALUES (:mimeType, :image, :uploadedBy, now())
            """,
            new MapSqlParameterSource()
                .addValue("mimeType", mimeType)
                .addValue("image", image)
                .addValue("uploadedBy", uploadedBy),
            keyHolder, new String[]{"picture_id"});
        return keyHolder.getKey().longValue();
    }

    /** The item's current picture id (null when none, or when the item is not this quotation's). */
    public Long findItemPictureId(long quotationId, long itemId) {
        List<Long> ids = jdbc.query("""
            SELECT picture_id FROM sales.quotation_item
             WHERE quotation_id = :quotationId AND quotation_item_id = :itemId AND picture_id IS NOT NULL
            """, Map.of("quotationId", quotationId, "itemId", itemId), (rs, rowNum) -> rs.getLong("picture_id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** The DRAFT predicate every picture write shares — the ENFORCEMENT, in the statement itself,
     * exactly like {@link #updateHeader}'s; the service's own status check only chooses the
     * error message. */
    private static final String EDITABLE_DRAFT_ITEM = """
             WHERE quotation_id = :quotationId AND quotation_item_id = :itemId
               AND EXISTS (SELECT 1 FROM sales.quotation q
                            WHERE q.quotation_id = :quotationId
                              AND q.origin = 'DEAL_DIRECT' AND q.doc_status = 'DRAFT')
            """;

    public int setItemPicture(long quotationId, long itemId, long pictureId, String placement) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_id = :pictureId, picture_placement = :placement"
                + EDITABLE_DRAFT_ITEM,
            new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("itemId", itemId)
                .addValue("pictureId", pictureId)
                .addValue("placement", placement));
    }

    public int clearItemPicture(long quotationId, long itemId) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_id = NULL, picture_placement = NULL"
                + EDITABLE_DRAFT_ITEM,
            Map.of("quotationId", quotationId, "itemId", itemId));
    }

    public int setItemPicturePlacement(long quotationId, long itemId, String placement) {
        return jdbc.update("UPDATE sales.quotation_item SET picture_placement = :placement"
                + EDITABLE_DRAFT_ITEM + " AND picture_id IS NOT NULL",
            new MapSqlParameterSource()
                .addValue("quotationId", quotationId)
                .addValue("itemId", itemId)
                .addValue("placement", placement));
    }

    /** One item's picture bytes — scoped to the quotation, so the caller's view access on THAT
     * quotation is what governs the read. */
    public Optional<PictureImage> findItemPicture(long quotationId, long itemId) {
        List<PictureImage> found = jdbc.query("""
            SELECT p.mime_type, p.image
              FROM sales.quotation_item i
              JOIN sales.quotation_item_picture p ON p.picture_id = i.picture_id
             WHERE i.quotation_id = :quotationId AND i.quotation_item_id = :itemId
            """, Map.of("quotationId", quotationId, "itemId", itemId),
            (rs, rowNum) -> new PictureImage(rs.getString("mime_type"), rs.getBytes("image")));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** item id → picture bytes, for rendering this quotation. */
    public Map<Long, PictureImage> findPictureImagesForQuotation(long quotationId) {
        Map<Long, PictureImage> images = new HashMap<>();
        jdbc.query("""
            SELECT i.quotation_item_id, p.mime_type, p.image
              FROM sales.quotation_item i
              JOIN sales.quotation_item_picture p ON p.picture_id = i.picture_id
             WHERE i.quotation_id = :id
            """, Map.of("id", quotationId), (ResultSet rs) -> {
                // RowCallbackHandler — one call per row; see #findPictureLinks.
                images.put(rs.getLong("quotation_item_id"),
                    new PictureImage(rs.getString("mime_type"), rs.getBytes("image")));
            });
        return images;
    }

    /** Deletes each picture row NO item references any more (on any quotation — a revision shares
     * its parent's). A still-referenced row is left alone; the FK would refuse it anyway. */
    public void deletePicturesIfUnreferenced(java.util.Collection<Long> pictureIds) {
        if (pictureIds == null || pictureIds.isEmpty()) {
            return;
        }
        jdbc.update("""
            DELETE FROM sales.quotation_item_picture p
             WHERE p.picture_id IN (:ids)
               AND NOT EXISTS (SELECT 1 FROM sales.quotation_item i WHERE i.picture_id = p.picture_id)
            """, Map.of("ids", List.copyOf(new java.util.HashSet<>(pictureIds))));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
