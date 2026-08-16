package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.importrequest.ImportRequestFormData.Line;

/**
 * Guards the one promise this feature makes: the generated ใบขอซื้อ is the business's own
 * F-SM-001 (04) with the deal's data on it — not a replica, not a shifted copy, and not values in
 * the wrong columns.
 *
 * <p>Mirrors {@code LorYor01RendererTest}'s structure, because the same three failures apply and
 * each is invisible to the other two:
 *
 * <ol>
 *   <li>{@link #renderingDestroysNoneOfTheControlledFormsOwnArtwork()} — no pixel of the form's own
 *       ink disappears. Catches an overlay covering a printed label or rule.</li>
 *   <li>{@link #everyTableValueLandsInsideItsOwnColumn()} — each value sits between the correct pair
 *       of measured column rules. Catches a wrong coordinate that check 1 happily allows because it
 *       landed on blank paper.</li>
 *   <li>{@link #rowPitchDoesNotDriftDownThePage()} — the defect that actually happened: a pitch
 *       measured from the gap BETWEEN rules rather than rule-to-rule looked perfect for four rows
 *       and had the text sitting on the rules by row six.</li>
 * </ol>
 */
class ImportRequestRendererTest {

    /**
     * sha256 of the owner-supplied F-SM-001 (04).
     *
     * <p>Pinned because every coordinate in {@link ImportRequestRenderer} was measured against these
     * exact bytes. Re-saving the template through any PDF editor nudges the rules, and this fails
     * first and says why instead of the overlay quietly drifting off the grid.
     */
    private static final String TEMPLATE_SHA256 =
        "e0c1fa1c5c184170bd860115d9dd81a83bb5de9f03d0c18e78bbe3371a8d3171";

    private static final float PX_PER_PT = 150f / 72f;

    private final ImportRequestRenderer renderer = new ImportRequestRenderer();

    /** The owner's real IR69068, the document every coordinate was measured against. */
    private static ImportRequestFormData ir69068() {
        return new ImportRequestFormData(
            "IR69068", "Padana", LocalDate.of(2026, 3, 6),
            "CHN1A-HUB Google ปลวกแดง ระยอง(Pomelo)",
            "ยู่ฮุย อินทีเรีย", "Ya", "Within 21/5/26",
            null, null, null, null, null,
            "Jennet", LocalDate.of(2026, 3, 6),
            List.of(
                new Line(1, "Lithos Nero Nat", "60x60 cm", new BigDecimal("308"), "pcs", "สั่งตามPO"),
                new Line(2, "Terrazzo White Nat", "30x60 cm", new BigDecimal("2370"), "pcs", "สั่งตามPO"),
                new Line(3, "Granito Evo Tucson Nat", "60x60", new BigDecimal("908"), "pcs", "สั่งตามPO")));
    }

    private static ImportRequestFormData withLines(List<Line> lines) {
        ImportRequestFormData d = ir69068();
        return new ImportRequestFormData(d.docNumber(), d.brand(), d.requestDate(), null,
            d.customerName(), d.requestedByName(), d.requiredByNote(), null, null, null, null, null,
            d.issuedByName(), d.depositReceivedDate(), lines);
    }

