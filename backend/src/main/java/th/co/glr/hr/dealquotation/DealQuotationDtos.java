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
        List<DealQuotationItemDto> items,
        Instant createdAt,
        Instant updatedAt
    ) {}

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
        String specialPriceLine
    ) {}
}
