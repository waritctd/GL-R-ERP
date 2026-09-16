package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Quotation v2 (direct deal quotation, V165) — see docs/sales/quotation-v2-plan.md's "API" section for the
 * exact shape. Sales creates a deal, adds items straight onto a quotation with typed unit price +
 * discount (the pricing-request/factory-quote/CEO-costing chain is bypassed for this release —
 * owner ruling 2026-09-09), the server computes every number, and sales_manager/ceo approves.
 *
 * <p>Extends the SAME {@code sales.quotation}/{@code sales.quotation_item} aggregate the pricing
 * chain (Step 4, {@code customerquotation/*}) already uses — tagged {@code origin =
 * 'DEAL_DIRECT'} so the two flows' rows never see each other (see V165's migration comment).
 */
public final class DealQuotationDtos {
    private DealQuotationDtos() {}

    public record DealQuotationDto(
        long id,
        String number,
        long ticketId,
        String docStatus,
        int revisionNo,
        Long parentQuotationId,
        long createdById,
        String createdByName,
        /** {@code hr.employee.first_name_en + last_name_en}, or null when the employee has none —
         * quotation v3b (2026-09-11). The ENGLISH document's signature block prints these and
         * FALLS BACK to the Thai name when blank, rather than printing an empty slot; the Thai
         * document never looks at them. Served on the DTO rather than resolved at render time so
         * the adapter stays the pure function it is documented to be. */
        String createdByNameEn,
        long salesRepId,
        String salesRepName,
        String salesRepNameEn,
        String salesRepPhone,
        Instant submittedAt,
        Long approvedById,
        String approvedByName,
        String approvedByNameEn,
        Instant approvedAt,
        String approvalNote,
        // The date the sales rep CREATED the quotation (Bangkok), for EVERY status — owner
        // feedback F8, 2026-09-10, "for วันที่ at the top of the page it should be the date it was
        // created by the sale". Was "approved date, else today" until then; the header cell the
        // renderer prints follows the same rule (DealQuotationRenderAdapter#toRenderModel), so the
        // UI and the document always agree. NOT offerDate (remark 1, วันที่รับจำนวน), which is a
        // separate rep-editable date.
        LocalDate quotationDate,
        String customerName,
        String customerAddress,
        String customerTaxId,
        String customerPhone,
        // ผู้สั่งซื้อ — the FROZEN snapshot V167 stores at create/update (owner feedback F2,
        // 2026-09-10), never a live read of the ticket's contact; null only on a pre-V167 row
        // whose ticket had no contact, which submit() refuses until one is chosen.
        Long contactId,
        String contactName,
        String contactPhone,
        String contactEmail,
        String projectName,
        String deptCode,
        String unitCode,
        LocalDate offerDate,
        Integer depositPercent,
        String remainderMode,
        Integer creditDays,
        Integer validityDays,
        LocalDate validityDate,
        /** "DAYS" (default) | "DATE" — owner feedback 2026-09-14, remark 7's second variant.
         * Never null on the wire; a stored NULL (every pre-V178 row, and the whole legacy
         * customer-quotation path) normalises to DAYS on read. */
        String validityMode,
        /** DATE mode only — the exact "ภายในวันที่" deadline the rep typed. Null in DAYS mode. */
        LocalDate validityUntil,
        String customerNotes,
        /** "NET" | "SPECIAL_SQM" | "DIRECT_NET" — quotation v3 (V168). Never null on the wire; a
         * stored NULL (every pre-V168 row) normalises to NET on read. Per-QUOTATION, because in
         * all nine of the owner's documents every tile row shares one mode. */
        String priceMode,
        /** "TH" | "EN" — quotation v3b (V169). Never null on the wire; a stored NULL (every
         * pre-V169 row) normalises to TH on read, which is the Thai F-SM-002 every document has
         * been until now. It is the ONE choice a rep makes: {@code currency} defaults from it
         * (TH→THB, EN→USD) and so does the VAT treatment — {@code vatAmount} is ZERO and
         * {@code grandTotal == subtotalAmount} on an EN document, because the English form carries
         * no VAT row at all. */
        String documentLanguage,
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal grandTotal,
        String currency,
        boolean approverHasSignature,
        // ── V179 (owner feedback #4, 2026-09-14) — PRINT-ONLY name override. "they should be able
        // to select who to show for ผู้พิมพ์ and พนักงานขาย": when admin fills in a quotation on
        // behalf of a sales rep, ผู้พิมพ์ should read the admin's name and พนักงานขาย the rep's.
        // NOT a change of deal ownership/access/commission — createdById/salesRepId (and every
        // access/commission decision built on them) are completely untouched; these seven fields
        // only ever change what DealQuotationRenderAdapter prints. Null (the default) means "use
        // the real name", i.e. today's behaviour — see that class's #printedByName /
        // #salesRepDisplayNameOrReal / #salesRepDisplayPhoneOrReal helpers.
        /** Overrides {@link #createdByName}/{@link #createdByNameEn} on the ผู้พิมพ์ slot when set. */
        Long printedByDisplayId,
        String printedByDisplayName,
        String printedByDisplayNameEn,
        /** Overrides {@link #salesRepName}/{@link #salesRepNameEn}/{@link #salesRepPhone} on the
         * พนักงานขาย slot AND the header "Sales/{name} T.{phone}" line when set. */
        Long salesRepDisplayId,
        String salesRepDisplayName,
        String salesRepDisplayNameEn,
        String salesRepDisplayPhone,
        /** Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ", owner ruling 2026-09-16) — see
         * {@code DealQuotationRequests.UpsertDealQuotationRequest#omitContactHonorific}'s Javadoc.
         * {@code NOT NULL DEFAULT FALSE} on the column (V180), so this is never null once read from
         * the repository — unlike the {@code Boolean} on the request DTO, which is nullable on the
         * wire and resolved to this primitive by
         * {@code DealQuotationService#resolveOmitContactHonorific}. */
        boolean omitContactHonorific,
        /** Item 4 (V181, "ไม่รับมัดจำ", owner ruling 2026-09-16) — see
         * {@code DealQuotationRequests.UpsertDealQuotationRequest#fullPaymentTerm}'s Javadoc. Null
         * on every row whose {@link #depositPercent} is not exactly 0, and on every LEGACY
         * zero-deposit row that predates this feature — {@code DealQuotationRenderAdapter} keeps
         * printing such a row's existing {@link #remainderMode}/{@link #creditDays}-based text
         * byte-for-byte when this is null, so an already-approved document never changes. */
        String fullPaymentTerm,
        List<DealQuotationItemDto> items,
        Instant createdAt,
        Instant updatedAt
    ) {
        /** The pre-V180/V181 shape (no {@link #omitContactHonorific}/{@link #fullPaymentTerm}) —
         * kept so every existing construction site (tests, mostly) compiles unchanged. Defaults
         * omitContactHonorific to {@code false} (UNticked — the only behaviour every one of those
         * fixtures means) and fullPaymentTerm to null (no zero-deposit row had a term to carry
         * before this change). */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            Long printedByDisplayId, String printedByDisplayName, String printedByDisplayNameEn,
            Long salesRepDisplayId, String salesRepDisplayName, String salesRepDisplayNameEn,
            String salesRepDisplayPhone,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, printedByDisplayId, printedByDisplayName, printedByDisplayNameEn,
                salesRepDisplayId, salesRepDisplayName, salesRepDisplayNameEn, salesRepDisplayPhone,
                false, null, items, createdAt, updatedAt);
        }

        /** The pre-V179 shape (no display-name override fields) — kept so every existing
         * construction site (tests, mostly) compiles unchanged. Defaults all seven of those fields to
         * null, which reads as "use the real name" — today's behaviour for every one of those
         * fixtures — and (via the overload above) omitContactHonorific to false / fullPaymentTerm
         * to null. */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String validityMode, LocalDate validityUntil,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate, validityMode, validityUntil,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, null, null, null, null, null, null, null, items, createdAt, updatedAt);
        }

        /** The pre-V178 shape (no {@link #validityMode}/{@link #validityUntil}, and so also no
         * V179 display-override fields) — kept so every existing construction site (tests, mostly)
         * compiles unchanged. Defaults to {@code DAYS}/{@code null}, which reads as the day-count
         * behaviour every one of those fixtures actually means. */
        public DealQuotationDto(
            long id, String number, long ticketId, String docStatus, int revisionNo,
            Long parentQuotationId, long createdById, String createdByName, String createdByNameEn,
            long salesRepId, String salesRepName, String salesRepNameEn, String salesRepPhone,
            Instant submittedAt, Long approvedById, String approvedByName, String approvedByNameEn,
            Instant approvedAt, String approvalNote, LocalDate quotationDate, String customerName,
            String customerAddress, String customerTaxId, String customerPhone, Long contactId,
            String contactName, String contactPhone, String contactEmail, String projectName,
            String deptCode, String unitCode, LocalDate offerDate, Integer depositPercent,
            String remainderMode, Integer creditDays, Integer validityDays, LocalDate validityDate,
            String customerNotes, String priceMode, String documentLanguage, BigDecimal subtotalAmount,
            BigDecimal vatAmount, BigDecimal grandTotal, String currency, boolean approverHasSignature,
            List<DealQuotationItemDto> items, Instant createdAt, Instant updatedAt) {
            this(id, number, ticketId, docStatus, revisionNo, parentQuotationId, createdById, createdByName,
                createdByNameEn, salesRepId, salesRepName, salesRepNameEn, salesRepPhone, submittedAt,
                approvedById, approvedByName, approvedByNameEn, approvedAt, approvalNote, quotationDate,
                customerName, customerAddress, customerTaxId, customerPhone, contactId, contactName,
                contactPhone, contactEmail, projectName, deptCode, unitCode, offerDate, depositPercent,
                remainderMode, creditDays, validityDays, validityDate,
                WastageCalculator.VALIDITY_MODE_DAYS, null,
                customerNotes, priceMode, documentLanguage, subtotalAmount, vatAmount, grandTotal, currency,
                approverHasSignature, items, createdAt, updatedAt);
        }
    }

    /**
     * Per-status counts for the caller's OWN list scope (owner feedback F5, 2026-09-10: "for
     * สถานะ make it ทั้งหมด, รออนุมัติ, แก้, ยกเลิก" — the list page's tab labels carry counts). Same
     * scope rule as {@code GET /api/deal-quotations} (sales: own deals only), computed in ONE SQL
     * statement so the tabs never need a second full fetch. {@code needsRework} = DRAFT rows sent
     * back with a reason ({@code approvalNote}) OR revisions in progress ({@code parentQuotationId}),
     * exactly the {@code needsRework=true} filter's own definition.
     */
    public record DealQuotationCountsDto(
        long all,
        long pendingApproval,
        long needsRework,
        long cancelled,
        long approved
    ) {}

    /** {@link DealQuotationRequests.ItemInput}'s fields, plus what the server computed for it. */
    public record DealQuotationItemDto(
        long id,
        int seq,
        String locationLabel,
        Long catalogPriceId,
        String productCode,
        String brand,
        String model,
        String color,
        String texture,
        String sizeText,
        BigDecimal thicknessMm,
        BigDecimal sqmPerPiece,
        String quantityMode,
        BigDecimal areaSqm,
        Integer piecesInput,
        String wastageMode,
        BigDecimal wastageValue,
        Integer piecesPerBox,
        BigDecimal unitPrice,
        BigDecimal discountPct,
        String originCountry,
        Integer leadTimeMinDays,
        Integer leadTimeMaxDays,
        String itemNotes,
        // Server-computed (WastageCalculator) — never trusted from the client.
        BigDecimal piecesPerSqm,
        int piecesBeforeWastage,
        int piecesAfterWastage,
        int piecesFinal,
        Integer boxes,
        BigDecimal netUnitPrice,
        BigDecimal lineAmount,
        // Printed-line text (DealQuotationLines) — served here so the frontend never has to
        // reimplement the wastage/description phrasing. sizeLine and calculationLine are null on
        // a PLAIN or ADJUSTMENT row, which has no size and no wastage arithmetic to print.
        String descriptionLine,
        String sizeLine,
        String calculationLine,

        // ── quotation v3 (owner feedback pass 3, 2026-09-11) ──────────────────────────────────
        /** "TILE" | "PLAIN" | "ADJUSTMENT". Never null on the wire — a stored NULL (every pre-V168
         * row) is normalised to TILE on read, so the client never has to know about the default. */
        String lineType,
        /** The printed จำนวน for EVERY row type: {@code piecesFinal} for a TILE row, the rep's own
         * quantity for PLAIN, and −1 for an ADJUSTMENT. Prefer this over {@code piecesFinal},
         * which is a TILE-only piece count and reads 0 on the other two. */
        BigDecimal quantity,
        /** The printed หน่วย — "แผ่น" for a TILE row, the rep's own (JOB/Bags/Barrels/ชุด) for
         * PLAIN, and null for an ADJUSTMENT, which prints an EMPTY unit cell. */
        String unit,
        /** SPECIAL_SQM rows only — the ราคาพิเศษ the rep typed, in บาท per ตร.ม. INCLUDING VAT. */
        BigDecimal specialPriceSqm,
        /** ADJUSTMENT rows only — the percent, when the adjustment was entered as one. */
        BigDecimal adjustmentPct,
        /** ADJUSTMENT rows only — the "สั่งซื้อภายใน" date. */
        LocalDate adjustmentDeadline,
        /** SPECIAL_SQM rows only — "(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)", the sub-line
         * the owner's documents carry under such a row. Null in every other mode. */
        String specialPriceLine,
        /**
         * ADJUSTMENT rows entered as a FLAT baht amount — the positive magnitude, mirroring
         * {@code ItemInput.adjustmentAmount}. Null on every other row, INCLUDING a percentage
         * adjustment (which round-trips through {@code adjustmentPct} instead), so exactly one of
         * the two is ever non-null and the "exactly one of percent / flat" rule is satisfied by
         * echoing what we returned.
         *
         * <p>Added by review fix F2, which is about the GET→PUT round-trip. Relaxing the
         * unitPrice rejection alone fixes only the percentage case: a flat adjustment stores its
         * figure in {@code unit_price}/{@code amount} and has NO dedicated column, so before this
         * field the client had no way to hand the amount back and the PUT 400'd on "must be a
         * percent or an amount". Derived on read rather than stored — see V168's note on why a
         * duplicate column would only invite drift.
         */
        BigDecimal adjustmentAmount,

        // ── GLA-75 item pictures (V170) ───────────────────────────────────────────────────────
        /** Whether this item carries a picture. The BYTES are never inlined in this DTO — fetch
         * them from {@link #pictureUrl}. */
        boolean hasPicture,
        /** "BELOW" (large, under the description lines) | "BESIDE" (small thumbnail at the right
         * of the description cell); null exactly when {@link #hasPicture} is false. */
        String picturePlacement,
        /** {@code /api/deal-quotations/{id}/items/{itemId}/picture} (same view access as the
         * quotation itself), or null when there is no picture. The item id in it is STABLE across
         * a draft save that sends this item's {@code id} back (see
         * {@code DealQuotationRequests.ItemInput#id}) — only an item saved without its id (new, or
         * one whose id was foreign/stale/a duplicate) gets a new id and therefore a new URL. */
        String pictureUrl,
        /** V176 — the supplier-stated square metres per box (TILE rows; null when unknown). An
         * ENGLISH per-sqm quotation's {@code quantity} is {@code boxes × sqmPerBox} (2dp). */
        BigDecimal sqmPerBox,
        /** Owner-approved "sell loose pieces" (2026-09-16, V182). {@code true} (the default, and
         * every pre-V182 row) prints/charges {@code piecesFinal} rounded UP to the next
         * {@code piecesPerBox} multiple, exactly as before. {@code false} sells the
         * wastage-adjusted piece count UNROUNDED — {@link #boxes} is then the full-box count and
         * {@code piecesFinal - boxes * piecesPerBox} (the frontend's own arithmetic, not a separate
         * wire field) is the loose-piece remainder. Never false together with an English
         * per-sqm price mode — see {@code DealQuotationService#requireBoxDataForPerSqm}. */
        boolean roundToFullBox
    ) {
        /** The pre-V176 canonical shape (no {@link #sqmPerBox}). */
        public DealQuotationItemDto(
            long id, int seq, String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm,
            Integer piecesInput, String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            BigDecimal unitPrice, BigDecimal discountPct, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            BigDecimal piecesPerSqm, int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal,
            Integer boxes, BigDecimal netUnitPrice, BigDecimal lineAmount,
            String descriptionLine, String sizeLine, String calculationLine,
            String lineType, BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
            BigDecimal adjustmentPct, LocalDate adjustmentDeadline, String specialPriceLine,
            BigDecimal adjustmentAmount, boolean hasPicture, String picturePlacement, String pictureUrl) {
            this(id, seq, locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays, leadTimeMaxDays, itemNotes,
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, netUnitPrice,
                lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity, unit,
                specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, null, true);
        }

        /** The pre-GLA-75 shape — no picture. Kept so existing call sites (the calculate-line
         * preview and many test fixtures) compile unchanged; same device as
         * {@code DealQuotationRequests.ItemInput}'s legacy constructor. */
        public DealQuotationItemDto(
            long id, int seq, String locationLabel, Long catalogPriceId, String productCode,
            String brand, String model, String color, String texture, String sizeText,
            BigDecimal thicknessMm, BigDecimal sqmPerPiece, String quantityMode, BigDecimal areaSqm,
            Integer piecesInput, String wastageMode, BigDecimal wastageValue, Integer piecesPerBox,
            BigDecimal unitPrice, BigDecimal discountPct, String originCountry,
            Integer leadTimeMinDays, Integer leadTimeMaxDays, String itemNotes,
            BigDecimal piecesPerSqm, int piecesBeforeWastage, int piecesAfterWastage, int piecesFinal,
            Integer boxes, BigDecimal netUnitPrice, BigDecimal lineAmount,
            String descriptionLine, String sizeLine, String calculationLine,
            String lineType, BigDecimal quantity, String unit, BigDecimal specialPriceSqm,
            BigDecimal adjustmentPct, LocalDate adjustmentDeadline, String specialPriceLine,
            BigDecimal adjustmentAmount) {
            this(id, seq, locationLabel, catalogPriceId, productCode, brand, model, color, texture, sizeText,
                thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput, wastageMode, wastageValue,
                piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays, leadTimeMaxDays, itemNotes,
                piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal, boxes, netUnitPrice,
                lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity, unit,
                specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                false, null, null, null, true);
        }

        /** This item carrying {@code value} as its {@link #sqmPerBox} (repository/preview paths). */
        public DealQuotationItemDto withSqmPerBox(BigDecimal value) {
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, value, roundToFullBox);
        }

        /** This item carrying {@code value} as its {@link #roundToFullBox} — same device as
         * {@link #withSqmPerBox}, for the same reason (both are appended fields written after the
         * shorter legacy constructor most call sites still build from). */
        public DealQuotationItemDto withRoundToFullBox(boolean value) {
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                hasPicture, picturePlacement, pictureUrl, sqmPerBox, value);
        }

        /** This item with its picture fields set from the stored link (repository read path). */
        public DealQuotationItemDto withPicture(long quotationId, String placement) {
            boolean has = placement != null;
            return new DealQuotationItemDto(id, seq, locationLabel, catalogPriceId, productCode, brand, model,
                color, texture, sizeText, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, unitPrice, discountPct, originCountry, leadTimeMinDays,
                leadTimeMaxDays, itemNotes, piecesPerSqm, piecesBeforeWastage, piecesAfterWastage, piecesFinal,
                boxes, netUnitPrice, lineAmount, descriptionLine, sizeLine, calculationLine, lineType, quantity,
                unit, specialPriceSqm, adjustmentPct, adjustmentDeadline, specialPriceLine, adjustmentAmount,
                has, has ? placement : null,
                has ? "/api/deal-quotations/" + quotationId + "/items/" + id + "/picture" : null, sqmPerBox,
                roundToFullBox);
        }
    }
}
