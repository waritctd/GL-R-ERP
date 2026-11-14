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

/** Fills the Revenue Department's own ล.ย.01 PDF and returns it flattened, ready to print and sign. Do not
 * regenerate, re-save, or "optimise" that file — every coordinate below is measured against those exact bytes,
 * and LorYor01RendererTest pins the checksum so a swap fails loudly. */
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

    // Tick groups. Widget order within each group is the printed left-to-right, top-to-bottom order;
    // LorYor01RendererTest pins every rectangle so a reordering cannot pass silently.
    private static final String R_MARITAL_STATE = "Radio Button1";    // ข้อ 1 โสด/หม้าย/สมรส/ตาย
    private static final String R_SPOUSAL_STATUS = "Radio Button2";   // ข้อ 1 สถานภาพการสมรส
    private static final String R_SPOUSE_INCOME = "Radio Button3";    // ข้อ 2 มี/ไม่มีเงินได้
    private static final String R_OWN_PARENT_CARE = "Radio Button4";  // ข้อ 4 บิดา/มารดา (ผู้มีเงินได้)
    private static final String R_SPOUSE_PARENT_CARE = "Radio Button5"; // ข้อ 4 บิดา/มารดา (คู่สมรส)
    private static final String R_OWN_PARENT_HEALTH = "Radio Button6";  // ข้อ 6 บิดา/มารดา (ผู้มีเงินได้)
    private static final String R_SPOUSE_PARENT_HEALTH = "Radio Button7"; // ข้อ 6 บิดา/มารดา (คู่สมรส)

    /** The tick the template itself nominates. Each box widget carries /MK /CA (4), and in ZapfDingbats encoding
     * code 0x34 is glyph a20 = U+2714 HEAVY CHECK MARK. PDFBox addresses standard-14 glyphs by Unicode, so this is
     * that same glyph, not a substitute. */
    private static final String TICK_GLYPH = "✔";
    private static final float TICK_HEIGHT_RATIO = 0.78f;

    /** The amount boxes are two printed cells, measured off the template at 300dpi: บาท spans x 448.4–544.7 and
     * สตางค์ spans x 544.7–568.7. */
    private static final float BAHT_CELL_RIGHT = 544.7f;
    private static final float SATANG_CELL_LEFT = 544.7f;
    private static final float SATANG_CELL_RIGHT = 568.7f;
    /** Keeps a glyph from touching the printed rule it sits next to. */
    private static final float CELL_INSET = 2.5f;
    /** Every amount field's own {@code /DA} sets 9pt; the สตางค์ overlay matches it. */
    private static final float AMOUNT_FONT_SIZE = 9f;

    /** The 15 amount boxes, in printed order. These are the fields whose widget is narrowed to the บาท cell before
     * filling — see #fillTextFields. */
    /** Filled by drawing one digit per printed box, not through the AcroForm comb. */
    private static final Set<String> COMB_FIELDS = Set.of(F_TAXPAYER_ID, F_POSTAL_CODE);

    private static final List<String> AMOUNT_FIELDS = List.of(
            F_CHILD_STD_AMOUNT, F_CHILD_EXTRA_AMOUNT, F_OWN_PARENT_CARE, F_SPOUSE_PARENT_CARE,
            F_DISABLED_AMOUNT, F_PARENT_HEALTH, F_LIFE_INSURANCE, F_HEALTH_INSURANCE,
            F_PROVIDENT_FUND, F_RMF_AMOUNT, F_LTF_AMOUNT, F_HOME_LOAN, F_SOCIAL_SECURITY,
            F_EDUCATION_DONATION, F_OTHER_DONATION_AMOUNT);

    /** The printed digit boxes for เลขประจำตัวผู้เสียภาษีอากร and รหัสไปรษณีย์, measured off the template at 300dpi
     * as {left, right} pairs. These exist because the AcroForm comb cannot land digits in these boxes. */
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

            // Read every overlay coordinate BEFORE flattening: flatten() deletes the widgets these rectangles come from.
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

    // Text fields

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

    /** Narrows an amount widget to the printed บาท cell and centres its text. Only the in-memory widget moves. Two
     * deliberate departures from the template's own wiring, both to honour what the box actually looks like on
     * paper. */
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

    /** The whole ล.ย.01 mapping as data: template field name to the string that goes in it. Pulled out as a pure
     * function so LorYor01RendererTest can assert the mapping directly. Swapping two entries below would put an
     * employee's ชั้นที่ in their ห้องเลขที่ and no rendering check would notice. */
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

    /** Writes one field, re-pointing its default appearance at the embedded Thai font while keeping the size and
     * colour the template chose for that particular field. */
    private void set(PDAcroForm form, COSName font, String name, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        PDField field = form.getField(name);
        if (!(field instanceof PDTextField text)) {
            throw new IOException("ล.ย.01 template is missing text field " + name);
        }
        text.setDefaultAppearance(withFont(text.getDefaultAppearance(), font));

        // The template caps the four count boxes at a single character. Respect it rather than widening the field: the
        // constraint is the form's, and a two-digit value would print outside the box it belongs to.
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

    /** The บาท part, comma-grouped. The สตางค์ part is printed separately into its own cell by #drawSatang, because
     * the form gives it a column of its own. String.format rather than a shared DecimalFormat: this renderer is a
     * singleton @Component and DecimalFormat is not thread-safe. */
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

    // Overlays

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

    /** Capture satang marks before flattening widgets; print 00 beside whole-baht amounts. */
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

    /** Where PDFBox will put the baseline inside a single-line field of this height, so the สตางค์ mark lines up
     * with the บาท figure beside it instead of floating a point or two off it. */
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

    /** Places each digit of เลขประจำตัวผู้เสียภาษีอากร and รหัสไปรษณีย์ in the centre of its own printed box. Read
     * before flatten because the baseline comes from the field's widget. */
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

    // Template plumbing

    /** Drops the ล้างข้อมูล push button before flattening. */
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

    /** Embeds Sarabun in full — embedSubset = false, and that argument is load-bearing. Do not turn subsetting on
     * to save the ~47 KB. It was tried. The result is a document that opens fine and renders every Thai name,
     * address and digit as tofu boxes — verified by rasterising it. */
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
