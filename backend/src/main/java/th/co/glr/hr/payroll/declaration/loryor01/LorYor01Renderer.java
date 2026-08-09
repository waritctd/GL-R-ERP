package th.co.glr.hr.payroll.declaration.loryor01;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.LorYor01Address;

/**
 * Fills the Revenue Department's own ล.ย.01 PDF and returns it flattened, ready to print and sign.
 *
 * <p><b>Why the government's file and not a replica.</b> ล.ย.01 is an official document, so the
 * output has to be the real form with the employee's data in the real form's own fields — not a
 * lookalike we drew. {@code forms/loryor01_290362.pdf} is the ฉบับ พ.ศ. 2562 revision, downloaded
 * verbatim from
 * {@code https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf}
 * (sha256 5bde6321f7f53a4cfdf07ca9ab2637f7b6718561a6bae4a738c92adea42e40ab). Do not regenerate,
 * re-save, or "optimise" that file — every coordinate below is measured against those exact bytes,
 * and {@code LorYor01RendererTest} pins the checksum so a swap fails loudly.
 *
 * <h2>What the template gives us, and the four places it does not</h2>
 *
 * The template is a real AcroForm: 39 text fields named {@code Text1.1}–{@code Text1.39}, mapped to
 * printed slots below purely by their widget rectangles. Four things it cannot carry, each handled
 * by drawing onto the page after the form is flattened:
 *
 * <ol>
 *   <li><b>The 18 tick boxes.</b> They ARE fields ({@code Radio Button1}–{@code Radio Button7}), but
 *       broken ones: every widget in a group shares the single on-state {@code Yes}, so setting the
 *       field value turns on <em>every</em> box in that group at once — a browser does this too. We
 *       therefore never set them; we draw the form's own tick glyph (its widgets declare
 *       {@code /MK /CA (4)}, i.e. ZapfDingbats {@code 4} = ✔) into the exact widget rectangle. Rects
 *       are read from the template at runtime, never hardcoded, so they follow the file.</li>
 *   <li><b>ข้อ 4 and ข้อ 6 are wired as radio groups but printed as independent boxes.</b> The
 *       printed text says "หักได้คนละ 30,000 บาท" (each) for ข้อ 4 and "รวมกันแล้วไม่เกิน 15,000 บาท"
 *       (combined) for ข้อ 6 — both only make sense if บิดา and มารดา can be ticked together, and the
 *       amount box beside them depends on it. The printed form wins over the file's wiring: those
 *       four pairs are independent here. ข้อ 1 and ข้อ 2 stay mutually exclusive, as intended.</li>
 *   <li><b>วัน/เดือน/ปี ที่แจ้งรายการ</b> has no field at all — drawn onto its dotted rule.</li>
 *   <li><b>ลงชื่อ</b> has no field and is deliberately left blank. The employee signs by hand; that
 *       signed scan coming back is what HR approves.</li>
 *   <li><b>The two comb rows cannot be filled through their comb.</b> เลขประจำตัวผู้เสียภาษีอากร and
 *       รหัสไปรษณีย์ are {@code /Comb} fields, and a comb splits the widget into cells of EQUAL
 *       width — but the printed tax-ID boxes run 1-4-5-2-1 with uneven gaps between the groups, so
 *       an even split drifts out of register and later digits straddle their box edges. Each digit
 *       is drawn centred in its own measured box instead ({@code TAX_ID_CELLS}).</li>
 * </ol>
 *
 * <h2>Fidelity details that are easy to get wrong</h2>
 *
 * <ul>
 *   <li><b>Thai text.</b> Every field's {@code /DA} names {@code MicrosoftSansSerif}, which is not
 *       embedded — which is exactly why filling this form in a browser renders Thai through whatever
 *       the local machine substitutes, differently per machine. We register the bundled Sarabun into
 *       the AcroForm's {@code /DR} and swap only the font name in each {@code /DA}, preserving that
 *       field's own size (several are {@code 0 Tf}, i.e. auto-fit) and its grey
 *       {@code 0.251 0.251 0.251 rg}. This is the one intentional divergence from "whatever the
 *       browser did", and it is the difference between correct Thai and tofu.</li>
 *   <li><b>The ล้างข้อมูล button is not printed.</b> Its annotation flags are {@code 0}, so the Print
 *       bit is clear and a browser omits it on paper. {@code PDAcroForm.flatten()} does not check
 *       that bit and would bake the grey button into the page, so it is removed first.</li>
 *   <li><b>Amounts respect the printed บาท/สตางค์ columns.</b> Each amount box is two printed cells
 *       divided by a rule at x 544.7, but the AcroForm field ignores that and runs to 559.5 — 15pt
 *       into the satang column — right-aligned. Typed in a browser, "90,000.00" therefore prints
 *       with its decimals lying across the divider, which reads as a figure that has escaped its
 *       box. We narrow the widget to the บาท cell, centre it there, and print the satang into the
 *       satang cell ({@code confineToBahtCell} / {@code drawSatang}). Only the in-memory widget
 *       moves; the artwork does not, and the pixel test proves it. Two decimals are always shown
 *       because the field's own {@code /AA /F AFNumber_Format(2, 0, 0, 0, "", true)} says so —
 *       PDFBox runs no JavaScript, so the renderer applies that formatting itself.</li>
 *   <li><b>Every other field keeps the template's own alignment.</b> {@code /Q} is 0 for the 20 text
 *       slots and 1 for the 4 count boxes; PDFBox honours each, which is exactly how the same value
 *       typed in a browser lands. The amount fields are the single exception, above.</li>
 * </ul>
 *
 * <p><b>How the output is verified.</b> {@code LorYor01RendererTest} rasterises a filled document
 * and the blank template at the same DPI and asserts that <em>no</em> pixel of the template's ink
 * disappeared — additions are expected, losses mean we moved or covered the government's artwork.
 * That check is the reason to trust every coordinate in this class; a "looks right" screenshot is
 * not evidence that nothing shifted.
 */
