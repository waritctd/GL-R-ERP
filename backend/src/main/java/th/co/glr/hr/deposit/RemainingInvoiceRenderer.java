package th.co.glr.hr.deposit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Renders ใบแจ้งหนี้ส่วนที่เหลือ (remaining invoice) by filling dynamic cells
 * into the official template. Template cells with formulas (H/I columns) are
 * left intact so Excel auto-calculates amounts on open.
 *
 * <p><b>Template layout, dumped cell-by-cell with POI before this branch touched anything</b>
 * (0-based rows below; 1-based in the template's own UI):
 * <ul>
 *   <li>Row 10 (0-based 9): column headers (ลำดับ/รายละเอียด/.../เป็นเงิน).</li>
 *   <li>Row 11 (0-based 10): reserved for "Project : ..." (B11) — never an item row.</li>
 *   <li>Rows 13-34 (0-based 12-33): the item/deposit-row zone this class writes into.</li>
 *   <li>Row 35 (0-based 34): "หมายเหตุ" header (B35) — static, never overwritten.</li>
 *   <li>Rows 36-41 (0-based 35-40): the six numbered หมายเหตุ lines (B36..B41).</li>
 *   <li>Row 42 (0-based 41): I42 = SUM(I11:I41) — "รวมเป็นเงิน".</li>
 *   <li>Row 43 (0-based 42): I43 = I42*H43 (H43 = 0.07) — VAT.</li>
 *   <li>Row 44 (0-based 43): I44 = SUM(I42:I43) — total payable.</li>
 * </ul>
 * <b>This matters because the old {@code MAX_ITEM_ROWS = 28} blanked rows 13-41 inclusive</b> —
 * which wiped the หมายเหตุ block's static text on every single render, independent of how many
 * items a deal had. The real usable zone for items + the deposit-deduction row is rows 13-34
 * (0-based 12-33): 22 rows, ending BEFORE the หมายเหตุ header at row 35. Both zones stay inside
 * I42's {@code SUM(I11:I41)} range, so writing real numbered notes into rows 36-41 does not
 * disturb the total (those rows' own H/I formulas evaluate to "" when C/E are blank, and SUM
 * ignores text).
 */
@Component
public class RemainingInvoiceRenderer {

    private static final String TEMPLATE = "templates/remaining_invoice_template.xls";
    private static final int ITEM_START_ROW = 12; // 0-based (= row 13 in 1-based)
    // Rows 13-34 (1-based) = 22 rows available for items + the deposit-deduction row, BEFORE the
    // หมายเหตุ header at row 35 (0-based 34). Capacity MUST count the deposit row — a caller
    // sizing "items + 1 deposit row" against this constant gets the real ceiling.
    public static final int MAX_ITEM_ROWS = 22;
    private static final int NOTES_START_ROW = 35; // 0-based (= row 36 in 1-based, first of B36..B41)
    // Public: DepositNoticeService gates on this BEFORE rendering (options: blockingReason;
    // file: 409) using the SAME wrapNotes this class writes with, so the check and the actual
    // render can never disagree about what fits — see wrapNotes's own Javadoc.
    public static final int MAX_NOTE_LINES  = 6;  // rows 36-41 (1-based)
    // Measured against the template's own pre-authored หมายเหตุ lines (remaining_invoice_template
    // .xls rows 36-41 as shipped): the longest that renders without overflowing column B is 63
    // characters ("2.จ่ายเช็คในนามบริษัท จี แอล แอนด์ อาร์ แทปส์ แอนด์ ไทลส์ จำกัด"). Kept a few
    // characters under that as a margin rather than riding the exact limit.
    private static final int NOTE_LINE_CHAR_BUDGET = 58;

    private static final String[] THAI_MONTHS = {
        "มกราคม","กุมภาพันธ์","มีนาคม","เมษายน","พฤษภาคม","มิถุนายน",
        "กรกฎาคม","สิงหาคม","กันยายน","ตุลาคม","พฤศจิกายน","ธันวาคม"
    };

    public byte[] toXlsx(RemainingInvoiceDto doc) throws Exception {
        List<RemainingInvoiceItemDto> items = doc.items() != null ? doc.items() : List.of();
        boolean hasDeposit = doc.depositAmount() != null && doc.depositAmount().compareTo(BigDecimal.ZERO) != 0;
        int rowsNeeded = items.size() + (hasDeposit ? 1 : 0);
        // Defensive-only: DepositNoticeService#getRemainingInvoiceXlsx gates this BEFORE calling
        // the renderer (with the Thai-facing ApiException the controller returns), so a caller
        // going through the normal service path never reaches this. Kept here so a future direct
        // caller of this class cannot silently truncate rows the old code used to drop — "never
        // truncate" holds even if the service-level gate is ever bypassed or refactored away.
        if (rowsNeeded > MAX_ITEM_ROWS) {
            throw new IllegalStateException(
                "remaining invoice needs " + rowsNeeded + " rows (items=" + items.size()
                + ", deposit=" + (hasDeposit ? 1 : 0) + ") but the template only has " + MAX_ITEM_ROWS);
        }

        try (InputStream tpl = new ClassPathResource(TEMPLATE).getInputStream();
             Workbook wb = WorkbookFactory.create(tpl);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);

            // Finding 2 (discount label clipping): column G (ส่วนลด) ships far narrower than the
            // money columns beside it — 1,177 width-units (~23pt / ~8mm) vs column E (ราคา) / H
            // (คงเหลือ)'s own 3,020 (~60pt) — so even the shipped template's OWN "พิเศษ" example (5
            // Thai chars) or a two-decimal discount like "ลด 43.17" (8 chars, well under the old
            // DISCOUNT_LABEL_CHAR_BUDGET=10) overflow the column's REAL rendered width and print
            // clipped in LibreOffice's PDF — DISCOUNT_LABEL_CHAR_BUDGET was checking string
            // LENGTH, never the column's actual pixel capacity, and the two had drifted apart.
            // Measured with this codebase's own column-width/font machinery (LibreOfficeMetrics +
            // FontResolver — the same tools QuotationRenderer's own column-fitting code uses, not
            // guessed): at the resolved row-13 font (Browallia New 14pt, or whatever fontconfig
            // substitutes it to on a fontless host), column E/H's own width comfortably fits every
            // realistic discount label up to 11 characters ("ลด 123456.78" is the first to
            // overflow, at 12) with room to spare. Matching column G to E/H — rather than
            // truncating the label or inventing an unrelated number — both fixes the clipping and
            // keeps every column in this row visually consistent; widening never disturbs the rest
            // of the layout (rows-13-34 zone, หมายเหตุ block, summary rows are unaffected — the
            // sheet's own fit-to-page zoom simply absorbs the wider row, see
            // rendersWithoutFormulaErrorsAsASinglePageWithTheCorrectTotal's continued single-page
            // assertion).
            int moneyColumnWidth = sh.getColumnWidth(4); // column E (ราคา) — same width as H (คงเหลือ)
            if (sh.getColumnWidth(6) < moneyColumnWidth) {
                sh.setColumnWidth(6, moneyColumnWidth);
            }

            // ── Header block ──────────────────────────────────────────────────
            // H7 = date (override =TODAY() formula)
            setStr(sh, 6, 7, thaiDate(doc.issueDate() != null ? doc.issueDate() : LocalDate.now()));
            // B7 = "<customerName> (<branch>) / เลขประจำตัวผู้เสียภาษี : <taxId>" — omitting
            // whichever parts are null, so a customer with no branch/taxId on file still prints a
            // clean name instead of "ACME () / เลขประจำตัวผู้เสียภาษี : ".
            setStr(sh, 6, 1, customerHeaderLine(doc));
            // H8 = document number
            setStr(sh, 7, 7, nullSafe(doc.docNumber()));
            // B8 = customer address (override broken VLOOKUP)
            setStr(sh, 7, 1, nullSafe(doc.customerAddress()));
            // H9 = reference (quotation number or a free-text customer PO) — blank when the
            // caller cleared it, exactly as typed (never the deposit reference; see
            // RemainingInvoiceDto's own header comment for why those two are separate fields now).
            if (doc.reference() != null && !doc.reference().isBlank()) {
                setStr(sh, 8, 7, doc.reference());
            } else {
                blank(sh, 8, 7);
            }
            // B11 = project name
            setStr(sh, 10, 1, "Project : " + nullSafe(doc.projectName()));

            // ── Item rows ─────────────────────────────────────────────────────
            // Style normalization (finding 2a): the template's own rows 14-34 carry INCONSISTENT
            // leftover per-row styling from however the sample was originally authored — amounts
            // from ~row 16 on print grey instead of black, some cells underlined, and a negative
            // number (the deposit-deduction row) prints as a plain "-18,557.09" instead of the
            // accounting format's "(18,557.09)" row 13 itself uses. Cloning row 13's own
            // CellStyle onto every column A-I of every row in the zone, BEFORE writing any value,
            // makes every item row — and the deposit row, which shares this same loop/zone — look
            // and format exactly like the first one. Column J (index 9) is intentionally left
            // unstyled (only ever blanked below) — it is not a data column this renderer writes.
            CellStyle[] templateColumnStyles = new CellStyle[9];
            Row templateRow = sh.getRow(ITEM_START_ROW);
            for (int c = 0; c <= 8; c++) {
                Cell templateCell = templateRow != null ? templateRow.getCell(c) : null;
                templateColumnStyles[c] = templateCell != null ? templateCell.getCellStyle() : null;
            }
            // Clear the item/deposit-row zone (rows 13-34) — NOT the หมายเหตุ block below it,
            // which this method writes separately further down. setBlank keeps whatever style was
            // just applied above (font, borders, number format) — c<=9 covers A..J, including
            // column J's stale "พิเศษ" backup price (row 13 in the shipped template).
            for (int r = ITEM_START_ROW; r < ITEM_START_ROW + MAX_ITEM_ROWS; r++) {
                for (int c = 0; c <= 9; c++) {
                    Cell cell = getOrCreate(sh, r, c);
                    if (c <= 8 && templateColumnStyles[c] != null) cell.setCellStyle(templateColumnStyles[c]);
                    cell.setBlank();
                }
            }
            for (int i = 0; i < items.size(); i++) {
                RemainingInvoiceItemDto item = items.get(i);
                int r = ITEM_START_ROW + i;
                double unitPrice = item.unitPrice() != null ? item.unitPrice().doubleValue() : 0d;
                double netPrice = item.netUnitPrice() != null ? item.netUnitPrice().doubleValue() : unitPrice;
                double qty = item.qty() != null ? item.qty().doubleValue() : 0d;
                double amount = item.amount() != null ? item.amount().doubleValue() : netPrice * qty;
                setNum(sh, r, 0, item.seq() > 0 ? item.seq() : i + 1); // A ลำดับ
                setStr(sh, r, 1, item.description());                 // B รายละเอียด
                setNum(sh, r, 2, qty);                                // C จำนวน
                setStr(sh, r, 3, item.unit());                        // D หน่วย
                setNum(sh, r, 4, unitPrice);                          // E ราคา
                if (item.discountLabel() != null && !item.discountLabel().isBlank()) {
                    // G ส่วนลด — real label (never "Net"/"พิเศษ"), but column G is narrow (finding
                    // 2b: the old "ส่วนลด X ต่อหน่วย" clipped). RemainingInvoiceItemDto's own
                    // producers already write the short "ลด X" form; this is a defensive clamp for
                    // any OTHER source (e.g. a legacy deposit-notice item's own discountLabel,
                    // authored under the old convention) so column G can never overflow regardless
                    // of where the label came from.
                    setStr(sh, r, 6, clampDiscountLabel(item.discountLabel()));
                }
                setNum(sh, r, 7, netPrice);                           // H คงเหลือ (net)
                setNum(sh, r, 8, amount);                             // I เป็นเงิน — the quotation's/deposit-
                                                                       // notice's OWN stored amount, direct.
            }

            // ── Deposit deduction row ─────────────────────────────────────────
            // Directly after the last item; C=-1 and I=-deposit render it as a deduction so
            // SUM(I11:I41) = items − deposit = remaining balance (VAT/total then compute on it).
            // Label cites depositReference — NEVER doc.reference() (that's H9's quotation/PO
            // reference; conflating the two was the pre-existing bug).
            if (hasDeposit) {
                int r = ITEM_START_ROW + items.size();
                double deposit = doc.depositAmount().doubleValue();
                String depositRef = doc.depositReference();
                String depositLabel = "หัก  มัดจำ"
                    + (depositRef != null && !depositRef.isBlank() ? "  " + depositRef : "");
                setStr(sh, r, 1, depositLabel);       // B
                setNum(sh, r, 2, -1.0);               // C = -1
                setNum(sh, r, 4, deposit);            // E = deposit
                setNum(sh, r, 7, deposit);             // H net
                setNum(sh, r, 8, -deposit);           // I = -deposit (direct)
            }

            // ── หมายเหตุ block (rows 36-41 = B36..B41 in 1-based) ───────────────
            // Always rewritten (numbered, replacing the template's static example lines) and
            // padded with blanks for any unused line — otherwise a caller who selects fewer notes
            // than the template shipped with would see stale example text below their real ones.
            // Wrapped onto continuation rows within the 6-line budget (finding 2c) — the caller
            // (DepositNoticeService) already refused anything that does not fit, using this SAME
            // wrapNotes, so rowsNeeded here is always <= MAX_NOTE_LINES for a caller going through
            // the normal service path.
            List<String> notes = doc.notes() != null ? doc.notes() : List.of();
            List<String> noteLines = wrapNotes(notes);
            if (noteLines.size() > MAX_NOTE_LINES) {
                // Defensive-only, mirroring MAX_ITEM_ROWS's own guard above: the service gates
                // this before ever calling the renderer, so a normal caller never reaches here.
                throw new IllegalStateException(
                    "remaining invoice notes need " + noteLines.size() + " lines but the template only has "
                    + MAX_NOTE_LINES);
            }
            for (int i = 0; i < MAX_NOTE_LINES; i++) {
                int r = NOTES_START_ROW + i;
                if (i < noteLines.size()) {
                    setStr(sh, r, 1, noteLines.get(i));
                } else {
                    blank(sh, r, 1);
                }
            }

            // ── Summary rows auto-calculated by existing formulas ─────────────
            // I42 = SUM(I11:I41) includes item amounts and -depositAmount → remainder
            // I43 = I42 * 0.07 (VAT)
            // I44 = I42 + I43 (total payable)
            wb.setForceFormulaRecalculation(true);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String customerHeaderLine(RemainingInvoiceDto doc) {
        StringBuilder sb = new StringBuilder(nullSafe(doc.customerName()));
        if (doc.customerBranch() != null && !doc.customerBranch().isBlank()) {
            sb.append(" (").append(doc.customerBranch()).append(')');
        }
        if (doc.customerTaxId() != null && !doc.customerTaxId().isBlank()) {
            sb.append(" / เลขประจำตัวผู้เสียภาษี : ").append(doc.customerTaxId());
        }
        return sb.toString();
    }

    /**
     * Lays out numbered notes ("1. ...", "2. ...", ...) onto physical B36..B41 lines, wrapping any
     * note whose text exceeds {@link #NOTE_LINE_CHAR_BUDGET} onto unnumbered continuation lines —
     * finding 2c: the template's own example notes already do exactly this (note 2 in the shipped
     * template spans 3 physical lines), but the previous renderer wrote one note per row
     * unconditionally, running a long note past column B's right border. Public + static so
     * DepositNoticeService can call the SAME function to decide "does this selection fit" (options:
     * blockingReason; file: 409) before ever invoking {@link #toXlsx} — the check and the actual
     * render can never disagree about what fits.
     */
    public static List<String> wrapNotes(List<String> notes) {
        List<String> lines = new ArrayList<>();
        if (notes == null) return lines;
        for (int i = 0; i < notes.size(); i++) {
            String text = notes.get(i) != null ? notes.get(i) : "";
            lines.addAll(wrapOneNote((i + 1) + ". ", text));
        }
        return lines;
    }

    /** Greedy word-wrap: fills each line up to {@link #NOTE_LINE_CHAR_BUDGET} characters, breaking
     * on spaces. The first line carries {@code prefix} (the note's own number); continuation lines
     * carry none, matching the template's own example ("2.จ่ายเช็ค..." / "หรือโอนเงิน..." — no
     * repeated "2." on the wrapped line). A single word longer than the whole budget (not expected
     * in practice — Thai note text is authored with spaces, per the template's own examples) is
     * placed on its own line rather than split mid-word. */
    private static List<String> wrapOneNote(String prefix, String text) {
        List<String> out = new ArrayList<>();
        String[] words = text.isBlank() ? new String[0] : text.trim().split("\\s+");
        StringBuilder current = new StringBuilder(prefix);
        boolean lineHasWord = false;
        for (String word : words) {
            int extra = (lineHasWord ? 1 : 0) + word.length();
            if (lineHasWord && current.length() + extra > NOTE_LINE_CHAR_BUDGET) {
                out.add(current.toString());
                current = new StringBuilder();
                lineHasWord = false;
            }
            if (lineHasWord) current.append(' ');
            current.append(word);
            lineHasWord = true;
        }
        out.add(current.toString()); // always at least the prefix line, even for blank text
        return out;
    }

    // Last-resort clamp for anything that doesn't already stay short (see
    // itemsFromQuotationForRemainingInvoice's own "ลด X" form, which does) — column G is widened
    // to match column E/H just above, but a legacy/foreign discountLabel could still be
    // arbitrarily long. 11 characters is measured, not guessed (see toXlsx's own column-widening
    // comment): at the widened column's real width, every realistic "ลด <amount>" label up to 11
    // characters fits without overflowing ("ลด 123456.78", 12 characters, is the first to
    // overflow) — this used to be 10 against the OLD, un-widened column, which was already wrong
    // (an 8-character "ลด 43.17" clipped under that budget despite being "under budget").
    private static final int DISCOUNT_LABEL_CHAR_BUDGET = 11;

    private String clampDiscountLabel(String label) {
        return label.length() > DISCOUNT_LABEL_CHAR_BUDGET
            ? label.substring(0, DISCOUNT_LABEL_CHAR_BUDGET - 1) + "…" : label;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setStr(Sheet sh, int rowIdx, int colIdx, String value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value != null ? value : "");
    }

    private void setNum(Sheet sh, int rowIdx, int colIdx, double value) {
        getOrCreate(sh, rowIdx, colIdx).setCellValue(value);
    }

    /** Blank an existing cell (content + formula), keeping its style — used to wipe the template's
     * pre-filled example rows before writing the real data. No-op if the cell doesn't exist. */
    private void blank(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) return;
        Cell cell = row.getCell(colIdx);
        if (cell != null) cell.setBlank();
    }

    private Cell getOrCreate(Sheet sh, int rowIdx, int colIdx) {
        Row row = sh.getRow(rowIdx);
        if (row == null) row = sh.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        // Writing a literal onto a template formula cell (B8 =VLOOKUP(#REF!…), H7 =TODAY(), or the
        // net-price H column) must clear the formula first: POI's setCellValue only sets the cached
        // result, so LibreOffice recalculates on PDF export and the literal is lost (→ #REF!/#N/A,
        // or the row-13 example's stale "พิเศษ"/J price). setBlank keeps the cell style/format.
        if (cell.getCellType() == CellType.FORMULA) cell.setBlank();
        return cell;
    }

    private String thaiDate(LocalDate d) {
        if (d == null) return "";
        return d.getDayOfMonth() + " " + THAI_MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    @SuppressWarnings("unused")
    private String fmt2(BigDecimal v) {
        if (v == null) return "-";
        return String.format(Locale.US, "%,.2f", v);
    }
}
