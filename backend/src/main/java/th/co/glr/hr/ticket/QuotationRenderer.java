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
import th.co.glr.hr.common.sheet.FontResolver;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.ticket.QuotationRenderModel.ItemPicture;
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
//
// Quotation v2 (2026-09-10): toXls/toXlsx ALWAYS render through this POI/XLS template (both
// paths, XLS output unchanged). toPdf(QuotationRenderModel) — the direct-deal path — prints that
// SAME sheet through one of two engines selected by app.quotation.pdf-renderer: LibreOffice
// (default) or headless Chromium drawing the sheet as HTML (QuotationHtmlDocument /
// th.co.glr.hr.common.sheet.SheetHtmlRenderer). toPdf(TicketDto, QuotationDto, CustomerDto) — the
// legacy/PCR path — is untouched: XLS through LibreOffice. See toPdf(QuotationRenderModel).
@Component
public class QuotationRenderer {
    private static final Logger log = LoggerFactory.getLogger(QuotationRenderer.class);

    // Real company XLS template — sheet "Update"
    private static final String TEMPLATE = "templates/quotation_template.xls";

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // ── quotation v3b (2026-09-11): the ENGLISH document, form F-SM-008 (01) ──────────────────
    //
    // ⚠️ THERE IS NO F-SM-008 TEMPLATE FILE. templates/ holds exactly one quotation template —
    // quotation_template.xls, the Thai F-SM-002 (03) — and this class works by FILLING it. The
    // English document is produced by driving that SAME workbook with English labels, an English
    // footer and no VAT row: the two forms are both 8 columns with the same shape, so the row/
    // column map is unchanged and only the text differs. That makes this MODELLED ON the owner's
    // PDF samples (QN6900902-6, QN6900933), NOT rendered from the real form — do not read it as
    // byte-fidelity with a file nobody has. Swapping in a genuine F-SM-008.xls later is confined
    // to #TEMPLATE and the row constants above.
    private static final String EN_TITLE = "QUOTATION";
    // The 8 column headings, in the template's own column order (see #TITLE_ROW's dump):
    // A ลำดับ, B รายละเอียด (BLANK in the Thai template — the English form labels it), C จำนวน,
    // D หน่วย, E ราคา, F..G ส่วนลด (a merged pair), H คงเหลือ, I เป็นเงิน.
    /** B1 on an English document — see #writeEnglishHeaderLabels for why this exact spelling. */
    static final String EN_COMPANY_NAME = "G.L.&R. TAPS AND TILES COMPANY LIMITED";
    /** B2 on an English document — must fit before the badge image; see #writeEnglishHeaderLabels. */
    static final String EN_COMPANY_ADDRESS =
        "201 Sukhumvit 63, Sukhumvit Road, North-Klongton, Wattana, Bangkok 10110";
    private static final String EN_COL_ITEMS = "Items";
    private static final String EN_COL_DESCRIPTION = "Description & Conditions";
    private static final String EN_COL_QTY = "Qty";
    private static final String EN_COL_UNIT = "Unit";
    private static final String EN_COL_UNIT_PRICE = "Unit price";
    private static final String EN_COL_DISCOUNT = "Disc.";
    private static final String EN_COL_NET_PRICE = "Net price";
    private static final String EN_COL_AMOUNT_PREFIX = "Amount";
    private static final String EN_LABEL_DEPT = "Dept.";
    private static final String EN_LABEL_REF = "Ref.";
    // ⚠️ ASK-THE-OWNER: QN6900902-6 prints "D.Co." here and QN6900933 prints "D.Name" — the same
    // slot, two labels. "D.Co." is used because it appears on the more recent of the two samples
    // and is the closer reading of หน่วยงาน (the company/unit the enquiry came through). Flagged
    // in the PR; changing it is this one constant.
    private static final String EN_LABEL_UNIT_CODE = "D.Co.";
    private static final String EN_LABEL_DATE = "Date";
    private static final String EN_LABEL_ATTN = "Attn :";
    private static final String EN_REMARKS_LABEL = "Remarks";
    private static final String EN_GRAND_TOTAL_PREFIX = "Grand Total";
    private static final String EN_ORDER_LINE = "Confirmed to order at the prices and conditions above";
    private static final String[] EN_SIG_LABELS = {"Printed by", "Quoted by", "Approved by", "Ordered by"};
    private static final String EN_BLANK_DATE_PLACEHOLDER = "Date ........./........./.........";
    // ⚠️ ASK-THE-OWNER: the Thai render BLANKS the F-SM-002 tag ("not customer-facing", a decision
    // that predates this change and is left exactly as it is). Her English samples DO print
    // "F-SM-008 (01)", and the spec asks for it, so the English render writes it. The two paths
    // therefore differ on this cell on purpose. Flagged in the PR.
    private static final String EN_FORM_TAG = "F-SM-008 (01)";

    // Template layout per document-generation-fix.md §A2-A3
    // The template's item zone is 0-based rows 9–20; the first item row (A10) starts the table.
    private static final int ITEM_START_ROW = 9; // 0-based (= row 10 in 1-based)
    private static final int TITLE_ROW = 6;      // 0-based: ลำดับ … เป็นเงิน (บาท), last of the repeated rows A1:I7

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
    // html-fidelity-spec §8 (owner, 2026-09-10): the remark block is its OWN closed box inside the
    // form — "หมายเหตุ :" (FOOTER_START) plus lines 1–8 — spanning columns B..H: a top rule B..H
    // directly under the last item line, the A|B separator as its left edge, the H|I separator as
    // its right edge, no interior column separators, closed at the bottom by the form's own A..I
    // rule under line 8 (#compactRemarksSection). ลำดับ (A) and เป็นเงิน (I) keep their own
    // verticals down to that rule, reading as open columns beside the box. So every remark row —
    // the label row too — is ONE merged B..H cell, not B..I: a merge ending at I swallowed the H|I
    // separator (a merged region draws no interior edge), which is exactly what the owner's crop
    // rejected. See #openRemarkBox.
    private static final int REMARK_BOX_FIRST_COL = LABEL_VALUE_COL; // B
    private static final int REMARK_BOX_LAST_COL = 7;                // H
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
            boolean english = model.isEnglish();
            String currency = model.currencyCode();

            Cell titleCell = getOrKeep(sh, 0, 7);
            if (english) {
                setStr(sh, 0, 7, "        " + EN_TITLE);
            } else if (titleCell.getCellType() == CellType.STRING) {
                setStr(sh, 0, 7, "        " + titleCell.getStringCellValue().strip());
            }
            if (english) {
                // The company block, the right-hand labels and the 8 column headings. Kept in one
                // method so every English-only overwrite of a TEMPLATE-OWNED cell is in one place
                // and a reviewer can see the whole list at once. The "***" under the title is
                // already in the template at I2 and is common to both forms, so nothing writes it.
                writeEnglishHeaderLabels(sh);
                writeEnglishColumnTitles(sh, currency);
            }