@Component
public class LorYor01Renderer {

    static final String TEMPLATE = "forms/loryor01_290362.pdf";
    private static final String THAI_FONT = "fonts/Sarabun-Regular.ttf";

    /** The ล้างข้อมูล push button — present in the file, absent from every printed copy. */
    private static final String CLEAR_BUTTON = "Button1";

    // -- Text fields, named for the slot they print into (see the class javadoc). ---------------
    private static final String F_AGENT = "Text1.1";
    private static final String F_TAXPAYER_ID = "Text1.2";
    private static final String F_FIRST_NAME = "Text1.3";
    private static final String F_LAST_NAME = "Text1.4";
    private static final String F_BUILDING = "Text1.5";
    private static final String F_ROOM_NO = "Text1.6";
    private static final String F_FLOOR = "Text1.7";
    private static final String F_VILLAGE = "Text1.8";
    private static final String F_HOUSE_NO = "Text1.9";
    private static final String F_MOO = "Text1.10";
    private static final String F_SOI = "Text1.11";
    private static final String F_JUNCTION = "Text1.12";
    private static final String F_ROAD = "Text1.13";
    private static final String F_SUB_DISTRICT = "Text1.14";
    private static final String F_DISTRICT = "Text1.15";
    private static final String F_PROVINCE = "Text1.16";
    private static final String F_POSTAL_CODE = "Text1.17";
    private static final String F_CHILDREN_TOTAL = "Text1.18";        // ข้อ 3
    private static final String F_CHILD_STD_COUNT = "Text1.19";
    private static final String F_CHILD_STD_AMOUNT = "Text1.20";
    private static final String F_CHILD_EXTRA_AMOUNT = "Text1.21";
    private static final String F_CHILD_EXTRA_COUNT = "Text1.22";
    private static final String F_OWN_PARENT_CARE = "Text1.23";       // ข้อ 4
    private static final String F_SPOUSE_PARENT_CARE = "Text1.24";
    private static final String F_DISABLED_COUNT = "Text1.25";        // ข้อ 5
    private static final String F_DISABLED_AMOUNT = "Text1.26";
    private static final String F_PARENT_HEALTH = "Text1.27";         // ข้อ 6
    private static final String F_LIFE_INSURANCE = "Text1.28";        // ข้อ 7
    private static final String F_HEALTH_INSURANCE = "Text1.29";      // ข้อ 8
    private static final String F_PROVIDENT_FUND = "Text1.30";        // ข้อ 9
    private static final String F_RMF_AMOUNT = "Text1.31";            // ข้อ 10
    private static final String F_RMF_SELLER = "Text1.32";
    private static final String F_LTF_AMOUNT = "Text1.33";            // ข้อ 11
    private static final String F_LTF_SELLER = "Text1.34";
    private static final String F_HOME_LOAN = "Text1.35";             // ข้อ 12
    private static final String F_SOCIAL_SECURITY = "Text1.36";       // ข้อ 13
    private static final String F_EDUCATION_DONATION = "Text1.37";    // ข้อ 14
    private static final String F_OTHER_DONATION_NOTE = "Text1.38";   // ข้อ 15
    private static final String F_OTHER_DONATION_AMOUNT = "Text1.39";

