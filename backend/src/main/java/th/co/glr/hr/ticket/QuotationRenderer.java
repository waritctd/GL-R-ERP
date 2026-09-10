package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;

// Renders a quotation by filling the official company template (quotation_template.xls).
// Rule: open the real template, write ONLY the cells listed below, never createRow/createCell
// on rows that already exist in the template, never call setCellType (wipes style).
//
// Everything renders through ONE rich model (QuotationRenderModel) now. The legacy/PCR
// toXls/toXlsx/toPdf(TicketDto, QuotationDto, CustomerDto) overloads are thin wrappers
// (#buildLegacyModel) that build that model EXACTLY the way this class used to compute things
// inline, so their output stays content-equivalent to before this refactor — one description
// line per item, only the original three dynamic remark lines, blank signatory names, the
// template's original signature labels. The direct-deal path (V165,
// th.co.glr.hr.dealquotation.DealQuotationRenderAdapter) builds the same model with multi-line
// item descriptions, location headings, all 8 remark lines, real signatory names, and the
// approver's signature image — see docs/sales/quotation-v2-plan.md's "Printed lines" section.
@Component
public class QuotationRenderer {
    private static final Logger log = LoggerFactory.getLogger(QuotationRenderer.class);

    // Real company XLS template — sheet "Update"
    private static final String TEMPLATE = "templates/quotation_template.xls";

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Template layout per document-generation-fix.md §A2-A3
    // The template's item zone is 0-based rows 9–20; the first item row (A10) starts the table.
    private static final int ITEM_START_ROW = 9; // 0-based (= row 10 in 1-based)

    // Flow layout: pack rows one per emitted item-row from ITEM_START_ROW and relocate the footer
    // block (notes/totals/signature) to sit right after the last emitted row, so any row count
    // paginates cleanly (Excel-like: fit columns to one page wide, paginate down, letterhead
    // repeated). Row 12 is a fully-bordered item row whose cell styles are cloned into rows past
    // the template's original item zone.
    private static final int ITEM_STYLE_PROTO_ROW = 12;
    private static final int FOOTER_START = 22; // 0-based: หมายเหตุ … totals … signature … F-SM-002
    // H3: every row constant from 28 (0-based) onward is +1 versus the RAW template, because
    // #insertLine3ContinuationRow permanently shifts every such row down by one at the START of
    // every render — see that method's Javadoc for why (line 3 had no continuation row and wrapped
    // into the border below it). These constants describe the layout AFTER that shift, not the
    // template file's own raw row numbers.
    private static final int FOOTER_END   = 47;
    private static final int SUBTOTAL_ROW = 38; // I39
    private static final int VAT_ROW      = 39; // I40
    private static final int TOTAL_ROW    = 40; // I41
    private static final int SALESPERSON_FORMULA_ROW = 45; // A46 lookup formula → blanked; also
                                                             // the "(name)" row under the signature
                                                             // labels once blanked (see writeSignatureBlock)
    private static final int LABELS_ROW = 44;               // A45 "ผู้พิมพ์___...ผู้สั่งซื้อ___" (all four in ONE cell)
    // layout-spec §5: S0 (the "ตกลงสั่งซื้อ…" line, one row above the labels) and S3 (the "วันที่…"
    // line, one row below the names) — S1=LABELS_ROW, S2=SALESPERSON_FORMULA_ROW.
    private static final int ORDER_LINE_ROW = 42; // D43 "ตกลงสั่งซื้อสินค้าตามราคาและเงื่อนไขข้างต้น"
    private static final int DATE_ROW = 46;        // A47 "วันที่…." (all four in ONE cell, pre-rebuild)
    private static final int FORM_TAG_ROW = 47;             // I48 "F-SM-002" → blanked
    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    // Header value cells (ฝ่าย / เลขที่อ้างอิง / หน่วยงาน) — found by dumping the template with POI;
    // see the class Javadoc / PR body for the raw dump. H3/H4/H5 are the template's own LABEL
    // cells ("ฝ่าย" / "เลขที่อ้างอิง" / "หน่วยงาน") and are never touched; I3/I4/I5 are their value
    // slots. A prior version of this renderer mis-attributed I3 as an unused "salesperson code"
    // cell and blanked H5 (the หน่วยงาน LABEL) to "" — both fixed here: I3 is the ฝ่าย value, and
    // the หน่วยงาน label at H5 is left alone.
    private static final int DEPT_VALUE_ROW = 2;   // I3
    private static final int NUMBER_VALUE_ROW = 3;  // I4
    private static final int UNIT_VALUE_ROW = 4;    // I5
    private static final int VALUE_COL = 8;         // column I
    private static final int SALES_LINE_ROW = 5;    // H6
    private static final int SALES_LINE_COL = 7;
    private static final int ATTN_ROW = 4;          // B5
    private static final int PHONE_ROW = 5;         // B6
    private static final int PROJECT_ROW = 7;       // B8
    private static final int LABEL_VALUE_COL = 1;   // column B

    // Remark rows (H3: post-shift numbering — see #FOOTER_END's comment). Originally lines
    // 1/2/4/5/6 each spanned a "head" row (the numbered sentence) plus a "continuation" row
    // (unnumbered, wraps onto the next line); lines 3/7/8 got only a single row. Line 3 is
    // DYNAMIC (composed lead-time groups, docs/sales/quotation-v2-plan.md's "Printed lines" §3) and routinely
    // needs more than one row's worth of text — with no continuation row of its own, its overflow
    // wrapped straight into the border of the row below (line 4's head row), which is exactly what
    // the reviewer's reference render showed. #insertLine3ContinuationRow gives it one, matching
    // every other multi-row line, by permanently inserting a row after (old) row 27 and shifting
    // everything from (old) row 28 down by one for the rest of THIS render.
    private static final int[] REMARK_HEAD_ROWS = {23, 25, 27, 29, 31, 33, 35, 36};
    private static final Map<Integer, Integer> REMARK_CONTINUATION_ROWS = Map.of(
        0, 24, 1, 26, 2, 28, 3, 30, 4, 32, 5, 34
    );

    // layout-spec §3 (v2/full-remarks render only): each of the 8 remark lines is ONE merged B..I
    // cell and NEVER wraps — it no longer needs a continuation row, and column B..I is roughly 8x
    // wider than column B alone, so a wrapped remark sentence fits on one row at this width in
    // every observed fixture. Packing all 8 lines onto 8 CONSECUTIVE rows starting at
    // REMARK_HEAD_ROWS[0] frees the template's original head+continuation rows 31..37 (6 unused
    // continuation rows for lines 5-6-7-8's slots plus the always-blank trailing row 37 — see
    // #compactRemarksSection's own Javadoc) — those must be physically removed, not just left
    // blank, or "no blank rows"/"box closes directly under the last remark line" (layout-spec §3)
    // is violated. #compactRemarksSection removes them with one shiftRows call; every row constant
    // at or past REMARK_V2_COMPACT_FIRST_UNUSED must add REMARK_V2_COMPACT_SHIFT for the rest of a
    // v2/full render (mirrors how #insertLine3ContinuationRow's own shift is baked into FOOTER_END
    // etc. already — see that field's comment).
    private static final int REMARK_V2_COMPACT_FIRST_UNUSED =
        REMARK_HEAD_ROWS[0] + REMARK_HEAD_ROWS.length; // 31 — first row past the 8 packed lines
    private static final int REMARK_V2_COMPACT_LAST_UNUSED =
        REMARK_HEAD_ROWS[REMARK_HEAD_ROWS.length - 1] + 1; // 37 — the always-blank trailing row
    private static final int REMARK_V2_COMPACT_SHIFT =
        -(REMARK_V2_COMPACT_LAST_UNUSED - REMARK_V2_COMPACT_FIRST_UNUSED + 1); // -7
    // Width-aware wrap budget for a remark head+continuation row pair (H3): measured in
    // characters against column B's own width at the template's default font — a generous 62 (not
    // the theoretical max the raw pixel width would allow) because Thai glyph clusters render
    // noticeably wider than a fixed-width character estimate would predict; calibrated against
    // the reviewer's own reference render, whose overflowing line 3 wrapped at roughly this point.
    private static final int REMARK_LINE_CHAR_BUDGET = 62;

    // Items that fit the template's native item-row budget (rows 9..21) keep the footer anchored
    // at its native rows on a single page — derived from the layout, not a magic number. This is
    // now a ROW budget (emitted rows: heading rows + item rows + continuation rows), not an item
    // count — a legacy render's rows always equal its item count (no headings, one description
    // line each), so this stays numerically identical to before for that path.
    private static final int NATIVE_ITEM_CAPACITY = FOOTER_START - ITEM_START_ROW; // = 13

    // ── dynamic-layout geometry (all lengths in points; see the layout equations in toXls) ──
    private static final double A4_WIDTH_PT  = 595.3;
    private static final double A4_HEIGHT_PT = 841.9;
    // Below this fraction of natural size a quote paginates instead of shrinking onto one page.
    // layout-spec §6/§7: the 5-item reference (Linear GLA-57 — real customer document, single
    // A4 page, form F-SM-002) has EVERY item's calc line wrapping to 2 rows inside column B (see
    // #wrapDescriptionLines), which the old 0.72 floor — calibrated before that wrap existed —
    // rejected (its own onePageScale computes ≈0.60 for that exact fixture), paginating a document
    // the owner rejected specifically for NOT being one page. Lowered to fit that real reference
    // (with headroom below its ≈0.60) while still correctly paginating a genuinely long quote — a
    // 12-item render computes ≈0.40, comfortably under this floor either way.
    private static final double MIN_SCALE = 0.55;
    // Money-column sizing: padding chars + width units per char, calibrated so an 8-figure baht total
    // never clips to "###" in the totals column's larger bold font.
    private static final int MONEY_PAD_CHARS = 1;
    private static final int MONEY_UNITS_PER_CHAR = 300;

    // ── legacy/PCR entry points — thin wrappers over the model (see class Javadoc) ──────────

    public byte[] toXls(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        return toXls(buildLegacyModel(ticket, quotation, customer));
    }