            // ⚠️ B4 — the single easiest thing to get wrong on this form. The Thai document prints
            // "30 กรกฎาคม 2569" (Buddhist era); the English one prints "September 8, 2026" — an
            // English month NAME and a COMMON-era year. #englishDate and #thaiDate sit next to each
            // other at the bottom of this class for exactly that reason.
            setStr(sh, 3, 1, english ? englishDate(model.issueDate()) : thaiDate(model.issueDate()));
            setStr(sh, DEPT_VALUE_ROW, VALUE_COL, nullSafe(model.deptCode()));   // I3 — ฝ่าย / Dept.
            setStr(sh, NUMBER_VALUE_ROW, VALUE_COL, nullSafe(model.number()));   // I4 — เลขที่อ้างอิง / Ref.
            setStr(sh, UNIT_VALUE_ROW, VALUE_COL, nullSafe(model.unitCode()));   // I5 — หน่วยงาน / D.Co.
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
            // v3b: the ENGLISH form has NO VAT row at all — no sample shows one — so the VAT here
            // is ZERO and the grand total IS the subtotal.
            //
            // ⚠️ Scope, stated because a mutation check proved it: this LOCAL is not what removes
            // the VAT row. Its only consumer on the English path is #sizeMoneyColumns below, which
            // sizes column I to the biggest figure the page will show — with a 7% VAT folded in
            // that column comes out over-wide, which is cosmetic, not wrong. #applyEnglishTotals is
            // what physically clears the subtotal and VAT rows, and THAT is the guard the tests
            // kill (see DealQuotationEnglishFormTest#totals_*).
            BigDecimal vat = english
                ? BigDecimal.ZERO
                : subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);

            // ① Size the money columns to the actual numbers so nothing ever clips to "###",
            //    whatever the magnitude. Must run before the width scale below is measured.
            sizeMoneyColumns(sh, items, subtotal.add(vat));

            // Consistent page margins (corner padding) on every layout and every page — set before
            // the scale math below so the width/height equations account for them.
            applyMargins(sh);

            boolean alwaysShowSeq = model.signatureLabelsV2();
            // GLA-75: every item's printed lines AND its picture rows, decided ONCE here and read
            // by #countEmittedRows, #fillItems and #insertNoSplitPageBreaks alike, so the three
            // can never disagree about how many rows an item takes (they used to each re-derive
            // the wrap). Column B's width is final by now (#sizeMoneyColumns touches E/H/I only).
            List<ItemLayout> layouts = layoutItems(sh, items, alwaysShowSeq);
            int emittedRows = countEmittedRows(items, layouts);

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
                renderSinglePage(sh, items, layouts, subtotal, alwaysShowSeq, footerShift, english);
                delta = 0;
            } else if (onePageScale(sh, emittedRows, footerShift) >= MIN_SCALE) {
                double scale = onePageScale(sh, emittedRows, footerShift);
                delta = layoutFlowing(sh, items, layouts, subtotal, alwaysShowSeq, footerShift, english);
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
                delta = layoutFlowing(sh, items, layouts, subtotal, alwaysShowSeq, footerShift, english);
                // The box is closed at the bottom of every PAGE, not under the last item row: a
                // previous version bordered FOOTER_START + delta - 1 unconditionally, which is
                // right only when the footer moves to the next page (then that row IS a page
                // bottom) and is an interior rule between the last item and หมายเหตุ whenever the
                // footer stays on the same page — layout-spec §1 forbids interior rules.
                // #insertNoSplitPageBreaks knows exactly where the pages end (it sets every break),
                // so it closes each page fragment itself; the last page closes under remark line 8
                // (#compactRemarksSection). (Its older LOW-fix history — bordering through
                // SUBTOTAL_ROW painted a bordered stub at the top of the next page — still holds:
                // never stripe the remarks rows.)
                int idx = sh.getWorkbook().getSheetIndex(sh);
                sh.getWorkbook().setPrintArea(idx, 0, 8, 0, footerEnd + delta);
                sh.setRepeatingRows(CellRangeAddress.valueOf("A1:I7"));
                // The template's title row does not close itself: its ราคา cell (E7, 0-based
                // (6,4)) has top+left borders only, and on page 1 the rule under ราคา is drawn by
                // the Project row's (row 8) own TOP border. A repeated title row on page 2+ sits
                // on an item row instead, so that stretch of the title's bottom rule was simply
                // missing there (≈12 mm hole, both engines — caught by HtmlXlsFidelityTest's §7
                // continuity check). Give every title cell its own bottom rule; on page 1 it
                // coincides with row 8's top rule, so nothing is double-drawn.
                closeItemTableBorders(sh, TITLE_ROW, TITLE_ROW);
                sh.getFooter().setCenter("หน้า &P/&N");
                // layout-spec §6: "never split an item's lines, the remark block, or the signature
                // block across pages". LOW's own note here used to record a REVERTED attempt at
                // this (an unconditional break right before LABELS_ROW landed INSIDE the approver
                // picture's own row span, decoupling the image from its labels) — #insertNoSplitPageBreaks
                // fixes that by breaking before the WHOLE relocated footer block as one unit
                // (remarks+totals+signature together, never just the signature portion), and by
                // walking the SAME per-item row accounting #fillItems used, so an item's own
                // heading+lines never straddle either.
                insertNoSplitPageBreaks(sh, items, layouts, FOOTER_START + delta, footerEnd + delta);
                fitToWidthPaginate(sh);
            }

            if (english) {
                applyEnglishTotals(sh, footerShift + delta, subtotal, currency);
            }

            writeSignatureBlock(sh, model.signatories(), model.signatureLabelsV2(), delta, footerShift,
                english);

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

    /**
     * The direct-deal path's PDF ({@code DealQuotationService} is the only caller). Which engine
     * prints it is {@code app.quotation.pdf-renderer}:
     * <ul>
     *   <li>{@code xls} (default) — the POI template converted by {@link LibreOfficePdfConverter},
     *       exactly as before;</li>
     *   <li>{@code chromium} — the SAME POI sheet this method just rendered, drawn as HTML by
     *       {@link th.co.glr.hr.common.sheet.SheetHtmlRenderer} and printed by headless Chromium
     *       ({@link th.co.glr.hr.common.ChromiumPdfPrinter}). Fails loudly with 503 when no
     *       Chromium can be launched — never a silent fallback to the other engine.</li>
     * </ul>
     * Both engines consume one row plan: the workbook. See {@code QuotationHtmlDocument} and
     * {@code HtmlXlsFidelityTest} for the pixel/rule-level proof that the two renders agree.
     */
    public byte[] toPdf(QuotationRenderModel model) {
        byte[] xls = toXls(model);
        if (PDF_RENDERER_CHROMIUM.equalsIgnoreCase(pdfRenderer)) {
            if (!th.co.glr.hr.common.ChromiumPdfPrinter.isAvailable()) {
                throw new th.co.glr.hr.common.ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "ระบบสร้าง PDF ไม่พร้อมใช้งาน");
            }
            String html = th.co.glr.hr.dealquotation.QuotationHtmlDocument.render(xls, model);
            return th.co.glr.hr.common.ChromiumPdfPrinter.print(html);
        }
        return LibreOfficePdfConverter.convert(xls);
    }

    /** The HTML {@code toPdf} prints in {@code chromium} mode — exposed for the fidelity gate. */
    public String toHtml(QuotationRenderModel model) {
        return th.co.glr.hr.dealquotation.QuotationHtmlDocument.render(toXls(model), model);
    }

    public static final String PDF_RENDERER_XLS = "xls";
    public static final String PDF_RENDERER_CHROMIUM = "chromium";

    // app.quotation.pdf-renderer — see application.yml. A field initialiser rather than a
    // constructor argument so `new QuotationRenderer()` (tests, hand-wired services) keeps the
    // long-standing LibreOffice engine.
    @org.springframework.beans.factory.annotation.Value("${app.quotation.pdf-renderer:xls}")
    private String pdfRenderer = PDF_RENDERER_XLS;

    public void setPdfRenderer(String pdfRenderer) {
        this.pdfRenderer = pdfRenderer == null ? PDF_RENDERER_XLS : pdfRenderer;
    }

    public String getPdfRenderer() {
        return pdfRenderer;
    }

    // ── layout ──────────────────────────────────────────────────────────────────

    /**
     * ≤ {@link #NATIVE_ITEM_CAPACITY} emitted rows: fill from the top, one row each, and leave the
     * footer block (notes/totals/signature) at its native template rows so it sits anchored near the
     * page bottom — filling the page exactly like the Excel template's "Save as PDF".
     */
    private void renderSinglePage(Sheet sh, List<RenderItem> items, List<ItemLayout> layouts, BigDecimal subtotal,
                                   boolean alwaysShowSeq,
                                   int footerShift, boolean englishForm) {
        int emitted = fillItems(sh, items, layouts, alwaysShowSeq);
        // Blank the template item-zone rows the items didn't reach: the pre-seeded "2."/"แผ่น"/"Net"
        // placeholder at row 12 and the per-row H/I formulas through row 21 would otherwise show as
        // phantom lines below the last real item. Content only — the footer stays put.
        for (int r = ITEM_START_ROW + emitted; r < FOOTER_START; r++) {
            for (int c = 0; c <= 8; c++) clearCell(sh, r, c);
        }
        setNum(sh, SUBTOTAL_ROW + footerShift, 8, subtotal.doubleValue()); // I38; I39/I40 are template formulas
        getOrKeep(sh, SALESPERSON_FORMULA_ROW + footerShift, 0).setBlank(); // lookup → "0" otherwise
        writeFormTag(sh, FORM_TAG_ROW + footerShift, englishForm);
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
    private int layoutFlowing(Sheet sh, List<RenderItem> items, List<ItemLayout> layouts, BigDecimal subtotal,
                               boolean alwaysShowSeq,
                               int footerShift, boolean englishForm) {
        int footerEnd = FOOTER_END + footerShift;
        List<CellRec> footer = captureBlock(sh, FOOTER_START, footerEnd);
        float[] heights = captureHeights(sh, FOOTER_START, footerEnd);
        List<int[]> merges = captureMerges(sh, FOOTER_START, footerEnd);
        clearBlock(sh, FOOTER_START, footerEnd);

        int emitted = fillItems(sh, items, layouts, alwaysShowSeq);

        int delta = (ITEM_START_ROW + emitted) - FOOTER_START;
        placeBlock(sh, footer, delta);
        applyHeights(sh, heights, FOOTER_START + delta);
        for (int[] m : merges) sh.addMergedRegion(new CellRangeAddress(m[0] + delta, m[1] + delta, m[2], m[3]));

        // v3b: ZERO on an English document. Like toXls's own copy of this expression, it is NOT
        // what removes the VAT row — #applyEnglishTotals clears these rows outright a moment later
        // — so a mutation here is invisible to the tests. It is written anyway so that the value
        // briefly held in this cell is never a rate the document does not charge, which is what
        // the next edit to this method would otherwise inherit.
        BigDecimal vat = englishForm
            ? BigDecimal.ZERO
            : subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        setNum(sh, SUBTOTAL_ROW + footerShift + delta, 8, subtotal.doubleValue());
        setNum(sh, VAT_ROW + footerShift + delta, 8, vat.doubleValue());
        setNum(sh, TOTAL_ROW + footerShift + delta, 8, subtotal.add(vat).doubleValue());
        getOrKeep(sh, SALESPERSON_FORMULA_ROW + footerShift + delta, 0).setBlank();
        writeFormTag(sh, FORM_TAG_ROW + footerShift + delta, englishForm);
        return delta;
    }

    // layout-spec §1: the table body is ONE box with only vertical column rules — no horizontal
    // rule under any item/description/heading/remark row. The ONLY horizontal rules are under the
    // column-title row (row 6, template-native), at the bottom of the whole box just above
    // รวมเป็นเงิน (legacy path: the template's own closing row, which travels with the footer block
    // on #layoutFlowing's relocation — see #placeBlock; v2 path: remark line 8, see
    // #compactRemarksSection), and at the bottom of every paginated page fragment (see
    // #insertNoSplitPageBreaks). So this method must NOT stripe every row in [firstRow, lastRow]
    // with a border (that painted the "grid under every row" the reviewer rejected) — it borders
    // ONLY {@code lastRow}, across A..I, exactly as the caller's own name promises.
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
    private int fillItems(Sheet sh, List<RenderItem> items, List<ItemLayout> layouts, boolean alwaysShowSeq) {
        Row proto = sh.getRow(ITEM_STYLE_PROTO_ROW);
        boolean showSeq = alwaysShowSeq || items.size() > 1;
        int r = ITEM_START_ROW;
        int seq = 0;
        String prevLabel = null;
        boolean prevSet = false;
        // H1: local to this render call — see #underlinedStyle's Javadoc for why this can never
        // again be a field on this @Component singleton.
        Map<Short, CellStyle> underlineCache = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            RenderItem item = items.get(i);
            ItemLayout layout = layouts.get(i);
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
            // The wrap itself was decided in #layoutItems (narrower beside a BESIDE thumbnail).
            List<String> physicalLines = layout.lines();

            int firstRow = r;
            ensureRowStyle(sh, r, proto);
            fillItemMainRow(sh, r, showSeq ? seq : -1, physicalLines.isEmpty() ? "" : physicalLines.get(0), item);
            r++;

            for (int l = 1; l < physicalLines.size(); l++) {
                ensureRowStyle(sh, r, proto);
                fillContinuationRow(sh, r, physicalLines.get(l));
                r++;
            }

            // GLA-75: the rows the item's picture needs beyond its text — BELOW: the picture's own
            // rows under the last line; BESIDE: blank rows only when the text is shorter than the
            // thumbnail. Written as EMPTY continuation rows, so the next item starts below the
            // picture and it can never overlap it (or, for the last item, the remark box).
            int textRows = Math.max(1, physicalLines.size());
            boolean below = layout.picture() != null && !layout.picture().beside();
            for (int extra = textRows; extra < layout.rows(); extra++) {
                ensureRowStyle(sh, r, proto);
                fillContinuationRow(sh, r, "");
                if (below) {
                    // Exactly the item-row height #layoutItems counted in — a native template row
                    // here can be 23.25pt, and the page arithmetic assumes the prototype's.
                    Row pictureRow = sh.getRow(r);
                    pictureRow.setHeightInPoints((float) itemRowHeight(sh));
                }
                r++;
            }
            if (layout.picture() != null) {
                anchorItemPicture(sh, layout.picture(), below ? firstRow + textRows : firstRow);
            }
        }
        return r - ITEM_START_ROW;
    }

    /** Pure count of {@link #fillItems}'s row output, with NO sheet side effects — needed to pick
     * the layout branch (which decides whether the footer must move) before any row is written.
     * Must stay in exact lockstep with {@link #fillItems}'s own row math, including the v2
     * width-aware wrap — a mismatch here picks the wrong layout branch for the row count that
     * actually gets written. */
    private int countEmittedRows(List<RenderItem> items, List<ItemLayout> layouts) {
        int rows = 0;
        String prevLabel = null;
        boolean prevSet = false;
        for (int i = 0; i < items.size(); i++) {
            RenderItem item = items.get(i);
            if (headingNeeded(item.headingLabel(), prevLabel, prevSet)) rows++;
            prevLabel = item.headingLabel();
            prevSet = true;
            rows += layouts.get(i).rows();
        }
        return rows;
    }

    // Width-aware wrap budget for an item's printed lines (description/size/calculation), at
    // column B's own width and the item row's font — the SAME column {@link #REMARK_LINE_CHAR_BUDGET}
    // already calibrates for, so it reuses that value rather than a second magic number: item rows
    // and remark rows are the same font size in the template (see both rows' captured heights).
    private List<String> wrapDescriptionLines(List<String> lines) {
        return wrapDescriptionLines(lines, REMARK_LINE_CHAR_BUDGET);
    }

    private List<String> wrapDescriptionLines(List<String> lines, int budget) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line != null && line.length() > budget) {
                out.addAll(wrapToWidth(line, budget));
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
        // Quotation v3: a NULL unit still falls back to "แผ่น" (no caller has ever passed null,
        // so nothing changes for them), but an EXPLICITLY EMPTY unit now prints an empty cell.
        // That distinction is what the ADJUSTMENT row needs: the owner's ส่วนลดพิเศษ line carries
        // จำนวน −1 and NO หน่วย, and the previous nullSafe(s, fallback) — which treats blank and
        // null alike — would have stamped "แผ่น" onto it.
        setStr(sh, r, 3, item.unit() != null ? item.unit() : "แผ่น");     // D: unit
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

    // ── v3b: the ENGLISH document (F-SM-008) ──────────────────────────────────────

    /**
     * Every template-owned LABEL cell the English form replaces, in one place.
     *
     * <p>The company block (B1/B2/B3) is a TRANSLITERATION of the template's own Thai block, not
     * new information: "บริษัท จี แอล แอนด์ อาร์ แทปส์ แอนด์ ไทลส์ จำกัด" and the Sukhumvit 63
     * address are already in the file, and B3's own "โทรศัพท์ 0-2711-5995" is reproduced here in
     * international form.
     *
     * <p>⚠️ ASK-THE-OWNER, and this is why the template's number wins: her two English samples
     * carry DIFFERENT company phone lines — QN6900902-6 has "Tel. +662 711 5995", QN6900933 has
     * "Tel. +662 392 1494-95 Fax +662 715 0738". Rather than pick one sample over the other (or
     * invent a third), this prints the number the TEMPLATE ITSELF carries, which happens to agree
     * with QN6900902-6. Flagged in the PR for her to confirm which line is current.
     *
     * <p>The leading spaces on B1/B2/B3 are the template's own centring device (the Thai strings
     * carry 26-28 of them) and are preserved so the English block sits in the same place.
     */
    private void writeEnglishHeaderLabels(Sheet sh) {
        // Company name EXACTLY as both of the owner's English samples print it (QN6900902-6,
        // QN6900933). This used to read "GL & R TAPS AND TILES CO., LTD.", an invented rendering;
        // the registered account name on her bank block ("G.L.& R. Taps and Tiles Co., Ltd.")
        // agrees with her form in substance, so there is no legal-name reason to differ from it.
        setStr(sh, 0, 1, "                          " + EN_COMPANY_NAME);
        // Her own address wording, which is shorter than the transliteration this replaced and so
        // FITS before the URS/UKAS badge. The old line ("201 Soi Sukhumvit 63, Sukhumvit Rd.,
        // Khlong Tan Nuea, Watthana, Bangkok 10110") ran under the badge image and printed as
        // "…Bangkok 101" — the postcode was clipped on every English quotation. Spelled
        // "Sukhumvit", the road's standard romanization, where her form has "Sukumvit".
        setStr(sh, 1, 1, "                           " + EN_COMPANY_ADDRESS);
        setStr(sh, 2, 1, "                            Tel. +662 711 5995    e-mail : info@glr.co.th"
            + "    Line:@glr_tiles");

        setStr(sh, DEPT_VALUE_ROW, SALES_LINE_COL, EN_LABEL_DEPT);       // H3  ฝ่าย        -> Dept.
        setStr(sh, NUMBER_VALUE_ROW, SALES_LINE_COL, EN_LABEL_REF);      // H4  เลขที่อ้างอิง -> Ref.
        setStr(sh, UNIT_VALUE_ROW, SALES_LINE_COL, EN_LABEL_UNIT_CODE);  // H5  หน่วยงาน    -> D.Co.
        setStr(sh, 3, 0, EN_LABEL_DATE);                                  // A4  วันที่       -> Date
        setStr(sh, ATTN_ROW, 0, EN_LABEL_ATTN);                           // A5  เรียน       -> Attn :

        // B23 "หมายเหตุ" — the remark box's own label. Written HERE, before #layoutFlowing captures
        // and relocates the footer block, so it travels with that block on a multi-page document
        // exactly like the eight remark lines under it do.
        setStr(sh, FOOTER_START, LABEL_VALUE_COL, EN_REMARKS_LABEL);
    }

    /**
     * The 8 column headings on {@link #TITLE_ROW}. Column B carries NO heading in the Thai template
     * (its รายละเอียด column is labelled only by the box itself) — the English form labels it
     * "Description &amp; Conditions", so this WRITES a cell the Thai path leaves empty. F and G are
     * one merged pair in the template, so the ส่วนลด heading is written at F.
     *
     * <p>The เป็นเงิน heading carries the currency: "Amount (USD)".
     */
    private void writeEnglishColumnTitles(Sheet sh, String currency) {
        setStr(sh, TITLE_ROW, 0, EN_COL_ITEMS);                     // A ลำดับ
        setStr(sh, TITLE_ROW, 1, EN_COL_DESCRIPTION);               // B (blank in the Thai form)
        setStr(sh, TITLE_ROW, 2, EN_COL_QTY);                       // C จำนวน
        setStr(sh, TITLE_ROW, 3, EN_COL_UNIT);                      // D หน่วย
        setStr(sh, TITLE_ROW, 4, EN_COL_UNIT_PRICE);                // E ราคา
        setStr(sh, TITLE_ROW, 5, EN_COL_DISCOUNT);                  // F:G ส่วนลด (merged pair)
        setStr(sh, TITLE_ROW, 7, EN_COL_NET_PRICE);                 // H คงเหลือ
        setStr(sh, TITLE_ROW, 8, EN_COL_AMOUNT_PREFIX + " (" + currency + ")"); // I เป็นเงิน (บาท)
    }

    /**
     * The English footer's totals: <b>Grand Total ({currency}) and NOTHING else.</b> The Thai form
     * prints three rows — รวมเป็นเงิน / ภาษีมูลค่าเพิ่ม 7% / รวมเป็นเงินทั้งสิ้น; her English samples
     * print ONE, with no subtotal row and <b>no VAT row</b>.
     *
     * <p>Runs AFTER {@code renderSinglePage}/{@code layoutFlowing} rather than instead of them, so
     * the Thai path stays byte-for-byte what it was and this is a clearly-scoped overwrite of three
     * rows. That ordering also matters for the single-page path specifically: there, I39 and I40
     * are the TEMPLATE'S OWN FORMULAS (={I38*H39}, ={SUM(I38+I39)}), and clearing I38/H39 without
     * replacing I40 with a literal would leave the grand total computing off blanked cells. Every
     * cell below is therefore explicitly written or explicitly cleared.
     *
     * <p>The label is written across a merged E..H range: "Grand Total (USD)" is far longer than
     * "รวมเป็นเงินทั้งสิ้น" and column H alone clips it. E..H is free on this row (the template's own
     * merge on the VAT row is E..G, one row above, and is cleared here anyway).
     */
    private void applyEnglishTotals(Sheet sh, int shift, BigDecimal subtotal, String currency) {
        int subtotalRow = SUBTOTAL_ROW + shift;
        int vatRow = VAT_ROW + shift;
        int totalRow = TOTAL_ROW + shift;

        // No subtotal row: the label (H38) and its value (I38) both go.
        clearCell(sh, subtotalRow, SALES_LINE_COL);
        clearCell(sh, subtotalRow, VALUE_COL);
        // No VAT row: the "ภาษีมูลค่าเพิ่ม" label (E39, merged E:G), the 0.07 rate (H39) and the
        // computed VAT (I39). All three, or a stray "0.07" prints on an English page.
        for (int c = 4; c <= VALUE_COL; c++) {
            clearCell(sh, vatRow, c);
        }

        // ⚠️ Clear E..H FIRST. The template's own "รวมเป็นเงินทั้งสิ้น" lives at H, and a merged
        // region does not erase the cells it covers — it only stops Excel DRAWING them. Merging
        // E..H over the top and writing the English label at E therefore left the Thai string
        // sitting in H in the emitted file, where SheetHtmlRenderer (the Chromium PDF path, which
        // walks CELLS) would still find it. Caught by
        // DealQuotationEnglishFormTest#totals_leaveNoVatTextAnywhereOnTheEnglishSheet.
        for (int c = 4; c <= SALES_LINE_COL; c++) {
            clearCell(sh, totalRow, c);
        }
        mergeIfAbsent(sh, totalRow, totalRow, 4, SALES_LINE_COL);
        setRightAligned(sh, totalRow, 4, EN_GRAND_TOTAL_PREFIX + " (" + currency + ")");
        setNum(sh, totalRow, VALUE_COL, subtotal.doubleValue());
    }

    /**
     * The form tag at I48. The Thai path BLANKS it — a pre-existing decision ("F-SM-002 tag, not
     * customer-facing") this change does not revisit. The English path WRITES "F-SM-008 (01)",
     * because both of the owner's English samples print it and the spec asks for it by name.
     *
     * <p>⚠️ The two paths therefore differ on this one cell ON PURPOSE, and that is flagged in the
     * PR: if she wants the Thai tag printed as well, that is a one-line change here — but it would
     * be a visible change to every Thai document, which is exactly what this branch's regression
     * test forbids.
     */
    private void writeFormTag(Sheet sh, int row, boolean englishForm) {
        if (englishForm) {
            setStr(sh, row, VALUE_COL, EN_FORM_TAG);
        } else {
            getOrKeep(sh, row, VALUE_COL).setBlank();
        }
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
            openRemarkBox(sh, FOOTER_START, REMARK_HEAD_ROWS[0] + REMARK_HEAD_ROWS.length - 1);
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
        // The removed range's LAST row (template row 37 post-H3 / raw 36) was not merely blank: it
        // was the row that CLOSED the item+remark box — every cell A..I carries the box's bottom
        // rule (POI dump: `.tt.` A..F, `.t.t` G..I), and รวมเป็นเงิน sits directly under it in the
        // customer's form. Shifting it away left the box open above the totals in BOTH engines
        // (the HTML draws this sheet's own borders). layout-spec §3 wants the box to close directly
        // under line 8 with no blank row, so the last packed remark row carries that bottom rule
        // instead of re-creating the blank row — across A..I, so a merged B..I remark row draws it
        // whichever cell an engine reads a merged region's bottom edge from.
        closeItemTableBorders(sh, REMARK_HEAD_ROWS[0], REMARK_HEAD_ROWS[0] + REMARK_HEAD_ROWS.length - 1);
    }

    // layout-spec §3 + html-fidelity-spec §8: each remark line is ONE merged B..H cell (see
    // REMARK_BOX_LAST_COL) — v2 only ({@code full} = all 8 lines supplied); the legacy path's 3
    // remark lines keep the template's own single-column-B cells untouched (stays
    // content-equivalent, see the class Javadoc).
    private void mergeRemarkRow(Sheet sh, int row) {
        mergeIfAbsent(sh, row, row, REMARK_BOX_FIRST_COL, REMARK_BOX_LAST_COL);
    }

    /**
     * html-fidelity-spec §8 — closes the remark box (see {@link #REMARK_BOX_LAST_COL}) over rows
     * {@code labelRow} ("หมายเหตุ :") .. {@code lastLineRow} (line 8): the label row becomes the
     * same merged B..H cell as the eight lines under it and carries the box's TOP rule on every
     * cell B..H (both engines read a merged region's perimeter from the perimeter cells' own
     * borders — see {@code SheetPlan#borders}); the left (B) and right (H) edges are the
     * template's own cell borders on every remark row, re-asserted here so a template edit can
     * never quietly open the box. The covered cells' values (the template's per-row H/I formulas)
     * are blanked, styles kept: column I stays an open column beside the box, so its cells must
     * keep their verticals but show nothing. The bottom rule is the form's own A..I closing rule
     * under line 8 ({@link #compactRemarksSection}), and the box travels with the footer block
     * ({@link #layoutFlowing} moves merges and styles together), so it lands directly under the
     * last item line wherever that is.
     */
    private void openRemarkBox(Sheet sh, int labelRow, int lastLineRow) {
        Map<String, CellStyle> cache = new HashMap<>();
        for (int r = labelRow; r <= lastLineRow; r++) {
            for (int c = REMARK_BOX_FIRST_COL + 1; c <= 8; c++) clearCell(sh, r, c);
            addThinBorder(sh, r, REMARK_BOX_FIRST_COL, REMARK_BOX_FIRST_COL, Side.LEFT, cache);
            addThinBorder(sh, r, REMARK_BOX_LAST_COL, REMARK_BOX_LAST_COL, Side.RIGHT, cache);
        }
        mergeIfAbsent(sh, labelRow, labelRow, REMARK_BOX_FIRST_COL, REMARK_BOX_LAST_COL);
        addThinBorder(sh, labelRow, REMARK_BOX_FIRST_COL, REMARK_BOX_LAST_COL, Side.TOP, cache);
    }

    private enum Side { TOP, RIGHT, BOTTOM, LEFT }

    /** Gives cells {@code firstCol..lastCol} of {@code row} a THIN border on {@code side}, cloning
     * the cell's own style (the template's font/alignment/other borders stay). Cells that already
     * carry it are left alone; {@code cache} (one per render call — never a field, see
     * {@link #underlinedStyle}) keys the clones by source style + side. */
    private void addThinBorder(Sheet sh, int row, int firstCol, int lastCol, Side side, Map<String, CellStyle> cache) {
        Workbook wb = sh.getWorkbook();
        for (int c = firstCol; c <= lastCol; c++) {
            Cell cell = getOrKeep(sh, row, c);
            CellStyle src = cell.getCellStyle();
            org.apache.poi.ss.usermodel.BorderStyle current = switch (side) {
                case TOP -> src.getBorderTop();
                case RIGHT -> src.getBorderRight();
                case BOTTOM -> src.getBorderBottom();
                case LEFT -> src.getBorderLeft();
            };
            if (current != null && current != org.apache.poi.ss.usermodel.BorderStyle.NONE) continue;
            String key = side + ":" + src.getIndex();
            CellStyle bordered = cache.get(key);
            if (bordered == null) {
                bordered = wb.createCellStyle();
                bordered.cloneStyleFrom(src);
                switch (side) {
                    case TOP -> bordered.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                    case RIGHT -> bordered.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                    case BOTTOM -> bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                    case LEFT -> bordered.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
                }
                cache.put(key, bordered);
            }
            cell.setCellStyle(bordered);
        }
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
    // Quotation v2 owner ruling 2026-09-10: keep the TEMPLATE's OWN original four labels
    // (ผู้พิมพ์ / พนักงานขาย / ผู้จัดการฝ่ายขาย / ผู้สั่งซื้อ — see the raw row-44 string quoted in
    // the class comment above) rather than the invented ผู้ตรวจ/ผู้อนุมัติ pair a previous
    // iteration of this rebuild substituted. Only the LABEL TEXT changed here — the name mapping
    // below (printedBy → slot 0, checkedBy/sales rep → slot 1, approvedBy → slot 2) and the
    // approver-signature-image anchor (slot 2, ผู้จัดการฝ่ายขาย = ผึ้ง/sales_manager or ราม/ceo)
    // are unchanged; every reader that used to look for "ผู้ตรวจ"/"ผู้อนุมัติ" must now look for
    // "พนักงานขาย"/"ผู้จัดการฝ่ายขาย" instead (QuotationRendererTest, QuotationHtmlDocument).
    private static final String[] SIG_LABELS = {"ผู้พิมพ์", "พนักงานขาย", "ผู้จัดการฝ่ายขาย", "ผู้สั่งซื้อ"};
    private static final int SIG_APPROVER_INDEX = 2; // ผู้จัดการฝ่ายขาย — where the signature image anchors
    private static final String BLANK_NAME_PLACEHOLDER = "(..........................)";
    private static final String BLANK_DATE_PLACEHOLDER = "วันที่........./........./.........";
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
    private void writeSignatureBlock(Sheet sh, Signatories sig, boolean v2, int delta, int footerShift,
                                     boolean english) {
        if (!v2) {
            return;
        }
        String[] labels = english ? EN_SIG_LABELS : SIG_LABELS;
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
        setRightAligned(sh, orderLineRow, 2,
            english ? EN_ORDER_LINE : "ตกลงสั่งซื้อสินค้าตามราคาและเงื่อนไขข้างต้น");

        // The real PIXEL width of each of the four equal slots, measured ONCE per render.
        //
        // ⚠️ MUST be LibreOffice's own column-width model (#totalColumnWidthPixelsLibreOffice),
        // NOT POI's #totalColumnWidthPixels — see that method's own Javadoc for the full
        // derivation. This IS defect 1: padding to 95% of POI's own (unrelated, hardcoded-
        // constant) figure targets a number that has no connection to what LibreOffice will
        // actually print the row at, and on this template the two disagree by roughly a third
        // (measured: POI 659.5pt vs LibreOffice's own model 979.0pt for this exact A..I range) —
        // padding to 95% of the SMALLER, wrong number is why the owner's export landed at ~65%
        // of the real page width instead of 95%: 0.95 × (659.5/979.0) ≈ 0.64, matching the
        // observed defect almost exactly. #fontPixelToAnchorPixelScale already corrects for
        // exactly this gap for the signature PICTURE's anchor; it was never applied to the row's
        // own fill target, which is the actual bug.
        double totalWidthPx = totalColumnWidthPixelsLibreOffice(sh, 0, 8);
        double slotWidthPx = totalWidthPx * SIGNATURE_ROW_FILL_FRACTION / labels.length;
        double underscoreWidthPx = charRunWidthPx(fontMetrics, '_');
        double spaceWidthPx = charRunWidthPx(fontMetrics, ' ');

        String[] names = {
            sig != null ? sig.printedBy() : null,
            sig != null ? sig.checkedBy() : null,
            sig != null ? sig.approvedBy() : null,
            // ผู้สั่งซื้อ — the deal's contact (owner feedback F2, 2026-09-10: "use that name to
            // auto fill in the name for signature"); the placeholder when the model has none.
            sig != null ? sig.orderedBy() : null,
        };
        // Owner feedback F4 ("also autofill in the dates"): ผู้พิมพ์ = created, พนักงานขาย =
        // submitted, ผู้จัดการฝ่ายขาย = approved; ผู้สั่งซื้อ always the placeholder — the customer
        // dates their own signature on paper.
        LocalDate[] dates = {
            sig != null ? sig.printedOn() : null,
            sig != null ? sig.checkedOn() : null,
            sig != null ? sig.approvedOn() : null,
            null,
        };

        mergeIfAbsent(sh, labelsRow, labelsRow, 0, 8);
        mergeIfAbsent(sh, nameRow, nameRow, 0, 8);
        mergeIfAbsent(sh, dateRow, dateRow, 0, 8);

        StringBuilder labelsLine = new StringBuilder();
        StringBuilder namesLine = new StringBuilder();
        StringBuilder datesLine = new StringBuilder();
        // Owner feedback F6-amended-again (2026-09-10 late): the signature picture is centred on
        // the approver slot's UNDERSCORE RUN — the blank stretch of rule AFTER the label words —
        // not on the whole slot, which drew it over "ผู้จัดการฝ่ายขาย" itself. The run is derived
        // from the very string being built here, measured with the SAME AWT metrics that laid it
        // out, so it tracks the real glyph widths of whatever font the template carries rather
        // than any hardcoded millimetre figure.
        double runStartPx = 0;
        double runEndPx = 0;
        double cursorPx = 0;
        for (int i = 0; i < labels.length; i++) {
            String labelSlot = padLabelSlotPx(fontMetrics, labels[i], slotWidthPx, underscoreWidthPx);
            labelsLine.append(labelSlot);
            double labelSlotPx = textWidthPx(fontMetrics, labelSlot);
            if (i == SIG_APPROVER_INDEX) {
                runStartPx = cursorPx + textWidthPx(fontMetrics, labels[i]);
                runEndPx = cursorPx + labelSlotPx;
            }
            cursorPx += labelSlotPx;

            String name = names[i];
            String nameText = name != null && !name.isBlank() ? "(" + name.trim() + ")" : BLANK_NAME_PLACEHOLDER;
            namesLine.append(centerInSlotPx(fontMetrics, nameText, slotWidthPx, spaceWidthPx));

            datesLine.append(centerInSlotPx(fontMetrics, signatureDateText(dates[i], english),
                slotWidthPx, spaceWidthPx));
        }
        writeFixedWidthRow(sh, labelsRow, labelsLine.toString());
        writeFixedWidthRow(sh, nameRow, namesLine.toString());
        writeFixedWidthRow(sh, dateRow, datesLine.toString());

        if (sig != null && sig.approverSignaturePng() != null) {
            anchorApproverSignature(sh, labelsRow, sig.approverSignaturePng(), sig.approverSignatureMime(),
                runStartPx, runEndPx);
        }
    }

    /**
     * The S3 ("วันที่…") slot text for one signatory: {@code "วันที่ d/M/BBBB"} — day and month
     * unpadded, Buddhist-era year ({@code วันที่ 10/9/2569}, owner feedback F4's own example) —
     * or the dotted placeholder when the date is absent. Deliberately NOT {@link #shortThaiDate}'s
     * zero-padded form: that one is a remark-line date inside a sentence; this one sits on a
     * signature line the signer would otherwise fill by hand, and the owner's example is unpadded.
     */
    static String signatureDateText(LocalDate date) {
        return signatureDateText(date, false);
    }

    /** v3b: the English slot reads {@code "Date 8/9/2026"} — a <b>CE</b> year, where the Thai one
     * adds 543 — and its empty placeholder is {@code "Date ..../..../...."} rather than
     * {@code "วันที่ ..../..../...."}. Same unpadded d/M shape in both. */
    static String signatureDateText(LocalDate date, boolean english) {
        if (date == null) return english ? EN_BLANK_DATE_PLACEHOLDER : BLANK_DATE_PLACEHOLDER;
        String dm = date.getDayOfMonth() + "/" + date.getMonthValue() + "/";
        return english ? "Date " + dm + date.getYear() : "วันที่ " + dm + (date.getYear() + 543);
    }

    /** Resolves the label row's OWN template font (before this render overwrites its value) as an
     * AWT {@code Font} + {@code FontRenderContext} pair for the pixel-measurement helpers below.
     * Returns {@link SignatureFontMetrics#UNAVAILABLE} if AWT can't construct one at all (never
     * lets a font-metrics failure break the render — matching this class's convention elsewhere,
     * see {@link #anchorApproverSignature}); every caller of an unavailable metrics object falls
     * back to {@link #textUnits} character-counting via {@link #FALLBACK_AVG_CHAR_WIDTH_PX}.
     *
     * <p><strong>Root cause of the signature row not filling {@link #SIGNATURE_ROW_FILL_FRACTION}
     * of the A4 width</strong> (owner's approved-quotation export, 2026-09-10 — forensics in the
     * PR body): this used to build {@code new java.awt.Font(poiFont.getFontName(), ...)} directly
     * from the template's DECLARED family ("Angsana New" / "Cordia New" — see the class Javadoc).
     * On a host without those licensed fonts (this deployed image among them, #666), that family
     * name is not one AWT enumerates, so {@code java.awt.Font}'s OWN fallback silently substitutes
     * its generic logical "Dialog" font — a DIFFERENT substitution than fontconfig's Thai-aware
     * alias (Angsana New → Kinnari, Cordia New → Garuda/Umpush), which is what LibreOffice
     * actually lays the row out with (confirmed: the owner's PDF embeds Kinnari-Bold/Garuda, and
     * {@code fc-match "Angsana New"} on a host with {@code fonts-thai-tlwg} answers Kinnari, while
     * plain {@code new Font("Angsana New", ...)} on the SAME host falls back to Dialog). Measuring
     * with the wrong font's glyph advances under-counts how many underscores are needed to reach
     * {@code slotWidthPx}, so the padded row comes up short once LibreOffice re-lays it out in the
     * font it actually uses. {@link FontResolver} already solves exactly this question for
     * {@link LibreOfficeMetrics#charWidthTwips} (verified there to ±1/100 mm against LibreOffice's
     * own PDF vectors) — reusing its answer here, rather than re-deriving font resolution a second
     * way, is what makes this fix independent of which Thai font happens to be installed: it
     * measures with whatever font the HOST will actually render, not a name that may not exist.
     */
    private SignatureFontMetrics resolveSignatureFontMetrics(Sheet sh, int labelsRow) {
        try {
            Cell probe = getOrKeep(sh, labelsRow, 0);
            Font poiFont = sh.getWorkbook().getFontAt(probe.getCellStyle().getFontIndexAsInt());
            String resolvedFamily = FontResolver.resolve(poiFont.getFontName()).family();
            int awtStyle = (poiFont.getBold() ? java.awt.Font.BOLD : 0)
                | (poiFont.getItalic() ? java.awt.Font.ITALIC : 0);
            java.awt.Font awtFont = new java.awt.Font(resolvedFamily,
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
     * Anchors the approver's signature image in the ผู้จัดการฝ่ายขาย slot using HSSF drawing,
     * reusing the template's EXISTING drawing patriarch (it already carries the letterhead/cert
     * images — creating a fresh one is the known POI corruption risk this deliberately avoids).
     * Any failure here is swallowed and logged: a broken image anchor must never break the whole
     * render, so the dotted-placeholder/name text {@link #writeSignatureBlock} already wrote
     * stands on its own.
     *
     * <p>Owner feedback F6 (2026-09-10, seen on the demo), amended twice the same evening: the
     * picture used to span rows {@code labelsRow-2..labelsRow} with {@code dy2 = 0} — its BOTTOM
     * edge sat on the TOP edge of the labels row, a whole row (~6.5 mm) above the underscore rule,
     * and its left edge was fixed at "centre minus half of a 30 mm guess" so a height-capped
     * (narrower) image drifted left of the slot centre. The first amendment moved the bottom onto
     * the rule and centred it on the whole slot — which put the ink over the words
     * "ผู้จัดการฝ่ายขาย" ("still make the line visible but put the signature on top of the line in
     * the middle and not too high up"). Now {@link #placeSignaturePicture} sets all four anchor
     * corners from the image's real scaled size and the measured geometry of the label string's
     * own approver-slot UNDERSCORE RUN ({@code runStartPx}..{@code runEndPx}, absolute pixels from
     * column A's left edge — see {@link #writeSignatureBlock}): centred on that run, capped at
     * {@link #SIGNATURE_RUN_WIDTH_FRACTION} of it (and {@link #SIGNATURE_MAX_HEIGHT_MM} tall),
     * with the bottom edge {@link #SIGNATURE_LIFT_ABOVE_RULE_MM} ABOVE the rule so it rests on the
     * line the way a real signature does. Nothing is ever painted behind the picture — the PNG's
     * own alpha is what keeps the rule visible on both sides of the ink.
     *
     * <p><strong>Root cause of the "signature shows on the chromium path but not this one"
     * defect</strong> (owner's approved-quotation export, 2026-09-10, LibreOffice 26.2.5.2 —
     * forensics in the PR body): {@code org.apache.poi.ss.util.ImageUtils#getImageDimension} —
     * what {@link Picture#getImageDimension()} calls — SWALLOWS an image ImageIO has no reader
     * for (an unsupported or corrupt format) and returns {@code Dimension(0, 0)} rather than
     * throwing (confirmed by reading its bytecode, and by reproducing end-to-end: the JVM logs
     * "ImageIO found no images" and no exception ever reaches this class). The OLD code called
     * {@link #placeSignaturePicture} anyway, whose {@code natural.width <= 0} guard then bailed
     * out silently — leaving the picture on the PROVISIONAL anchor below, which is deliberately
     * ZERO-WIDTH ({@code col1==col2}, {@code dx1==dx2}): a shape POI's own object model keeps
     * without complaint (so a POI-model-only test finds a "picture" and a plausible-looking
     * anchor — this is exactly why one didn't catch it), but that LibreOffice's PDF export (and,
     * by the same mechanism, a real Excel/Calc open of the .xls download) simply does not draw.
     * Nothing logged the failure either: no exception means the {@code catch} below never ran.
     * {@link #decodableImageSize} probes decodability BEFORE anything touches the workbook, so an
     * undecodable image never gets a shape created for it at all — falling back to the text-only
     * name exactly as this method's own contract promises, with a warning that finally says why.
     */
    private void anchorApproverSignature(Sheet sh, int labelsRow, byte[] png, String mime,
                                         double runStartPx, double runEndPx) {
        java.awt.Dimension natural = decodableImageSize(png);
        if (natural == null) {
            log.warn("Approver signature image ({} bytes, mime={}) is not a decodable image "
                + "(ImageIO has no reader for these bytes) — skipping the picture and keeping the "
                + "text-only name", png.length, mime);
            return;
        }
        try {
            Drawing<?> patriarch = sh.getDrawingPatriarch();
            if (patriarch == null) {
                patriarch = sh.createDrawingPatriarch();
            }
            Workbook wb = sh.getWorkbook();
            int pictureType = mime != null && mime.toLowerCase(Locale.ROOT).contains("jpeg")
                ? Workbook.PICTURE_TYPE_JPEG : Workbook.PICTURE_TYPE_PNG;
            int pictureIdx = wb.addPicture(png, pictureType);

            // Provisional box (replaced wholesale by #placeSignaturePicture): the approver slot's
            // centre column, the labels row and the one above it.
            double totalWidthPx = totalColumnWidthPixels(sh, 0, 8);
            int[] center = absolutePixelToColumn(sh, totalWidthPx * SIGNATURE_APPROVER_SLOT_CENTER_FRACTION, 0, 8);
            ClientAnchor anchor = patriarch.createAnchor(center[1], 0, center[1], 0,
                center[0], Math.max(0, labelsRow - 1), center[0], labelsRow);
            Picture picture = patriarch.createPicture(anchor, pictureIdx);
            placeSignaturePicture(sh, picture, labelsRow, runStartPx, runEndPx);
        } catch (RuntimeException e) {
            log.warn("Approver signature image anchor failed; falling back to text-only name: {}", e.getMessage(), e);
        }
    }

    /**
     * Whether ImageIO can decode {@code imageBytes} at all — the same check
     * {@code org.apache.poi.ss.util.ImageUtils#getImageDimension} makes internally, run here
     * BEFORE any workbook shape exists so a decode failure can be handled by never creating one
     * (see {@link #anchorApproverSignature}'s Javadoc for why that matters — POI silently keeps a
     * degenerate shape LibreOffice then silently drops). Returns the decoded natural size, or
     * {@code null} on anything ImageIO rejects — a missing reader (unsupported/corrupt format,
     * {@code ImageIO.read} returns {@code null}) or a reader that throws partway through (e.g. a
     * CMYK JPEG, which some JDKs recognise by header but cannot actually decode).
     *
     * <p>Delegates to {@link th.co.glr.hr.common.ImageDecodability}, the shared probe
     * {@code EmployeeSignatureService#upload} now runs at UPLOAD time so a bad image is rejected
     * and re-encoded before it is ever stored, rather than silently degrading here months later.
     * This render-time check stays as defence in depth for signatures stored before that fix
     * shipped.
     */
    private static java.awt.Dimension decodableImageSize(byte[] imageBytes) {
        return th.co.glr.hr.common.ImageDecodability.size(imageBytes);
    }

    // M6: the signature was never scaled — an employee's uploaded image renders at whatever
    // pixel size they happened to save it at, which can dwarf or barely mark the ผู้อนุมัติ box.
    // Scale to a real-world box, preserving aspect. F6-amended-again (owner, 2026-09-10 late,
    // "scale it more down"): the width cap is no longer a fixed 30 mm guess but a FRACTION of the
    // approver slot's own measured underscore run — 60% of a ~28 mm run ≈ 16 mm — so the rule
    // stays visible on both sides of the ink whatever font/column widths the template carries.
    private static final double SIGNATURE_RUN_WIDTH_FRACTION = 0.60;
    private static final double SIGNATURE_MAX_HEIGHT_MM = 8.0;
    // Only when the run could not be measured at all (AWT font metrics unavailable AND the
    // character-count fallback produced a degenerate run) — never in a normal render.
    private static final double SIGNATURE_FALLBACK_WIDTH_MM = 16.0;
    private static final double MM_PER_INCH = 25.4;
    // Assumed image DPI for Picture#getImageDimension()'s pixel dimensions — POI/Excel's own
    // convention for a raster image with no embedded DPI metadata (PNG signature uploads here
    // are screen-captured or scanned at typical screen resolution, not print resolution).
    private static final double ASSUMED_IMAGE_DPI = 96.0;
    // F6: the underscore RULE itself sits this far above the labels row's BOTTOM edge — roughly
    // the 14pt font's descent, i.e. the row's text baseline. Everything vertical is measured from
    // here rather than from the row edge (the owner's "floats above its line" was the picture
    // ending a whole row higher than this).
    private static final double SIGNATURE_BASELINE_LIFT_MM = 1.0;
    // F6-amended-3 (owner, 2026-09-10 late: "it should sit ABOVE the line, right now it's across
    // the middle of the line"): the ink's BOTTOM edge rests this far ABOVE the rule, so the
    // signature sits ON the line without cutting through it. Measured before the change: ink
    // bottom 264.16 mm against a rule at 262.54 mm — 1.62 mm THROUGH it. Keep this positive; a
    // negative value would put the ink back across the rule.
    private static final double SIGNATURE_LIFT_ABOVE_RULE_MM = 0.4;
    // Never walk the anchor more than this many rows away from the labels row — a corrupt row
    // height must not send the walk off the sheet.
    private static final int SIGNATURE_MAX_ROW_WALK = 8;
    private static final double POINTS_PER_INCH = 72.0;
    // HSSF two-cell anchors express dx in 1/1024 of the column width and dy in 1/256 of the row
    // height (the HTML engine, SheetHtmlRenderer#anchorY, reads the same units).
    private static final int HSSF_DY_UNITS = 256;

    /**
     * Sets ALL FOUR corners of the picture's anchor from its natural dimensions and the approver
     * slot's measured underscore run ({@code runStartPx}..{@code runEndPx}, absolute pixels from
     * column A's left edge):
     *
     * <ul>
     *   <li><strong>width</strong> — scaled to {@link #SIGNATURE_RUN_WIDTH_FRACTION} of the run
     *       (or {@link #SIGNATURE_MAX_HEIGHT_MM} tall, whichever binds first), aspect preserved,
     *       so the rule shows on both sides of the ink;</li>
     *   <li><strong>horizontal</strong> — centred on the RUN's centre, not the slot's: the run
     *       starts where the label text ends, so the picture can never be drawn over the words
     *       "ผู้จัดการฝ่ายขาย" (the owner's complaint on the 2026-09-10 demo);</li>
     *   <li><strong>vertical</strong> — the bottom edge lands {@link #SIGNATURE_LIFT_ABOVE_RULE_MM}
     *       ABOVE the rule (itself {@link #SIGNATURE_BASELINE_LIFT_MM} above {@code labelsRow}'s
     *       bottom edge), so the strokes cross the line; the top is the same point minus the
     *       scaled height. Both are converted to (row, dy) by {@link #rowOffsetToAnchor}, which
     *       walks up OR down as needed — the bottom now deliberately falls past the labels row's
     *       own bottom edge.</li>
     * </ul>
     *
     * <p>Column/row offsets go through {@link #absolutePixelToColumn} rather than
     * {@link Picture#resize(double, double)}, which walks the same column math internally from
     * whatever {@code col1} is and threw {@code col2 must be between 0 and 255} when that walk ran
     * past this sheet's printable range. A picture whose dimensions POI cannot read is left on its
     * provisional anchor.
     */
    private void placeSignaturePicture(Sheet sh, Picture picture, int labelsRow,
                                       double runStartPx, double runEndPx) {
        java.awt.Dimension natural = picture.getImageDimension();
        if (natural.width <= 0 || natural.height <= 0) {
            return;
        }
        double totalWidthPx = totalColumnWidthPixels(sh, 0, 8);
        // Font pixels are NOT anchor pixels — see #textPixelToAnchorPixel.
        double fontToAnchor = fontPixelToAnchorPixelScale(sh);
        double runStartAnchorPx = textPixelToAnchorPixel(runStartPx, fontToAnchor);
        double runEndAnchorPx = textPixelToAnchorPixel(runEndPx, fontToAnchor);
        // Degenerate run (font metrics unavailable and the char-count fallback produced nothing
        // usable): fall back to a fixed box centred on the whole slot rather than render a
        // zero-width picture.
        double runWidthAnchorPx = runEndAnchorPx - runStartAnchorPx;
        double maxWidthAnchorPx;
        double runCentreAnchorPx;
        if (runEndPx > runStartPx && runWidthAnchorPx > 0) {
            maxWidthAnchorPx = runWidthAnchorPx * SIGNATURE_RUN_WIDTH_FRACTION;
            runCentreAnchorPx = (runStartAnchorPx + runEndAnchorPx) / 2;
        } else {
            maxWidthAnchorPx = SIGNATURE_FALLBACK_WIDTH_MM * ASSUMED_IMAGE_DPI / MM_PER_INCH / fontToAnchor;
            runCentreAnchorPx = totalWidthPx * SIGNATURE_APPROVER_SLOT_CENTER_FRACTION;
        }

        // The aspect ratio must be preserved in PHYSICAL space, so the width cap (an anchor-pixel
        // figure) converts back to font pixels before the height is derived from it; row heights,
        // unlike column widths, are exact twips in the file and need no such correction.
        double naturalHeightMm = natural.height / ASSUMED_IMAGE_DPI * MM_PER_INCH;
        double scale = maxWidthAnchorPx * fontToAnchor / natural.width;
        if (naturalHeightMm * scale > SIGNATURE_MAX_HEIGHT_MM) {
            scale = SIGNATURE_MAX_HEIGHT_MM / naturalHeightMm;
        }
        double widthPx = natural.width * scale / fontToAnchor;
        double heightPt = natural.height * scale / ASSUMED_IMAGE_DPI * POINTS_PER_INCH;

        // Horizontal: centre the REAL scaled width on the underscore run's centre.
        double leftPx = Math.max(0, runCentreAnchorPx - widthPx / 2);
        int[] start = absolutePixelToColumn(sh, leftPx, 0, 8);
        int[] end = absolutePixelToColumn(sh, leftPx + widthPx, 0, 8);

        // Vertical: the rule sits SIGNATURE_BASELINE_LIFT_MM above the labels row's bottom edge;
        // the ink's bottom is SIGNATURE_LIFT_ABOVE_RULE_MM above THAT, and the top is the scaled height
        // above the bottom.
        double liftPt = SIGNATURE_BASELINE_LIFT_MM / MM_PER_INCH * POINTS_PER_INCH;
        double sitAbovePt = SIGNATURE_LIFT_ABOVE_RULE_MM / MM_PER_INCH * POINTS_PER_INCH;
        double bottomFromRowTopPt = rowHeightPoints(sh, labelsRow) - liftPt - sitAbovePt;
        int[] bottom = rowOffsetToAnchor(sh, labelsRow, bottomFromRowTopPt);
        int[] top = rowOffsetToAnchor(sh, labelsRow, bottomFromRowTopPt - heightPt);

        ClientAnchor anchor = picture.getClientAnchor();
        anchor.setCol1(start[0]);
        anchor.setDx1(start[1]);
        anchor.setCol2(end[0]);
        anchor.setDx2(end[1]);
        anchor.setRow1(top[0]);
        anchor.setDy1(top[1]);
        anchor.setRow2(bottom[0]);
        anchor.setDy2(bottom[1]);
    }

    /**
     * Converts an offset in POINTS from {@code baseRow}'s TOP edge — negative (above it) or larger
     * than the row's own height (below it) both allowed — into HSSF's {@code [rowIndex, dyUnits]}
     * anchor pair, walking whole rows in either direction. The vertical counterpart of
     * {@link #absolutePixelToColumn}. Walks at most {@link #SIGNATURE_MAX_ROW_WALK} rows so a
     * degenerate (zero-height) row can never spin this loop.
     */
    private int[] rowOffsetToAnchor(Sheet sh, int baseRow, double offsetPt) {
        return rowOffsetToAnchor(sh, baseRow, offsetPt, SIGNATURE_MAX_ROW_WALK);
    }

    private int[] rowOffsetToAnchor(Sheet sh, int baseRow, double offsetPt, int maxRowWalk) {
        int row = baseRow;
        double offset = offsetPt;
        int walked = 0;
        while (offset < 0 && row > 0 && walked++ < maxRowWalk) {
            row--;
            offset += rowHeightPoints(sh, row);
        }
        while (offset >= rowHeightPoints(sh, row) && walked++ < maxRowWalk) {
            offset -= rowHeightPoints(sh, row);
            row++;
        }
        double height = rowHeightPoints(sh, row);
        int dy = height > 0 ? clampDy((int) Math.round(HSSF_DY_UNITS * Math.max(0, offset) / height)) : 0;
        return new int[]{row, dy};
    }

    /**
     * Font pixels are NOT anchor pixels. The signature rows' text is positioned by the FONT's own
     * advances (what {@link #textWidthPx} measures), while a {@link ClientAnchor} is positioned by
     * COLUMN widths — and LibreOffice does not compute a column's width the way POI's
     * {@link Sheet#getColumnWidthInPixels} does. LibreOffice scales the XLS 1/256-character width
     * unit by the workbook default font's widest digit ({@code XclRoot::SetCharWidth}, replicated
     * exactly by {@link LibreOfficeMetrics#columnTwips}); on this template that comes out ~3%
     * NARROWER than POI's figure, which is a systematic ~5 mm leftward error on a signature
     * anchored two thirds of the way across the page — measured, not guessed: the picture landed
     * at 132.2 mm on a page where its underscore run centred at 137.6 mm.
     *
     * <p>Returns {@code (LibreOffice's A..I width) / (POI's A..I width)}, both in 96 dpi pixels —
     * the factor that turns an anchor pixel into a font pixel, and (dividing) a font pixel into
     * the anchor pixel that lands at the same physical place on the page. Falls back to 1.0 (the
     * uncorrected behaviour) if the column unit cannot be measured at all.
     */
    private double fontPixelToAnchorPixelScale(Sheet sh) {
        try {
            int charWidthTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
            long loTwips = 0;
            for (int c = 0; c <= 8; c++) {
                loTwips += LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charWidthTwips);
            }
            double loPx = loTwips / (double) LibreOfficeMetrics.TWIPS_PER_INCH * ASSUMED_IMAGE_DPI;
            double poiPx = totalColumnWidthPixels(sh, 0, 8);
            return loPx > 0 && poiPx > 0 ? loPx / poiPx : 1.0;
        } catch (RuntimeException e) {
            log.debug("LibreOffice column unit unavailable; anchoring the signature in POI pixels: {}",
                e.getMessage());
            return 1.0;
        }
    }

    /** An offset in FONT pixels from the merged cell's left edge (plus LibreOffice's own text
     * inset, which the string is drawn after) as an absolute ANCHOR pixel offset from column A's
     * left edge — see {@link #fontPixelToAnchorPixelScale}. */
    private double textPixelToAnchorPixel(double textPx, double fontToAnchor) {
        double insetPx = LibreOfficeMetrics.TEXT_INSET_TWIPS / (double) LibreOfficeMetrics.TWIPS_PER_INCH
            * ASSUMED_IMAGE_DPI;
        return (insetPx + textPx) / fontToAnchor;
    }

    private static int clampDy(int dy) {
        return Math.max(0, Math.min(HSSF_DY_UNITS - 1, dy));
    }

    private double rowHeightPoints(Sheet sh, int r) {
        Row row = sh.getRow(r);
        return row != null ? row.getHeightInPoints() : sh.getDefaultRowHeightInPoints();
    }

    /** The lower-level primitive {@link #placeSignaturePicture} builds on — takes an ABSOLUTE
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

    /**
     * The SAME range, in LibreOffice's OWN column-width model (twips, via
     * {@link LibreOfficeMetrics#charWidthTwips}/{@link LibreOfficeMetrics#columnTwips} — the
     * mechanism {@link #fontPixelToAnchorPixelScale} already trusts for the signature PICTURE's
     * anchor) rather than {@link #totalColumnWidthPixels}'s POI-native pixel figure — converted to
     * 96dpi px so it lands in the SAME unit space {@link #textWidthPx} already measures in.
     *
     * <p>This is THE row-width defect, not a refinement of it: {@link #writeSignatureBlock} used
     * to pad to {@link #SIGNATURE_ROW_FILL_FRACTION} of {@link #totalColumnWidthPixels}'s figure,
     * which has no relationship to what LibreOffice actually prints the A..I range at. On this
     * template the two disagree by roughly a third — measured directly (not estimated): POI says
     * 659.5 pt for A..I, LibreOffice's own model says 979.0 pt, because the WORKBOOK's default
     * font (index 0, what a raw XLS column-width unit is defined against) is one the host
     * substitutes to something with a noticeably wider digit than POI's hardcoded constant
     * assumes. Padding to 95% of the SMALLER, wrong figure is exactly {@code 0.95 × (659.5/979.0)
     * ≈ 0.64} of the REAL page width — matching the owner's exported PDF (roughly 65%, not 95%)
     * almost exactly. Reusing {@link #fontPixelToAnchorPixelScale}'s already-verified twips math
     * here — rather than re-deriving a second way to ask the same question — is what makes the
     * fix independent of which font the host happens to substitute: it targets whatever
     * LibreOffice will actually use, not a number POI made up.
     */
    private double totalColumnWidthPixelsLibreOffice(Sheet sh, int firstCol, int lastCol) {
        try {
            int charWidthTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
            long loTwips = 0;
            for (int c = firstCol; c <= lastCol; c++) {
                loTwips += LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charWidthTwips);
            }
            double loPx = loTwips / (double) LibreOfficeMetrics.TWIPS_PER_INCH * ASSUMED_IMAGE_DPI;
            return loPx > 0 ? loPx : totalColumnWidthPixels(sh, firstCol, lastCol);
        } catch (RuntimeException e) {
            log.debug("LibreOffice column width unavailable for the signature row's fill target; "
                + "falling back to POI's own (less accurate) figure: {}", e.getMessage());
            return totalColumnWidthPixels(sh, firstCol, lastCol);
        }
    }

    // ── GLA-75: per-item pictures ─────────────────────────────────────────────────

    /**
     * How one item prints: its physical description {@code lines}, its picture plan (null when it
     * has none), and the TOTAL rows it occupies — text rows, plus the rows a BELOW picture sits
     * on, or padding up to a BESIDE thumbnail's height. Heading rows are counted separately (they
     * belong to a location group, not an item).
     */
    private record ItemLayout(List<String> lines, PicturePlan picture, int rows) {}

    /**
     * Where a picture goes, in engine-neutral terms: a horizontal slice of column B expressed as
     * FRACTIONS of its width ({@code leftFraction}, {@code widthFraction}), and a height in points
     * from the top of its first row. Fractions, not pixels, because a {@link ClientAnchor}'s dx is
     * itself a fraction of its column — so the picture lands on the same slice of column B in
     * LibreOffice and in the HTML engine however each one sizes the column. That is the lesson of
     * the signature's 5 mm drift (see {@link #fontPixelToAnchorPixelScale}): that drift came from
     * positioning an anchor with FONT-advance pixels; nothing here is measured in font pixels.
     * The aspect ratio IS a physical quantity, so column B's physical width is taken from
     * LibreOffice's own column arithmetic ({@link #descriptionColumnMm}), the width both engines
     * actually print.
     */
    private record PicturePlan(ItemPicture source, boolean beside, double leftFraction, double widthFraction,
                               double heightPt, int textBudget) {}

    // Owner, 2026-09-10: "make sure the sizing appropriate like the reference picture". The
    // references need two sizes, not one:
    //  • BELOW (QN6900902-6's mosaic panel, QN6900782-2's cut drawings) — the full width of the
    //    description column less a small inset, aspect kept, capped at BELOW_MAX_HEIGHT_MM so a
    //    tall portrait image cannot eat a page. ~60 mm is "fairly large": about eight item rows.
    //  • BESIDE (QN6900971-4's tap, towel ring, shower set) — a thumbnail about TWO text rows tall
    //    at the right of the description cell, no wider than BESIDE_MAX_WIDTH_FRACTION of it so a
    //    wide image cannot push into the text.
    private static final double PICTURE_INSET_MM = 1.5;
    private static final double PICTURE_PAD_PT = 2.0;
    private static final double BELOW_MAX_HEIGHT_MM = 60.0;
    private static final int BESIDE_ROWS = 2;
    private static final double BESIDE_MAX_WIDTH_FRACTION = 0.30;
    private static final double BESIDE_TEXT_GAP_MM = 2.0;
    // A BELOW picture spans at most ~9 rows; this only bounds the anchor walk.
    private static final int PICTURE_MAX_ROW_WALK = 64;
    // Never wrap a line narrower than this beside a thumbnail, whatever the image's shape.
    private static final int BESIDE_MIN_TEXT_BUDGET = 20;

    private List<ItemLayout> layoutItems(Sheet sh, List<RenderItem> items, boolean alwaysShowSeq) {
        List<ItemLayout> out = new ArrayList<>(items.size());
        double columnMm = -1;
        double rowHeightPt = itemRowHeight(sh);
        for (RenderItem item : items) {
            PicturePlan plan = null;
            if (item.picture() != null && item.picture().data() != null) {
                if (columnMm < 0) columnMm = descriptionColumnMm(sh);
                plan = planPicture(item.picture(), columnMm, rowHeightPt);
            }
            List<String> lines;
            if (!alwaysShowSeq) {
                lines = item.descriptionLines();
            } else if (plan != null && plan.beside()) {
                lines = wrapDescriptionLines(item.descriptionLines(), plan.textBudget());
            } else {
                lines = wrapDescriptionLines(item.descriptionLines());
            }
            int textRows = Math.max(1, lines.size());
            int rows = textRows;
            if (plan != null) {
                // The picture plus a pad above and below, in whole item rows. The pad keeps the
                // anchor's bottom strictly INSIDE the picture's last row — a bottom on the next
                // row's top edge would belong to the next item (and, across a page break, the
                // HTML engine would drop a picture whose end row is on another page).
                int pictureRows = (int) Math.ceil((plan.heightPt() + 2 * PICTURE_PAD_PT) / rowHeightPt - 1e-9);
                rows = plan.beside() ? Math.max(textRows, pictureRows) : textRows + pictureRows;
            }
            out.add(new ItemLayout(lines, plan, rows));
        }
        return out;
    }

    private PicturePlan planPicture(ItemPicture picture, double columnMm, double rowHeightPt) {
        int[] natural = th.co.glr.hr.dealquotation.QuotationItemPictures.headerDimensions(picture.data());
        if (natural == null || columnMm <= 0) {
            // Validated at upload, so this is a corrupt stored row: print the item without it
            // rather than fail the whole document.
            log.warn("Quotation item picture unreadable; rendering the item without it");
            return null;
        }
        double aspect = natural[1] / (double) natural[0]; // height / width
        double widthMm;
        double heightMm;
        double leftMm;
        int textBudget = REMARK_LINE_CHAR_BUDGET;
        if (picture.beside()) {
            double maxHeightMm = (BESIDE_ROWS * rowHeightPt - 2 * PICTURE_PAD_PT) / POINTS_PER_INCH * MM_PER_INCH;
            double maxWidthMm = columnMm * BESIDE_MAX_WIDTH_FRACTION;
            heightMm = maxHeightMm;
            widthMm = heightMm / aspect;
            if (widthMm > maxWidthMm) {
                widthMm = maxWidthMm;
                heightMm = widthMm * aspect;
            }
            leftMm = columnMm - PICTURE_INSET_MM - widthMm;
            // Wrap the item's text to the part of column B left of the thumbnail, in the same
            // characters-per-column-width unit REMARK_LINE_CHAR_BUDGET is calibrated in.
            double textFraction = (leftMm - BESIDE_TEXT_GAP_MM) / columnMm;
            textBudget = Math.max(BESIDE_MIN_TEXT_BUDGET, (int) Math.floor(REMARK_LINE_CHAR_BUDGET * textFraction));
        } else {
            widthMm = columnMm - 2 * PICTURE_INSET_MM;
            heightMm = widthMm * aspect;
            if (heightMm > BELOW_MAX_HEIGHT_MM) {
                heightMm = BELOW_MAX_HEIGHT_MM;
                widthMm = heightMm / aspect;
            }
            leftMm = PICTURE_INSET_MM;
        }
        return new PicturePlan(picture, picture.beside(), leftMm / columnMm, widthMm / columnMm,
            heightMm / MM_PER_INCH * POINTS_PER_INCH, textBudget);
    }

    /** Column B's printed width in mm, by LibreOffice's column arithmetic (the width both the
     * LibreOffice PDF and the HTML engine lay out); POI's own figure only if that is unavailable. */
    private double descriptionColumnMm(Sheet sh) {
        try {
            int charWidthTwips = LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
            double twips = LibreOfficeMetrics.columnTwips(sh.getColumnWidth(LABEL_VALUE_COL), charWidthTwips);
            return twips / LibreOfficeMetrics.TWIPS_PER_INCH * MM_PER_INCH;
        } catch (RuntimeException e) {
            log.debug("LibreOffice column unit unavailable; sizing item pictures in POI pixels: {}", e.getMessage());
            return sh.getColumnWidthInPixels(LABEL_VALUE_COL) / ASSUMED_IMAGE_DPI * MM_PER_INCH;
        }
    }

    /**
     * Anchors the picture inside column B (col1 == col2 == B, so it can never reach the จำนวน
     * column), from {@code topRow}'s top edge plus a pad, for exactly the planned height. Reuses the
     * template's existing drawing patriarch, like {@link #anchorApproverSignature}; a failure is
     * logged and the item prints without its picture — its rows are already reserved, so nothing
     * below moves.
     */
    private void anchorItemPicture(Sheet sh, PicturePlan plan, int topRow) {
        try {
            Drawing<?> patriarch = sh.getDrawingPatriarch();
            if (patriarch == null) {
                patriarch = sh.createDrawingPatriarch();
            }
            String mime = plan.source().mimeType();
            int pictureType = mime != null && mime.toLowerCase(Locale.ROOT).contains("jpeg")
                ? Workbook.PICTURE_TYPE_JPEG : Workbook.PICTURE_TYPE_PNG;
            int pictureIdx = sh.getWorkbook().addPicture(plan.source().data(), pictureType);
            int dx1 = clampDx((int) Math.round(plan.leftFraction() * 1024));
            int dx2 = clampDx((int) Math.round((plan.leftFraction() + plan.widthFraction()) * 1024));
            int[] top = rowOffsetToAnchor(sh, topRow, PICTURE_PAD_PT, PICTURE_MAX_ROW_WALK);
            int[] bottom = rowOffsetToAnchor(sh, topRow, PICTURE_PAD_PT + plan.heightPt(), PICTURE_MAX_ROW_WALK);
            ClientAnchor anchor = patriarch.createAnchor(dx1, top[1], dx2, bottom[1],
                LABEL_VALUE_COL, top[0], LABEL_VALUE_COL, bottom[0]);
            patriarch.createPicture(anchor, pictureIdx);
        } catch (RuntimeException e) {
            log.warn("Quotation item picture anchor failed; the item prints without it: {}", e.getMessage(), e);
        }
    }

    private static int clampDx(int dx) {
        return Math.max(0, Math.min(1023, dx));
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
     *
     * <p>html-fidelity-spec §7 ("page 2+: the box closes at the page bottom and re-opens under the
     * repeated title row"): LibreOffice draws nothing at a page break — the template's own borders
     * simply stop where the page ends (the owner's workbook render, chn1a-2.png, shows exactly
     * that), so the row above every break inserted here gets the box's bottom rule across A..I
     * ({@link #closeItemTableBorders}). The re-opening needs nothing: the repeated title row
     * carries its own top and bottom rules.
     *
     * <p>html-fidelity-spec §9 ("seamless page breaks"): the page budget is the page LibreOffice
     * will actually fill, not the unscaled template. {@link #fitToWidthPaginate} prints at the
     * fit-to-width zoom (≈72% on this template), so a page holds 1/zoom more rows than their raw
     * point heights suggest — measuring the budget in raw points (as this method once did) broke
     * pages a third early and left every page 20–30% blank. The accounting here is LibreOffice's
     * own ({@link th.co.glr.hr.common.sheet.LibreOfficeMetrics}, the same arithmetic
     * {@code SheetPlan#paginate} uses to lay the HTML pages, so both engines agree on the
     * break by construction): zoom = {@link #fitWidthZoomPercent}, every row truncated to
     * 1/100 mm at that zoom, page body = paper minus the top/bottom margins, the repeating rows
     * A1:I7 charged to every page. One item row of headroom is kept below the budget so
     * LibreOffice's own rounding at the boundary can never drop an automatic break INSIDE a
     * block that this method judged to fit.
     */
    private void insertNoSplitPageBreaks(Sheet sh, List<RenderItem> items, List<ItemLayout> layouts,
                                          int footerStartRow, int footerEndRow) {
        int zoom = fitWidthZoomPercent(sh);
        int bodyHmm = th.co.glr.hr.common.sheet.LibreOfficeMetrics.A4_HEIGHT_HMM
            - th.co.glr.hr.common.sheet.LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.TOP))
            - th.co.glr.hr.common.sheet.LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.BOTTOM));
        int repeatedHmm = scaledRowsHmm(sh, 0, ITEM_START_ROW - 1, zoom);
        int headroomHmm = scaledRowsHmm(sh, ITEM_STYLE_PROTO_ROW, ITEM_STYLE_PROTO_ROW, zoom);
        int capacity = bodyHmm - repeatedHmm - headroomHmm;
        int usedOnPage = 0;
        int r = ITEM_START_ROW;
        String prevLabel = null;
        boolean prevSet = false;
        for (int i = 0; i < items.size(); i++) {
            RenderItem item = items.get(i);
            boolean heading = headingNeeded(item.headingLabel(), prevLabel, prevSet);
            prevLabel = item.headingLabel();
            prevSet = true;
            // GLA-75: the block is the item's text AND its picture rows (ItemLayout#rows), so a
            // picture moves to the next page WITH its item and is never cut by a page break.
            int blockRows = (heading ? 1 : 0) + layouts.get(i).rows();
            int blockHmm = scaledRowsHmm(sh, r, r + blockRows - 1, zoom);
            if (usedOnPage > 0 && usedOnPage + blockHmm > capacity) {
                sh.setRowBreak(r - 1);
                closeItemTableBorders(sh, ITEM_START_ROW, r - 1);
                usedOnPage = 0;
            }
            usedOnPage += blockHmm;
            r += blockRows;
        }
        // The whole tail — remark box, totals, ตกลงสั่งซื้อ, signature rows, F-SM-002 — is one
        // block: never split, and never parted from the remark box.
        int footerHmm = scaledRowsHmm(sh, footerStartRow, footerEndRow, zoom);
        if (usedOnPage > 0 && usedOnPage + footerHmm > capacity) {
            sh.setRowBreak(footerStartRow - 1);
            closeItemTableBorders(sh, ITEM_START_ROW, footerStartRow - 1);
        }
    }

    /** The zoom LibreOffice prints {@link #fitToWidthPaginate}'s sheet at (fit 1 wide, height
     * unconstrained): columns A..I in twips exactly as LibreOffice converts them, against the
     * page width minus the sheet's own margins. */
    private int fitWidthZoomPercent(Sheet sh) {
        int charWidth = th.co.glr.hr.common.sheet.LibreOfficeMetrics.charWidthTwips(sh.getWorkbook());
        long contentWidthTwips = 0;
        for (int c = 0; c <= 8; c++) {
            contentWidthTwips += th.co.glr.hr.common.sheet.LibreOfficeMetrics.columnTwips(sh.getColumnWidth(c), charWidth);
        }
        double availableWidthTwips = (th.co.glr.hr.common.sheet.LibreOfficeMetrics.A4_WIDTH_HMM
            - th.co.glr.hr.common.sheet.LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.LEFT))
            - th.co.glr.hr.common.sheet.LibreOfficeMetrics.inchesToHmm(sh.getMargin(PageMargin.RIGHT)))
            / th.co.glr.hr.common.sheet.LibreOfficeMetrics.HMM_PER_TWIP;
        return th.co.glr.hr.common.sheet.LibreOfficeMetrics.fitZoomPercent(contentWidthTwips, 0,
            availableWidthTwips, 0, 1, 0);
    }

    /** Rows {@code from..to} as LibreOffice prints them at {@code zoom}: each row's twips scaled
     * and truncated to 1/100 mm on its own (the plan's unit), then summed. */
    private int scaledRowsHmm(Sheet sh, int from, int to, int zoom) {
        int sum = 0;
        for (int r = from; r <= to; r++) {
            Row row = sh.getRow(r);
            int twips = row == null ? Math.round(sh.getDefaultRowHeightInPoints() * 20f)
                : row.getZeroHeight() ? 0 : row.getHeight();
            sum += th.co.glr.hr.common.sheet.LibreOfficeMetrics.scaledHmm(twips, zoom);
        }
        return sum;
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

    /**
     * ⚠️ The English header date: {@code "September 8, 2026"} — an English month NAME and a
     * <b>COMMON-era</b> year, where {@link #thaiDate} directly below prints
     * {@code "8 กันยายน 2569"} (Buddhist era, +543). The owner's spec names this as the single
     * easiest thing to get wrong on the English form, which is why the two live adjacent rather
     * than one being reused with a locale flag. {@code Locale.US} explicitly, so a JVM running
     * under a Thai default locale cannot make {@code MMMM} come back in Thai.
     */
    private String englishDate(LocalDate d) {
        if (d == null) return "";
        return d.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US));
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