    // -- Tick groups. Widget order within each group is the printed left-to-right, top-to-bottom
    //    order; LorYor01RendererTest pins every rectangle so a reordering cannot pass silently. ---
    private static final String R_MARITAL_STATE = "Radio Button1";    // ข้อ 1 โสด/หม้าย/สมรส/ตาย
    private static final String R_SPOUSAL_STATUS = "Radio Button2";   // ข้อ 1 สถานภาพการสมรส
    private static final String R_SPOUSE_INCOME = "Radio Button3";    // ข้อ 2 มี/ไม่มีเงินได้
    private static final String R_OWN_PARENT_CARE = "Radio Button4";  // ข้อ 4 บิดา/มารดา (ผู้มีเงินได้)
    private static final String R_SPOUSE_PARENT_CARE = "Radio Button5"; // ข้อ 4 บิดา/มารดา (คู่สมรส)
    private static final String R_OWN_PARENT_HEALTH = "Radio Button6";  // ข้อ 6 บิดา/มารดา (ผู้มีเงินได้)
    private static final String R_SPOUSE_PARENT_HEALTH = "Radio Button7"; // ข้อ 6 บิดา/มารดา (คู่สมรส)

    /**
     * The tick the template itself nominates. Each box widget carries {@code /MK /CA (4)}, and in
     * ZapfDingbats encoding code {@code 0x34} is glyph {@code a20} = U+2714 HEAVY CHECK MARK. PDFBox
     * addresses standard-14 glyphs by Unicode, so this is that same glyph, not a substitute.
     */
    private static final String TICK_GLYPH = "✔";
    private static final float TICK_HEIGHT_RATIO = 0.78f;

    /**
     * The amount boxes are two printed cells, measured off the template at 300dpi: บาท spans
     * x 448.4–544.7 and สตางค์ spans x 544.7–568.7. The AcroForm field ignores that divider and runs
     * to 559.5, i.e. 15pt into the สตางค์ cell — which is why a right-aligned "90,000.00" typed into
     * this form in a browser prints with its decimals straddling the printed rule.
     */
    private static final float BAHT_CELL_RIGHT = 544.7f;
    private static final float SATANG_CELL_LEFT = 544.7f;
    private static final float SATANG_CELL_RIGHT = 568.7f;
    /** Keeps a glyph from touching the printed rule it sits next to. */
    private static final float CELL_INSET = 2.5f;
    /** Every amount field's own {@code /DA} sets 9pt; the สตางค์ overlay matches it. */
    private static final float AMOUNT_FONT_SIZE = 9f;

    /**
     * The 15 amount boxes, in printed order. These are the fields whose widget is narrowed to the
     * บาท cell before filling — see {@link #fillTextFields}.
     */
    /** Filled by drawing one digit per printed box, not through the AcroForm comb. */
    private static final Set<String> COMB_FIELDS = Set.of(F_TAXPAYER_ID, F_POSTAL_CODE);

    private static final List<String> AMOUNT_FIELDS = List.of(
            F_CHILD_STD_AMOUNT, F_CHILD_EXTRA_AMOUNT, F_OWN_PARENT_CARE, F_SPOUSE_PARENT_CARE,
            F_DISABLED_AMOUNT, F_PARENT_HEALTH, F_LIFE_INSURANCE, F_HEALTH_INSURANCE,
            F_PROVIDENT_FUND, F_RMF_AMOUNT, F_LTF_AMOUNT, F_HOME_LOAN, F_SOCIAL_SECURITY,
            F_EDUCATION_DONATION, F_OTHER_DONATION_AMOUNT);