    // Keep toXlsx as a shim — callers get XLS bytes now (format matches template)
    public byte[] toXlsx(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        return toXls(ticket, quotation, customer);
    }

    // PDF is the XLS template converted to PDF by LibreOffice (soffice --headless).
    // This ensures the PDF is pixel-identical to the XLS output.
    // Requires LibreOffice: brew install --cask libreoffice  (macOS)
    //                       apt-get install -y libreoffice-calc  (Ubuntu/Docker)
    public byte[] toPdf(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        return LibreOfficePdfConverter.convert(toXls(ticket, quotation, customer));
    }

    private QuotationRenderModel buildLegacyModel(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        TicketSummaryDto s = ticket.summary();
        LocalDate issueDate = quotation.issuedAt() != null
            ? quotation.issuedAt().atZone(ZoneId.of("Asia/Bangkok")).toLocalDate()
            : LocalDate.now();

        String salesLine = quotation.issuedByName() != null ? "Sales : " + quotation.issuedByName() : "";

        String contactPart = s.contactName() != null && !s.contactName().isBlank()
            ? "คุณ" + s.contactName() + "   /   " : "";
        String taxIdPart = customer != null && customer.taxId() != null && !customer.taxId().isBlank()
            ? "   เลขที่ผู้เสียภาษี : " + customer.taxId() : "";
        String attnLine = contactPart + nullSafe(s.customerName()) + taxIdPart;

        String phonePart = customer != null && customer.phone() != null && !customer.phone().isBlank()
            ? "โทร. " + customer.phone() : "";

        List<TicketItemDto> priceItems = ticket.items().stream()
            .filter(it -> it.approvedPrice() != null)
            .toList();
        List<RenderItem> items = priceItems.stream().map(this::toLegacyRenderItem).toList();

        LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : issueDate;
        int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 50;
        int deliveryDays = quotation.deliveryLeadDays() != null ? quotation.deliveryLeadDays() : 90;
        List<String> remarkLines = List.of(
            "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate),
            "2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือขอรับ",
            "3.ขณะนี้โรงงานผู้ผลิตประเทศอิตาลีมีสินค้าในสต็อก ระยะเวลานำเข้าประมาณ " + deliveryDays + " วัน"
        );

        return new QuotationRenderModel(
            issueDate, quotation.number(), null, null, salesLine, attnLine, phonePart,
            s.projectName(), items, remarkLines,
            new Signatories(null, null, null, null, null),
            false);
    }

    private RenderItem toLegacyRenderItem(TicketItemDto item) {
        BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
        BigDecimal price = item.approvedPrice() != null ? item.approvedPrice() : BigDecimal.ZERO;
        return new RenderItem(null, List.of(buildDesc(item)), qty, nullSafe(item.rawUnit(), "แผ่น"),
            price, "Net", price, price.multiply(qty));
    }

    // ── the model entry points ───────────────────────────────────────────────────────────

