package th.co.glr.hr.ticket;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.customer.CustomerDto;

// Renders a quotation by filling the official company template (quotation_template.xls).
// Rule: open the real template, write ONLY the cells listed below, never createRow/createCell
// on rows that already exist in the template, never call setCellType (wipes style).
@Component
public class QuotationRenderer {

    // Real company XLS template — sheet "Update"
    private static final String TEMPLATE = "templates/quotation_template.xls";

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    // Template layout per document-generation-fix.md §A2-A3
    // The template's item zone is 0-based rows 9–20; the first item row (A10) starts the table.
    private static final int ITEM_START_ROW = 9; // 0-based (= row 10 in 1-based)

    // Flow layout: pack one item per row from ITEM_START_ROW and relocate the footer block
    // (notes/totals/signature) to sit right after the last item, so any item count paginates
    // cleanly (Excel-like: fit columns to one page wide, paginate down, letterhead repeated).
    // Row 12 is a fully-bordered item row whose cell styles are cloned into rows past the
    // template's original item zone.
    private static final int ITEM_STYLE_PROTO_ROW = 12;
    private static final int FOOTER_START = 22; // 0-based: หมายเหตุ … totals … signature … F-SM-002
    private static final int FOOTER_END   = 46;
    private static final int SUBTOTAL_ROW = 37; // I38
    private static final int VAT_ROW      = 38; // I39
    private static final int TOTAL_ROW    = 39; // I40
    private static final int SALESPERSON_FORMULA_ROW = 44; // A45 lookup formula → blanked
    private static final int FORM_TAG_ROW = 46;            // I47 "F-SM-002" → blanked
    private static final BigDecimal VAT_RATE = new BigDecimal("0.07");

    // Items that fit the template's native item zone (rows 9..21) keep the footer anchored at its
    // native rows on a single page — derived from the layout, not a magic number.
    private static final int NATIVE_ITEM_CAPACITY = FOOTER_START - ITEM_START_ROW; // = 13

    // ── dynamic-layout geometry (all lengths in points; see the layout equations in toXls) ──
    private static final double A4_WIDTH_PT  = 595.3;
    private static final double A4_HEIGHT_PT = 841.9;
    // Below this fraction of natural size a quote paginates instead of shrinking onto one page.
    private static final double MIN_SCALE = 0.72;
    // Money-column sizing: padding chars + width units per char, calibrated so an 8-figure baht total
    // never clips to "###" in the totals column's larger bold font.
    private static final int MONEY_PAD_CHARS = 1;
    private static final int MONEY_UNITS_PER_CHAR = 300;