    /**
     * The printed digit boxes for เลขประจำตัวผู้เสียภาษีอากร and รหัสไปรษณีย์, measured off the
     * template at 300dpi as {@code {left, right}} pairs.
     *
     * <p>These exist because the AcroForm comb cannot land digits in these boxes. Both fields are
     * {@code /Comb} — 17 cells for the tax ID, 5 for the postcode — and a comb divides the widget
     * into cells of EQUAL width. The printed tax-ID boxes are not equal: they run 1-4-5-2-1 with
     * gaps of 5.3, 8.0, 8.4 and 7.7pt between the groups, so an even 17-way split drifts further out
     * of register with every group and the later digits sit across their box edges. (Visible in a
     * browser too — this is the form's own defect, not PDFBox's.) Each digit is therefore drawn
     * centred in its own measured box.
     */
    private static final float[][] TAX_ID_CELLS = {
            {125.0f, 138.1f},
            {143.4f, 157.8f}, {157.8f, 171.8f}, {171.8f, 185.9f}, {185.9f, 200.3f},
            {208.3f, 221.9f}, {221.9f, 236.2f}, {236.2f, 250.2f}, {250.2f, 264.6f}, {264.6f, 278.3f},
            {286.7f, 299.0f}, {299.0f, 313.3f},
            {321.0f, 333.6f},
    };

    private static final float[][] POSTAL_CODE_CELLS = {
            {73.3f, 84.0f}, {84.0f, 96.7f}, {96.7f, 109.7f}, {109.7f, 123.7f}, {123.7f, 135.2f},
    };

    /** Both comb rows are set at 9pt by their own {@code /DA}. */
    private static final float COMB_FONT_SIZE = 9f;

    /** วัน/เดือน/ปี dotted rule: x 443.23–568.37, baseline 794.64, label set at 12pt. */
    private static final float DATE_LEFT = 443.23f;
    private static final float DATE_RIGHT = 568.37f;
    private static final float DATE_BASELINE = 796.1f;
    private static final float DATE_FONT_SIZE = 12f;

    public byte[] render(LorYor01FormData data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(templateBytes())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                throw new IOException("ล.ย.01 template has no AcroForm — the resource is not the official file");
            }
            PDPage page = doc.getPage(0);
            PDType0Font thai = loadThaiFont(doc);
            COSName thaiName = form.getDefaultResources().add(thai);

            fillTextFields(form, thaiName, data);

            // Read every overlay coordinate BEFORE flattening: flatten() deletes the widgets these
            // rectangles come from.
            List<PDRectangle> ticks = collectTicks(form, data);
            List<SatangMark> satang = collectSatangMarks(form, data);
            List<CellMark> combs = collectCombMarks(form, data);

