package th.co.glr.hr.payroll.declaration.loryor01;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.LorYor01Address;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.MaritalState;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01FormData.SpousalStatus;

/**
 * Guards the one promise this feature makes: the generated ล.ย.01 is the Revenue Department's own
 * document with the employee's data in it — not a replica, not a shifted copy, and not a page with
 * values in the wrong boxes.
 *
 * <p>Three checks carry the weight, because each catches a failure the others cannot see:
 *
 * <ol>
 *   <li>{@link #fillingTheFormDestroysNoneOfTheOfficialArtwork()} — no pixel of the template's own
 *       ink disappears. Catches a coordinate covering a printed label, or a rewritten template
 *       shifting the page.</li>
 *   <li>{@link #nothingIsPrintedOutsideASlotTheFormProvides()} — no pixel of <em>our</em> ink lands
 *       outside a real field or tick box. Catches text drawn at a wrong coordinate, which check 1
 *       would happily allow as long as it landed on blank paper.</li>
 *   <li>{@link #theFieldMappingIsExactlyThis()} — the right datum in the right named field. Catches
 *       a swap between two adjacent boxes, which both pixel checks would pass.</li>
 * </ol>
 *
 * <p>Deliberately NOT the primary check: reading values back with {@code PDFTextStripper}. The
 * template's dot-leaders interleave with field text, so {@code สมชาย} extracts as
 * {@code ส...ม....ช...า...ย} — fuzzy exactly where this needs to be exact.
 */
class LorYor01RendererTest {

    /**
     * sha256 of the file as downloaded from
     * {@code https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf}.
     *
     * <p>Pinned because every coordinate and field name in {@link LorYor01Renderer} was measured
     * against these exact bytes. If someone re-saves the template through a PDF editor — which
     * silently renumbers fields and nudges widget rectangles — this fails first and explains why,
     * instead of the output quietly drifting off the boxes.
     */
    private static final String TEMPLATE_SHA256 =
            "5bde6321f7f53a4cfdf07ca9ab2637f7b6718561a6bae4a738c92adea42e40ab";

    private static final float DPI = 150f;
    private static final float PX_PER_PT = DPI / 72f;

    private final LorYor01Renderer renderer = new LorYor01Renderer();