    public byte[] toXls(QuotationRenderModel model) {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // H3: must run BEFORE anything below touches a row ≥ 28 (0-based) — every such row
            // constant on this class already assumes this shift happened (see FOOTER_END's own
            // comment).
            insertLine3ContinuationRow(sh);

            // layout-spec §3: v2/full-remarks compaction (see REMARK_V2_COMPACT_SHIFT's own
            // Javadoc) — must also run before anything touches a row ≥ REMARK_V2_COMPACT_FIRST_UNUSED.
            // footerShift folds into every footer-block row constant used below, exactly like
            // insertLine3ContinuationRow's shift already folds into FOOTER_END etc.
            boolean v2CompactRemarks = model.signatureLabelsV2() && model.remarkLines() != null
                && model.remarkLines().size() >= REMARK_HEAD_ROWS.length;
            int footerShift = v2CompactRemarks ? REMARK_V2_COMPACT_SHIFT : 0;
            if (v2CompactRemarks) {
                compactRemarksSection(sh);
            }

            // Title cell H1 (row 0, col 7) — "ใบเสนอราคา" carries ~25 leading spaces to
            // position it in Excel. LibreOffice's wider substitute font pushes the trailing
            // sara-aa (า) past the print-area right edge (col I), clipping it. Re-anchor
            // with fewer leading spaces so it stays right-aligned but fits within the page.
            Cell titleCell = getOrKeep(sh, 0, 7);
            if (titleCell.getCellType() == CellType.STRING) {
                setStr(sh, 0, 7, "        " + titleCell.getStringCellValue().strip());
            }

            setStr(sh, 3, 1, thaiDate(model.issueDate()));                      // B4 — issue date
            setStr(sh, DEPT_VALUE_ROW, VALUE_COL, nullSafe(model.deptCode()));   // I3 — ฝ่าย
            setStr(sh, NUMBER_VALUE_ROW, VALUE_COL, nullSafe(model.number()));   // I4 — เลขที่อ้างอิง
            setStr(sh, UNIT_VALUE_ROW, VALUE_COL, nullSafe(model.unitCode()));   // I5 — หน่วยงาน
            // layout-spec §3: SALES_LINE_COL (H) is the SAME physical column #sizeMoneyColumns
            // sizes for the "net" money figure — a previous version of this fix WIDENED that data
            // column to fit "Sales/{name} T.{phone}", which made คงเหลือ absurdly wide on every
            // row, not just this header line. Merge H6:I6 only (never widen a DATA column for
            // header-only text) — column G on this row is already claimed by the template's own
            // B6:G6 phone-line merge (#PHONE_ROW is the SAME row), so the merge cannot widen
            // leftward without colliding with it (confirmed: addMergedRegion throws on the
            // overlap). H:I alone (≈154px) is too narrow for "Sales/{name} T.{phone}" at the
            // template's normal font, so the last character clipped at the page's right edge
            // (reviewer's own reference render) — right-align + shrink-to-fit instead, the same
            // built-in answer #setCentered already uses for the signature block's narrow ranges.
            mergeIfAbsent(sh, SALES_LINE_ROW, SALES_LINE_ROW, SALES_LINE_COL, SALES_LINE_COL + 1);
            setRightAligned(sh, SALES_LINE_ROW, SALES_LINE_COL, nullSafe(model.salesLine())); // H6:I6
            setStr(sh, ATTN_ROW, LABEL_VALUE_COL, nullSafe(model.attnLine()));       // B5
            setStr(sh, PHONE_ROW, LABEL_VALUE_COL, nullSafe(model.phoneLine()));     // B6
            if (model.projectName() != null && !model.projectName().isBlank()) {
                if (model.signatureLabelsV2()) {
                    // layout-spec §2: "Project : {name}" centred in B on the first body row (v2
                    // only — legacy keeps its original left-aligned template style, see the class
                    // Javadoc's "stays content-equivalent" guarantee).
                    setCentered(sh, PROJECT_ROW, LABEL_VALUE_COL, "Project  : " + model.projectName());
                } else {
                    setStr(sh, PROJECT_ROW, LABEL_VALUE_COL, "Project  : " + model.projectName());
                }
            }

            // One-off template data fix, independent of the caller: strip the stray "+B27:B29"
            // cell reference the template author left in B27's (line 2's continuation) text.
            stripStrayCellReference(sh);
            writeRemarks(sh, model.remarkLines());

            List<RenderItem> items = model.items();
            BigDecimal subtotal = items.stream()
                .map(RenderItem::amount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal vat = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);

            // ① Size the money columns to the actual numbers so nothing ever clips to "###",
            //    whatever the magnitude. Must run before the width scale below is measured.
            sizeMoneyColumns(sh, items, subtotal.add(vat));

            // Consistent page margins (corner padding) on every layout and every page — set before
            // the scale math below so the width/height equations account for them.
            applyMargins(sh);

            boolean alwaysShowSeq = model.signatureLabelsV2();
            int emittedRows = countEmittedRows(items, alwaysShowSeq);

            // Pick the layout from the real geometry rather than hardcoded row counts:
            //   contentH = letterhead + emittedRows·rowHeight + footer   (all measured from the template)
            //   onePageScale = min(widthScale, printableHeight / contentH)
            // ② ≤ native row budget  → footer anchored at its native rows (fills one page like Excel).
            // ③ still fits one page at a readable scale (≥ MIN_SCALE) → shrink the whole quote to one
            //    page instead of stranding a near-empty second page.
            // ④ otherwise            → paginate at natural scale.
            int footerEnd = FOOTER_END + footerShift;
            int delta;
            if (emittedRows <= NATIVE_ITEM_CAPACITY) {
                renderSinglePage(sh, items, subtotal, alwaysShowSeq, footerShift);
                delta = 0;
            } else if (onePageScale(sh, emittedRows, footerShift) >= MIN_SCALE) {
                double scale = onePageScale(sh, emittedRows, footerShift);
                delta = layoutFlowing(sh, items, subtotal, alwaysShowSeq, footerShift);
                // H5: the TEMPLATE carries its own fixed print area (A1:I47, dumped from the raw
                // file) — layoutFlowing relocates the footer to footerEnd + delta, which for any
                // delta > 0 sits PAST that stale native range, so the relocated
                // เงื่อนไข/totals/signature block was silently EXCLUDED from print/PDF output
                // (still "1 page" — nothing paginated, the content was simply clipped by the print
                // area, not shrunk into view). Extend it every time, exactly like the paginate
                // branch below already has to.
                int idxOnePage = sh.getWorkbook().getSheetIndex(sh);
                sh.getWorkbook().setPrintArea(idxOnePage, 0, 8, 0, footerEnd + delta);
                fitToOnePageAtScale(sh, scale * 100.0);
            } else {
                delta = layoutFlowing(sh, items, subtotal, alwaysShowSeq, footerShift);
                // LOW fix: this used to run through SUBTOTAL_ROW - 1 + delta — which, once the
                // footer is relocated by `delta`, reaches well PAST the last actual item row and
                // gives every remarks/notes row (blank in columns C..I, since remarks only write
                // column B) the SAME thin-bottom-border treatment as a real item row. On a
                // multi-page render whose page break happened to fall inside the remarks block,
                // that painted a wide, empty, bordered "table" fragment at the top of the next
                // page — the reviewer's reported stub. The item table itself ends at
                // FOOTER_START + delta - 1 (see #layoutFlowing's own delta derivation); only that
                // range should ever get an item-table border.
                closeItemTableBorders(sh, ITEM_START_ROW, FOOTER_START + delta - 1);
                int idx = sh.getWorkbook().getSheetIndex(sh);
                sh.getWorkbook().setPrintArea(idx, 0, 8, 0, footerEnd + delta);
                sh.setRepeatingRows(CellRangeAddress.valueOf("A1:I7"));
                sh.getFooter().setCenter("หน้า &P/&N");
                // layout-spec §6: "never split an item's lines, the remark block, or the signature
                // block across pages". LOW's own note here used to record a REVERTED attempt at
                // this (an unconditional break right before LABELS_ROW landed INSIDE the approver
                // picture's own row span, decoupling the image from its labels) — #insertNoSplitPageBreaks
                // fixes that by breaking before the WHOLE relocated footer block as one unit
                // (remarks+totals+signature together, never just the signature portion), and by
                // walking the SAME per-item row accounting #fillItems used, so an item's own
                // heading+lines never straddle either.
                insertNoSplitPageBreaks(sh, items, alwaysShowSeq, FOOTER_START + delta,
                    sumRowHeights(sh, FOOTER_START + delta, footerEnd + delta));
                fitToWidthPaginate(sh);
            }

            writeSignatureBlock(sh, model.signatories(), model.signatureLabelsV2(), delta, footerShift);

            wb.setForceFormulaRecalculation(true);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Quotation render failed: " + e.getMessage(), e);
        }
    }

    public byte[] toXlsx(QuotationRenderModel model) {
        return toXls(model);
    }

    public byte[] toPdf(QuotationRenderModel model) {
        return LibreOfficePdfConverter.convert(toXls(model));
    }

    // ── layout ──────────────────────────────────────────────────────────────────

    /**
     * ≤ {@link #NATIVE_ITEM_CAPACITY} emitted rows: fill from the top, one row each, and leave the
     * footer block (notes/totals/signature) at its native template rows so it sits anchored near the
     * page bottom — filling the page exactly like the Excel template's "Save as PDF".
     */
    private void renderSinglePage(Sheet sh, List<RenderItem> items, BigDecimal subtotal, boolean alwaysShowSeq,
                                   int footerShift) {
        int emitted = fillItems(sh, items, alwaysShowSeq);
        // Blank the template item-zone rows the items didn't reach: the pre-seeded "2."/"แผ่น"/"Net"
        // placeholder at row 12 and the per-row H/I formulas through row 21 would otherwise show as
        // phantom lines below the last real item. Content only — the footer stays put.
        for (int r = ITEM_START_ROW + emitted; r < FOOTER_START; r++) {
            for (int c = 0; c <= 8; c++) clearCell(sh, r, c);
        }
        setNum(sh, SUBTOTAL_ROW + footerShift, 8, subtotal.doubleValue()); // I38; I39/I40 are template formulas
        getOrKeep(sh, SALESPERSON_FORMULA_ROW + footerShift, 0).setBlank(); // lookup → "0" otherwise
        getOrKeep(sh, FORM_TAG_ROW + footerShift, 8).setBlank();            // F-SM-002 tag, not customer-facing
        fitToOnePage(sh);
    }

    /**
     * Fill items (one row per description line, plus optional heading rows), relocating the
     * footer block (notes/totals/signature) to sit immediately below the last emitted row, and
     * write the totals as computed values at the moved rows. Returns the row offset (delta) the
     * footer was shifted by. The footer must be captured and CLEARED before filling — an emitted
     * row count beyond the native zone would otherwise be overwritten by leftover footer content.
     * {@code footerShift} folds in the v2/full-remarks compaction (see
     * {@link #REMARK_V2_COMPACT_SHIFT}) — by the time this runs the sheet's OWN rows have already
     * been physically compacted, so the footer's captured range must end at {@code FOOTER_END +
     * footerShift}, not the raw template constant.
     */
    private int layoutFlowing(Sheet sh, List<RenderItem> items, BigDecimal subtotal, boolean alwaysShowSeq,
                               int footerShift) {
        int footerEnd = FOOTER_END + footerShift;
        List<CellRec> footer = captureBlock(sh, FOOTER_START, footerEnd);
        float[] heights = captureHeights(sh, FOOTER_START, footerEnd);
        List<int[]> merges = captureMerges(sh, FOOTER_START, footerEnd);
        clearBlock(sh, FOOTER_START, footerEnd);

        int emitted = fillItems(sh, items, alwaysShowSeq);

        int delta = (ITEM_START_ROW + emitted) - FOOTER_START;
        placeBlock(sh, footer, delta);
        applyHeights(sh, heights, FOOTER_START + delta);
        for (int[] m : merges) sh.addMergedRegion(new CellRangeAddress(m[0] + delta, m[1] + delta, m[2], m[3]));

        BigDecimal vat = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        setNum(sh, SUBTOTAL_ROW + footerShift + delta, 8, subtotal.doubleValue());
        setNum(sh, VAT_ROW + footerShift + delta, 8, vat.doubleValue());
        setNum(sh, TOTAL_ROW + footerShift + delta, 8, subtotal.add(vat).doubleValue());
        getOrKeep(sh, SALESPERSON_FORMULA_ROW + footerShift + delta, 0).setBlank();
        getOrKeep(sh, FORM_TAG_ROW + footerShift + delta, 8).setBlank();
        return delta;
    }

    // layout-spec §1: the table body is ONE box with only vertical column rules — no horizontal
    // rule under any item/description/heading/remark row. The ONLY horizontal rules are under the
    // column-title row (row 6, template-native) and at the bottom of the whole box, just above
    // รวมเป็นเงิน (the template's own row carries that border and travels with the footer block on
    // #layoutFlowing's relocation — see #placeBlock). So this method must NOT stripe every row in
    // [firstRow, lastRow] with a border (that painted the "grid under every row" the reviewer
    // rejected) — it borders ONLY {@code lastRow}, closing a genuinely paginated page fragment that
    // ends mid-table without a border of its own, exactly as the caller's own name promises.
    private void closeItemTableBorders(Sheet sh, int firstRow, int lastRow) {
        Workbook wb = sh.getWorkbook();
        Map<Short, CellStyle> cache = new HashMap<>();
        Row row = sh.getRow(lastRow);
        if (row == null) return;
        for (int c = 0; c <= 8; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) cell = row.createCell(c);
            CellStyle src = cell.getCellStyle();
            CellStyle bordered = cache.get(src.getIndex());
            if (bordered == null) {
                bordered = wb.createCellStyle();
                bordered.cloneStyleFrom(src);
                bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                cache.put(src.getIndex(), bordered);
            }
            cell.setCellStyle(bordered);
        }
    }

    // ── item rows (heading + description lines) ──────────────────────────────────

    /**
     * Writes every item's rows from {@link #ITEM_START_ROW} — an optional underlined heading row
     * (only when {@code headingLabel} is non-null and differs from the previous item's raw
     * label), then one row per description line (qty/unit/price/discount/net/amount on the first
     * row only). Rows past the template's native zone (row 20) get the item-row style cloned from
     * {@link #ITEM_STYLE_PROTO_ROW}. Returns the total number of rows emitted.
     */
    private int fillItems(Sheet sh, List<RenderItem> items, boolean alwaysShowSeq) {
        Row proto = sh.getRow(ITEM_STYLE_PROTO_ROW);
        boolean showSeq = alwaysShowSeq || items.size() > 1;
        int r = ITEM_START_ROW;
        int seq = 0;
        String prevLabel = null;
        boolean prevSet = false;
        // H1: local to this render call — see #underlinedStyle's Javadoc for why this can never
        // again be a field on this @Component singleton.
        Map<Short, CellStyle> underlineCache = new HashMap<>();
        for (RenderItem item : items) {
            seq++;
            if (headingNeeded(item.headingLabel(), prevLabel, prevSet)) {
                ensureRowStyle(sh, r, proto);
                writeHeadingRow(sh, r, item.headingLabel(), underlineCache);
                r++;
            }
            prevLabel = item.headingLabel();
            prevSet = true;

            // layout-spec §2: every printed line must stay INSIDE column B — wrap width-aware
            // (alwaysShowSeq is true only for the v2/direct-deal path; legacy items keep the
            // template's own wrap-text-cell/tall-row behaviour on their single description line,
            // never this multi-row emission — see the class Javadoc's "stays content-equivalent").
            List<String> physicalLines = alwaysShowSeq
                ? wrapDescriptionLines(item.descriptionLines())
                : item.descriptionLines();

            ensureRowStyle(sh, r, proto);
            fillItemMainRow(sh, r, showSeq ? seq : -1, physicalLines.isEmpty() ? "" : physicalLines.get(0), item);
            r++;

            for (int i = 1; i < physicalLines.size(); i++) {
                ensureRowStyle(sh, r, proto);
                fillContinuationRow(sh, r, physicalLines.get(i));
                r++;
            }
        }
        return r - ITEM_START_ROW;
    }

    /** Pure count of {@link #fillItems}'s row output, with NO sheet side effects — needed to pick
     * the layout branch (which decides whether the footer must move) before any row is written.
     * Must stay in exact lockstep with {@link #fillItems}'s own row math, including the v2
     * width-aware wrap — a mismatch here picks the wrong layout branch for the row count that
     * actually gets written. */
    private int countEmittedRows(List<RenderItem> items, boolean alwaysShowSeq) {
        int rows = 0;
        String prevLabel = null;
        boolean prevSet = false;
        for (RenderItem item : items) {
            if (headingNeeded(item.headingLabel(), prevLabel, prevSet)) rows++;
            prevLabel = item.headingLabel();
            prevSet = true;
            List<String> lines = alwaysShowSeq
                ? wrapDescriptionLines(item.descriptionLines())
                : item.descriptionLines();
            rows += Math.max(1, lines.size());
        }
        return rows;
    }

    // Width-aware wrap budget for an item's printed lines (description/size/calculation), at
    // column B's own width and the item row's font — the SAME column {@link #REMARK_LINE_CHAR_BUDGET}
    // already calibrates for, so it reuses that value rather than a second magic number: item rows
    // and remark rows are the same font size in the template (see both rows' captured heights).
    private List<String> wrapDescriptionLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line != null && line.length() > REMARK_LINE_CHAR_BUDGET) {
                out.addAll(wrapToWidth(line, REMARK_LINE_CHAR_BUDGET));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private boolean headingNeeded(String label, String prevLabel, boolean prevSet) {
        if (label == null) return false;
        return !prevSet || !label.equals(prevLabel);
    }

    private void ensureRowStyle(Sheet sh, int r, Row proto) {
        if (r > 20 && proto != null) cloneRowStyle(sh, proto, r); // past the template's native item zone
    }

    private void writeHeadingRow(Sheet sh, int r, String text, Map<Short, CellStyle> underlineCache) {
        Cell cell = getOrKeep(sh, r, LABEL_VALUE_COL);
        cell.setCellStyle(underlinedStyle(sh.getWorkbook(), cell.getCellStyle(), underlineCache));
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        cell.setCellValue(text != null ? text : "");
        clearCell(sh, r, 0);
        for (int c = 2; c <= 8; c++) clearCell(sh, r, c);
    }

    // H1: underlineCache used to be a FIELD on this class — and this class is a Spring
    // @Component, i.e. a SINGLETON reused across every render. A CellStyle is tied to the
    // Workbook instance that created it (workbook.createCellStyle()), and every render opens a
    // FRESH Workbook from the template (see #toXls). So a second render onto a heading-bearing
    // model, through the SAME renderer instance, hit a style CACHED from the FIRST render's
    // workbook — and POI throws "This Style does not belong to the supplied Workbook" the moment
    // that stale style is applied to a cell in the new one. #approve's own PDF re-render
    // (DealQuotationService#sendApprovalEmail) is exactly a second render on the same instance,
    // deferred to run after the approving transaction commits — so this silently killed the
    // approval email on every quotation whose SECOND item onward carried a location heading,
    // with the failure swallowed by that method's own (now-fixed, see its Javadoc) catch block.
    // The cache must live for exactly ONE render call — passed in from #fillItems, which creates
    // a fresh one every time #toXls runs — never on `this`. #closeItemTableBorders already got
    // this right; this method was the one holdout on the class.
    private CellStyle underlinedStyle(Workbook wb, CellStyle base, Map<Short, CellStyle> underlineCache) {
        CellStyle cached = underlineCache.get(base.getIndex());
        if (cached != null) return cached;
        CellStyle style = wb.createCellStyle();
        style.cloneStyleFrom(base);
        Font baseFont = wb.getFontAt(base.getFontIndexAsInt());
        Font underline = wb.createFont();
        underline.setFontName(baseFont.getFontName());
        underline.setFontHeightInPoints(baseFont.getFontHeightInPoints());
        underline.setBold(baseFont.getBold());
        underline.setItalic(baseFont.getItalic());
        underline.setColor(baseFont.getColor());
        underline.setUnderline(Font.U_SINGLE);
        style.setFont(underline);
        underlineCache.put(base.getIndex(), style);
        return style;
    }

    private void fillItemMainRow(Sheet sh, int r, int seq, String firstLine, RenderItem item) {
        if (seq > 0) setNum(sh, r, 0, seq); else clearCell(sh, r, 0); // A: sequence
        setStr(sh, r, 1, firstLine != null ? firstLine : ""); // B: description (first physical line)
        BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
        setNum(sh, r, 2, qty.doubleValue());                              // C: qty
        setStr(sh, r, 3, nullSafe(item.unit(), "แผ่น"));                  // D: unit
        setNum(sh, r, 4, orZero(item.unitPrice()));                       // E: unit price
        setStr(sh, r, 6, item.discountLabel() != null ? item.discountLabel() : "Net"); // G: ส่วนลด
        setNum(sh, r, 7, orZero(item.netUnitPrice()));                    // H: คงเหลือ (net)
        setNum(sh, r, 8, orZero(item.amount()));                         // I: เป็นเงิน (amount)
    }

    private void fillContinuationRow(Sheet sh, int r, String text) {
        clearCell(sh, r, 0);
        setStr(sh, r, 1, text);
        for (int c = 2; c <= 8; c++) clearCell(sh, r, c);
    }

    // ── header / remarks / signature block ────────────────────────────────────────

    private void stripStrayCellReference(Sheet sh) {
        Cell b27 = getOrKeep(sh, 26, 1);
        if (b27.getCellType() == CellType.STRING && b27.getStringCellValue().contains("+B27:B29")) {
            setStr(sh, 26, 1, b27.getStringCellValue().replace("+B27:B29", "").stripTrailing());
        }
    }

    /**
     * H3: line 3 (กำหนดส่งมอบสินค้า — a DYNAMIC, composed list of lead-time groups) is the only
     * remark line among 1/2/3/4/5/6 with no continuation row: lines 1/2/4/5/6 each already got
     * one in the template's original design, but line 3's "head" row (originally row 27) sits
     * directly against line 4's head row (originally row 28) with nothing in between. A long
     * lead-time list therefore wrapped straight into line 4's border — the reviewer's reference
     * render shows exactly this.
     *
     * <p>Inserts ONE blank row after (raw template) row 27 via {@link Sheet#shiftRows}, which
     * physically relocates every row from 28 onward down by one — the new row 28 is empty (no
     * style) until this method clones one from an existing continuation row (24) onto it, so it
     * reads as the same kind of unnumbered wrap row as its siblings. Every row constant on this
     * class from {@code REMARK_HEAD_ROWS[3]} onward already assumes this shift ran — see
     * {@link #FOOTER_END}'s comment — so this must run exactly once, before any of them are used,
     * which {@link #toXls(QuotationRenderModel)} enforces by calling it immediately after opening
     * the (always-fresh-per-render) workbook.
     */
    private void insertLine3ContinuationRow(Sheet sh) {
        int lastRow = sh.getLastRowNum();
        sh.shiftRows(28, lastRow, 1);
        Row proto = sh.getRow(24); // an existing continuation row, unaffected by the shift (< 28)
        if (proto != null) {
            cloneRowStyle(sh, proto, 28);
        }
    }

    /**
     * Writes each supplied remark line into the template's numbered "head" row (see
     * {@link #REMARK_HEAD_ROWS}), wrapping into that line's continuation row (if it has one, per
     * {@link #REMARK_CONTINUATION_ROWS}) whenever the text would run past
     * {@link #REMARK_LINE_CHAR_BUDGET} at column B..I's width — H3's general fix, not just line
     * 3's specific one: ANY remark line composed at render time (not just line 3's lead-time
     * groups) is protected the same way. Only when ALL 8 lines are supplied (the direct-deal path
     * always sends 8 — see docs/sales/quotation-v2-plan.md) does an UNWRAPPED line also blank its
     * continuation row — its text fits on one line, so the old composed-sentence-with-baked-in-
     * continuation-text convention no longer applies. The legacy wrappers send exactly 3 (matching
     * this renderer's pre-existing behaviour), so their continuation rows are left untouched.
     */
    private void writeRemarks(Sheet sh, List<String> remarkLines) {
        if (remarkLines == null) return;
        boolean full = remarkLines.size() >= REMARK_HEAD_ROWS.length;
        if (full) {
            // layout-spec §3: 8 lines, 8 CONSECUTIVE rows (REMARK_HEAD_ROWS[0]..+7 — the compaction
            // in #compactRemarksSection has already removed the now-unused continuation/trailing
            // rows, so this range abuts the totals block with no gap), each ONE merged B..I cell,
            // never wrapped/split — see REMARK_V2_COMPACT_SHIFT's Javadoc for why a wrap is neither
            // needed (8x the width of column B alone) nor safe (it would re-open the gap the
            // compaction just closed).
            for (int i = 0; i < remarkLines.size() && i < REMARK_HEAD_ROWS.length; i++) {
                int row = REMARK_HEAD_ROWS[0] + i;
                setStr(sh, row, LABEL_VALUE_COL, remarkLines.get(i));
                mergeRemarkRow(sh, row);
            }
            return;
        }
        // Legacy (3 lines): unchanged — template's own head+continuation rows, single column B,
        // width-aware wrap into the continuation row when a composed line runs long.
        for (int i = 0; i < remarkLines.size() && i < REMARK_HEAD_ROWS.length; i++) {
            String line = remarkLines.get(i);
            Integer continuationRow = REMARK_CONTINUATION_ROWS.get(i);
            if (continuationRow != null && line != null && line.length() > REMARK_LINE_CHAR_BUDGET) {
                List<String> wrapped = wrapToWidth(line, REMARK_LINE_CHAR_BUDGET);
                setStr(sh, REMARK_HEAD_ROWS[i], LABEL_VALUE_COL, wrapped.get(0));
                setStr(sh, continuationRow, LABEL_VALUE_COL,
                    wrapped.size() > 1 ? String.join(" ", wrapped.subList(1, wrapped.size())) : "");
            } else {
                setStr(sh, REMARK_HEAD_ROWS[i], LABEL_VALUE_COL, line);
            }
        }
    }

    /**
     * v2/full-remarks render only — see {@link #REMARK_V2_COMPACT_SHIFT}'s Javadoc for why this is
     * needed. Physically removes the {@code [REMARK_V2_COMPACT_FIRST_UNUSED, REMARK_V2_COMPACT_LAST_UNUSED]}
     * row range (the template's now-unused remark continuation rows plus the always-blank trailing
     * row) by shifting every row at or below it up by {@code -REMARK_V2_COMPACT_SHIFT}, so the
     * totals block lands immediately after the 8 packed remark rows with no gap and no leftover
     * bordered "phantom" row. Must run once, immediately after {@link #insertLine3ContinuationRow},
     * before anything else reads a row ≥ {@link #REMARK_V2_COMPACT_FIRST_UNUSED}.
     */
    private void compactRemarksSection(Sheet sh) {
        int lastRow = sh.getLastRowNum();
        sh.shiftRows(REMARK_V2_COMPACT_LAST_UNUSED + 1, lastRow, REMARK_V2_COMPACT_SHIFT);
    }

    // layout-spec §3: each remark line is ONE merged B..I cell — v2 only ({@code full} = all 8
    // lines supplied); the legacy path's 3 remark lines keep the template's own single-column-B
    // cells untouched (stays content-equivalent, see the class Javadoc).
    private void mergeRemarkRow(Sheet sh, int row) {
        mergeIfAbsent(sh, row, row, LABEL_VALUE_COL, 8);
    }

    /**
     * H3: a plain greedy word-wrap at whitespace boundaries, so a long remark line (line 3's
     * composed lead-time groups today; {@code customerNotes} too, if a future caller ever routes
     * it through a remark-shaped slot — this helper is deliberately not line-3-specific) breaks
     * into readable chunks instead of clipping mid-word at the column edge. Never splits a single
     * word, even one longer than {@code maxChars} — an unbroken run that long is not this
     * helper's problem to solve mid-word. Only the first TWO chunks ever reach the sheet (one
     * head row + one continuation row — the template has no third slot); a caller supplying text
     * so long it needs a third row will render two crowded rows and not a clipped one, which is
     * the same trade-off {@link #REMARK_CONTINUATION_ROWS} already makes for every other line.
     */
    private List<String> wrapToWidth(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            int extra = current.length() > 0 ? 1 : 0;
            if (current.length() + extra + word.length() > maxChars && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.isEmpty() ? List.of(text) : lines;
    }

    // layout-spec §5, REBUILT: columns cannot be split into even quarters (B alone is ~55% of the
    // page), so S1/S2/S3 are each laid out as ONE merged A:I cell — positioned by TEXT, the way the
    // template's OWN original row 44 did it ("ผู้พิมพ์____ พนักงานขาย____ ผู้จัดการฝ่ายขาย____
    // ผู้สั่งซื้อ____", one string) — rather than by column boundaries. The four-discrete-merged-
    // column-range scheme this replaced (git history) made ผู้พิมพ์ alone fill the left half of the
    // page (behind column B) while the other three were crammed into the right 45%, with ผู้ตรวจ
    // shrunk-to-fit to a sliver.
    //
    // Each row is split into FOUR EQUAL-PIXEL-WIDTH slots ({@link #signatureSlotWidthPixels}) — not
    // equal CHARACTER-COUNT slots. An earlier version of this rebuild used one shared "average
    // character width" for all three rows, but S1's padding character (underscore, ~5px) and
    // S2/S3's (space, ~3px) are NOT the same width in a proportional font — the same unit count
    // produced visibly different real widths per row, so slot 4's content drifted left of slot 4's
    // own label as the mismatch compounded across the row (confirmed by rendering
    // reference-v4/v5.pdf and LOOKING at it). Measuring each row's own real string width via AWT
    // {@link java.awt.Font#getStringBounds} and padding with THAT row's own padding character's own
    // measured width keeps all three rows' slot boundaries at the same physical x position.
    private static final String[] SIG_LABELS = {"ผู้พิมพ์", "ผู้ตรวจ", "ผู้อนุมัติ", "ผู้สั่งซื้อ"};
    private static final int SIG_APPROVER_INDEX = 2; // ผู้อนุมัติ — where the signature image anchors
    private static final String BLANK_NAME_PLACEHOLDER = "(..........................)";
    // Fraction of the A..I row's total pixel width that S1 (labels+underscores) should fill —
    // matching the template's own original row 44, which ran its four-label string almost but not
    // quite edge-to-edge.
    private static final double SIGNATURE_ROW_FILL_FRACTION = 0.95;
    // ผู้อนุมัติ is slot index SIG_APPROVER_INDEX of SIG_LABELS.length equal-width slots — its centre
    // sits at (index + 0.5) / count of the way across A..I, i.e. (2.5×W)/(4×W) = 0.625 of the total
    // width, regardless of the slot's actual pixel width, since all four slots are equal.
    private static final double SIGNATURE_APPROVER_SLOT_CENTER_FRACTION =
        (SIG_APPROVER_INDEX + 0.5) / SIG_LABELS.length;
    // AWT's unscaled Graphics2D#getFontRenderContext() measures glyph advances at ~72 DPI (1 unit =
    // 1/72in), while POI's Sheet#getColumnWidthInPixels — the other pixel figure this class works
    // in — assumes 96 DPI screen pixels (its own javadoc says so). Scale every AWT measurement up so
    // both share one space.
    private static final double AWT_FONT_METRICS_TO_96DPI_PIXELS = 96.0 / 72.0;
    // Thai combining marks (tone marks + vowel signs that stack on the preceding base character) —
    // used ONLY by the {@link #textUnits} fallback below, for the rare case AWT's own font metrics
    // throw (e.g. a headless box that can't construct a Graphics2D at all) and this class falls back
    // to a simpler character-counting estimate instead of real pixel measurement. Ranges: U+0E31
    // (mai han-akat), U+0E34-U+0E3A (vowel signs I/II/UE/UEE/U/UU + phinthu), U+0E47-U+0E4E (mai
    // taikhu + the four tone marks + thanthakhat + nikhahit).
    private static final java.util.regex.Pattern THAI_COMBINING_MARK =
        java.util.regex.Pattern.compile("[ัิ-ฺ็-๎]");
    // Used only if AWT font metrics are unavailable at all (see #textUnits above) — a mid-size-
    // Thai-glyph-at-14pt guess, so the row still lays out something reasonable rather than throwing.
    private static final double FALLBACK_AVG_CHAR_WIDTH_PX = 6.5;

    /** The label row's real AWT font (resolved once per render off the template's own style) plus a
     * ready {@link java.awt.font.FontRenderContext}, threaded through the S1/S2/S3 pixel-measurement
     * helpers below so each only constructs the {@code Font}/{@code Graphics2D} once rather than per
     * call. */
    private record SignatureFontMetrics(java.awt.Font awtFont, java.awt.font.FontRenderContext frc) {
        static final SignatureFontMetrics UNAVAILABLE = new SignatureFontMetrics(null, null);

        boolean isAvailable() {
            return awtFont != null && frc != null;
        }
    }

    /**
     * v2 only ({@code signatureLabelsV2}): rebuilds the signature block as S0 (ตกลงสั่งซื้อ…,
     * right-aligned over the right edge), S1 (label+underscores), S2 ("(name)" or a dotted
     * placeholder, centred per slot), S3 (วันที่…., centred per slot) — replacing the template's
     * original one-cell-per-row text (blanked first). S1-S3 are each ONE merged A:I cell built from
     * four equal-PIXEL-width slots (see the class comment above {@link #SIG_LABELS}), left-aligned
     * with NO shrink-to-fit — unlike S0, these rows are sized to already fit at the template's
     * normal font, so shrinking would only make them harder to read for no reason. Legacy renders
     * ({@code signatureLabelsV2 = false}) never call into this — the template's original single-cell
     * labels and blank names stand untouched, matching QuotationRendererTest's "พนักงานขาย"
     * assertion.
     */
    private void writeSignatureBlock(Sheet sh, Signatories sig, boolean v2, int delta, int footerShift) {
        if (!v2) {
            return;
        }
        int orderLineRow = ORDER_LINE_ROW + footerShift + delta;
        int labelsRow = LABELS_ROW + footerShift + delta;
        int nameRow = SALESPERSON_FORMULA_ROW + footerShift + delta;
        int dateRow = DATE_ROW + footerShift + delta;

        // Resolve the label row's real font BEFORE #clearCell/#writeFixedWidthRow touch anything —
        // clearCell only blanks the VALUE and keeps the style, but reading it first is clearer intent.
        SignatureFontMetrics fontMetrics = resolveSignatureFontMetrics(sh, labelsRow);

        // Blank the template's original one-cell-per-row strings before laying the new ranges —
        // #getOrKeep on a merged range's non-top-left cell would otherwise leave stray leftover
        // text peeking out from under a narrower new merge.
        for (int c = 0; c <= 8; c++) {
            clearCell(sh, orderLineRow, c);
            clearCell(sh, labelsRow, c);
            clearCell(sh, nameRow, c);
            clearCell(sh, dateRow, c);
        }

        // layout-spec §5 asks for this right-aligned "over the ผู้สั่งซื้อ column" — but H:I alone
        // (≈154px) is far narrower than the ~40-character sentence needs, and a MERGED cell clips
        // overflow instead of spilling into empty neighbours the way a plain cell would. Widen the
        // merge leftward to C:I: still ends flush at the same right edge (right-aligned), just
        // with enough room to actually hold the whole sentence without clipping its own start.
        mergeIfAbsent(sh, orderLineRow, orderLineRow, 2, 8);
        setRightAligned(sh, orderLineRow, 2, "ตกลงสั่งซื้อสินค้าตามราคาและเงื่อนไขข้างต้น");

        // The real PIXEL width of each of the four equal slots, measured ONCE per render.
        double totalWidthPx = totalColumnWidthPixels(sh, 0, 8);
        double slotWidthPx = totalWidthPx * SIGNATURE_ROW_FILL_FRACTION / SIG_LABELS.length;
        double underscoreWidthPx = charRunWidthPx(fontMetrics, '_');
        double spaceWidthPx = charRunWidthPx(fontMetrics, ' ');

        String[] names = {
            sig != null ? sig.printedBy() : null,
            sig != null ? sig.checkedBy() : null,
            sig != null ? sig.approvedBy() : null,
            null, // ผู้สั่งซื้อ — the customer, never has a name on our side; always the placeholder
        };

        mergeIfAbsent(sh, labelsRow, labelsRow, 0, 8);
        mergeIfAbsent(sh, nameRow, nameRow, 0, 8);
        mergeIfAbsent(sh, dateRow, dateRow, 0, 8);

        StringBuilder labelsLine = new StringBuilder();
        StringBuilder namesLine = new StringBuilder();
        StringBuilder datesLine = new StringBuilder();
        for (int i = 0; i < SIG_LABELS.length; i++) {
            labelsLine.append(padLabelSlotPx(fontMetrics, SIG_LABELS[i], slotWidthPx, underscoreWidthPx));

            String name = names[i];
            String nameText = name != null && !name.isBlank() ? "(" + name.trim() + ")" : BLANK_NAME_PLACEHOLDER;
            namesLine.append(centerInSlotPx(fontMetrics, nameText, slotWidthPx, spaceWidthPx));

            datesLine.append(centerInSlotPx(fontMetrics, "วันที่........./........./.........",
                slotWidthPx, spaceWidthPx));
        }
        writeFixedWidthRow(sh, labelsRow, labelsLine.toString());
        writeFixedWidthRow(sh, nameRow, namesLine.toString());
        writeFixedWidthRow(sh, dateRow, datesLine.toString());

        if (sig != null && sig.approverSignaturePng() != null) {
            anchorApproverSignature(sh, labelsRow, sig.approverSignaturePng(), sig.approverSignatureMime());
        }
    }

    /** Resolves the label row's OWN template font (before this render overwrites its value) as an
     * AWT {@code Font} + {@code FontRenderContext} pair for the pixel-measurement helpers below.
     * Returns {@link SignatureFontMetrics#UNAVAILABLE} if AWT can't construct one at all (never
     * lets a font-metrics failure break the render — matching this class's convention elsewhere,
     * see {@link #anchorApproverSignature}); every caller of an unavailable metrics object falls
     * back to {@link #textUnits} character-counting via {@link #FALLBACK_AVG_CHAR_WIDTH_PX}. */
    private SignatureFontMetrics resolveSignatureFontMetrics(Sheet sh, int labelsRow) {
        try {
            Cell probe = getOrKeep(sh, labelsRow, 0);
            Font poiFont = sh.getWorkbook().getFontAt(probe.getCellStyle().getFontIndexAsInt());
            int awtStyle = (poiFont.getBold() ? java.awt.Font.BOLD : 0)
                | (poiFont.getItalic() ? java.awt.Font.ITALIC : 0);
            java.awt.Font awtFont = new java.awt.Font(poiFont.getFontName(),
                awtStyle == 0 ? java.awt.Font.PLAIN : awtStyle, (int) poiFont.getFontHeightInPoints());
            java.awt.image.BufferedImage probeImg =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.font.FontRenderContext frc = probeImg.createGraphics().getFontRenderContext();
            return new SignatureFontMetrics(awtFont, frc);
        } catch (RuntimeException e) {
            log.debug("Signature label font metrics unavailable, falling back to char-count estimate: {}",
                e.getMessage());
            return SignatureFontMetrics.UNAVAILABLE;
        }
    }

    /** The real 96dpi-pixel width of {@code text} rendered in the label row's own font — the
     * layout-spec §5-suggested "better" alternative to character counting. Falls back to {@link
     * #textUnits}{@code (text) * }{@link #FALLBACK_AVG_CHAR_WIDTH_PX} when {@code metrics} is
     * {@link SignatureFontMetrics#UNAVAILABLE}. */
    private double textWidthPx(SignatureFontMetrics metrics, String text) {
        if (text == null || text.isEmpty()) return 0;
        if (!metrics.isAvailable()) return textUnits(text) * FALLBACK_AVG_CHAR_WIDTH_PX;
        return metrics.awtFont().getStringBounds(text, metrics.frc()).getWidth() * AWT_FONT_METRICS_TO_96DPI_PIXELS;
    }

    /** The real 96dpi-pixel width of ONE {@code c}, measured over a 10-character run and divided
     * down — reduces per-glyph rounding/kerning noise versus measuring a single character alone. */
    private double charRunWidthPx(SignatureFontMetrics metrics, char c) {
        return textWidthPx(metrics, String.valueOf(c).repeat(10)) / 10.0;
    }

    /** Counts Thai-combining-mark-aware "character units" (every code point counts as 1 EXCEPT a
     * {@link #THAI_COMBINING_MARK}, which stacks on the previous base character and costs 0) — the
     * layout-spec §5 fallback estimate, used only when {@link #textWidthPx} can't get real AWT font
     * metrics. */
    private int textUnits(String s) {
        if (s == null || s.isEmpty()) return 0;
        int units = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!THAI_COMBINING_MARK.matcher(new String(Character.toChars(cp))).matches()) units++;
        }
        return units;
    }

    /** S1's left/underscore padding: appends {@code label} + enough '_' (at {@code underscorePx}
     * each) to land as close as possible to {@code slotWidthPx} without exceeding it. */
    private String padLabelSlotPx(SignatureFontMetrics metrics, String label, double slotWidthPx,
            double underscorePx) {
        double labelPx = textWidthPx(metrics, label);
        double remainingPx = Math.max(0, slotWidthPx - labelPx);
        int underscoreCount = underscorePx > 0 ? (int) Math.round(remainingPx / underscorePx) : 0;
        return label + "_".repeat(Math.max(0, underscoreCount));
    }

    /** S2/S3's centring: space-pads {@code text} on both sides (at {@code spacePx} each) to land as
     * close as possible to {@code slotWidthPx} — the proportional-font equivalent of Excel's own
     * CENTER alignment, computed by hand because all four slots live in ONE cell (a real per-cell
     * alignment would centre the whole concatenated string, not each slot within it). Approximate in
     * a proportional font — acceptable per layout-spec §5 — but measuring each row's OWN padding
     * character at the SAME font keeps the four slots landing at consistent physical x positions
     * across all three rows (see the class comment above {@link #SIG_LABELS}). */
    private String centerInSlotPx(SignatureFontMetrics metrics, String text, double slotWidthPx, double spacePx) {
        double textPx = textWidthPx(metrics, text);
        double padPx = Math.max(0, slotWidthPx - textPx);
        int leftCount = spacePx > 0 ? (int) Math.round(padPx / 2 / spacePx) : 0;
        int rightCount = spacePx > 0 ? (int) Math.round((padPx - leftCount * spacePx) / spacePx) : 0;
        return " ".repeat(Math.max(0, leftCount)) + text + " ".repeat(Math.max(0, rightCount));
    }

    /** Writes one already-fully-padded S1/S2/S3 string into its merged A:I cell — LEFT aligned (the
     * per-slot centring/padding above already did the visual alignment work; a cell-level CENTER
     * would re-centre the whole concatenated string instead), no wrap, and deliberately NO
     * shrink-to-fit (layout-spec §5: these rows are sized to already fit at the template's normal
     * font, unlike S0 above). */
    private void writeFixedWidthRow(Sheet sh, int row, String text) {
        Cell cell = getOrKeep(sh, row, 0);
        cell.setCellValue(text);
        CellStyle style = sh.getWorkbook().createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT);
        style.setWrapText(false);
        style.setShrinkToFit(false);
        cell.setCellStyle(style);
    }

    private void setCentered(Sheet sh, int row, int col, String text) {
        Cell cell = getOrKeep(sh, row, col);
        cell.setCellValue(text);
        CellStyle style = sh.getWorkbook().createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
        // ผู้ตรวจ's C:D range is only ≈94px (จำนวน/หน่วย were never meant to hold prose) — too
        // narrow for "(name)" or "วันที่…." at the sheet's normal font, and a MERGED cell clips
        // overflow rather than spilling into its (also occupied, on this row) neighbours.
        // ShrinkToFit is Excel/LO's own built-in answer to exactly this: auto-shrink the FONT to
        // the cell's real width rather than clip text or force layout changes for one narrow range.
        style.setShrinkToFit(true);
        cell.setCellStyle(style);
    }

    private void setRightAligned(Sheet sh, int row, int col, String text) {
        Cell cell = getOrKeep(sh, row, col);
        cell.setCellValue(text);
        CellStyle style = sh.getWorkbook().createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
        style.setShrinkToFit(true); // second line of defense against an unusually long line
        cell.setCellStyle(style);
    }

    /**
     * Anchors the approver's signature image over the ผู้อนุมัติ slot's underscores using HSSF
     * drawing, reusing the template's EXISTING drawing patriarch (it already carries the
     * letterhead/cert images — creating a fresh one is the known POI corruption risk this
     * deliberately avoids). Spans rows {@code labelsRow-2..labelsRow} — i.e. sitting ON the
     * underscore line, extending upward above it. Any failure here is swallowed and logged: a
     * broken image anchor must never break the whole render, so the dotted-placeholder/name text
     * {@link #writeSignatureBlock} already wrote stands on its own.
     *
     * <p>Since S1-S3 no longer have per-label column ranges (layout-spec §5 rebuild — see the
     * comment above {@link #SIG_LABELS}), the image is positioned purely by PIXEL FRACTION of the
     * whole A..I row width — {@link #SIGNATURE_APPROVER_SLOT_CENTER_FRACTION} — converted to a
     * column+dx anchor via {@link #absolutePixelToColumn}, independent of where the underlying
     * (very unevenly sized) column boundaries happen to fall. The initial box is centred on that
     * fraction at the target ~30mm width; {@link #scaleSignaturePicture} then fine-tunes the END
     * (col2/dx2) from the image's real aspect ratio, keeping this start point fixed.
     */
    private void anchorApproverSignature(Sheet sh, int labelsRow, byte[] png, String mime) {
        try {
            Drawing<?> patriarch = sh.getDrawingPatriarch();
            if (patriarch == null) {
                patriarch = sh.createDrawingPatriarch();
            }
            Workbook wb = sh.getWorkbook();
            int pictureType = mime != null && mime.toLowerCase(Locale.ROOT).contains("jpeg")
                ? Workbook.PICTURE_TYPE_JPEG : Workbook.PICTURE_TYPE_PNG;
            int pictureIdx = wb.addPicture(png, pictureType);

            double totalWidthPx = totalColumnWidthPixels(sh, 0, 8);
            double centerPx = totalWidthPx * SIGNATURE_APPROVER_SLOT_CENTER_FRACTION;
            double approxTargetWidthPx = SIGNATURE_TARGET_WIDTH_MM / MM_PER_INCH * ASSUMED_IMAGE_DPI;
            double leftPx = Math.max(0, centerPx - approxTargetWidthPx / 2);
            int[] start = absolutePixelToColumn(sh, leftPx, 0, 8);
            int[] endGuess = absolutePixelToColumn(sh, leftPx + approxTargetWidthPx, 0, 8);

            ClientAnchor anchor = patriarch.createAnchor(start[1], 0, endGuess[1], 0,
                start[0], Math.max(0, labelsRow - 2), endGuess[0], labelsRow);
            Picture picture = patriarch.createPicture(anchor, pictureIdx);
            scaleSignaturePicture(sh, picture);
        } catch (RuntimeException e) {
            log.warn("Approver signature image anchor failed; falling back to text-only name: {}", e.getMessage(), e);
        }
    }

    // M6: the signature was never scaled — an employee's uploaded image renders at whatever
    // pixel size they happened to save it at, which can dwarf or barely mark the ผู้อนุมัติ box.
    // Scale to a fixed real-world box (~30mm wide, capped at ~15mm tall), preserving aspect.
    private static final double SIGNATURE_TARGET_WIDTH_MM = 30.0;
    private static final double SIGNATURE_MAX_HEIGHT_MM = 15.0;
    private static final double MM_PER_INCH = 25.4;
    // Assumed image DPI for Picture#getImageDimension()'s pixel dimensions — POI/Excel's own
    // convention for a raster image with no embedded DPI metadata (PNG signature uploads here
    // are screen-captured or scanned at typical screen resolution, not print resolution).
    private static final double ASSUMED_IMAGE_DPI = 96.0;

    /**
     * Sets the anchor's END column/offset directly from a target PIXEL width, instead of calling
     * {@link Picture#resize(double, double)} — which walks the SAME kind of column-width math
     * internally, but starting from whatever column the anchor's `col1` happens to be, and threw
     * {@code IllegalArgumentException: col2 must be between 0 and 255} the moment that internal
     * walk needed to search past this sheet's own printable range. Computing the end point
     * ourselves — anchored at the SAME pixel origin the H2 fix already placed `col1`/`dx1` at,
     * using the identical {@link #absolutePixelToColumn} column-walk — keeps this in one
     * consistent, already-tested pixel model rather than a second (and here, broken) one inside
     * POI itself. Height (row2/dy2) is left as the caller's original anchor set it: the vertical
     * box already spans two template rows, comfortably more than {@link #SIGNATURE_MAX_HEIGHT_MM}
     * for the row heights this template uses, so the image (anchored at the top-left) never needs
     * the box grown taller — only ever narrower than the box, never wider than the page.
     */
    private void scaleSignaturePicture(Sheet sh, Picture picture) {
        java.awt.Dimension natural = picture.getImageDimension();
        if (natural.width <= 0 || natural.height <= 0) {
            return;
        }
        double naturalWidthMm = natural.width / ASSUMED_IMAGE_DPI * MM_PER_INCH;
        double naturalHeightMm = natural.height / ASSUMED_IMAGE_DPI * MM_PER_INCH;
        double scale = SIGNATURE_TARGET_WIDTH_MM / naturalWidthMm;
        if (naturalHeightMm * scale > SIGNATURE_MAX_HEIGHT_MM) {
            scale = SIGNATURE_MAX_HEIGHT_MM / naturalHeightMm;
        }
        ClientAnchor anchor = picture.getClientAnchor();
        double startPx = absoluteColumnPixel(sh, anchor.getCol1(), anchor.getDx1(), 0);
        double targetWidthPx = natural.width * scale;
        int[] end = absolutePixelToColumn(sh, startPx + targetWidthPx, 0, 8);
        anchor.setCol2(end[0]);
        anchor.setDx2(end[1]);
    }

    /** The absolute pixel offset of {@code col}'s left edge (relative to {@code firstCol}) plus
     * {@code dx1024}/1024 of that column's own width — the inverse of
     * {@link #absolutePixelToColumn}, so a {@link ClientAnchor}'s existing col1/dx1 can be
     * converted back to the same pixel space {@link #scaleSignaturePicture} computes a target
     * width in. */
    private double absoluteColumnPixel(Sheet sh, int col, int dx1024, int firstCol) {
        double cumulative = totalColumnWidthPixels(sh, firstCol, col - 1);
        double colWidth = sh.getColumnWidthInPixels(col);
        return cumulative + (dx1024 / 1024.0) * colWidth;
    }

    /** The lower-level primitive {@link #scaleSignaturePicture} builds on — takes an ABSOLUTE
     * pixel offset (relative to {@code firstCol}'s left edge) and returns
     * {@code [columnIndex, dxUnits]} (dx in HSSF's 1/1024-of-that-column's-own-width units), so a
     * {@link ClientAnchor} can start or end mid-column instead of only ever landing on a whole
     * column boundary. A target past the last column's right edge clamps
     * to {@code lastCol} at its full width (dx 1023) rather than searching further — the caller
     * must never be handed a column outside the sheet's own printable range. */
    private int[] absolutePixelToColumn(Sheet sh, double target, int firstCol, int lastCol) {
        double cumulative = 0;
        for (int c = firstCol; c <= lastCol; c++) {
            double colWidth = sh.getColumnWidthInPixels(c);
            if (target <= cumulative + colWidth || c == lastCol) {
                double within = Math.max(0, target - cumulative);
                int dx = colWidth > 0 ? (int) Math.round(Math.min(1023, (within / colWidth) * 1024)) : 0;
                return new int[]{c, dx};
            }
            cumulative += colWidth;
        }
        return new int[]{lastCol, 0};
    }

    private double totalColumnWidthPixels(Sheet sh, int firstCol, int lastCol) {
        double total = 0;
        for (int c = firstCol; c <= lastCol; c++) {
            total += sh.getColumnWidthInPixels(c);
        }
        return total;
    }

    // ── dynamic-layout geometry & sizing ──────────────────────────────────────────

    // ① Size each money column to the widest number it will actually show, so values never clip to
    //    "###" whatever the magnitude. E/H (unit price, net) size to the largest unit price; I
    //    (amount) sizes to the grand total — the biggest number on the document.
    private void sizeMoneyColumns(Sheet sh, List<RenderItem> items, BigDecimal grandTotal) {
        BigDecimal maxUnit = BigDecimal.ZERO;
        for (RenderItem it : items) {
            if (it.unitPrice() != null && it.unitPrice().compareTo(maxUnit) > 0) maxUnit = it.unitPrice();
            if (it.netUnitPrice() != null && it.netUnitPrice().compareTo(maxUnit) > 0) maxUnit = it.netUnitPrice();
        }
        fitColumnToNumber(sh, 4, maxUnit);     // E ราคา (unit price)
        fitColumnToNumber(sh, 7, maxUnit);     // H คงเหลือ (net)
        fitColumnToNumber(sh, 8, grandTotal);  // I เป็นเงิน / totals
    }

    private void fitColumnToNumber(Sheet sh, int col, BigDecimal value) {
        int chars = String.format(Locale.US, "%,.2f", value).length();
        int needed = (chars + MONEY_PAD_CHARS) * MONEY_UNITS_PER_CHAR;
        if (needed > sh.getColumnWidth(col)) sh.setColumnWidth(col, needed);
    }


    // ② Natural width scale — the scale at which the A..I columns just fit the printable page width
    //    (≈0.86 with the real fonts; recomputes automatically after ① widens a column).
    private double naturalWidthScale(Sheet sh) {
        double columnsW = 0;
        for (int c = 0; c <= 8; c++) columnsW += sh.getColumnWidthInPixels(c) * 72.0 / 96.0; // px → pt
        double printableW = A4_WIDTH_PT
            - (sh.getMargin(PageMargin.LEFT) + sh.getMargin(PageMargin.RIGHT)) * 72.0;
        return columnsW <= 0 ? 1.0 : Math.min(1.0, printableW / columnsW);
    }

    // ③ Scale at which the whole quote (letterhead + emittedRows + footer) fits on one page — the
    //    min of the width scale and the height-fit scale.
    private double onePageScale(Sheet sh, int emittedRows, int footerShift) {
        double contentH = sumRowHeights(sh, 0, ITEM_START_ROW - 1)      // letterhead + column titles
                        + emittedRows * itemRowHeight(sh)                // items
                        + sumRowHeights(sh, FOOTER_START, FOOTER_END + footerShift); // notes + totals + signature
        return Math.min(naturalWidthScale(sh), printableHeightPt(sh) / contentH);
    }

    private double printableHeightPt(Sheet sh) {
        return A4_HEIGHT_PT - (sh.getMargin(PageMargin.TOP) + sh.getMargin(PageMargin.BOTTOM)) * 72.0;
    }

    /**
     * layout-spec §6 (paginated case): "NEVER split an item's lines, the remark block, or the
     * signature block across pages" — natural pagination (repeating letterhead, {@link
     * #fitToWidthPaginate}'s FitHeight=0) breaks strictly on absolute row height vs the printable
     * page height, with no awareness of a "logical block" spanning several rows, so a heading+item
     * (or the whole relocated footer) can straddle a page boundary exactly where the printable
     * height happens to run out — the 12-item pagination fixture shows this precisely (item 8's
     * description split across pages 1/2). Walks the SAME item/wrap accounting {@link #fillItems}
     * already did to write the rows, tracking how much of the CURRENT page's budget is used, and
     * inserts an explicit {@link Sheet#setRowBreak} right before any block (heading+item, or the
     * footer) that would otherwise straddle — moving the WHOLE block to the next page instead.
     * Only ever called from the genuinely-multi-page branch: the single-page branches never risk a
     * mid-block split in the first place (everything fits one page by construction).
     */
    private void insertNoSplitPageBreaks(Sheet sh, List<RenderItem> items, boolean alwaysShowSeq,
                                          int footerStartRow, double footerHeight) {
        double capacity = printableHeightPt(sh) - sumRowHeights(sh, 0, ITEM_START_ROW - 1);
        double rowH = itemRowHeight(sh);
        double usedOnPage = 0;
        int r = ITEM_START_ROW;
        String prevLabel = null;
        boolean prevSet = false;
        for (RenderItem item : items) {
            boolean heading = headingNeeded(item.headingLabel(), prevLabel, prevSet);
            prevLabel = item.headingLabel();
            prevSet = true;
            List<String> lines = alwaysShowSeq ? wrapDescriptionLines(item.descriptionLines()) : item.descriptionLines();
            int blockRows = (heading ? 1 : 0) + Math.max(1, lines.size());
            double blockHeight = blockRows * rowH;
            if (usedOnPage > 0 && usedOnPage + blockHeight > capacity) {
                sh.setRowBreak(r - 1);
                usedOnPage = 0;
            }
            usedOnPage += blockHeight;
            r += blockRows;
        }
        if (usedOnPage > 0 && usedOnPage + footerHeight > capacity) {
            sh.setRowBreak(footerStartRow - 1);
        }
    }

    // Consistent page margins (inches) on every layout so content has corner padding and every page
    // lines up the same way. Applied before the scale equations so they account for the padding.
    private void applyMargins(Sheet sh) {
        sh.setMargin(PageMargin.LEFT, 0.3);
        sh.setMargin(PageMargin.RIGHT, 0.3);
        sh.setMargin(PageMargin.TOP, 0.3);
        sh.setMargin(PageMargin.BOTTOM, 0.4);   // extra room for the "หน้า X/Y" footer
    }

    private double itemRowHeight(Sheet sh) {
        Row proto = sh.getRow(ITEM_STYLE_PROTO_ROW);
        return proto != null ? proto.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
    }

    private double sumRowHeights(Sheet sh, int from, int to) {
        double s = 0;
        for (int r = from; r <= to; r++) {
            Row row = sh.getRow(r);
            s += row != null ? row.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
        }
        return s;
    }

    // Single page: fit the whole quote onto exactly one page (1 wide × 1 tall), like Excel's
    // "fit to 1×1" print setting on the template. With the correct narrow Thai fonts installed this
    // lands at ≈ the template's native 86% scale; LibreOffice shrinks a hair more if needed so the
    // anchored footer never spills to a 2nd page.
    // Single page, content ≤ the template's native item-zone row budget: LibreOffice's own
    // FitToPage=1×1 auto-calc only ever has to shrink a MILD amount here (the template's native
    // ~86%, per this class's own long-standing comment) and is proven reliable at that range by
    // every renderSinglePage-path test in this suite.
    private void fitToOnePage(Sheet sh) {
        sh.setFitToPage(true);
        sh.setAutobreaks(true);
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(false);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 1);
    }

    // H4/layout-spec §6: the flowing-layout branch can need a MUCH more aggressive shrink (the
    // 5-item reference with wrapped calc lines computes ≈60%) — and LibreOffice's headless PDF
    // conversion of a legacy HSSF FitToPage=1×1 hint does NOT reliably auto-calculate a scale that
    // low: it silently TRUNCATES the sheet at the natural one-page boundary instead of shrinking
    // (confirmed by rendering this exact fixture — เงื่อนไข/totals/signature simply vanished, with
    // no error and a "1 page" PDF). An EXPLICIT print `scale` percentage is a different, much
    // simpler LibreOffice code path (no auto-fit search) and reliably shrinks text+geometry
    // together at any percentage — so the flowing branch computes its own required scale
    // (#onePageScale) and sets it directly here rather than delegating the arithmetic to LO.
    private void fitToOnePageAtScale(Sheet sh, double scalePercent) {
        sh.setFitToPage(false);
        sh.setAutobreaks(true);
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(false);
        ps.setScale((short) Math.round(scalePercent));
    }

    // Multi-page: fit columns to one page wide (never spill columns onto a 2nd horizontal page) but
    // leave the height unconstrained (FitHeight=0) so items paginate down across as many pages as
    // needed.
    private void fitToWidthPaginate(Sheet sh) {
        sh.setFitToPage(true);
        sh.setAutobreaks(true);
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(false);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 0);
    }

    // ── footer block-move (flow layout only) ────────────────────────────────────
    private record CellRec(int rowOff, int col, CellStyle style, CellType type, String s, double d) {}

    private List<CellRec> captureBlock(Sheet sh, int start, int end) {
        List<CellRec> out = new ArrayList<>();
        for (int r = start; r <= end; r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                CellType t = c.getCellType();
                out.add(new CellRec(r - start, c.getColumnIndex(), c.getCellStyle(), t,
                    t == CellType.STRING ? c.getStringCellValue() : null,
                    t == CellType.NUMERIC ? c.getNumericCellValue() : 0));
            }
        }
        return out;
    }

    private float[] captureHeights(Sheet sh, int start, int end) {
        float[] h = new float[end - start + 1];
        for (int r = start; r <= end; r++) {
            Row row = sh.getRow(r);
            h[r - start] = row != null ? row.getHeightInPoints() : -1f;
        }
        return h;
    }

    private List<int[]> captureMerges(Sheet sh, int start, int end) {
        List<int[]> out = new ArrayList<>();
        for (int i = sh.getNumMergedRegions() - 1; i >= 0; i--) {
            CellRangeAddress m = sh.getMergedRegion(i);
            if (m.getFirstRow() >= start && m.getLastRow() <= end) {
                out.add(new int[]{m.getFirstRow(), m.getLastRow(), m.getFirstColumn(), m.getLastColumn()});
                sh.removeMergedRegion(i);
            }
        }
        return out;
    }

    private void clearBlock(Sheet sh, int start, int end) {
        for (int r = start; r <= end; r++) {
            Row row = sh.getRow(r);
            if (row != null) sh.removeRow(row);
        }
    }

    private void placeBlock(Sheet sh, List<CellRec> recs, int delta) {
        for (CellRec rec : recs) {
            Cell c = getOrKeep(sh, rec.rowOff() + FOOTER_START + delta, rec.col());
            c.setCellStyle(rec.style());
            switch (rec.type()) {
                case STRING -> c.setCellValue(rec.s());
                case NUMERIC -> c.setCellValue(rec.d());
                default -> { /* formulas (VAT/total/lookup) are rewritten as values by the caller */ }
            }
        }
    }

    private void applyHeights(Sheet sh, float[] h, int destStart) {
        for (int i = 0; i < h.length; i++) {
            if (h[i] > 0) {
                Row row = sh.getRow(destStart + i);
                if (row == null) row = sh.createRow(destStart + i);
                row.setHeightInPoints(h[i]);
            }
        }
    }

    private void cloneRowStyle(Sheet sh, Row proto, int r) {
        Row row = sh.getRow(r);
        if (row == null) row = sh.createRow(r);
        row.setHeightInPoints(proto.getHeightInPoints());
        for (int col = 0; col <= 10; col++) {
            Cell pc = proto.getCell(col);
            if (pc != null) getOrKeep(sh, r, col).setCellStyle(pc.getCellStyle());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    // Size values reach this renderer in TWO shapes, and only one of them wants a unit appended:
    //   - bare numerics from the catalog import (price_catalog "FORMATO" -> size_raw, e.g.
    //     "600x1200", "598X598X18") -- these need the " cm." suffix to read as a measurement;
    //   - values that ALREADY carry their own unit, because that is literally what the product
    //     asks for: TicketDetailPage's ขนาด field placeholder is "เช่น 60x60 ซม.", and the seeded
    //     sales data follows it ("60x60 ซม.", "60x120 cm").
    // Appending " cm." unconditionally printed the second shape as "ขนาด 60x60 ซม. cm." on a
    // customer-facing quotation. That was latent for the legacy flow and went live for Step 4
    // the moment V162 started populating these columns -- see
    // QuotationRendererTest#descriptionRendersAllAttributes_andSuppliesTheSizeUnitOnlyWhenItIsMissing.
    // Anchored at the end of the trimmed value, so a bare "600x1200" (ends in a digit) can never
    // match and still gets its unit.
    private static final java.util.regex.Pattern SIZE_ALREADY_HAS_UNIT =
        java.util.regex.Pattern.compile("(?i)(ซม\\.?|มม\\.?|ม\\.?|นิ้ว|cm\\.?|mm\\.?|m\\.?|in\\.?|\")\\s*$");

    private String buildDesc(TicketItemDto item) {
        StringBuilder sb = new StringBuilder("กระเบื้อง");
        if (item.model() != null && !item.model().isBlank())   sb.append(" รุ่น ").append(item.model());
        if (item.color() != null && !item.color().isBlank())   sb.append(" สี ").append(item.color());
        if (item.size() != null && !item.size().isBlank()) {
            sb.append(" ขนาด ").append(item.size());
            if (!SIZE_ALREADY_HAS_UNIT.matcher(item.size().trim()).find()) sb.append(" cm.");
        }
        if (item.texture() != null && !item.texture().isBlank()) sb.append(" ").append(item.texture());
        return sb.toString();
    }

    // Preserve template cell style — get existing cell, only create if absent
    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        // Drop any template formula (e.g. B4 =TODAY()) so our literal value wins;
        // otherwise POI keeps the formula and LibreOffice recalculates + reformats it.
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        cell.setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        cell.setCellValue(value);
    }

    private double orZero(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    // Blank a cell's content but keep its style (so template borders/formatting stay).
    private void clearCell(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) return;
        Cell cell = row.getCell(colIdx);
        if (cell != null) cell.setBlank();
    }

    private Cell getOrKeep(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        return cell;
    }

    // Adds a merged region unless one already covers this exact range — a render can run twice
    // through the same renderer instance (see #underlinedStyle's own H1 Javadoc for why), and a
    // duplicate addMergedRegion on a re-render throws.
    private void mergeIfAbsent(Sheet sh, int firstRow, int lastRow, int firstCol, int lastCol) {
        for (int i = 0; i < sh.getNumMergedRegions(); i++) {
            CellRangeAddress m = sh.getMergedRegion(i);
            if (m.getFirstRow() == firstRow && m.getLastRow() == lastRow
                && m.getFirstColumn() == firstCol && m.getLastColumn() == lastCol) {
                return;
            }
        }
        sh.addMergedRegion(new CellRangeAddress(firstRow, lastRow, firstCol, lastCol));
    }

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    // LOW: zero-pad dd/mm ("01/09/2569", not "1/9/2569") -- a bare single-digit day/month read as
    // a typo on a customer-facing document next to the fully-padded thaiDate() above.
    private String shortThaiDate(LocalDate d) {
        if (d == null) return "";
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }
}