            removeClearButton(form, page);
            form.flatten();

            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
                drawTicks(cs, ticks);
                drawSatang(cs, thai, satang);
                drawCellMarks(cs, thai, combs);
                drawDeclaredOn(cs, thai, data.declaredOn());
            }

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Text fields
    // ---------------------------------------------------------------------------------------

    private void fillTextFields(PDAcroForm form, COSName font, LorYor01FormData data)
            throws IOException {
        for (String amountField : AMOUNT_FIELDS) {
            confineToBahtCell(form, amountField);
        }
        for (Map.Entry<String, String> entry : textFieldValues(data).entrySet()) {
            if (COMB_FIELDS.contains(entry.getKey())) {
                continue;   // drawn per printed box instead — see TAX_ID_CELLS
            }
            set(form, font, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Narrows an amount widget to the printed บาท cell and centres its text.
     *
     * <p>Two deliberate departures from the template's own wiring, both to honour what the box
     * actually looks like on paper. The field is right-aligned and overhangs the บาท/สตางค์ rule, so
     * as shipped it prints "90,000.00" with the decimals sitting across the divider — the satang
     * column rendered meaningless and the figure reading as if it had escaped its box. Clipping the
     * widget to the rule puts the baht where the baht column is; the satang go into the satang
     * column as a separate mark ({@link #drawSatang}), which is what the two printed cells are for.
     *
     * <p>Only the in-memory widget moves. The page's artwork is untouched, and
     * {@code LorYor01RendererTest} proves it per-pixel.
     */
    private void confineToBahtCell(PDAcroForm form, String fieldName) throws IOException {
        PDField field = form.getField(fieldName);
        if (field == null) {
            throw new IOException("ล.ย.01 template is missing amount field " + fieldName);
        }
        PDRectangle rect = field.getWidgets().get(0).getRectangle();
        rect.setUpperRightX(BAHT_CELL_RIGHT - CELL_INSET);
        field.getWidgets().get(0).setRectangle(rect);
        field.getCOSObject().setInt(COSName.Q, 1);   // centre within the บาท cell
    }

    /**
     * The whole ล.ย.01 mapping as data: template field name to the string that goes in it.
     *
     * <p>Pulled out as a pure function so {@code LorYor01RendererTest} can assert the mapping
     * directly. It has to be checkable somehow, and reading it back out of the finished PDF is not
     * good enough: the template's dot-leaders interleave with the values in extracted text
     * ({@code สมชาย} comes back as {@code ส...ม....ช...า...ย}), so a text assertion is fuzzy exactly
     * where this needs to be exact. Swapping two entries below would put an employee's ชั้นที่ in
     * their ห้องเลขที่ and no rendering check would notice. This map is the thing to guard.
     *
     * <p>Entries with a null value are omitted — a blank box, not a printed zero.
     */
    static Map<String, String> textFieldValues(LorYor01FormData d) {
        LorYor01Address a = d.address() == null ? LorYor01Address.empty() : d.address();
        Map<String, String> values = new LinkedHashMap<>();
        put(values, F_AGENT, d.withholdingAgentName());
        put(values, F_TAXPAYER_ID, digitsOnly(d.taxpayerId()));
        put(values, F_FIRST_NAME, d.firstName());
        put(values, F_LAST_NAME, d.lastName());
        put(values, F_BUILDING, a.building());
        put(values, F_ROOM_NO, a.roomNo());
        put(values, F_FLOOR, a.floor());
        put(values, F_VILLAGE, a.village());
        put(values, F_HOUSE_NO, a.houseNo());
        put(values, F_MOO, a.moo());
        put(values, F_SOI, a.soi());
        put(values, F_JUNCTION, a.junction());
        put(values, F_ROAD, a.road());
        put(values, F_SUB_DISTRICT, a.subDistrict());
        put(values, F_DISTRICT, a.district());
        put(values, F_PROVINCE, a.province());
        put(values, F_POSTAL_CODE, digitsOnly(a.postalCode()));
        put(values, F_CHILDREN_TOTAL, count(d.childrenTotal()));
        put(values, F_CHILD_STD_COUNT, count(d.childStandardCount()));
        put(values, F_CHILD_STD_AMOUNT, money(d.childStandardAmount()));
        put(values, F_CHILD_EXTRA_COUNT, count(d.childExtraCount()));
        put(values, F_CHILD_EXTRA_AMOUNT, money(d.childExtraAmount()));
        put(values, F_OWN_PARENT_CARE, money(d.ownParentCareAmount()));
        put(values, F_SPOUSE_PARENT_CARE, money(d.spouseParentCareAmount()));
        put(values, F_DISABLED_COUNT, count(d.disabledCareCount()));
        put(values, F_DISABLED_AMOUNT, money(d.disabledCareAmount()));
        put(values, F_PARENT_HEALTH, money(d.parentHealthInsuranceAmount()));
        put(values, F_LIFE_INSURANCE, money(d.lifeInsuranceAmount()));
        put(values, F_HEALTH_INSURANCE, money(d.healthInsuranceAmount()));
        put(values, F_PROVIDENT_FUND, money(d.providentFundAmount()));
        put(values, F_RMF_AMOUNT, money(d.rmfAmount()));
        put(values, F_RMF_SELLER, d.rmfSellerName());
        put(values, F_LTF_AMOUNT, money(d.ltfAmount()));
        put(values, F_LTF_SELLER, d.ltfSellerName());
        put(values, F_HOME_LOAN, money(d.homeLoanInterestAmount()));
        put(values, F_SOCIAL_SECURITY, money(d.socialSecurityAmount()));
        put(values, F_EDUCATION_DONATION, money(d.educationDonationAmount()));
        put(values, F_OTHER_DONATION_NOTE, d.otherDonationNote());
        put(values, F_OTHER_DONATION_AMOUNT, money(d.otherDonationAmount()));
        return values;
    }

    private static void put(Map<String, String> values, String field, String value) {
        if (value != null && !value.isBlank()) {
            values.put(field, value);
        }
    }

    /**
     * Writes one field, re-pointing its default appearance at the embedded Thai font while keeping
     * the size and colour the template chose for that particular field.
     *
     * <p>A null or blank value leaves the box empty rather than writing "" — the difference matters
     * on a tax form, and skipping the write also skips generating a pointless appearance stream.
     */
    private void set(PDAcroForm form, COSName font, String name, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        PDField field = form.getField(name);
        if (!(field instanceof PDTextField text)) {
            throw new IOException("ล.ย.01 template is missing text field " + name);
        }
        text.setDefaultAppearance(withFont(text.getDefaultAppearance(), font));

        // The template caps the four count boxes at a single character. Respect it rather than
        // widening the field: the constraint is the form's, and a two-digit value would print
        // outside the box it belongs to.
        int maxLen = text.getMaxLen();
        String toWrite = maxLen > 0 && value.length() > maxLen ? value.substring(0, maxLen) : value;
        text.setValue(toWrite);
    }

    /** Swaps only the font name in a {@code /DA}, e.g. {@code /MicrosoftSansSerif 9 Tf 0.251 …}. */
    private static String withFont(String defaultAppearance, COSName font) {
        String da = defaultAppearance == null || defaultAppearance.isBlank()
                ? "/X 0 Tf 0 g"
                : defaultAppearance;
        return da.replaceFirst("/[^\\s]+", "/" + font.getName());
    }

    private static String digitsOnly(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.replaceAll("\\D", "");
        return stripped.isEmpty() ? null : stripped;
    }

    private static String count(Integer value) {
        return value == null || value <= 0 ? null : String.valueOf(value);
    }

    /**
     * The บาท part, comma-grouped. The สตางค์ part is printed separately into its own cell by
     * {@link #drawSatang}, because the form gives it a column of its own.
     *
     * <p>{@code String.format} rather than a shared {@code DecimalFormat}: this renderer is a
     * singleton {@code @Component} and {@code DecimalFormat} is not thread-safe.
     */
    private static String money(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return String.format("%,d", value.setScale(2, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.DOWN).longValue());
    }

    /** The สตางค์ part as two digits, or null when there is no amount in the box at all. */
    static String satangOf(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        return String.format("%02d",
                scaled.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue());
    }

    // ---------------------------------------------------------------------------------------
    // Overlays
    // ---------------------------------------------------------------------------------------

    private List<PDRectangle> collectTicks(PDAcroForm form, LorYor01FormData d) throws IOException {
        List<PDRectangle> rects = new ArrayList<>();
        if (d.maritalState() != null) {
            rects.add(widget(form, R_MARITAL_STATE, d.maritalState().ordinal()));
        }
        if (d.spousalStatus() != null) {
            rects.add(widget(form, R_SPOUSAL_STATUS, d.spousalStatus().ordinal()));
        }
        if (d.spouseHasIncome() != null) {
            rects.add(widget(form, R_SPOUSE_INCOME, d.spouseHasIncome() ? 0 : 1));
        }
        addPair(rects, form, R_OWN_PARENT_CARE, d.ownFatherSupported(), d.ownMotherSupported());
        addPair(rects, form, R_SPOUSE_PARENT_CARE, d.spouseFatherSupported(), d.spouseMotherSupported());
        addPair(rects, form, R_OWN_PARENT_HEALTH, d.ownFatherHealthInsured(), d.ownMotherHealthInsured());
        addPair(rects, form, R_SPOUSE_PARENT_HEALTH, d.spouseFatherHealthInsured(),
                d.spouseMotherHealthInsured());
        return rects;
    }

    private void addPair(List<PDRectangle> out, PDAcroForm form, String group, boolean father,
                         boolean mother) throws IOException {
        if (father) {
            out.add(widget(form, group, 0));
        }
        if (mother) {
            out.add(widget(form, group, 1));
        }
    }

    private PDRectangle widget(PDAcroForm form, String fieldName, int index) throws IOException {
        PDField field = form.getField(fieldName);
        if (field == null) {
            throw new IOException("ล.ย.01 template is missing tick group " + fieldName);
        }
        List<PDAnnotationWidget> widgets = field.getWidgets();
        if (index < 0 || index >= widgets.size()) {
            throw new IOException("ล.ย.01 tick group " + fieldName + " has no widget " + index);
        }
        return widgets.get(index).getRectangle();
    }

    private void drawTicks(PDPageContentStream cs, List<PDRectangle> rects) throws IOException {
        if (rects.isEmpty()) {
            return;
        }
        PDFont ding = new PDType1Font(Standard14Fonts.FontName.ZAPF_DINGBATS);
        for (PDRectangle r : rects) {
            float size = r.getHeight() * TICK_HEIGHT_RATIO;
            float glyphWidth = ding.getStringWidth(TICK_GLYPH) / 1000f * size;
            // ZapfDingbats reports no cap height, so centre on the glyph's own bounding box.
            float glyphHeight = ding.getBoundingBox().getHeight() / 1000f * size * 0.55f;
            float x = r.getLowerLeftX() + (r.getWidth() - glyphWidth) / 2f;
            float y = r.getLowerLeftY() + (r.getHeight() - glyphHeight) / 2f;
            cs.beginText();
            cs.setFont(ding, size);
            cs.newLineAtOffset(x, y);
            cs.showText(TICK_GLYPH);
            cs.endText();
        }
    }

    /** A สตางค์ value and the baseline of the amount row it belongs to. */
    private record SatangMark(String text, float baseline) {}

    /**
     * One สตางค์ mark per amount box that has a value, read BEFORE the widgets are narrowed and
     * flattened away. "00" is printed rather than left blank: the box is a currency field and the
     * template's own {@code AFNumber_Format(2, …)} shows two decimals for every value, so a blank
     * satang cell beside a filled baht cell would read as an unfinished form.
     */
    private List<SatangMark> collectSatangMarks(PDAcroForm form, LorYor01FormData d)
            throws IOException {
        List<SatangMark> marks = new ArrayList<>();
        satang(marks, form, F_CHILD_STD_AMOUNT, d.childStandardAmount());
        satang(marks, form, F_CHILD_EXTRA_AMOUNT, d.childExtraAmount());
        satang(marks, form, F_OWN_PARENT_CARE, d.ownParentCareAmount());
        satang(marks, form, F_SPOUSE_PARENT_CARE, d.spouseParentCareAmount());
        satang(marks, form, F_DISABLED_AMOUNT, d.disabledCareAmount());
        satang(marks, form, F_PARENT_HEALTH, d.parentHealthInsuranceAmount());
        satang(marks, form, F_LIFE_INSURANCE, d.lifeInsuranceAmount());
        satang(marks, form, F_HEALTH_INSURANCE, d.healthInsuranceAmount());
        satang(marks, form, F_PROVIDENT_FUND, d.providentFundAmount());
        satang(marks, form, F_RMF_AMOUNT, d.rmfAmount());
        satang(marks, form, F_LTF_AMOUNT, d.ltfAmount());
        satang(marks, form, F_HOME_LOAN, d.homeLoanInterestAmount());
        satang(marks, form, F_SOCIAL_SECURITY, d.socialSecurityAmount());
        satang(marks, form, F_EDUCATION_DONATION, d.educationDonationAmount());
        satang(marks, form, F_OTHER_DONATION_AMOUNT, d.otherDonationAmount());
        return marks;
    }

    private void satang(List<SatangMark> out, PDAcroForm form, String fieldName, BigDecimal amount)
            throws IOException {
        String text = satangOf(amount);
        if (text == null) {
            return;
        }
        PDField field = form.getField(fieldName);
        if (field == null) {
            throw new IOException("ล.ย.01 template is missing amount field " + fieldName);
        }
        out.add(new SatangMark(text, baselineOf(field.getWidgets().get(0).getRectangle(), AMOUNT_FONT_SIZE)));
    }

    /**
     * Where PDFBox will put the baseline inside a single-line field of this height, so the สตางค์
     * mark lines up with the บาท figure beside it instead of floating a point or two off it.
     */
    private static float baselineOf(PDRectangle rect, float fontSize) {
        return rect.getLowerLeftY() + (rect.getHeight() - fontSize) / 2f + fontSize * 0.22f;
    }

    private void drawSatang(PDPageContentStream cs, PDFont font, List<SatangMark> marks)
            throws IOException {
        float centre = (SATANG_CELL_LEFT + SATANG_CELL_RIGHT) / 2f;
        for (SatangMark mark : marks) {
            float width = font.getStringWidth(mark.text()) / 1000f * AMOUNT_FONT_SIZE;
            cs.beginText();
            cs.setFont(font, AMOUNT_FONT_SIZE);
            cs.setNonStrokingColor(0.251f, 0.251f, 0.251f);
            cs.newLineAtOffset(centre - width / 2f, mark.baseline());
            cs.showText(mark.text());
            cs.endText();
        }
    }

    /** One character and the printed box it belongs in. */
    private record CellMark(String text, float centreX, float baseline) {}

    /**
     * Places each digit of เลขประจำตัวผู้เสียภาษีอากร and รหัสไปรษณีย์ in the centre of its own
     * printed box. Read before flatten because the baseline comes from the field's widget.
     *
     * <p>Extra digits beyond the printed boxes are dropped rather than overflowing the row: a
     * malformed 15-digit tax ID should look obviously wrong in the boxes, not silently smear past
     * them into ผู้มีเงินได้ชื่อ below.
     */
    private List<CellMark> collectCombMarks(PDAcroForm form, LorYor01FormData d) throws IOException {
        List<CellMark> marks = new ArrayList<>();
        Map<String, String> values = textFieldValues(d);
        addCellMarks(marks, form, F_TAXPAYER_ID, values.get(F_TAXPAYER_ID), TAX_ID_CELLS);
        addCellMarks(marks, form, F_POSTAL_CODE, values.get(F_POSTAL_CODE), POSTAL_CODE_CELLS);
        return marks;
    }

    private void addCellMarks(List<CellMark> out, PDAcroForm form, String fieldName, String digits,
                              float[][] cells) throws IOException {
        if (digits == null || digits.isBlank()) {
            return;
        }
        PDField field = form.getField(fieldName);
        if (field == null) {
            throw new IOException("ล.ย.01 template is missing comb field " + fieldName);
        }
        float baseline = baselineOf(field.getWidgets().get(0).getRectangle(), COMB_FONT_SIZE);
        for (int i = 0; i < digits.length() && i < cells.length; i++) {
            out.add(new CellMark(String.valueOf(digits.charAt(i)),
                    (cells[i][0] + cells[i][1]) / 2f, baseline));
        }
    }

    private void drawCellMarks(PDPageContentStream cs, PDFont font, List<CellMark> marks)
            throws IOException {
        for (CellMark mark : marks) {
            float width = font.getStringWidth(mark.text()) / 1000f * COMB_FONT_SIZE;
            cs.beginText();
            cs.setFont(font, COMB_FONT_SIZE);
            cs.setNonStrokingColor(0.251f, 0.251f, 0.251f);
            cs.newLineAtOffset(mark.centreX() - width / 2f, mark.baseline());
            cs.showText(mark.text());
            cs.endText();
        }
    }

    private void drawDeclaredOn(PDPageContentStream cs, PDFont font, LocalDate date)
            throws IOException {
        if (date == null) {
            return;
        }
        String text = String.format("%02d/%02d/%d", date.getDayOfMonth(), date.getMonthValue(),
                date.getYear() + 543);
        float width = font.getStringWidth(text) / 1000f * DATE_FONT_SIZE;
        float centre = (DATE_LEFT + DATE_RIGHT) / 2f;
        cs.beginText();
        cs.setFont(font, DATE_FONT_SIZE);
        cs.setNonStrokingColor(0.251f, 0.251f, 0.251f);
        cs.newLineAtOffset(centre - width / 2f, DATE_BASELINE);
        cs.showText(text);
        cs.endText();
    }

    // ---------------------------------------------------------------------------------------
    // Template plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * Drops the ล้างข้อมูล push button before flattening. Its annotation flags are {@code 0}, so the
     * Print bit is clear and no printed copy of this form has ever shown it — but
     * {@code PDAcroForm.flatten()} only skips hidden/invisible widgets, not unprintable ones, and
     * would leave a grey "ล้างข้อมูล" box in the corner of an official document.
     */
    private void removeClearButton(PDAcroForm form, PDPage page) throws IOException {
        PDField button = form.getField(CLEAR_BUTTON);
        if (button == null) {
            return;
        }
        page.getAnnotations().removeAll(button.getWidgets());
        List<PDField> remaining = new ArrayList<>(form.getFields());
        remaining.remove(button);
        form.setFields(remaining);
    }

    /**
     * Embeds Sarabun in full — {@code embedSubset = false}, and that argument is load-bearing.
     *
     * <p><b>Do not turn subsetting on to save the ~47 KB.</b> It was tried. PDFBox generates the
     * AcroForm appearance streams while the field values are set, but only renumbers glyph IDs when
     * the subset is written at {@code save()}; the already-serialised appearance streams keep
     * pointing at the pre-subset GIDs. The result is a document that opens fine and renders every
     * Thai name, address and digit as tofu boxes — verified by rasterising it. Subsetting is safe in
     * {@code PdfDocumentWriter}/{@code PayslipRenderer} because those draw straight into a page
     * content stream; it is not safe for filled form fields.
     */
    private PDType0Font loadThaiFont(PDDocument doc) throws IOException {
        try (InputStream in = new ClassPathResource(THAI_FONT).getInputStream()) {
            return PDType0Font.load(doc, in, false);
        }
    }

    static byte[] templateBytes() throws IOException {
        try (InputStream in = new ClassPathResource(TEMPLATE).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