    @Test
    void templateIsTheUnmodifiedRevenueDepartmentFile() throws Exception {
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(LorYor01Renderer.templateBytes()));
        assertThat(actual).isEqualTo(TEMPLATE_SHA256);
    }

    @Test
    void templateStillCarriesEveryFieldTheRendererAddresses() throws Exception {
        try (PDDocument doc = Loader.loadPDF(LorYor01Renderer.templateBytes())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            for (int i = 1; i <= 39; i++) {
                assertThat(form.getField("Text1." + i)).as("text field Text1.%d", i).isNotNull();
            }
            for (int i = 1; i <= 7; i++) {
                assertThat(form.getField("Radio Button" + i)).as("tick group %d", i).isNotNull();
            }
        }
    }

    /**
     * Pins the widget order inside each tick group, because the renderer selects a box by INDEX.
     * An index is the cheapest possible way to get this silently wrong: swap two widgets and
     * "โสด" quietly becomes "หม้าย" on a tax filing, with every other test still green.
     */
    @Test
    void tickWidgetsAreInTheDocumentedPrintedOrder() throws Exception {
        try (PDDocument doc = Loader.loadPDF(LorYor01Renderer.templateBytes())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            // ข้อ 1 สถานภาพ: โสด, หม้าย, สมรส, ตายระหว่างปีภาษี
            assertGroup(form, "Radio Button1", 32.0f, 629.0f, 103.4f, 629.5f, 31.8f, 612.1f, 103.5f, 611.9f);
            // ข้อ 1 สถานภาพการสมรส: ตลอดปีภาษี, ระหว่างปีภาษี, หย่า, ตาย
            assertGroup(form, "Radio Button2", 237.2f, 630.0f, 386.6f, 629.8f, 237.8f, 613.3f, 385.9f, 613.8f);
            // ข้อ 2: มีเงินได้, ไม่มีเงินได้
            assertGroup(form, "Radio Button3", 159.3f, 596.0f, 237.1f, 596.0f);
            // ข้อ 4 ของผู้มีเงินได้ / ของคู่สมรส: บิดา, มารดา
            assertGroup(form, "Radio Button4", 123.9f, 493.6f, 173.8f, 494.0f);
            assertGroup(form, "Radio Button5", 123.7f, 476.7f, 173.3f, 476.9f);
            // ข้อ 6 ของผู้มีเงินได้ / ของคู่สมรส: บิดา, มารดา
            assertGroup(form, "Radio Button6", 32.1f, 425.9f, 75.4f, 426.2f);
            assertGroup(form, "Radio Button7", 31.6f, 409.3f, 74.9f, 409.1f);
        }
    }

    private void assertGroup(PDAcroForm form, String name, float... expectedXy) {
        List<?> widgets = form.getField(name).getWidgets();
        assertThat(widgets).as("%s widget count", name).hasSize(expectedXy.length / 2);
        for (int i = 0; i < widgets.size(); i++) {
            PDRectangle r = form.getField(name).getWidgets().get(i).getRectangle();
            assertThat(r.getLowerLeftX()).as("%s widget %d x", name, i)
                    .isCloseTo(expectedXy[i * 2], Offset.offset(0.1f));
            assertThat(r.getLowerLeftY()).as("%s widget %d y", name, i)
                    .isCloseTo(expectedXy[i * 2 + 1], Offset.offset(0.1f));
        }
    }

    /**
     * Every digit of เลขประจำตัวผู้เสียภาษีอากร and รหัสไปรษณีย์ must sit inside its own printed box,
     * touching no cell divider.
     *
     * <p>This is the guard for drawing the comb rows per-cell. It replaced an earlier approach that
     * fed the AcroForm comb a space-padded string matching the field's own
     * {@code AFSpecial_KeystrokeEx("9 9999 99999 99 9")} mask. That looked right in code and was
     * wrong on paper: a comb divides the widget into cells of EQUAL width, while the printed tax-ID
     * boxes run 1-4-5-2-1 with uneven gaps, so the digits drifted further off their boxes across the
     * row. Nothing in the test suite noticed — it was caught by eye. Hence a check that measures the
     * dividers themselves.
     */
    @Test
    void everyCombDigitSitsInsideItsOwnPrintedBox() throws Exception {
        BufferedImage blank = rasterise(LorYor01Renderer.templateBytes());
        BufferedImage filled = rasterise(renderer.render(fullyPopulated()));

        // Internal dividers of both comb rows, from the same measurement the renderer uses.
        float[] taxIdDividers = {138.1f, 157.8f, 171.8f, 185.9f, 200.3f, 221.9f, 236.2f, 250.2f,
                264.6f, 278.3f, 299.0f, 313.3f};
        float[] postcodeDividers = {84.0f, 96.7f, 109.7f, 123.7f};

        assertThat(inkOnDividers(blank, filled, 741f, 760f, taxIdDividers))
                .as("ink touching a เลขประจำตัวผู้เสียภาษีอากร cell divider").isZero();
        assertThat(inkOnDividers(blank, filled, 663f, 679f, postcodeDividers))
                .as("ink touching a รหัสไปรษณีย์ cell divider").isZero();
    }

    /** Counts added (non-template) ink within ±0.7pt of any given divider, inside a row band. */
    private static int inkOnDividers(BufferedImage blank, BufferedImage filled, float yBottom,
                                     float yTop, float[] dividers) {
        int fromY = Math.round((blank.getHeight() / PX_PER_PT - yTop) * PX_PER_PT);
        int toY = Math.round((blank.getHeight() / PX_PER_PT - yBottom) * PX_PER_PT);
        int hits = 0;
        for (float divider : dividers) {
            int fromX = Math.round((divider - 0.7f) * PX_PER_PT);
            int toX = Math.round((divider + 0.7f) * PX_PER_PT);
            for (int y = Math.max(0, fromY); y <= Math.min(blank.getHeight() - 1, toY); y++) {
                for (int x = fromX; x <= toX; x++) {
                    if (!isInk(blank.getRGB(x, y)) && isInk(filled.getRGB(x, y))) {
                        hits++;
                    }
                }
            }
        }
        return hits;
    }

    /** Every datum in the right named box, บาท comma-grouped as the template's own script does. */
    @Test
    void theFieldMappingIsExactlyThis() {
        assertThat(LorYor01Renderer.textFieldValues(fullyPopulated())).containsExactly(
                entry("Text1.1", "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด"),
                entry("Text1.2", "1234567890123"),
                entry("Text1.3", "สมชาย"),
                entry("Text1.4", "ใจดีมีสุข"),
                entry("Text1.5", "อาคารเอ็มไพร์ทาวเวอร์"),
                entry("Text1.6", "1204"),
                entry("Text1.7", "12"),
                entry("Text1.8", "หมู่บ้านสีวลี"),
                entry("Text1.9", "195/8"),
                entry("Text1.10", "4"),
                entry("Text1.11", "ซอยสาทร 12"),
                entry("Text1.12", "แยกนราธิวาส"),
                entry("Text1.13", "ถนนสาทรใต้"),
                entry("Text1.14", "แขวงยานนาวา"),
                entry("Text1.15", "เขตสาทร"),
                entry("Text1.16", "กรุงเทพมหานคร"),
                entry("Text1.17", "10120"),
                entry("Text1.18", "3"),          // ข้อ 3 จำนวนบุตรรวม
                entry("Text1.19", "3"),
                entry("Text1.20", "90,000"),
                entry("Text1.22", "2"),
                entry("Text1.21", "120,000"),
                entry("Text1.23", "60,000"),  // ข้อ 4 ของผู้มีเงินได้ — บิดา+มารดา
                entry("Text1.24", "30,000"),  // ข้อ 4 ของคู่สมรส — บิดาเท่านั้น
                entry("Text1.25", "1"),          // ข้อ 5
                entry("Text1.26", "60,000"),
                entry("Text1.27", "15,000"),  // ข้อ 6
                entry("Text1.28", "100,000"), // ข้อ 7
                entry("Text1.29", "25,000"),  // ข้อ 8
                entry("Text1.30", "45,000"),  // ข้อ 9 — บาท only; the 50 satang print in their own cell
                entry("Text1.31", "300,000"), // ข้อ 10
                entry("Text1.32", "บลจ. กสิกรไทย จำกัด"),
                entry("Text1.35", "100,000"), // ข้อ 12 — ข้อ 11 (LTF) omitted, nothing declared
                entry("Text1.36", "9,000"),   // ข้อ 13
                entry("Text1.37", "20,000"),  // ข้อ 14
                entry("Text1.38", "มูลนิธิรามาธิบดี"),
                entry("Text1.39", "5,000"));
    }

    /** A blank declaration must print a blank form — not a page of zeros. */
    @Test
    void unansweredItemsPrintNothingRatherThanZero() {
        assertThat(LorYor01Renderer.textFieldValues(blank())).containsExactly(
                entry("Text1.1", "บริษัท ทดสอบ จำกัด"));
    }

    /**
     * สตางค์ are printed into the สตางค์ column, not tacked onto the บาท figure.
     *
     * <p>The amount box is two printed cells. Left as the template ships it, the field is
     * right-aligned and overhangs the divider, so a browser prints "45,000.50" straddling the rule —
     * the decimals land in the satang column but as part of the baht string, and the figure reads as
     * though it has burst its box. Splitting them is what makes the two printed columns mean what
     * they say.
     */
    @Test
    void satangArePrintedSeparatelyFromBaht() {
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("45000.50"))).isEqualTo("50");
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("45000.05"))).isEqualTo("05");
        // A whole-baht amount still shows "00": the box is a currency field, and a blank satang cell
        // beside a filled baht cell reads as an unfinished form.
        assertThat(LorYor01Renderer.satangOf(new BigDecimal("90000"))).isEqualTo("00");
        assertThat(LorYor01Renderer.satangOf(BigDecimal.ZERO)).isEqualTo("00");
        // No amount at all means no mark — a blank row stays blank.
        assertThat(LorYor01Renderer.satangOf(null)).isNull();
    }

    /**
     * No printed figure may straddle the บาท/สตางค์ rule.
     *
     * <p>This is the guard for {@code confineToBahtCell}. The mapping test only sees strings, and
     * both whole-page pixel tests are satisfied by a figure lying across the divider — it destroys
     * no artwork, and it stays inside the (overhanging) field rectangle the template declares. Only
     * measuring the divider column itself catches it, which is precisely the defect that shipped in
     * the first draft of this renderer and was spotted by eye, not by a test.
     */
    @Test
    void noFigureStraddlesTheBahtSatangRule() throws Exception {
        BufferedImage blank = rasterise(LorYor01Renderer.templateBytes());
        BufferedImage filled = rasterise(renderer.render(fullyPopulated()));

        int fromPx = Math.round(543.6f * PX_PER_PT);
        int toPx = Math.round(545.8f * PX_PER_PT);
        int straddling = 0;
        for (int y = 0; y < blank.getHeight(); y++) {
            for (int x = fromPx; x <= toPx; x++) {
                if (!isInk(blank.getRGB(x, y)) && isInk(filled.getRGB(x, y))) {
                    straddling++;
                }
            }
        }
        assertThat(straddling).as("added ink sitting on the บาท/สตางค์ divider").isZero();
    }

    /**
     * THE structural test. Rasterises the blank template and a filled document at the same
     * resolution and asserts no dark pixel survives only in the blank one.
     *
     * <p>Written this way round on purpose: "the filled page has more ink" is trivially true and
     * proves nothing. "The filled page lost none of the original ink" catches a mis-measured
     * coordinate covering a printed label, a flatten step dropping artwork, or a swapped template.
     */
    @Test
    void fillingTheFormDestroysNoneOfTheOfficialArtwork() throws Exception {
        BufferedImage blank = rasterise(LorYor01Renderer.templateBytes());
        BufferedImage filled = rasterise(renderer.render(fullyPopulated()));

        assertThat(filled.getWidth()).isEqualTo(blank.getWidth());
        assertThat(filled.getHeight()).isEqualTo(blank.getHeight());

        int lost = 0;
        int added = 0;
        for (int y = 0; y < blank.getHeight(); y++) {
            for (int x = 0; x < blank.getWidth(); x++) {
                boolean before = isInk(blank.getRGB(x, y));
                boolean after = isInk(filled.getRGB(x, y));
                if (before && !after) {
                    lost++;
                } else if (!before && after) {
                    added++;
                }
            }
        }
        assertThat(lost).as("pixels of the official form's own ink destroyed by filling").isZero();
        assertThat(added).as("filling must actually put data on the page").isGreaterThan(5_000);
    }

    /**
     * The complement of the check above, and the one that catches a wrong coordinate. Losing no
     * artwork is easy if you print in the margin; this asserts every pixel we add lands inside a
     * slot the template actually provides — a text field, a tick box, or the วัน/เดือน/ปี rule.
     */
    @Test
    void nothingIsPrintedOutsideASlotTheFormProvides() throws Exception {
        List<PDRectangle> slots = templateSlots();
        BufferedImage blank = rasterise(LorYor01Renderer.templateBytes());
        BufferedImage filled = rasterise(renderer.render(fullyPopulated()));

        List<String> strays = new ArrayList<>();
        for (int y = 0; y < blank.getHeight() && strays.size() < 10; y++) {
            for (int x = 0; x < blank.getWidth(); x++) {
                if (isInk(blank.getRGB(x, y)) || !isInk(filled.getRGB(x, y))) {
                    continue;
                }
                float px = x / PX_PER_PT;
                float py = (blank.getHeight() - y) / PX_PER_PT;
                if (slots.stream().noneMatch(r -> contains(r, px, py))) {
                    strays.add(String.format("(%.1f, %.1f)", px, py));
                    break;
                }
            }
        }
        assertThat(strays).as("ink printed outside every field, tick box and dated rule").isEmpty();
    }

    /**
     * ข้อ 4 and ข้อ 6 print "หักได้คนละ 30,000 บาท" and "รวมกันแล้วไม่เกิน 15,000 บาท" — wording that
     * only works if บิดา and มารดา can both be ticked. The template wires each pair as a single radio
     * group, which would allow only one; the renderer follows the printed form instead. This pins
     * that: ticking มารดา as well must actually add a second mark.
     */
    @Test
    void bothParentsCanBeTickedEvenThoughTheTemplateWiresThemAsRadios() throws Exception {
        int both = countInk(renderer.render(withOwnParentTicks(true, true)));
        int fatherOnly = countInk(renderer.render(withOwnParentTicks(true, false)));
        int neither = countInk(renderer.render(withOwnParentTicks(false, false)));

        assertThat(fatherOnly).as("one tick must add ink").isGreaterThan(neither);
        assertThat(both).as("the second tick must add ink too — a radio group would swallow it")
                .isGreaterThan(fatherOnly);
    }

    /**
     * The ล้างข้อมูล push button must never reach paper.
     *
     * <p>Its annotation flags are {@code 0}, so the Print bit is clear and a viewer omits it — which
     * is exactly why this needs its own test. Both sides of the artwork comparison rasterise for
     * PRINT and therefore agree the button is absent, so that check stays green whether or not the
     * renderer removes it (confirmed by mutation). But {@code PDAcroForm.flatten()} does not consult
     * the Print bit: leave the button in and its grey appearance is baked into the page content,
     * where it prints like any other artwork. This asserts the region is blank in the output.
     */
    @Test
    void theClearDataButtonNeverAppearsOnAPrintedForm() throws Exception {
        PDRectangle button;
        try (PDDocument doc = Loader.loadPDF(LorYor01Renderer.templateBytes())) {
            button = doc.getDocumentCatalog().getAcroForm().getField("Button1")
                    .getWidgets().get(0).getRectangle();
        }
        BufferedImage filled = rasterise(renderer.render(fullyPopulated()));

        int ink = 0;
        for (int y = 0; y < filled.getHeight(); y++) {
            for (int x = 0; x < filled.getWidth(); x++) {
                float px = x / PX_PER_PT;
                float py = (filled.getHeight() - y) / PX_PER_PT;
                if (contains(button, px, py) && isInk(filled.getRGB(x, y))) {
                    ink++;
                }
            }
        }
        assertThat(ink).as("ink inside the ล้างข้อมูล button's rectangle").isZero();
    }

    // -----------------------------------------------------------------------------------------


    /** Every rectangle the template offers as a place to write, plus the drawn-date rule. */
    private static List<PDRectangle> templateSlots() throws IOException {
        List<PDRectangle> slots = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(LorYor01Renderer.templateBytes())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            for (PDField field : form.getFieldTree()) {
                // Button1 (ล้างข้อมูล) is deliberately excluded: it is a slot the template offers
                // but never a place a printed form may carry ink. Including it here would let a
                // flattened clear-button slip past this check as "inside a slot".
                if ("Button1".equals(field.getFullyQualifiedName())) {
                    continue;
                }
                field.getWidgets().forEach(w -> slots.add(w.getRectangle()));
            }
        }
        // วัน/เดือน/ปี ที่แจ้งรายการ has no widget — it is drawn onto its dotted rule.
        slots.add(new PDRectangle(443.2f, 792.0f, 125.2f, 14f));
        return slots;
    }

    /**
     * Thai renders marks above and below the base glyph, and auto-sized field text sits on the
     * baseline rather than inside the widget box, so a couple of points of slop either way is
     * normal typography — not a misplaced value. Anything genuinely in the wrong place misses its
     * slot by tens of points.
     */
    private static boolean contains(PDRectangle r, float x, float y) {
        float pad = 4f;
        return x >= r.getLowerLeftX() - pad && x <= r.getUpperRightX() + pad
                && y >= r.getLowerLeftY() - pad && y <= r.getUpperRightY() + pad;
    }

    private static boolean isInk(int rgb) {
        return (rgb & 0xFF) < 128;
    }

    /**
     * Rasterises as a PRINTER would, not as a screen would.
     *
     * <p>{@link RenderDestination#PRINT} honours each annotation's Print flag. That matters for
     * exactly one object: the template's ล้างข้อมูล push button has annotation flags {@code 0}, so no
     * printed copy of this form has ever shown it — and the renderer removes it. Rendering for
     * SCREEN would draw the button on the blank side of the comparison only, and report its
     * deliberate removal as 857 pixels of destroyed artwork.
     */
    private static BufferedImage rasterise(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(0, PX_PER_PT, ImageType.RGB,
                    RenderDestination.PRINT);
        }
    }

    private static int countInk(byte[] pdf) throws IOException {
        BufferedImage image = rasterise(pdf);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isInk(image.getRGB(x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static LorYor01FormData blank() {
        return new LorYor01FormData(
                "บริษัท ทดสอบ จำกัด", LocalDate.of(2026, 1, 5), null, null, null,
                LorYor01Address.empty(), null, null, null,
                null, null, null, null, null,
                false, false, null, false, false, null,
                null, null,
                false, false, false, false, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null);
    }

    /** A minimal form differing only in the ข้อ 4 บิดา/มารดา ticks. */
    private static LorYor01FormData withOwnParentTicks(boolean father, boolean mother) {
        return new LorYor01FormData(
                null, null, null, null, null, LorYor01Address.empty(), null, null, null,
                null, null, null, null, null,
                father, mother, null, false, false, null,
                null, null,
                false, false, false, false, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null);
    }

    private static LorYor01FormData fullyPopulated() {
        return new LorYor01FormData(
                "บริษัท จีแอลแอนด์อาร์แทปส์แอนด์ไทลส์ จำกัด",
                LocalDate.of(2026, 8, 8),
                "1234567890123",
                "สมชาย",
                "ใจดีมีสุข",
                new LorYor01Address("อาคารเอ็มไพร์ทาวเวอร์", "1204", "12", "หมู่บ้านสีวลี",
                        "195/8", "4", "ซอยสาทร 12", "แยกนราธิวาส", "ถนนสาทรใต้",
                        "แขวงยานนาวา", "เขตสาทร", "กรุงเทพมหานคร", "10120"),
                MaritalState.MARRIED,
                SpousalStatus.MARRIED_WHOLE_YEAR,
                Boolean.FALSE,
                3, 3, new BigDecimal("90000"), 2, new BigDecimal("120000"),
                true, true, new BigDecimal("60000"),
                true, false, new BigDecimal("30000"),
                1, new BigDecimal("60000"),
                true, true, false, true, new BigDecimal("15000"),
                new BigDecimal("100000"),
                new BigDecimal("25000"),
                new BigDecimal("45000.50"),
                new BigDecimal("300000"), "บลจ. กสิกรไทย จำกัด",
                null, null,
                new BigDecimal("100000"),
                new BigDecimal("9000"),
                new BigDecimal("20000"),
                "มูลนิธิรามาธิบดี", new BigDecimal("5000"));
    }
}
