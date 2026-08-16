package th.co.glr.hr.importrequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Draws an import request onto the business's own F-SM-001 (04) form and returns it ready to print
 * and sign.
 *
 * <p><b>Why the business's file and not a replica.</b> F-SM-001 is an ISO controlled form — it
 * carries the CB and UKAS certification marks and its own revision code in the footer — so the
 * output has to be that document with our data on it, not a lookalike we drew. This is the same
 * decision {@code LorYor01Renderer} records for ล.ย.01, and for the same reason: a rebuilt
 * template is a new document that merely resembles the controlled one.
 * {@code forms/import_request_fsm001_04.pdf} is the owner-supplied file, byte-for-byte
 * (sha256 {@value #TEMPLATE_SHA256}). Do not re-save, re-export or "optimise" it — every
 * coordinate below is measured against those exact bytes and {@code ImportRequestRendererTest}
 * pins the checksum so a swap fails loudly rather than silently shifting the overlay.
 *
 * <h2>Why everything is drawn, and nothing is filled</h2>
 *
 * The file has an {@code /AcroForm} entry but <strong>zero fields</strong> — it is a Word 2010
 * export, and the empty dictionary is an artefact of that export rather than a form we can fill.
 * So unlike ล.ย.01 (39 real text fields) there is nothing here to set: every value is drawn into an
 * appended content stream at a measured coordinate. That also means there is no
 * {@code PDAcroForm.flatten()} step and no risk of the subsetting hazard ล.ย.01 documents — that
 * hazard is specific to field appearance streams, and its own Javadoc notes subsetting stays safe
 * for renderers that "draw straight into a page content stream", which is all this class does.
 *
 * <h2>How the coordinates were obtained</h2>
 *
 * Not by eye. The template's vector rules were extracted with a {@code PDFGraphicsStreamEngine}
 * probe, giving the eight column rules and a body of 26 rows at a 22.2077pt pitch (cluster the
 * rule edges before measuring — see {@link #ROW_PITCH}); the printed labels were located with a
 * {@code PDFTextStripper} probe. The owner also supplied a filled example
 * (IR69068, Padana, 6/3/26), which is the SAME form re-exported at a different scale
 * ({@code filled ≈ 1.1274·blank − 4.78} in x, {@code 1.1211·blank − 4.64} in y — fitted from four
 * shared landmarks and accurate to under half a point). Mapping that example's glyphs back through
 * the inverse of that fit is where the value positions below come from, so each one is where the
 * business actually writes it rather than where it seemed reasonable to put it.
 *
 * <p>Two things in the owner's example are NOT in its text layer and are therefore not modelled
 * here: the {@code IR69068} number itself and an {@code AI2600078} reference, both pasted in as
 * overlays, along with a Teams screenshot and a spec-list image. The number is printed on its
 * dotted rule below; the rest are human annotations to a paper document, not form fields.
 *
 * <h2>Dates</h2>
 *
 * The example mixes conventions and this class reproduces that rather than unifying it: the
 * document NUMBER carries the Buddhist year ({@code IR69068} = IR + 2569 + sequence 068) while the
 * printed dates use a 2-digit Christian year ({@code 6/3/26}, {@code Within 21/5/26}). Numbering
 * lives in the repository; only the date rendering is here.
 */
@Component
public class ImportRequestRenderer {

    static final String TEMPLATE = "forms/import_request_fsm001_04.pdf";
    static final String TEMPLATE_SHA256 =
        "e0c1fa1c5c184170bd860115d9dd81a83bb5de9f03d0c18e78bbe3371a8d3171";
    private static final String THAI_FONT = "fonts/Sarabun-Regular.ttf";

    // ── Table geometry, PDF user space (origin BOTTOM-left), page 595.32 x 841.92 ──────────────
    // Column rules as measured; each printed rule is ~0.85pt wide and these are its left edge.
    private static final float COL_ITEM_L     =  30.0f;
    private static final float COL_CODE_L     =  50.7f;
    private static final float COL_SIZE_L     = 241.0f;
    private static final float COL_QTY_L      = 305.5f;
    private static final float COL_UNIT_L     = 348.0f;
    private static final float COL_FOR_L      = 385.0f;   // "สั่งมาให้"
    private static final float COL_REQBY_L    = 437.5f;   // "Requested by"
    private static final float COL_WANTED_L   = 500.8f;   // "กำหนดวันที่ต้องการของ"
    private static final float TABLE_R        = 565.3f;

    /** Bottom rule of the header row = top rule of body row 1. */
    private static final float BODY_TOP_Y = 743.6f;
    /**
     * Row height, top rule to top rule: 27 body rules span 743.6 down to 166.2, so
     * {@code 577.4 / 26 = 22.2077} (individual gaps measure 22.1–22.3, i.e. the template's own
     * rounding).
     *
     * <p><b>This was 21.35 and that was wrong.</b> Each printed rule is ~0.9pt thick and therefore
     * reports TWO y values to a graphics probe — its top and its bottom edge. 21.35 is the gap from
     * one rule's BOTTOM edge to the next rule's TOP edge, i.e. the row's interior, not its pitch.
     * Being 0.86pt short per row, it accumulated: rows 1–5 looked fine and by row 6 the text was
     * sitting on the rules. Cluster the edges before measuring a pitch.
     */
    private static final float ROW_PITCH  = 22.2077f;
    /** Body rows between the header rule and the table's bottom rule at y≈166.2. */
    public static final int BODY_ROWS = 26;
    /** Lifts a baseline off the rule it sits on, matching the printed header row's own inset. */
    private static final float TEXT_INSET = 7.0f;
    /** Keeps a glyph clear of the vertical rule beside it. */
    private static final float CELL_PAD = 3.0f;

    /**
     * Lifts every value that sits on one of the form's printed dotted rules.
     *
     * <p>Measured placement put these values on the label's own baseline — geometrically exact, and
     * wrong to look at: a dotted rule on a paper form is something you write ABOVE, not on, so text
     * sharing the dots' baseline reads as sagging into them. (Confirmed by probing this renderer's
     * own output: value and label both reported the identical baseline, so the misalignment was
     * perceptual, not a coordinate error — which is why the fix is a deliberate offset rather than a
     * corrected constant.) Applies only to dotted-rule values; table cells sit inside ruled boxes
     * and are positioned by the cell, not by a rule.
     */
    private static final float RULE_LIFT = 2.0f;

    // ── Header ────────────────────────────────────────────────────────────────────────────────
    private static final float REF_NO_X   = 350.0f;
    private static final float REF_NO_Y   = 793.44f;
    private static final float BRAND_X    =  86.3f;
    private static final float REQ_DATE_X = 410.1f;
    private static final float HEADER_Y   = 778.44f;

    // ── Footer. The three date rules are printed as dd/mm/yy with the slashes already there, so
    //    each component is centred in the gap between them rather than written as one string. ───
    private static final float VESSEL_ETA_X = 148.0f;
    private static final float VESSEL_ETA_Y = 140.42f;

    private static final float CHECKED_Y     = 117.98f;
    private static final float CHECKED_BY_X  = 300.0f;
    private static final float[] CHECKED_DATE_CENTRES = {112.0f, 139.6f, 170.0f};

    private static final float APPROVE_Y     =  95.66f;
    private static final float APPROVE_BY_X  = 293.0f;
    private static final float[] APPROVE_DATE_CENTRES = {111.0f, 138.4f, 169.0f};

    private static final float REQUEST_BY_X  = 124.4f;
    private static final float FOOTER_Y      =  56.42f;
    private static final float[] DEPOSIT_DATE_CENTRES = {316.0f, 343.0f, 372.0f};

    /** "หน้า X/Y", left of the printed F-SM-001 (04) code. Multi-sheet forms only. */
    private static final float PAGE_MARK_X = 400.0f;
    private static final float PAGE_MARK_Y =  20.64f;

    // ── Type ──────────────────────────────────────────────────────────────────────────────────
    private static final float BODY_SIZE   = 9.0f;
    private static final float HEADER_SIZE = 9.5f;
    /** Below this, shrink-to-fit gives up and the value is truncated instead. */
    private static final float MIN_SIZE    = 6.0f;
    /**
     * No thousands separator, deliberately: the owner's example writes {@code 2370}, not
     * {@code 2,370}. Grouping would be easier to read and is what every other money/qty surface in
     * this codebase does — but this is a controlled form being reproduced, and matching what the
     * business actually prints beats improving it.
     */
    private static final DecimalFormat QTY = new DecimalFormat("0.##");

    /**
     * Table rows {@code lines} needs. A line with a note occupies TWO printed rows (the line, then
     * its note underneath) — exactly how the owner's example lays out "สั่งตามPO". One further row
     * is taken by the {@code Project : ...} header line when present.
     *
     * <p>Exposed so the service can refuse a form that will not fit rather than silently dropping
     * lines off the bottom: F-SM-001 has no continuation page.
     */
    public static int rowsRequired(ImportRequestFormData data) {
        return blocks(data).stream().mapToInt(List::size).sum();
    }

    /**
     * Sheets this form needs.
     *
     * <p>NOT {@code ceil(rows / BODY_ROWS)}: a line and its note are one indivisible block, so a
     * page that has a single row left cannot take a two-row line and finishes short. The count
     * therefore comes from the actual packing, never from arithmetic on the row total.
     */
    public static int pagesRequired(ImportRequestFormData data) {
        return paginate(data).size();
    }

    /**
     * Renders the form, paginating onto further copies of F-SM-001 when the lines do not fit.
     *
     * <p><b>Pagination follows {@code QuotationRenderer}'s model</b> (owner instruction: "use the
     * same way as the quotation"): the letterhead — here the Brand / ReF. No. / Request date block
     * and the printed column titles, which come with the template — repeats on every sheet, and
     * each sheet is numbered {@code หน้า X/Y}. The page marker appears ONLY on a multi-sheet form,
     * because a single-sheet F-SM-001 has never carried one and adding it would change what a
     * one-page form looks like.
     *
     * <p><b>The quotation's middle case is deliberately not reproduced.</b> That renderer has three
     * strategies — fit natively, shrink onto one page at ≥ MIN_SCALE, else paginate — and the
     * shrink is only available to it because POI regenerates the whole layout. Here the grid is
     * printed into the controlled form at a fixed size; scaling it would scale the certification
     * marks and the F-SM-001 code with it and produce a document that is no longer the controlled
     * form. So this renderer fits or paginates, with nothing in between.
     *
     * <p>Extra sheets are imported from freshly loaded copies of the template, all held open until
     * save: {@code importPage} does not deep-copy a page's resources, so closing a source document
     * early would leave the imported page referencing streams that have gone.
     */
    public byte[] render(ImportRequestFormData data) throws IOException {
        List<List<PrintRow>> pages = paginate(data);
        int pageCount = pages.size();
        List<PDDocument> sources = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(templateBytes())) {
            for (int i = 1; i < pageCount; i++) {
                PDDocument extra = Loader.loadPDF(templateBytes());
                sources.add(extra);
                doc.importPage(extra.getPage(0));
            }
            PDType0Font font = loadThaiFont(doc);

            for (int p = 0; p < pageCount; p++) {
                List<PrintRow> pageRows = pages.get(p);
                try (PDPageContentStream cs = new PDPageContentStream(
                         doc, doc.getPage(p), AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    drawHeader(cs, font, data);
                    drawBody(cs, font, data, pageRows);
                    drawFooter(cs, font, data);
                    if (pageCount > 1) {
                        show(cs, font, "หน้า " + (p + 1) + "/" + pageCount,
                             PAGE_MARK_X, PAGE_MARK_Y, BODY_SIZE);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } finally {
            for (PDDocument source : sources) {
                source.close();
            }
        }
    }

    /** One printed table row. */
    private record PrintRow(String seq, String code, float codeX, String size, String qty,
                            String unit) {}

    /**
     * The body as INDIVISIBLE blocks: the {@code Project :} line is one row of its own, and a line
     * with a note is two rows that must not be separated.
     *
     * <p>This exists because chunking the flat row list every {@link #BODY_ROWS} is not safe. It
     * looks safe — and on the first overflow case tried here it happened to break cleanly — but
     * that was arithmetic luck (1 project row + 13 two-row lines is exactly 27). Shift the count by
     * one and the page boundary lands between a line and its own {@code สั่งตามPO}, stranding the
     * note at the top of the next sheet under a different item number. Packing blocks makes that
     * unrepresentable rather than unlikely.
     */
    private static List<List<PrintRow>> blocks(ImportRequestFormData data) {
        List<List<PrintRow>> blocks = new ArrayList<>();
        if (!isBlank(data.projectName())) {
            // The example indents this into the Code column rather than starting at the Item rule.
            blocks.add(List.of(new PrintRow(null, "Project : " + data.projectName(),
                                            COL_CODE_L + 12f, null, null, null)));
        }
        for (ImportRequestFormData.Line line : data.lines()) {
            PrintRow head = new PrintRow(String.valueOf(line.seq()), line.code(),
                                         COL_CODE_L + CELL_PAD, line.size(), qty(line.qty()),
                                         line.unit());
            blocks.add(isBlank(line.note())
                ? List.of(head)
                : List.of(head, new PrintRow(null, line.note(), COL_CODE_L + CELL_PAD,
                                             null, null, null)));
        }
        return blocks;
    }

    /**
     * Packs blocks onto sheets, first-fit in printed order, never splitting a block. Always returns
     * at least one page so an IR with no lines still renders its header and footer.
     *
     * <p>A block wider than a whole sheet cannot occur — the largest is two rows against a 27-row
     * body — so there is no "does not fit anywhere" case to handle.
     */
    private static List<List<PrintRow>> paginate(ImportRequestFormData data) {
        List<List<PrintRow>> pages = new ArrayList<>();
        List<PrintRow> current = new ArrayList<>();
        for (List<PrintRow> block : blocks(data)) {
            if (current.size() + block.size() > BODY_ROWS) {
                pages.add(current);
                current = new ArrayList<>();
            }
            current.addAll(block);
        }
        pages.add(current);
        return pages;
    }

    /** Repeated on every page, like the quotation's letterhead + column titles. */
    private void drawHeader(PDPageContentStream cs, PDFont font, ImportRequestFormData d)
            throws IOException {
        left(cs, font, d.docNumber(), REF_NO_X, REF_NO_Y + RULE_LIFT, HEADER_SIZE,
             TABLE_R - REF_NO_X);
        left(cs, font, d.brand(), BRAND_X, HEADER_Y + RULE_LIFT, HEADER_SIZE, 150f);
        left(cs, font, shortDate(d.requestDate()), REQ_DATE_X, HEADER_Y + RULE_LIFT, HEADER_SIZE,
             120f);
    }

    /**
     * The table. Row indices are 0-based from the top of the body; a row's baseline sits
     * {@link #TEXT_INSET} above its own bottom rule.
     *
     * <p>Rows beyond {@link #BODY_ROWS} are NOT drawn — they would land on the footer. The service
     * is expected to have refused the form already via {@link #rowsRequired}; this is the
     * belt-and-braces half of that rule, and silently overprinting the signature block would be a
     * far worse failure than a missing line.
     */
    /**
     * One page's worth of rows. Alignment per column is copied from the owner's IR69068, not
     * chosen: Code and Size are left-aligned hard against their rule, while จำนวน and หน่วยนับ are
     * centred. Taking that from the example rather than from taste is the difference between
     * reproducing the form and redesigning it.
     */
    private void drawBody(PDPageContentStream cs, PDFont font, ImportRequestFormData d,
                          List<PrintRow> pageRows) throws IOException {
        int firstItemRow = -1;
        for (int i = 0; i < pageRows.size(); i++) {
            PrintRow r = pageRows.get(i);
            float y = baselineOf(i);
            if (r.seq() != null) {
                centred(cs, font, r.seq(), COL_ITEM_L, COL_CODE_L, y, BODY_SIZE);
                if (firstItemRow < 0) {
                    firstItemRow = i;
                }
            }
            left(cs, font, r.code(), r.codeX(), y, BODY_SIZE,
                 COL_SIZE_L - r.codeX() - CELL_PAD);
            left(cs, font, r.size(), COL_SIZE_L + CELL_PAD, y, BODY_SIZE,
                 COL_QTY_L - COL_SIZE_L - 2 * CELL_PAD);
            centred(cs, font, r.qty(), COL_QTY_L, COL_UNIT_L, y, BODY_SIZE);
            centred(cs, font, r.unit(), COL_UNIT_L, COL_FOR_L, y, BODY_SIZE);
        }

        // สั่งมาให้ / Requested by / กำหนดวันที่ต้องการของ are ONE value each for the whole form. On
        // the paper original their cells are merged down the line block and the text sits at its
        // vertical middle — in IR69068, against the second of three items. A flat row model cannot
        // merge cells, so the nearest faithful thing is to write each once, centred on the middle
        // row of this page's block. Writing them against the first line instead (as this did
        // initially) reads as if they belonged to that line alone.
        //
        // Repeated per page rather than printed once on page 1: on a paginated form each sheet is a
        // complete F-SM-001 with its own table, and a sheet whose สั่งมาให้ column is blank does not
        // say who its lines are for.
        if (firstItemRow >= 0) {
            int middle = (firstItemRow + pageRows.size() - 1) / 2;
            int room = pageRows.size() - firstItemRow;
            centredWrapped(cs, font, d.customerName(), COL_FOR_L, COL_REQBY_L, middle, room);
            centredWrapped(cs, font, d.requestedByName(), COL_REQBY_L, COL_WANTED_L, middle, room);
            centredWrapped(cs, font, d.requiredByNote(), COL_WANTED_L, TABLE_R, middle, room);
        }
    }

    private void drawFooter(PDPageContentStream cs, PDFont font, ImportRequestFormData d)
            throws IOException {
        left(cs, font, d.vesselEtaNote(), VESSEL_ETA_X, VESSEL_ETA_Y + RULE_LIFT, BODY_SIZE, 300f);
        left(cs, font, d.checkedByName(), CHECKED_BY_X, CHECKED_Y + RULE_LIFT, BODY_SIZE, 45f);
        dateParts(cs, font, d.checkedDate(), CHECKED_DATE_CENTRES, CHECKED_Y + RULE_LIFT);
        left(cs, font, d.approvedByName(), APPROVE_BY_X, APPROVE_Y + RULE_LIFT, BODY_SIZE, 52f);
        dateParts(cs, font, d.approvedDate(), APPROVE_DATE_CENTRES, APPROVE_Y + RULE_LIFT);
        left(cs, font, d.issuedByName(), REQUEST_BY_X, FOOTER_Y + RULE_LIFT, BODY_SIZE, 80f);
        dateParts(cs, font, d.depositReceivedDate(), DEPOSIT_DATE_CENTRES, FOOTER_Y + RULE_LIFT);
        // "ลงชื่อ" and "ผู้อนุมัติ" are deliberately left blank — they are wet signatures, and the
        // owner's ruling for this branch is that the approval blocks print empty.
    }

    // ── Drawing primitives ────────────────────────────────────────────────────────────────────

    /** Left-aligned, shrunk to fit {@code maxWidth} and truncated only as a last resort. */
    private void left(PDPageContentStream cs, PDFont font, String text, float x, float baseline,
                      float size, float maxWidth) throws IOException {
        if (isBlank(text)) {
            return;
        }
        String value = text.strip();
        float fitted = size;
        while (fitted > MIN_SIZE && width(font, value, fitted) > maxWidth) {
            fitted -= 0.25f;
        }
        while (value.length() > 1 && width(font, value, fitted) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        show(cs, font, value, x, baseline, fitted);
    }

    /**
     * The merged trio (สั่งมาให้ / Requested by / กำหนดวันที่ต้องการของ), wrapped across the rows
     * their cell is merged over instead of being truncated into one.
     *
     * <p>These three columns are narrow — สั่งมาให้ is 52.5pt — and a real {@code customer_name}
     * like "บริษัท ยู่ฮุย อินทีเรีย จำกัด" does not come close to fitting. The owner's own IR69068
     * carries a short "ยู่ฮุย อินทีเรีย", which is why single-line truncation looked fine against
     * that example and failed the moment a real deal's customer name went through
     * ({@code ImportRequestServiceIntegrationTest}). Silently cutting a party's name off a purchase
     * document can name the WRONG party, so it wraps.
     *
     * <p>Wrapping is on whitespace and bounded by the rows actually available on this page, so it
     * can never spill past the block into another line's cells. If even the wrapped form does not
     * fit, {@link #centred} still shrinks and, as a last resort, truncates — the printed grid is
     * fixed and there is nowhere else for the text to go.
     */
    private void centredWrapped(PDPageContentStream cs, PDFont font, String text, float cellLeft,
                                float cellRight, int middleRow, int rowsAvailable)
            throws IOException {
        if (isBlank(text)) {
            return;
        }
        float maxWidth = cellRight - cellLeft - 2 * CELL_PAD;

        // One line wins whenever shrink-to-fit can manage it. Wrapping a value that merely needed a
        // point smaller — "Within 21/5/26" is 60pt of text in a 64.5pt column — reads as a defect
        // and, worse, splits it in the extracted text layer. Only genuinely oversized values wrap.
        if (width(font, text.strip(), MIN_SIZE) <= maxWidth) {
            centred(cs, font, text, cellLeft, cellRight, baselineOf(middleRow), BODY_SIZE);
            return;
        }

        // Shrink until the WHOLE value fits the rows available, rather than wrapping at a fixed
        // size and dropping the overflow. Dropping is what this did first, and it silently lost
        // "จำกัด" off the end of a customer's registered name — a purchase document naming a party
        // that does not exist. Every word survives or the size comes down.
        String[] words = text.strip().split("\\s+");
        float size = BODY_SIZE;
        List<String> lines = wrap(font, words, maxWidth, size);
        while (lines.size() > rowsAvailable && size > MIN_SIZE) {
            size -= 0.25f;
            lines = wrap(font, words, maxWidth, size);
        }

        // Only if even MIN_SIZE cannot fit it — a genuine physical limit of a printed grid that
        // cannot grow — does anything get cut, and then it is the last LINES that go, never a
        // silently shortened word.
        int usable = Math.max(1, Math.min(lines.size(), rowsAvailable));
        int top = middleRow - (usable - 1) / 2;
        for (int i = 0; i < usable; i++) {
            centred(cs, font, lines.get(i), cellLeft, cellRight, baselineOf(top + i), size);
        }
    }

    /** Greedy whitespace wrap at {@code size}; a single word wider than the cell keeps its own line. */
    private static List<String> wrap(PDFont font, String[] words, float maxWidth, float size) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(font, candidate, size) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private void centred(PDPageContentStream cs, PDFont font, String text, float cellLeft,
                         float cellRight, float baseline, float size) throws IOException {
        if (isBlank(text)) {
            return;
        }
        float maxWidth = cellRight - cellLeft - 2 * CELL_PAD;
        String value = text.strip();
        float fitted = size;
        while (fitted > MIN_SIZE && width(font, value, fitted) > maxWidth) {
            fitted -= 0.25f;
        }
        while (value.length() > 1 && width(font, value, fitted) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        float x = cellLeft + (cellRight - cellLeft - width(font, value, fitted)) / 2f;
        show(cs, font, value, x, baseline, fitted);
    }

    private void dateParts(PDPageContentStream cs, PDFont font, LocalDate date, float[] centres,
                           float baseline) throws IOException {
        if (date == null) {
            return;
        }
        String[] parts = {
            String.format("%02d", date.getDayOfMonth()),
            String.format("%02d", date.getMonthValue()),
            String.format("%02d", date.getYear() % 100),
        };
        for (int i = 0; i < parts.length && i < centres.length; i++) {
            float w = width(font, parts[i], BODY_SIZE);
            show(cs, font, parts[i], centres[i] - w / 2f, baseline, BODY_SIZE);
        }
    }

    private void show(PDPageContentStream cs, PDFont font, String text, float x, float baseline,
                      float size) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    private static float width(PDFont font, String text, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException e) {
            // getStringWidth only throws on a glyph the font cannot measure; treating that as
            // "very wide" makes the fit loop shrink rather than overflow the cell.
            return Float.MAX_VALUE;
        }
    }

    private static float baselineOf(int row) {
        return BODY_TOP_Y - (row + 1) * ROW_PITCH + TEXT_INSET;
    }

    /** {@code d/M/yy} with a 2-digit Christian year, matching the owner's "6/3/26". */
    private static String shortDate(LocalDate date) {
        return date == null ? null
            : date.getDayOfMonth() + "/" + date.getMonthValue() + "/"
              + String.format("%02d", date.getYear() % 100);
    }

    private static String qty(BigDecimal value) {
        return value == null ? null : QTY.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Subsetting is left on (the 2-argument overload's default), which is safe here for the reason
     * {@code LorYor01Renderer} spells out: the hazard it documents applies to AcroForm appearance
     * streams generated before the subset is written, and this renderer sets no fields at all.
     */
    private PDType0Font loadThaiFont(PDDocument doc) throws IOException {
        try (InputStream in = new ClassPathResource(THAI_FONT).getInputStream()) {
            return PDType0Font.load(doc, in);
        }
    }

    static byte[] templateBytes() throws IOException {
        try (InputStream in = new ClassPathResource(TEMPLATE).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