    @Test
    void theTemplateIsTheOwnerSuppliedFileByteForByte() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        assertThat(HexFormat.of().formatHex(sha.digest(ImportRequestRenderer.templateBytes())))
            .as("F-SM-001 (04) must not be re-saved or re-exported; every coordinate is measured "
                + "against these bytes")
            .isEqualTo(TEMPLATE_SHA256);
    }

    @Test
    void renderingDestroysNoneOfTheControlledFormsOwnArtwork() throws Exception {
        BufferedImage blank = rasterise(ImportRequestRenderer.templateBytes());
        BufferedImage filled = rasterise(renderer.render(ir69068()));

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
        assertThat(lost).as("pixels of the controlled form's own ink destroyed by the overlay")
            .isZero();
        assertThat(added).as("the overlay must actually put data on the page").isGreaterThan(2_000);
    }

    /**
     * Column rules as measured off the template, as {@code {left, right}} in PDF user space. A value
     * whose glyphs stray outside its pair is in the wrong cell even if the page looks plausible.
     */
    @Test
    void everyTableValueLandsInsideItsOwnColumn() throws Exception {
        List<Placed> placed = placedText(renderer.render(ir69068()));

        assertBetween(placed, "Lithos Nero Nat", 50.7f, 241.0f);          // Code
        assertBetween(placed, "60x60 cm", 241.0f, 305.5f);                // Size
        assertBetween(placed, "2370", 305.5f, 348.0f);                    // จำนวน
        assertBetween(placed, "pcs", 348.0f, 385.0f);                     // หน่วยนับ
        assertBetween(placed, "Ya", 437.5f, 500.8f);                      // Requested by
        assertBetween(placed, "Within 21/5/26", 500.8f, 565.3f);          // กำหนดวันที่ต้องการของ
    }

    /**
     * The pitch defect, pinned. 13 lines fill all 26 body rows, so any per-row error accumulates to
     * its maximum by the bottom of the page. Each item's baseline must sit the SAME distance above
     * its own rule; with the original 21.35 (the gap between rule edges, not the 22.2077 pitch) the
     * spread runs to ~11pt and the text ends up on the rules.
     */
    @Test
    void rowPitchDoesNotDriftDownThePage() throws Exception {
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            lines.add(new Line(i, "Drift Line " + i, "60x60 cm", new BigDecimal(100 * i), "pcs",
                               "สั่งตามPO"));
        }
        List<Placed> placed = placedText(renderer.render(withLines(lines)));

        List<Float> baselines = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            baselines.add(baselineOf(placed, "Drift Line " + i));
        }

        float first = baselines.get(0);
        float worst = 0f;
        for (int i = 0; i < baselines.size(); i++) {
            // Each item occupies two rows (line + note), so consecutive items are 2 pitches apart.
            float expected = first - i * 2 * 22.2077f;
            worst = Math.max(worst, Math.abs(baselines.get(i) - expected));
        }
        assertThat(worst).as("row-pitch drift accumulated over a full page").isLessThan(0.75f);
    }

    /**
     * A line and its note are one indivisible block. 13 two-row lines exactly fill a sheet, so a
     * 14th must move WHOLE to sheet two — chunking a flat row list every 26 would leave its
     * "สั่งตามPO" stranded at the top of sheet two under a different item number.
     */
    @Test
    void aLineIsNeverSeparatedFromItsOwnNoteByAPageBreak() throws Exception {
        List<Line> lines = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            lines.add(new Line(i, "Boundary Line " + i, "60x60 cm", new BigDecimal(100 * i), "pcs",
                               "สั่งตามPO"));
        }
        ImportRequestFormData data = withLines(lines);
        assertThat(ImportRequestRenderer.pagesRequired(data)).isEqualTo(2);

        try (PDDocument doc = Loader.loadPDF(renderer.render(data))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            String sheetTwo = textOfPage(doc, 2);
            assertThat(sheetTwo)
                .as("line 14 must arrive on sheet two WITH its own note, not the note alone")
                .contains("Boundary Line 14");
            assertThat(textOfPage(doc, 1))
                .as("line 14 must not have been left behind on sheet one")
                .doesNotContain("Boundary Line 14");
        }
    }

    /**
     * Regression: a long customer name must arrive WHOLE.
     *
     * <p>สั่งมาให้ is a 52.5pt column and its cell is merged over however many rows the line block
     * spans — which on a two-line deal is only two. The first wrapping implementation wrapped at a
     * fixed size and cut the overflow, which silently dropped "จำกัด" off the end of a registered
     * company name: a purchase document naming a party that does not exist. It must shrink until
     * every word fits, not drop words.
     *
     * <p>Deliberately uses a TWO-line deal, the tightest case — with more lines the block is taller
     * and the bug hides.
     */
    @Test
    void aLongCustomerNameSurvivesWholeEvenInTheTightestBlock() throws Exception {
        ImportRequestFormData data = new ImportRequestFormData(
            "IR69068", "Padana", LocalDate.of(2026, 3, 6), null,
            "บริษัท ยู่ฮุย อินทีเรีย จำกัด", "Ya", "Within 21/5/26",
            null, null, null, null, null, "Jennet", null,
            List.of(new Line(1, "Lithos Nero Nat", "60x60 cm", new BigDecimal("308"), "pcs", null),
                    new Line(2, "Terrazzo White Nat", "30x60 cm", new BigDecimal("2370"), "pcs", null)));

        String text = placedText(renderer.render(data)).stream()
            .map(Placed::text).reduce("", (a, b) -> a + " " + b);

        // Every word, including the last. Asserted word by word because the value legitimately
        // wraps across rows and is not contiguous in the text layer.
        assertThat(text).contains("บริษัท").contains("ยู่ฮุย").contains("อินทีเรีย").contains("จำกัด");
    }

    @Test
    void aFormThatFitsIsASingleSheetAndCarriesNoPageMarker() throws Exception {
        try (PDDocument doc = Loader.loadPDF(renderer.render(ir69068()))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            // The paper original has no page marker; adding one would change what a normal form
            // looks like.
            assertThat(textOfPage(doc, 1)).doesNotContain("หน้า");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private record Placed(String text, float x, float endX, float baseline) {}

    private static void assertBetween(List<Placed> placed, String text, float left, float right) {
        Placed p = placed.stream().filter(it -> it.text().contains(text)).findFirst()
            .orElseThrow(() -> new AssertionError("not rendered at all: " + text));
        assertThat(p.x()).as("%s starts left of its column", text).isGreaterThanOrEqualTo(left);
        assertThat(p.endX()).as("%s overflows its column", text).isLessThanOrEqualTo(right);
    }

    private static float baselineOf(List<Placed> placed, String text) {
        return placed.stream().filter(it -> it.text().contains(text)).findFirst()
            .orElseThrow(() -> new AssertionError("not rendered at all: " + text))
            .baseline();
    }

    /** Text with positions converted to PDF user space (origin bottom-left), page 1 only. */
    private static List<Placed> placedText(byte[] pdf) throws IOException {
        List<Placed> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float height = doc.getPage(0).getMediaBox().getHeight();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (text.isBlank() || positions.isEmpty()) {
                        return;
                    }
                    TextPosition first = positions.get(0);
                    TextPosition last = positions.get(positions.size() - 1);
                    out.add(new Placed(text.strip(), first.getXDirAdj(),
                        last.getXDirAdj() + last.getWidthDirAdj(), height - first.getYDirAdj()));
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            stripper.getText(doc);
        }
        return out;
    }

    private static String textOfPage(PDDocument doc, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    private static BufferedImage rasterise(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(0, PX_PER_PT, ImageType.RGB,
                RenderDestination.PRINT);
        }
    }

    private static boolean isInk(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r + g + b) / 3 < 200;
    }
}