    public byte[] toXls(TicketDto ticket, QuotationDto quotation, CustomerDto customer) {
        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            TicketSummaryDto s = ticket.summary();
            LocalDate issueDate = quotation.issuedAt() != null
                ? quotation.issuedAt().atZone(ZoneId.of("Asia/Bangkok")).toLocalDate()
                : LocalDate.now();

            // Title cell H1 (row 0, col 7) — "ใบเสนอราคา" carries ~25 leading spaces to
            // position it in Excel. LibreOffice's wider substitute font pushes the trailing
            // sara-aa (า) past the print-area right edge (col I), clipping it. Re-anchor
            // with fewer leading spaces so it stays right-aligned but fits within the page.
            Cell titleCell = getOrKeep(sh, 0, 7);
            if (titleCell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                setStr(sh, 0, 7, "        " + titleCell.getStringCellValue().strip());
            }

            // B4 (row 3, col 1) — issue date, overrides TODAY() formula
            setStr(sh, 3, 1, thaiDate(issueDate));

            // I3 (row 2, col 8) — salesperson code (employee_code of assigned-to)
            // issuedByName is the full name; code not yet surfaced in QuotationDto — leave blank
            setStr(sh, 2, 8, "");

            // I4 (row 3, col 8) — quotation number
            setStr(sh, 3, 8, nullSafe(quotation.number()));

            // H5 (row 4, col 7) — department (not in current data model — leave blank)
            setStr(sh, 4, 7, "");

            // H6 (row 5, col 7) — "Sales : {name} T.{phone}"
            String salesLine = quotation.issuedByName() != null
                ? "Sales : " + quotation.issuedByName()
                : "";
            setStr(sh, 5, 7, salesLine);

            // A5/B5 (row 4, col 0/1) — "เรียน: คุณ{contact} / {company} เลขที่ผู้เสียภาษี : {taxId}"
            String contactPart = s.contactName() != null && !s.contactName().isBlank()
                ? "คุณ" + s.contactName() + "   /   " : "";
            String taxIdPart = customer != null && customer.taxId() != null && !customer.taxId().isBlank()
                ? "   เลขที่ผู้เสียภาษี : " + customer.taxId() : "";
            setStr(sh, 4, 1, contactPart + nullSafe(s.customerName()) + taxIdPart);

            // B6 (row 5, col 1) — "โทร. {phone}    / E-mail : {email}"
            String phonePart = customer != null && customer.phone() != null && !customer.phone().isBlank()
                ? "โทร. " + customer.phone() : "";
            setStr(sh, 5, 1, phonePart);

            // B8 (row 7, col 1) — project
            if (s.projectName() != null && !s.projectName().isBlank()) {
                setStr(sh, 7, 1, "Project  : " + s.projectName());
            }

            List<TicketItemDto> priceItems = ticket.items().stream()
                .filter(it -> it.approvedPrice() != null)
                .toList();

            BigDecimal subtotal = priceItems.stream()
                .map(item -> {
                    BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
                    return item.approvedPrice().multiply(qty);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Remarks block — B24 (row 23), B26 (row 25), B28 (row 27). Written BEFORE the layout
            // branch so the flow path captures the real (dynamic) note text when it relocates the
            // footer block below the last item.
            LocalDate offerDate = quotation.offerDate() != null ? quotation.offerDate() : issueDate;
            int depositPct = quotation.depositPercent() != null ? quotation.depositPercent() : 50;
            int deliveryDays = quotation.deliveryLeadDays() != null ? quotation.deliveryLeadDays() : 90;
            setStr(sh, 23, 1,
                "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  " + shortThaiDate(offerDate));
            setStr(sh, 25, 1,
                "2.บริษัทฯ ขอรับมัดจำ " + depositPct + "% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือขอรับ");
            setStr(sh, 27, 1,
                "3.ขณะนี้โรงงานผู้ผลิตประเทศอิตาลีมีสินค้าในสต็อก ระยะเวลานำเข้าประมาณ " + deliveryDays + " วัน");
            // B27 (row 26) — strip the template author's stray "+B27:B29" cell reference.
            Cell b27 = getOrKeep(sh, 26, 1);
            if (b27.getCellType() == CellType.STRING) {
                setStr(sh, 26, 1, b27.getStringCellValue().replace("+B27:B29", "").stripTrailing());
            }

            // ① Size the money columns to the actual numbers so nothing ever clips to "###", whatever
            //    the magnitude. This changes the total column width, so it must run before the width
            //    scale below is measured.
            BigDecimal vat = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
            sizeMoneyColumns(sh, priceItems, subtotal.add(vat));

            // Consistent page margins (corner padding) on every layout and every page — set before the
            // scale math below so the width/height equations account for them.
            applyMargins(sh);

            // Pick the layout from the real geometry rather than hardcoded row counts:
            //   contentH = letterhead + n·itemRow + footer      (all measured from the template)
            //   onePageScale = min(widthScale, printableHeight / contentH)
            // ② ≤ native item zone   → footer anchored at its native rows (fills one page like Excel).
            // ③ still fits one page at a readable scale (≥ MIN_SCALE) → shrink the whole quote to one
            //    page instead of stranding a near-empty second page.
            // ④ otherwise            → paginate at natural scale.
            int n = priceItems.size();
            if (n <= NATIVE_ITEM_CAPACITY) {
                renderSinglePage(sh, priceItems, subtotal);
            } else if (onePageScale(sh, n) >= MIN_SCALE) {
                renderOnePageShrunk(sh, priceItems, subtotal);
            } else {
                renderPaginated(sh, priceItems, subtotal);
            }

            wb.setForceFormulaRecalculation(true);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Quotation render failed: " + e.getMessage(), e);
        }
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

    // ── layout ──────────────────────────────────────────────────────────────────

    /**
     * ≤ {@link #NATIVE_ITEM_CAPACITY} items: fill items from the top, one row each, and leave the
     * footer block (notes/totals/signature) at its native template rows so it sits anchored near the
     * page bottom — filling the page exactly like the Excel template's "Save as PDF". The template's
     * own VAT/total formulas (I39/I40) are kept; only the subtotal (I38) is written.
     */
    private void renderSinglePage(Sheet sh, List<TicketItemDto> priceItems, BigDecimal subtotal) {
        int n = priceItems.size();
        // Sequence numbers only make sense with more than one line item — a single product shows
        // no "1." (per the document spec).
        for (int i = 0; i < n; i++) {
            fillItemRow(sh, ITEM_START_ROW + i, priceItems.get(i), n > 1 ? i + 1 : -1, true);
        }
        // Blank the template item-zone rows the items didn't reach: the pre-seeded "2."/"แผ่น"/"Net"
        // placeholder at row 12 and the per-row H/I formulas through row 21 would otherwise show as
        // phantom lines below the last real item. Content only — the footer stays put.
        for (int r = ITEM_START_ROW + n; r < FOOTER_START; r++) {
            for (int c = 0; c <= 8; c++) clearCell(sh, r, c);
        }
        setNum(sh, SUBTOTAL_ROW, 8, subtotal.doubleValue()); // I38; I39/I40 are template formulas
        getOrKeep(sh, SALESPERSON_FORMULA_ROW, 0).setBlank(); // lookup → "0" otherwise
        getOrKeep(sh, FORM_TAG_ROW, 8).setBlank();            // F-SM-002 tag, not customer-facing
        fitToOnePage(sh);
    }

    /**
     * More items than the native zone but still fits one page at ≥ {@link #MIN_SCALE}: flow the items
     * and relocate the footer right below the last one, then shrink the whole quote onto a single page
     * so a moderately long quote stays one page instead of stranding a near-empty second page.
     */
    private void renderOnePageShrunk(Sheet sh, List<TicketItemDto> priceItems, BigDecimal subtotal) {
        layoutFlowing(sh, priceItems, subtotal);
        fitToOnePage(sh);
    }

    /**
     * Too many items to fit one readable page: paginate. Items flow one per row and the notes/totals
     * flow immediately below the last item (kept near the items rather than stranded on a near-empty
     * page). The items + notes are gridded into one continuous bordered table, so a page break anywhere
     * closes cleanly on both sides; the letterhead + column-title row repeat on every page and each
     * page is numbered "หน้า X/Y".
     */
    private void renderPaginated(Sheet sh, List<TicketItemDto> priceItems, BigDecimal subtotal) {
        int delta = layoutFlowing(sh, priceItems, subtotal);

        // Grid the items AND the notes into one continuous bordered block (down to the last note row,
        // just above the totals). A break in either section then has a closing line on the page above
        // and the repeated bordered column-header row opens the table on the page below.
        closeItemTableBorders(sh, ITEM_START_ROW, SUBTOTAL_ROW - 1 + delta);

        // Print area = A..I content columns; repeat the letterhead AND the column-title row (rows 1-7,
        // the title row is 1-based row 7) at the top of every page. Number each page "หน้า X/Y".
        int idx = sh.getWorkbook().getSheetIndex(sh);
        sh.getWorkbook().setPrintArea(idx, 0, 8, 0, FOOTER_END + delta);
        sh.setRepeatingRows(CellRangeAddress.valueOf("A1:I7"));
        sh.getFooter().setCenter("หน้า &P/&N");
        fitToWidthPaginate(sh);
    }

    /**
     * Fill items one per row from ITEM_START_ROW (table styling cloned for rows past the template
     * zone) and relocate the footer block (notes/totals/signature) to sit immediately below the last
     * item, writing the totals as computed values at the moved rows. Returns the row offset (delta)
     * the footer was shifted by.
     */
    private int layoutFlowing(Sheet sh, List<TicketItemDto> priceItems, BigDecimal subtotal) {
        int n = priceItems.size();
        // 1. lift the footer block out of the way (cells + row heights + merged regions).
        List<CellRec> footer = captureBlock(sh, FOOTER_START, FOOTER_END);
        float[] heights = captureHeights(sh, FOOTER_START, FOOTER_END);
        List<int[]> merges = captureMerges(sh, FOOTER_START, FOOTER_END);
        clearBlock(sh, FOOTER_START, FOOTER_END);

        // 2. one item per row; clone the table styling for rows past the template's item zone.
        Row proto = sh.getRow(ITEM_STYLE_PROTO_ROW);
        for (int i = 0; i < n; i++) {
            int r = ITEM_START_ROW + i;
            if (r > 20 && proto != null) cloneRowStyle(sh, proto, r);
            fillItemRow(sh, r, priceItems.get(i), i + 1, true);
        }

        // 3. put the footer back, immediately after the last item (no gap row).
        int delta = (ITEM_START_ROW + n) - FOOTER_START;
        placeBlock(sh, footer, delta);
        applyHeights(sh, heights, FOOTER_START + delta);
        for (int[] m : merges) sh.addMergedRegion(new CellRangeAddress(m[0] + delta, m[1] + delta, m[2], m[3]));

        // 4. totals as computed values at the relocated rows (the template formulas' cell refs no
        //    longer point at the right cells after the move, so we write the numbers directly).
        BigDecimal vat = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        setNum(sh, SUBTOTAL_ROW + delta, 8, subtotal.doubleValue());
        setNum(sh, VAT_ROW + delta, 8, vat.doubleValue());
        setNum(sh, TOTAL_ROW + delta, 8, subtotal.add(vat).doubleValue());
        getOrKeep(sh, SALESPERSON_FORMULA_ROW + delta, 0).setBlank();
        getOrKeep(sh, FORM_TAG_ROW + delta, 8).setBlank();
        return delta;
    }

    // Give every item row a thin bottom border across all content columns (A..I), so a paginated item
    // table is closed on every page fragment rather than running off an open bottom edge. Styles are
    // cloned once per source style (workbooks cap at a few thousand styles).
    private void closeItemTableBorders(Sheet sh, int firstRow, int lastRow) {
        Workbook wb = sh.getWorkbook();
        java.util.Map<Short, CellStyle> cache = new java.util.HashMap<>();
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
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
    }

    // ── dynamic-layout geometry & sizing ──────────────────────────────────────────

    // ① Size each money column to the widest number it will actually show, so values never clip to
    //    "###" whatever the magnitude. E/H (unit price, net) size to the largest unit price; I
    //    (amount) sizes to the grand total — the biggest number on the document.
    private void sizeMoneyColumns(Sheet sh, List<TicketItemDto> items, BigDecimal grandTotal) {
        BigDecimal maxUnit = BigDecimal.ZERO;
        for (TicketItemDto it : items) {
            if (it.approvedPrice() != null && it.approvedPrice().compareTo(maxUnit) > 0) maxUnit = it.approvedPrice();
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
            - (sh.getMargin(Sheet.LeftMargin) + sh.getMargin(Sheet.RightMargin)) * 72.0;
        return columnsW <= 0 ? 1.0 : Math.min(1.0, printableW / columnsW);
    }

    // ③ Scale at which the whole quote (letterhead + n items + footer) fits on one page — the min of
    //    the width scale and the height-fit scale.
    private double onePageScale(Sheet sh, int n) {
        double contentH = sumRowHeights(sh, 0, ITEM_START_ROW - 1)      // letterhead + column titles
                        + n * itemRowHeight(sh)                          // items
                        + sumRowHeights(sh, FOOTER_START, FOOTER_END);   // notes + totals + signature
        double printableH = A4_HEIGHT_PT
            - (sh.getMargin(Sheet.TopMargin) + sh.getMargin(Sheet.BottomMargin)) * 72.0;
        return Math.min(naturalWidthScale(sh), printableH / contentH);
    }

    // Consistent page margins (inches) on every layout so content has corner padding and every page
    // lines up the same way. Applied before the scale equations so they account for the padding.
    private void applyMargins(Sheet sh) {
        sh.setMargin(Sheet.LeftMargin, 0.3);
        sh.setMargin(Sheet.RightMargin, 0.3);
        sh.setMargin(Sheet.TopMargin, 0.3);
        sh.setMargin(Sheet.BottomMargin, 0.4);   // extra room for the "หน้า X/Y" footer
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

    private void fillItemRow(Sheet sh, int r, TicketItemDto item, int seq, boolean writeAmounts) {
        BigDecimal qty = item.qty() != null ? item.qty() : BigDecimal.ONE;
        BigDecimal price = item.approvedPrice();
        if (seq > 0) setNum(sh, r, 0, seq); else clearCell(sh, r, 0); // A: sequence
        setStr(sh, r, 1, buildDesc(item));                           // B: description
        setNum(sh, r, 2, qty.doubleValue());                         // C: qty
        setStr(sh, r, 3, nullSafe(item.rawUnit(), "แผ่น"));          // D: unit
        setNum(sh, r, 4, price.doubleValue());                       // E: unit price
        setStr(sh, r, 6, "Net");                                     // G: ส่วนลด label
        if (writeAmounts) {
            // Flow rows past the template zone have no per-row H/I formula, so write both directly.
            setNum(sh, r, 7, price.doubleValue());                   // H: คงเหลือ (net)
            setNum(sh, r, 8, price.multiply(qty).doubleValue());     // I: เป็นเงิน (amount)
        }
    }

    // Single page: fit the whole quote onto exactly one page (1 wide × 1 tall), like Excel's
    // "fit to 1×1" print setting on the template. With the correct narrow Thai fonts installed this
    // lands at ≈ the template's native 86% scale; LibreOffice shrinks a hair more if needed so the
    // anchored footer never spills to a 2nd page.
    private void fitToOnePage(Sheet sh) {
        sh.setFitToPage(true);
        sh.setAutobreaks(true);
        PrintSetup ps = sh.getPrintSetup();
        ps.setLandscape(false);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 1);
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

    private String buildDesc(TicketItemDto item) {
        StringBuilder sb = new StringBuilder("กระเบื้อง");
        if (item.model() != null && !item.model().isBlank())   sb.append(" รุ่น ").append(item.model());
        if (item.color() != null && !item.color().isBlank())   sb.append(" สี ").append(item.color());
        if (item.size() != null && !item.size().isBlank())     sb.append(" ขนาด ").append(item.size()).append(" cm.");
        if (item.texture() != null && !item.texture().isBlank()) sb.append(" ").append(item.texture());
        return sb.toString();
    }

    // Preserve template cell style — get existing cell, only create if absent
    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        // Drop any template formula (e.g. B4 =TODAY()) so our literal value wins;
        // otherwise POI keeps the formula and LibreOffice recalculates + reformats it.
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) cell.setBlank();
        cell.setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        Cell cell = getOrKeep(sh, rowIdx, colIdx);
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) cell.setBlank();
        cell.setCellValue(value);
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

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private String shortThaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + (d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }
    private String nullSafe(String s, String fallback) { return (s != null && !s.isBlank()) ? s : fallback; }

    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
}
