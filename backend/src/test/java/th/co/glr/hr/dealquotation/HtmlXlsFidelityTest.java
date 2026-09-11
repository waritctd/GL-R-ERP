package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import th.co.glr.hr.common.ChromiumPdfPrinter;
import th.co.glr.hr.common.ChromiumTestGate;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.common.sheet.LibreOfficeMetrics;
import th.co.glr.hr.common.sheet.RuleScanner;
import th.co.glr.hr.common.sheet.RuleScanner.Rule;
import th.co.glr.hr.common.sheet.SheetHtmlRenderer;
import th.co.glr.hr.common.sheet.SheetPlan;
import th.co.glr.hr.ticket.QuotationRenderModel;
import th.co.glr.hr.ticket.QuotationRenderModel.RenderItem;
import th.co.glr.hr.ticket.QuotationRenderModel.Signatories;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * html-fidelity-spec.md §5/§6 — the owner's acceptance for the Chromium engine: the HTML render
 * of a quotation must be visually the same as the LibreOffice render of the same XLS,
 * "including border coverage and line thickness". Both engines print the SAME workbook; the two
 * PDFs are rasterised at 110 dpi, EVERY page, and every ruled line is measured ({@link RuleScanner}):
 * <ul>
 *   <li>every LibreOffice rule has an HTML counterpart within {@value #POS_TOL_MM} mm of position
 *       and both ends within {@value #EXTENT_TOL_MM} mm — that is border coverage, per rule;</li>
 *   <li>no rule exists in the HTML render that the LibreOffice render does not have;</li>
 *   <li>each matched pair's stroke agrees within {@value #STROKE_TOL_PT} pt;</li>
 *   <li>page counts agree.</li>
 * </ul>
 * Spec §7 ("the border of the line covers each section properly") is pinned on top of that, in
 * BOTH renders, from the sheet plan the HTML was drawn from (i.e. the borders the XLS path wrote):
 * every planned rule is CONTINUOUS along its whole extent (no gap over {@value #GAP_TOL_MM} mm),
 * and each section is a closed box — the column-title row (four edges plus every column
 * separator), the item+remark box (outer left/right rules from the title row down to a bottom
 * rule at or below remark line 8, never an open bottom above รวมเป็นเงิน), each totals amount
 * cell; on a paginated document the box closes at the bottom of every page but the last.
 *
 * <p>Spec §8 (the remark block is its OWN closed box B..H — top rule under the last item line
 * stopping at the เป็นเงิน column, interior separators B|C…G|H ending there, A|B and H|I running
 * on to the bottom rule) is asserted from the plan AND from the ink of both renders
 * ({@link #assertRemarkBoxInk}). Spec §9 (multi-page) is asserted on 12- and 30-item fixtures:
 * identical page counts in both engines, "หน้า X/Y" on every page at the same position and size
 * in both (and on no single-page render), every page but the last closed by an A..I rule that the
 * column rules end on, no item block or heading split across pages (from the plan and from each
 * page's own extracted text), §5 tolerances and §7 continuity on every page.
 *
 * <p>Text: on every page of every fixture the two renders must print the SAME TEXT
 * ({@link #assertSameText}): each page's text is extracted in geometric order (PDFBox
 * {@code sortByPosition} — the two PDFs' content streams are in different orders, LibreOffice a
 * row at a time, Chromium its absolutely positioned boxes in DOM order, so geometric order is the
 * only one they share), NFC-normalised, and compared with ALL whitespace removed — PDFBox's own
 * word-gap heuristic splits a Thai glyph run differently in the two PDFs ("จำกัด" comes back as
 * "จำ กัด" from the Chromium one), which is an artefact of extraction, not of what was printed.
 * A missing, extra or altered character anywhere on the page fails; when only the ORDER differs
 * (PDFBox's line grouping splits a large-font title differently once the host substitutes the
 * fonts), the characters are compared as a sorted multiset instead — see {@link #assertSameText}.
 * One known exception is
 * declared per fixture where it applies, and asserted to be present rather than silently
 * substituted — see {@link KnownTextDifference}.
 *
 * <p>Fixtures: the 5-item reference through the XLS path, the owner's REAL filled workbook
 * ({@code fixtures/QN6900-CHN1A-HUB.xls}, fit-to-page 1×1 on a POI copy — spec §6; its own cells
 * carry the template's column separators through the remark rows, so the §8 box is not asserted
 * on it), and the 12/30-item paginated references. Side-by-side and diff PNGs, page rasters and
 * the numeric reports go to {@code -Dfidelity.outDir} (default {@code target/fidelity}).
 * Skipped where LibreOffice or Chromium is absent — except under {@code REQUIRE_CHROMIUM=1}
 * (CI), where absence fails ({@link ChromiumTestGate}).
 */
class HtmlXlsFidelityTest {
    static final double POS_TOL_MM = 1.0;
    static final double EXTENT_TOL_MM = 1.0;
    static final double STROKE_TOL_PT = 0.2;
    /** §7 continuity: the longest stretch of a planned rule with no ink is at most this. */
    static final double GAP_TOL_MM = 0.3;
    /** Perpendicular window around a planned rule's position in which its ink is looked for —
     * the two renders sit within 0.15 mm of each other and of the plan (see the v3/v4 reports);
     * neighbouring rules are never closer than 5 mm. */
    static final double SEARCH_MM = 0.75;
    /** §5 text: baselines within 1.5 mm — applied to the page-number footer in both engines. */
    static final double TEXT_TOL_MM = 1.5;
    /** Footer size, as the glyphs' rasterised height: 1 pt of Cordia New is ≈0.5 mm of ink. */
    static final double FOOTER_SIZE_TOL_MM = 0.6;
    /** Grey level below which a pixel counts as text ink (rules use {@link RuleScanner#DARK}). */
    static final int TEXT_INK = 160;
    static final double MM_PER_PT = 25.4 / 72.0;

    private static Path outDir;

    @BeforeAll
    static void requireTools() throws IOException {
        // Skips locally when either engine is missing; FAILS under REQUIRE_CHROMIUM=1 (CI).
        ChromiumTestGate.requireBothOrSkip();
        outDir = Path.of(System.getProperty("fidelity.outDir", "target/fidelity"));
        Files.createDirectories(outDir);
    }

    @Test
    void fiveItemReference_htmlRulesMatchLibreOfficeRules() throws Exception {
        String name = "html-vs-xls-v5";
        QuotationRenderModel model = fiveItemReference();
        byte[] xls = new QuotationRenderer().toXls(model);
        byte[] loPdf = LibreOfficePdfConverter.convert(xls);
        SheetHtmlRenderer.Rendered rendered = QuotationHtmlDocument.rendered(xls, model);
        byte[] htmlPdf = ChromiumPdfPrinter.print(rendered.html());
        SheetPlan plan = rendered.plan();
        Comparison c = compare(plan, loPdf, htmlPdf, name);
        c.assertWithinTolerance();
        assertSameText(plan, loPdf, htmlPdf, name);
        assertThat(plan.pages.size()).as("single page").isEqualTo(1);
        Sections sections = Sections.of(plan);
        sections.assertClosed(name);
        // The v2 path closes the box on remark line 8 itself (no blank closing row).
        assertThat(sections.boxBottomRow).as("v2 box closes directly under remark line 8").isEqualTo(sections.line8Row);
        sections.assertRemarkBox(name);
        assertRemarkBoxInk(plan, sections, loPdf, htmlPdf, name);
        assertPlannedRulesContinuous(plan, loPdf, htmlPdf, name);
        // §9: a single-page document prints no page number, in either engine.
        assertThat(pageText(loPdf, 0)).as("LibreOffice single page: no page number").doesNotContain(norm("หน้า 1/1"));
        assertThat(pageText(htmlPdf, 0)).as("Chromium single page: no page number").doesNotContain(norm("หน้า 1/1"));
        assertThat(footerWord(loPdf, 0)).isNull();
        assertThat(footerWord(htmlPdf, 0)).isNull();
    }

    @Test
    void realWorkbook_fitToOnePage_htmlRulesMatchLibreOfficeRules() throws Exception {
        String name = "html-vs-workbook-v5";
        byte[] workbook = fitToOnePageCopy(new ClassPathResource("fixtures/QN6900-CHN1A-HUB.xls"));
        byte[] loPdf = LibreOfficePdfConverter.convert(workbook);
        SheetHtmlRenderer.Rendered rendered = QuotationHtmlDocument.rendered(workbook, null);
        byte[] htmlPdf = ChromiumPdfPrinter.print(rendered.html());
        Comparison c = compare(rendered.plan(), loPdf, htmlPdf, name);
        c.assertWithinTolerance();
        assertSameText(rendered.plan(), loPdf, htmlPdf, name, WORKBOOK_DATE_CELL);
        Sections sections = Sections.of(rendered.plan());
        sections.assertClosed(name);
        // The hand-filled workbook keeps the template's own blank closing row under line 8.
        assertThat(sections.boxBottomRow).as("workbook box closes on the template's closing row").isEqualTo(sections.line8Row + 1);
        assertPlannedRulesContinuous(rendered.plan(), loPdf, htmlPdf, name);
    }

    /** The last items and the whole tail share the final page, so the remark box's top rule
     * sits directly under an item line on a paginated document (9 items with the licensed
     * fonts, 13 with CI's substitutes; the two fixtures below carry their tail alone on the last page, under the repeated
     * title row). The counts are found from the plan, not hardcoded — see {@link #itemCountFor}. */
    @Test
    void tailSharesItsPageWithTheLastItems() throws Exception {
        int n = itemCountFor(true, 6, 2);
        assertPaginated(n, "paginated-v5-" + n + "-tail-with-items", true);
    }

    @Test
    void twoPages_paginateSeamlesslyInBothEngines() throws Exception {
        int n = itemCountFor(false, 10, 2);
        assertPaginated(n, "paginated-v5-" + n, false);
    }

    @Test
    void threeOrMorePages_paginateSeamlesslyInBothEngines() throws Exception {
        int n = itemCountFor(false, 25, 3);
        assertPaginated(n, "paginated-v5-" + n, false);
    }

    /** The smallest item count at or above {@code from} whose plan fills at least {@code minPages}
     * pages and lands the remark box on its page the way {@code tailWithItems} says. Which count
     * produces which page shape is a function of the page fill, and the page fill moves with the
     * column unit of whatever font the host resolves the workbook's default font to
     * ({@link th.co.glr.hr.common.sheet.FontResolver}): the licensed Cordia New gives 9 / 11 / 27
     * here, CI's fonts-thai-tlwg substitute (Umpush, a 156-twip unit against Cordia's 102) prints
     * shorter rows and lands on 13 / 10 / 25. The
     * plan is read from the XLS engine alone — no LibreOffice, no Chromium — so this costs a few
     * hundred milliseconds. */
    static int itemCountFor(boolean tailWithItems, int from, int minPages) {
        for (int n = from; n <= from + 40; n++) {
            QuotationRenderModel model = reference(n, String.format(Locale.US, "QT-2026-%04d", n));
            byte[] xls = new QuotationRenderer().toXls(model);
            SheetPlan plan = QuotationHtmlDocument.rendered(xls, model).plan();
            if (plan.pages.size() < minPages) continue;
            Sections sections = Sections.of(plan);
            boolean shares = pageOf(plan, sections.remarkRow - 1) == pageOf(plan, sections.remarkRow);
            if (shares == tailWithItems) return n;
        }
        throw new AssertionError("no item count in " + from + ".." + (from + 40) + " gives "
            + (tailWithItems ? "a tail sharing its page with the last item" : "a tail alone on the last page")
            + " over " + minPages + "+ pages");
    }

    /** Spec §9 on an {@code n}-item reference. {@code tailWithItems} pins which shape the fixture
     * exercises: the tail under the last item line (its top rule B..H is then asserted as ink)
     * or the tail alone under the repeated title row. */
    private void assertPaginated(int n, String name, boolean tailWithItems) throws Exception {
        QuotationRenderModel model = reference(n, String.format(Locale.US, "QT-2026-%04d", n));
        byte[] xls = new QuotationRenderer().toXls(model);
        byte[] loPdf = LibreOfficePdfConverter.convert(xls);
        SheetHtmlRenderer.Rendered rendered = QuotationHtmlDocument.rendered(xls, model);
        byte[] htmlPdf = ChromiumPdfPrinter.print(rendered.html());
        SheetPlan plan = rendered.plan();
        int pages = plan.pages.size();
        assertThat(pages).as("fixture must paginate").isGreaterThanOrEqualTo(2);
        assertThat(RuleScanner.pageCount(loPdf)).as("LibreOffice pages").isEqualTo(pages);
        assertThat(RuleScanner.pageCount(htmlPdf)).as("Chromium pages").isEqualTo(pages);
        for (int p = 0; p < pages; p++) {
            ImageIO.write(RuleScanner.rasterize(loPdf, p), "png", outDir.resolve(name + "-lo-" + (p + 1) + ".png").toFile());
            ImageIO.write(RuleScanner.rasterize(htmlPdf, p), "png", outDir.resolve(name + "-html-" + (p + 1) + ".png").toFile());
        }

        // §5 on every page.
        compare(plan, loPdf, htmlPdf, name).assertWithinTolerance();
        assertSameText(plan, loPdf, htmlPdf, name);

        Sections sections = Sections.of(plan);
        sections.assertClosed(name);
        assertThat(sections.boxBottomRow).isEqualTo(sections.line8Row);
        sections.assertRemarkBox(name);
        assertRemarkBoxInk(plan, sections, loPdf, htmlPdf, name);
        assertThat(pageOf(plan, sections.remarkRow - 1) == pageOf(plan, sections.remarkRow))
            .as(name + ": the remark box " + (tailWithItems ? "shares its page with the last item" : "opens the last page alone"))
            .isEqualTo(tailWithItems);

        StringBuilder rep = new StringBuilder("=== " + name + " pages ===\n");
        String[] engine = {"LibreOffice", "Chromium"};
        byte[][] pdfs = {loPdf, htmlPdf};
        for (SheetPlan.Page page : plan.pages) {
            int idx = page.number() - 1;
            Edges edges = new Edges(plan, page);
            boolean last = page.number() == pages;
            List<Integer> rows = page.rowIndices();
            if (page.number() > 1) {
                assertThat(page.repeatedRowCount()).as("page " + page.number() + " re-opens under the repeated title rows").isGreaterThan(0);
                assertThat(rows.subList(0, page.repeatedRowCount())).contains(sections.titleRow);
                for (int c = plan.firstCol; c <= plan.lastCol; c++) {
                    assertThat(edges.bottom(sections.titleRow, c)).as("page " + page.number() + " repeated title row closed on its own bottom, col " + c).isTrue();
                }
            }
            if (!last) {
                int pageBottom = rows.get(rows.size() - 1);
                for (int c = plan.firstCol; c <= plan.lastCol; c++) {
                    assertThat(edges.bottom(pageBottom, c)).as("page " + page.number() + " box closed at its bottom row " + pageBottom + " col " + c).isTrue();
                }
            }

            // §9: the page's last drawn horizontal rule closes the box across A..I, and the
            // column rules end on it (on the last page the last rules are the signature
            // underscores; its box bottom is pinned by assertClosed + continuity instead).
            double ox = plan.originXHmm() / 100.0;
            double left = ox + plan.columnLeftHmm(plan.firstCol) / 100.0;
            double right = ox + plan.contentWidthHmm() / 100.0;
            double[] lastRuleY = new double[2];
            InkBox[] footerInk = new InkBox[2];
            for (int e = 0; e < 2; e++) {
                BufferedImage raster = RuleScanner.rasterize(pdfs[e], idx);
                List<Rule> rules = RuleScanner.scan(raster);
                Rule lowest = null;
                double columnsEnd = 0;
                for (Rule r : rules) {
                    if (r.horizontal()) {
                        if (lowest == null || r.pos() > lowest.pos()) lowest = r;
                    } else {
                        columnsEnd = Math.max(columnsEnd, r.toMm());
                    }
                }
                assertThat(lowest).as(name + " page " + page.number() + " " + engine[e] + ": no horizontal rule").isNotNull();
                lastRuleY[e] = lowest.posMm();
                // The sheet footer prints inside the bottom margin (LibreOffice's footer band).
                footerInk[e] = inkBelow(raster, (plan.pageHeightHmm - plan.marginBottomHmm) / 100.0);
                if (!last) {
                    assertThat(lowest.fromMm()).as(name + " page " + page.number() + " " + engine[e] + ": last rule starts at A").isCloseTo(left, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
                    assertThat(lowest.toMm()).as(name + " page " + page.number() + " " + engine[e] + ": last rule ends at I").isCloseTo(right, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
                    assertThat(columnsEnd).as(name + " page " + page.number() + " " + engine[e] + ": column rules end on the closing rule").isCloseTo(lowest.posMm(), org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
                }
            }

            // §9: "หน้า X/Y" on every page, both engines, same place, same size, below the box.
            String number = "หน้า " + page.number() + "/" + pages;
            assertThat(pageText(loPdf, idx)).as(name + " page " + page.number() + " LibreOffice page number").contains(norm(number));
            assertThat(pageText(htmlPdf, idx)).as(name + " page " + page.number() + " Chromium page number").contains(norm(number));
            Word lo = footerWord(loPdf, idx);
            Word html = footerWord(htmlPdf, idx);
            assertThat(lo).as("LibreOffice footer word").isNotNull();
            assertThat(html).as("Chromium footer word").isNotNull();
            InkBox loInk = footerInk[0];
            InkBox htmlInk = footerInk[1];
            assertThat(loInk).as("LibreOffice footer ink").isNotNull();
            assertThat(htmlInk).as("Chromium footer ink").isNotNull();
            rep.append(String.format(Locale.US, "page %d: footer text LO x=%.2f..%.2fmm y=%.2fmm | HTML x=%.2f..%.2fmm y=%.2fmm"
                    + " | footer ink LO %s HTML %s | last rule LO %.2fmm HTML %.2fmm%n",
                page.number(), lo.x0Mm(), lo.x1Mm(), lo.yMm(), html.x0Mm(), html.x1Mm(), html.yMm(),
                loInk, htmlInk, lastRuleY[0], lastRuleY[1]));
            // Position and size are compared as INK (both rasters at 110 dpi): PDFBox reports
            // Chromium's text at its CSS pixel size (14 for an 11 pt footer), so its font size is
            // no oracle; the glyphs' own bounding box is.
            assertThat(Math.abs(lo.centerMm() - html.centerMm())).as(name + " page " + page.number() + ": footer text centre").isLessThanOrEqualTo(TEXT_TOL_MM);
            assertThat(Math.abs(loInk.centerMm() - htmlInk.centerMm())).as(name + " page " + page.number() + ": footer ink centre").isLessThanOrEqualTo(TEXT_TOL_MM);
            assertThat(Math.abs(loInk.y1Mm() - htmlInk.y1Mm())).as(name + " page " + page.number() + ": footer baseline").isLessThanOrEqualTo(TEXT_TOL_MM);
            assertThat(Math.abs(loInk.heightMm() - htmlInk.heightMm())).as(name + " page " + page.number() + ": footer glyph height (size)").isLessThanOrEqualTo(FOOTER_SIZE_TOL_MM);
            assertThat(Math.abs(loInk.widthMm() - htmlInk.widthMm())).as(name + " page " + page.number() + ": footer glyph run width (size)").isLessThanOrEqualTo(TEXT_TOL_MM);
            assertThat(loInk.y0Mm()).as("LibreOffice footer below the last rule").isGreaterThan(lastRuleY[0]);
            assertThat(htmlInk.y0Mm()).as("Chromium footer below the last rule").isGreaterThan(lastRuleY[1]);
        }

        // §9: no item block (heading + line 1 + ขนาด line + calc lines) is split — from the plan …
        List<List<Integer>> blocks = itemBlocks(plan, sections);
        assertThat(blocks).as("one block per item").hasSize(n);
        for (int i = 0; i < blocks.size(); i++) {
            List<Integer> block = blocks.get(i);
            int page = pageOf(plan, block.get(0));
            for (int r : block) {
                assertThat(pageOf(plan, r)).as(name + ": item " + (i + 1) + " row " + r + " on the same page as row " + block.get(0)).isEqualTo(page);
            }
            rep.append("item ").append(i + 1).append(": rows ").append(block).append(" page ").append(page).append('\n');
        }
        // … and from what each page of each render actually prints.
        for (int e = 0; e < 2; e++) {
            for (int i = 1; i <= n; i++) {
                String first = norm("Model-" + i + " สี");
                String size = norm("ขนาด 60x60 cm.");
                String calcEnd = norm("= " + (32 * i) + " แผ่น)");
                int found = -1;
                for (int p = 0; p < pages; p++) {
                    String text = pageText(pdfs[e], p);
                    if (text.contains(first)) {
                        assertThat(found).as(name + " " + engine[e] + ": item " + i + " printed on one page only").isEqualTo(-1);
                        found = p;
                        assertThat(text).as(name + " " + engine[e] + " page " + (p + 1) + ": item " + i + "'s calc line on the same page").contains(calcEnd);
                        assertThat(text).as(name + " " + engine[e] + " page " + (p + 1) + ": item " + i + "'s size line on the same page").contains(size);
                        if (i == 1) assertThat(text).as("heading โซนที่ 1 with item 1").contains(norm("โซนที่ 1"));
                        if (i == 3) assertThat(text).as("heading โซนที่ 2 with item 3").contains(norm("โซนที่ 2"));
                    }
                }
                assertThat(found).as(name + " " + engine[e] + ": item " + i + " printed").isGreaterThanOrEqualTo(0);
            }
        }
        // The tail stays with the remark box.
        assertThat(pageOf(plan, sections.totalRow)).isEqualTo(pageOf(plan, sections.remarkRow));
        assertThat(pageOf(plan, plan.lastRow)).isEqualTo(pageOf(plan, sections.remarkRow));
        Files.writeString(outDir.resolve(name + "-pages.txt"), rep.toString(), StandardCharsets.UTF_8);

        assertPlannedRulesContinuous(plan, loPdf, htmlPdf, name);
    }

    // ── comparison ───────────────────────────────────────────────────────────────────────

    record Pair(Rule a, Rule b) {
        double posDeltaMm() { return Math.abs(a.posMm() - b.posMm()); }
        double fromDeltaMm() { return Math.abs(a.fromMm() - b.fromMm()); }
        double toDeltaMm() { return Math.abs(a.toMm() - b.toMm()); }
        double strokeDeltaPt() { return Math.abs(a.strokePt() - b.strokePt()); }
    }

    record Comparison(String name, int pagesA, int pagesB, List<Pair> matched, List<Rule> onlyA, List<Rule> onlyB,
                      String report) {
        void assertWithinTolerance() {
            assertThat(pagesB).as(name + ": page count").isEqualTo(pagesA);
            assertThat(onlyA).as(name + ": LibreOffice rules with no HTML counterpart\n" + report).isEmpty();
            assertThat(onlyB).as(name + ": HTML rules LibreOffice does not draw\n" + report).isEmpty();
            for (Pair p : matched) {
                String where = name + ": " + RuleScanner.describe(p.a()) + " vs " + RuleScanner.describe(p.b()) + "\n" + report;
                assertThat(p.posDeltaMm()).as(where).isLessThanOrEqualTo(POS_TOL_MM);
                assertThat(p.fromDeltaMm()).as(where).isLessThanOrEqualTo(EXTENT_TOL_MM);
                assertThat(p.toDeltaMm()).as(where).isLessThanOrEqualTo(EXTENT_TOL_MM);
                assertThat(p.strokeDeltaPt()).as(where).isLessThanOrEqualTo(STROKE_TOL_PT);
            }
            assertThat(matched).as(name + ": no rules detected at all").isNotEmpty();
        }
    }

    /** [left, top, right, bottom] in page mm of every picture on {@code page} — the badge
     * picture has a thin frame that the scanner reads as faint rules (0.15–0.2 pt); pictures are
     * compared as pixels in the diff map, not as borders, so rules lying inside a picture box are
     * ignored. */
    static List<double[]> pictureBoxesMm(SheetPlan plan, SheetPlan.Page page) {
        List<double[]> boxes = new ArrayList<>();
        Map<Integer, Integer> rowTop = new java.util.HashMap<>();
        int y = 0;
        for (int r : page.rowIndices()) { rowTop.put(r, y); y += plan.rows.get(r).scaledHmm(); }
        double ox = plan.originXHmm() / 100.0;
        double oy = plan.originYHmm(page) / 100.0;
        for (SheetPlan.Picture pic : plan.pictures) {
            if (!rowTop.containsKey(pic.row1()) || !rowTop.containsKey(pic.row2())) continue;
            // e.g. the owner's workbook carries a picture anchored at AA17, outside the print range
            if (pic.col1() < plan.firstCol || pic.col2() > plan.lastCol) continue;
            double x1 = (plan.columnLeftHmm(pic.col1()) + plan.column(pic.col1()).scaledHmm() * pic.dx1() / 1024.0) / 100.0;
            double x2 = (plan.columnLeftHmm(pic.col2()) + plan.column(pic.col2()).scaledHmm() * pic.dx2() / 1024.0) / 100.0;
            double y1 = (rowTop.get(pic.row1()) + plan.rows.get(pic.row1()).scaledHmm() * pic.dy1() / 256.0) / 100.0;
            double y2 = (rowTop.get(pic.row2()) + plan.rows.get(pic.row2()).scaledHmm() * pic.dy2() / 256.0) / 100.0;
            boxes.add(new double[]{ox + x1 - 0.5, oy + y1 - 0.5, ox + x2 + 0.5, oy + y2 + 0.5});
        }
        return boxes;
    }

    static boolean insidePicture(Rule r, List<double[]> boxes) {
        for (double[] b : boxes) {
            double x0 = r.horizontal() ? r.fromMm() : r.posMm();
            double x1 = r.horizontal() ? r.toMm() : r.posMm();
            double y0 = r.horizontal() ? r.posMm() : r.fromMm();
            double y1 = r.horizontal() ? r.posMm() : r.toMm();
            if (x0 >= b[0] && x1 <= b[2] && y0 >= b[1] && y1 <= b[3]) return true;
        }
        return false;
    }

    /** §5 on EVERY page of both renders. Page 1's images keep the plain {@code -side}/{@code -diff}
     * names; later pages get {@code -side-pN}/{@code -diff-pN}. */
    static Comparison compare(SheetPlan plan, byte[] loPdf, byte[] htmlPdf, String name) throws IOException {
        int pagesA = RuleScanner.pageCount(loPdf);
        int pagesB = RuleScanner.pageCount(htmlPdf);
        List<Pair> matched = new ArrayList<>();
        List<Rule> onlyA = new ArrayList<>();
        List<Rule> onlyB = new ArrayList<>();
        StringBuilder rep = new StringBuilder();
        rep.append("=== ").append(name).append(" === (A = XLS/LibreOffice, B = HTML/Chromium, 110 dpi)\n");
        rep.append("pages A=").append(pagesA).append(" B=").append(pagesB).append('\n');
        double maxPos = 0, maxExt = 0, maxStroke = 0;
        for (int p = 0; p < Math.min(pagesA, pagesB); p++) {
            List<double[]> pictureBoxes = p < plan.pages.size() ? pictureBoxesMm(plan, plan.pages.get(p)) : List.of();
            BufferedImage imgA = RuleScanner.rasterize(loPdf, p);
            BufferedImage imgB = RuleScanner.rasterize(htmlPdf, p);
            List<Rule> rulesA = new ArrayList<>(RuleScanner.scan(imgA));
            List<Rule> rulesB = new ArrayList<>(RuleScanner.scan(imgB));
            rulesA.removeIf(r -> insidePicture(r, pictureBoxes));
            rulesB.removeIf(r -> insidePicture(r, pictureBoxes));

            List<Pair> pageMatched = new ArrayList<>();
            List<Rule> pageOnlyA = new ArrayList<>();
            List<Rule> remainingB = new ArrayList<>(rulesB);
            for (Rule a : rulesA) {
                Rule best = null;
                double bestScore = Double.MAX_VALUE;
                for (Rule b : remainingB) {
                    if (b.horizontal() != a.horizontal()) continue;
                    double score = Math.abs(a.pos() - b.pos()) + 0.25 * (Math.abs(a.from() - b.from()) + Math.abs(a.to() - b.to()));
                    if (score < bestScore) { bestScore = score; best = b; }
                }
                if (best != null && Math.abs(a.posMm() - best.posMm()) <= 3.0) {
                    remainingB.remove(best);
                    pageMatched.add(new Pair(a, best));
                } else {
                    pageOnlyA.add(a);
                }
            }
            rep.append("-- page ").append(p + 1).append(": rules A=").append(rulesA.size()).append(" B=").append(rulesB.size())
                .append(" matched=").append(pageMatched.size()).append(" only-A=").append(pageOnlyA.size())
                .append(" only-B=").append(remainingB.size()).append('\n');
            for (Pair pair : pageMatched) {
                maxPos = Math.max(maxPos, pair.posDeltaMm());
                maxExt = Math.max(maxExt, Math.max(pair.fromDeltaMm(), pair.toDeltaMm()));
                maxStroke = Math.max(maxStroke, pair.strokeDeltaPt());
                rep.append(String.format(Locale.US, "  %s  pos %6.2f/%6.2fmm dP=%.2f  ext %6.1f..%6.1f / %6.1f..%6.1f dE=%.2f/%.2f  stroke %.2f/%.2fpt dS=%.2f%s%n",
                    pair.a().horizontal() ? "H" : "V", pair.a().posMm(), pair.b().posMm(), pair.posDeltaMm(),
                    pair.a().fromMm(), pair.a().toMm(), pair.b().fromMm(), pair.b().toMm(), pair.fromDeltaMm(), pair.toDeltaMm(),
                    pair.a().strokePt(), pair.b().strokePt(), pair.strokeDeltaPt(),
                    (pair.posDeltaMm() > POS_TOL_MM || pair.fromDeltaMm() > EXTENT_TOL_MM || pair.toDeltaMm() > EXTENT_TOL_MM
                        || pair.strokeDeltaPt() > STROKE_TOL_PT) ? "  <-- OUT OF TOLERANCE" : ""));
            }
            for (Rule a : pageOnlyA) rep.append("  ONLY-IN-A ").append(RuleScanner.describe(a)).append('\n');
            for (Rule b : remainingB) rep.append("  ONLY-IN-B ").append(RuleScanner.describe(b)).append('\n');
            matched.addAll(pageMatched);
            onlyA.addAll(pageOnlyA);
            onlyB.addAll(remainingB);
            writeImages(imgA, imgB, p == 0 ? name : name + "-p" + (p + 1));
        }
        rep.append(String.format(Locale.US, "matched=%d only-A=%d only-B=%d; max position delta %.2fmm, max extent delta %.2fmm, max stroke delta %.2fpt%n",
            matched.size(), onlyA.size(), onlyB.size(), maxPos, maxExt, maxStroke));
        Files.write(outDir.resolve(name + "-lo.pdf"), loPdf);
        Files.write(outDir.resolve(name + "-html.pdf"), htmlPdf);
        Files.writeString(outDir.resolve(name + "-report.txt"), rep.toString(), StandardCharsets.UTF_8);
        return new Comparison(name, pagesA, pagesB, matched, onlyA, onlyB, rep.toString());
    }

    private static void writeImages(BufferedImage a, BufferedImage b, String name) throws IOException {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        BufferedImage side = new BufferedImage(w * 2 + 20, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = side.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, side.getWidth(), side.getHeight());
        g.drawImage(a, 0, 0, null);
        g.drawImage(b, w + 20, 0, null);
        g.setColor(Color.RED);
        g.drawString("A (XLS/LibreOffice)", 10, 14);
        g.drawString("B (HTML/Chromium)", w + 30, 14);
        g.dispose();
        ImageIO.write(side, "png", outDir.resolve(name + "-side.png").toFile());

        int[][] ga = RuleScanner.gray(a);
        int[][] gb = RuleScanner.gray(b);
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int d = Math.abs(ga[y][x] - gb[y][x]);
                int base = 128 + ga[y][x] / 2; // faded A as background
                int r = Math.min(255, base + d);
                int gg = Math.max(0, base - d);
                diff.setRGB(x, y, (r << 16) | (gg << 8) | gg);
            }
        }
        ImageIO.write(diff, "png", outDir.resolve(name + "-diff.png").toFile());
    }

    // ── text (PDFBox) ────────────────────────────────────────────────────────────────────

    /** One extracted word: x extent and baseline in page pt (from the top-left), font size. */
    record Word(String text, double x0Pt, double x1Pt, double yPt, double fontPt) {
        double x0Mm() { return x0Pt * MM_PER_PT; }
        double x1Mm() { return x1Pt * MM_PER_PT; }
        double centerMm() { return (x0Pt + x1Pt) / 2 * MM_PER_PT; }
        double yMm() { return yPt * MM_PER_PT; }
    }

    static List<Word> words(byte[] pdf, int pageIndex) throws IOException {
        List<Word> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    if (positions.isEmpty()) return;
                    TextPosition a = positions.get(0);
                    TextPosition b = positions.get(positions.size() - 1);
                    out.add(new Word(text, a.getXDirAdj(), b.getXDirAdj() + b.getWidthDirAdj(), a.getYDirAdj(), a.getFontSizeInPt()));
                }
            };
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(doc);
        }
        return out;
    }

    /** The "หน้า X/Y" footer as ONE word spanning both tokens, or null when the page has none. */
    static Word footerWord(byte[] pdf, int pageIndex) throws IOException {
        List<Word> ws = words(pdf, pageIndex);
        for (int i = 0; i < ws.size(); i++) {
            Word w = ws.get(i);
            if (norm(w.text()).startsWith(norm("หน้า"))) {
                Word next = i + 1 < ws.size() ? ws.get(i + 1) : null;
                boolean number = next != null && next.text().matches("\\d+/\\d+");
                if (number) return new Word(w.text() + " " + next.text(), w.x0Pt(), next.x1Pt(), w.yPt(), w.fontPt());
                if (norm(w.text()).matches(norm("หน้า") + " ?\\d+/\\d+")) return w;
            }
        }
        return null;
    }

    /** A text difference between the two renders that is known, explained, and must be PRESENT
     * (an exception that stops occurring is stale and fails): {@code libreOffice} and
     * {@code chromium} are the whitespace-stripped forms each engine prints for the same cell. */
    record KnownTextDifference(String what, String libreOffice, String chromium) {}

    /** Every page's text, both engines, in geometric order with all whitespace removed (see the
     * class Javadoc, "Text"). Writes {@code <name>-text.txt} with each page's text and the first
     * divergence. */
    static void assertSameText(SheetPlan plan, byte[] loPdf, byte[] htmlPdf, String name, KnownTextDifference... known) throws IOException {
        int pagesA = RuleScanner.pageCount(loPdf);
        int pagesB = RuleScanner.pageCount(htmlPdf);
        StringBuilder rep = new StringBuilder("=== " + name + " text (A = LibreOffice, B = Chromium; geometric order, whitespace removed) ===\n");
        List<String> failures = new ArrayList<>();
        for (int p = 0; p < Math.min(pagesA, pagesB); p++) {
            String a = textStream(loPdf, p, plan);
            String b = textStream(htmlPdf, p, plan);
            for (KnownTextDifference k : known) {
                assertThat(a).as(name + " page " + (p + 1) + ": known difference '" + k.what() + "' — LibreOffice must still print " + k.libreOffice()).contains(k.libreOffice());
                assertThat(b).as(name + " page " + (p + 1) + ": known difference '" + k.what() + "' — Chromium must still print " + k.chromium()).contains(k.chromium());
                String marker = "\u0000" + k.what() + "\u0000";
                a = a.replace(k.libreOffice(), marker);
                b = b.replace(k.chromium(), marker);
            }
            boolean sameOrder = a.equals(b);
            boolean sameCharacters = sameOrder || sortedChars(a).equals(sortedChars(b));
            rep.append(String.format(Locale.US, "-- page %d: chars A=%d B=%d%s%n", p + 1, a.length(), b.length(),
                sameOrder ? " EQUAL" : sameCharacters ? " SAME CHARACTERS, different extraction order" : ""));
            rep.append("  A: ").append(a).append('\n');
            rep.append("  B: ").append(b).append('\n');
            if (!sameOrder) {
                int i = 0;
                while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
                String where = String.format(Locale.US, "page %d diverges at char %d: LibreOffice \"…%s…\" vs Chromium \"…%s…\"",
                    p + 1, i, a.substring(Math.max(0, i - 20), Math.min(a.length(), i + 40)),
                    b.substring(Math.max(0, i - 20), Math.min(b.length(), i + 40)));
                rep.append(sameCharacters ? "  ORDER " : "  DIFF ").append(where).append('\n');
                // Geometric order is PDFBox's line-grouping heuristic, and with a SUBSTITUTED font
                // (CI has only fonts-thai-tlwg) the two PDFs' glyph metrics differ enough that a
                // large-font title ("ใบเสนอราคา") is split across "lines" in one PDF and not the
                // other, moving "ราคา" past the address line. That is extraction, not print: the
                // same characters, each the same number of times, is what the page printed. A
                // missing, extra or altered character anywhere still fails.
                if (!sameCharacters) failures.add(where);
            }
        }
        Files.writeString(outDir.resolve(name + "-text.txt"), rep.toString(), StandardCharsets.UTF_8);
        assertThat(pagesB).as(name + ": page count").isEqualTo(pagesA);
        assertThat(failures).as(name + ": the two renders print different text\n" + rep).isEmpty();
    }

    /** The page's characters sorted — the order-insensitive form {@link #assertSameText} falls
     * back to when the two extraction orders differ (see its comment). */
    static String sortedChars(String s) {
        char[] chars = s.toCharArray();
        java.util.Arrays.sort(chars);
        return new String(chars);
    }

    /** The page's PRINTED text in PDFBox's position-sorted order, NFC-normalised, every
     * whitespace character removed. Printed: only glyphs that intersect the page's sheet box
     * horizontally (the print range, {@code SheetPlan#originXHmm}..{@code + contentWidthHmm}) —
     * both engines clip text at that edge, but LibreOffice's PDF keeps the clipped glyphs in
     * the content stream behind a clip path while Chromium's culls a glyph that lies entirely
     * outside its clip, so a run that overflows the range (the owner's workbook title in a
     * substitute font wider than Angsana New) is extracted whole from one PDF and cut from the
     * other although the two pages show the same ink. The footer band (page number) is inside
     * the box horizontally and is kept. */
    static String textStream(byte[] pdf, int pageIndex, SheetPlan plan) throws IOException {
        double left = LibreOfficeMetrics.hmmToPt(plan.originXHmm()) - 0.5;
        double right = LibreOfficeMetrics.hmmToPt(plan.originXHmm() + plan.contentWidthHmm()) + 0.5;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    double x0 = text.getXDirAdj();
                    double x1 = x0 + text.getWidthDirAdj();
                    if (x1 <= left || x0 >= right) return; // entirely outside the printed range
                    super.processTextPosition(text);
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return java.text.Normalizer.normalize(stripper.getText(doc), java.text.Normalizer.Form.NFC).replaceAll("\\s+", "");
        }
    }

    static String pageText(byte[] pdf, int pageIndex) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return norm(stripper.getText(doc));
        }
    }

    /** Bounding box of the ink in a horizontal band of a rasterised page, in page mm. */
    record InkBox(double x0Mm, double x1Mm, double y0Mm, double y1Mm) {
        double centerMm() { return (x0Mm + x1Mm) / 2; }
        double widthMm() { return x1Mm - x0Mm; }
        double heightMm() { return y1Mm - y0Mm; }
        public String toString() {
            return String.format(Locale.US, "x %.2f..%.2f y %.2f..%.2f (%.2f x %.2f mm)", x0Mm, x1Mm, y0Mm, y1Mm, widthMm(), heightMm());
        }
    }

    /** The ink below {@code fromMm} down to the page bottom — the bottom-margin footer band — or
     * null when empty. */
    static InkBox inkBelow(BufferedImage img, double fromMm) {
        int[][] g = RuleScanner.gray(img);
        int h = g.length, w = g[0].length;
        int from = Math.max(0, (int) Math.round(fromMm / RuleScanner.PX_TO_MM));
        int x0 = w, x1 = -1, y0 = h, y1 = -1;
        for (int y = from; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (g[y][x] >= TEXT_INK) continue;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return null;
        return new InkBox(x0 * RuleScanner.PX_TO_MM, (x1 + 1) * RuleScanner.PX_TO_MM, y0 * RuleScanner.PX_TO_MM, (y1 + 1) * RuleScanner.PX_TO_MM);
    }

    /** Thai combining marks (vowel signs, tone marks) can come back from a PDF in a different
     * order than the source string — drop them and collapse whitespace before matching. */
    static String norm(String s) {
        return s.replaceAll("[ัิ-ฺ็-๎]", "").replaceAll("\\s+", " ").trim();
    }

    // ── §7/§8: section closure and rule continuity, from the plan ────────────────────────

    /** Drawn edges of the plan's cells: a cell edge is drawn when either cell sharing it has the
     * facet, and never inside a merged region (the Excel/LibreOffice model SheetPlan documents on
     * {@code borders}). Vertical neighbours are the rows printed above/below on the PAGE when one
     * is given — a repeated title row on page 2+ sits on a different row than in the sheet, and
     * an edge the sheet neighbour draws is not drawn there (the ราคา hole this gate found). */
    static final class Edges {
        final SheetPlan plan;
        private final Map<Integer, Integer> above = new java.util.HashMap<>();
        private final Map<Integer, Integer> below = new java.util.HashMap<>();

        Edges(SheetPlan plan) { this(plan, null); }

        Edges(SheetPlan plan, SheetPlan.Page page) {
            this.plan = plan;
            if (page != null) {
                List<Integer> rows = page.rowIndices();
                for (int i = 0; i < rows.size(); i++) {
                    above.put(rows.get(i), i > 0 ? rows.get(i - 1) : -1);
                    below.put(rows.get(i), i + 1 < rows.size() ? rows.get(i + 1) : -1);
                }
            }
        }

        private int rowBelow(int r) { return below.isEmpty() ? r + 1 : below.getOrDefault(r, -1); }

        private int rowAbove(int r) { return above.isEmpty() ? r - 1 : above.getOrDefault(r, -1); }

        SheetPlan.Borders b(int r, int c) {
            SheetPlan.Borders x = plan.borders.get(SheetPlan.key(r, c));
            return x != null ? x : new SheetPlan.Borders(BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE, BorderStyle.NONE);
        }

        private CellRangeAddress region(int r, int c) {
            for (CellRangeAddress m : plan.mergedRegions) if (m.isInRange(r, c)) return m;
            return null;
        }

        /** True when (r,c) and (r2,c2) sit inside the same merged region — that edge is interior. */
        private boolean interior(int r, int c, int r2, int c2) {
            CellRangeAddress m = region(r, c);
            return m != null && m.isInRange(r2, c2);
        }

        boolean bottom(int r, int c) {
            int n = rowBelow(r);
            if (n >= 0 && interior(r, c, n, c)) return false;
            return b(r, c).bottom() != BorderStyle.NONE || (n >= 0 && b(n, c).top() != BorderStyle.NONE);
        }

        boolean top(int r, int c) {
            int n = rowAbove(r);
            if (n >= 0 && interior(r, c, n, c)) return false;
            return b(r, c).top() != BorderStyle.NONE || (n >= 0 && b(n, c).bottom() != BorderStyle.NONE);
        }

        boolean right(int r, int c) {
            if (interior(r, c, r, c + 1)) return false;
            return b(r, c).right() != BorderStyle.NONE || b(r, c + 1).left() != BorderStyle.NONE;
        }

        boolean left(int r, int c) {
            if (interior(r, c, r, c - 1)) return false;
            return b(r, c).left() != BorderStyle.NONE || b(r, c - 1).right() != BorderStyle.NONE;
        }
    }

    /** The quotation's sections located by their template texts, and the row that closes the
     * item+remark box (the lowest row at or below remark line 8, above รวมเป็นเงิน, whose bottom
     * edge is drawn in column A) — absent when the box is left open. */
    record Sections(SheetPlan plan, int titleRow, int remarkRow, int line8Row, int subtotalRow, int vatRow, int totalRow,
                    int boxBottomRow) {
        /** The remark box's columns (spec §8): B..H. */
        static final int BOX_FIRST_COL = 1;
        static final int BOX_LAST_COL = 7;

        static Sections of(SheetPlan plan) {
            int title = rowWithText(plan, t -> t.equals("ลำดับ"));
            int remark = rowWithText(plan, t -> t.startsWith("หมายเหตุ"));
            int line8 = rowWithText(plan, t -> t.startsWith("8."));
            // exact label texts — remark line 8 ("...ไม่รวมภาษีมูลค่าเพิ่ม") would otherwise match ภาษีมูลค่าเพิ่ม
            int subtotal = rowWithText(plan, t -> t.equals("รวมเป็นเงิน"));
            int vat = rowWithText(plan, t -> t.equals("ภาษีมูลค่าเพิ่ม"));
            int total = rowWithText(plan, t -> t.equals("รวมเป็นเงินทั้งสิ้น"));
            assertThat(title).as("column-title row").isGreaterThanOrEqualTo(0);
            assertThat(remark).as("หมายเหตุ row").isGreaterThan(title);
            assertThat(line8).as("remark line 8 row").isGreaterThan(remark);
            assertThat(subtotal).as("รวมเป็นเงิน row").isGreaterThan(line8);
            assertThat(vat).isEqualTo(subtotal + 1);
            assertThat(total).isEqualTo(subtotal + 2);
            Edges e = new Edges(plan);
            int boxBottom = -1;
            for (int r = subtotal - 1; r >= line8; r--) {
                if (e.bottom(r, plan.firstCol)) { boxBottom = r; break; }
            }
            return new Sections(plan, title, remark, line8, subtotal, vat, total, boxBottom);
        }

        static int rowWithText(SheetPlan plan, java.util.function.Predicate<String> match) {
            int found = -1;
            for (SheetPlan.PlannedCell cell : plan.cells.values()) {
                if (cell.text() != null && match.test(cell.text().strip()) && (found < 0 || cell.row() < found)) found = cell.row();
            }
            return found;
        }

        void assertClosed(String name) {
            Edges e = new Edges(plan);
            int a = plan.firstCol, i = plan.lastCol;
            for (int c = a; c <= i; c++) {
                assertThat(e.top(titleRow, c)).as(name + ": title row top edge, col " + c).isTrue();
                assertThat(e.bottom(titleRow, c)).as(name + ": title row bottom edge, col " + c).isTrue();
                if (c < i) {
                    // every column separator of the title row, except inside its own merges (ส่วนลด spans F:G)
                    boolean merged = false;
                    for (CellRangeAddress m : plan.mergedRegions) merged |= m.isInRange(titleRow, c) && m.isInRange(titleRow, c + 1);
                    if (!merged) assertThat(e.right(titleRow, c)).as(name + ": title row separator after col " + c).isTrue();
                }
            }
            assertThat(e.left(titleRow, a)).as(name + ": title row left edge").isTrue();
            assertThat(e.right(titleRow, i)).as(name + ": title row right edge").isTrue();

            assertThat(boxBottomRow).as(name + ": item+remark box has NO bottom rule between remark line 8 (row "
                + line8Row + ") and รวมเป็นเงิน (row " + subtotalRow + ")").isGreaterThanOrEqualTo(line8Row);
            for (int r = titleRow + 1; r <= boxBottomRow; r++) {
                assertThat(e.left(r, a)).as(name + ": box left rule broken at row " + r).isTrue();
                assertThat(e.right(r, i)).as(name + ": box right rule broken at row " + r).isTrue();
            }
            for (int c = a; c <= i; c++) {
                assertThat(e.bottom(boxBottomRow, c)).as(name + ": box bottom rule missing at col " + c).isTrue();
            }
            for (int r : new int[]{subtotalRow, vatRow, totalRow}) {
                assertThat(e.top(r, i)).as(name + ": totals cell top, row " + r).isTrue();
                assertThat(e.bottom(r, i)).as(name + ": totals cell bottom, row " + r).isTrue();
                assertThat(e.left(r, i)).as(name + ": totals cell left, row " + r).isTrue();
                assertThat(e.right(r, i)).as(name + ": totals cell right, row " + r).isTrue();
            }
        }

        /** Spec §8, from the plan: the remark box B..H is closed on all four edges by the cells'
         * own borders, has no interior separator, and the item zone above it keeps every column
         * separator (A|B, B|C, C|D, D|E, E|F, G|H, H|I — F|G is never drawn on an item row, the
         * template's ส่วนลด spans F:G) down to the box's top rule. */
        void assertRemarkBox(String name) {
            Edges e = new Edges(plan);
            int a = plan.firstCol, i = plan.lastCol;
            // Top rule: on the label row's own cells B..H, and on neither A nor I.
            for (int c = BOX_FIRST_COL; c <= BOX_LAST_COL; c++) {
                assertThat(e.b(remarkRow, c).top()).as(name + ": remark box top rule, col " + c).isNotEqualTo(BorderStyle.NONE);
            }
            assertThat(e.b(remarkRow, a).top()).as(name + ": remark box top rule must not cross ลำดับ").isEqualTo(BorderStyle.NONE);
            assertThat(e.b(remarkRow, i).top()).as(name + ": remark box top rule must not cross เป็นเงิน").isEqualTo(BorderStyle.NONE);
            // The last item line has no rule of its own under ลำดับ/เป็นเงิน — unless it is a page
            // bottom, which the XLS path closes across A..I on purpose (spec §7/§9).
            if (pageOf(plan, remarkRow - 1) == pageOf(plan, remarkRow)) {
                assertThat(e.b(remarkRow - 1, a).bottom()).as(name + ": the row above the box has no bottom rule in ลำดับ").isEqualTo(BorderStyle.NONE);
                assertThat(e.b(remarkRow - 1, i).bottom()).as(name + ": the row above the box has no bottom rule in เป็นเงิน").isEqualTo(BorderStyle.NONE);
            }
            for (int r = remarkRow; r <= line8Row; r++) {
                assertThat(e.left(r, BOX_FIRST_COL)).as(name + ": remark box left edge (A|B), row " + r).isTrue();
                assertThat(e.right(r, BOX_LAST_COL)).as(name + ": remark box right edge (H|I), row " + r).isTrue();
                assertThat(e.left(r, a)).as(name + ": ลำดับ outer rule, row " + r).isTrue();
                assertThat(e.right(r, i)).as(name + ": เป็นเงิน outer rule, row " + r).isTrue();
                for (int c = BOX_FIRST_COL; c < BOX_LAST_COL; c++) {
                    assertThat(e.right(r, c)).as(name + ": no interior separator after col " + c + " inside the remark box, row " + r).isFalse();
                }
                if (r > remarkRow) {
                    for (int c = a; c <= i; c++) {
                        assertThat(e.top(r, c)).as(name + ": no interior rule inside the remark box at row " + r + " col " + c).isFalse();
                    }
                }
            }
            for (int c = a; c <= i; c++) {
                assertThat(e.bottom(line8Row, c)).as(name + ": remark box bottom rule (A..I), col " + c).isTrue();
            }
            // The item zone keeps every separator down to the box's top.
            for (int r = titleRow + 1; r < remarkRow; r++) {
                for (int c : new int[]{0, 1, 2, 3, 4, 6, 7}) {
                    assertThat(e.right(r, c)).as(name + ": item zone separator after col " + c + " at row " + r).isTrue();
                }
            }
        }
    }

    /** Spec §8 as INK, in both renders: at the interior separators' x positions every detected
     * column rule ends at the box's top; at A|B, H|I and the two outer rules it runs on to the
     * bottom rule; the top rule spans exactly B..H (unless the box opens directly under the
     * repeated title row, whose own A..I rule then coincides); the bottom rule spans A..I. */
    static void assertRemarkBoxInk(SheetPlan plan, Sections s, byte[] loPdf, byte[] htmlPdf, String name) throws IOException {
        int pageIdx = pageOf(plan, s.remarkRow) - 1;
        SheetPlan.Page page = plan.pages.get(pageIdx);
        double ox = plan.originXHmm() / 100.0;
        double oy = plan.originYHmm(page) / 100.0;
        Map<Integer, Double> rowTop = new java.util.HashMap<>();
        double y = oy;
        for (int r : page.rowIndices()) { rowTop.put(r, y); y += plan.rows.get(r).scaledHmm() / 100.0; }
        double boxTop = rowTop.get(s.remarkRow);
        double boxBottom = rowTop.get(s.line8Row) + plan.rows.get(s.line8Row).scaledHmm() / 100.0;
        double left = ox + plan.columnLeftHmm(Sections.BOX_FIRST_COL) / 100.0;
        double right = ox + (plan.columnLeftHmm(Sections.BOX_LAST_COL) + plan.column(Sections.BOX_LAST_COL).scaledHmm()) / 100.0;
        double pageLeft = ox;
        double pageRight = ox + plan.contentWidthHmm() / 100.0;
        List<Integer> rows = page.rowIndices();
        boolean underTitle = rows.indexOf(s.remarkRow) > 0 && rows.get(rows.indexOf(s.remarkRow) - 1) == s.titleRow;

        String[] engine = {"LibreOffice", "Chromium"};
        byte[][] pdfs = {loPdf, htmlPdf};
        StringBuilder rep = new StringBuilder("=== " + name + " remark box (page " + (pageIdx + 1) + ") ===\n");
        rep.append(String.format(Locale.US, "box top %.2fmm bottom %.2fmm x %.2f..%.2fmm%n", boxTop, boxBottom, left, right));
        for (int e = 0; e < 2; e++) {
            List<Rule> rules = RuleScanner.scan(RuleScanner.rasterize(pdfs[e], pageIdx));
            // interior separators end at the box top
            for (int c = Sections.BOX_FIRST_COL; c < Sections.BOX_LAST_COL; c++) {
                double x = ox + (plan.columnLeftHmm(c) + plan.column(c).scaledHmm()) / 100.0;
                for (Rule r : rules) {
                    if (r.horizontal() || Math.abs(r.posMm() - x) > SEARCH_MM) continue;
                    if (r.toMm() <= boxTop - SEARCH_MM || r.fromMm() >= boxBottom + SEARCH_MM) continue;
                    rep.append(String.format(Locale.US, "  %s separator after col %d: %s%n", engine[e], c, RuleScanner.describe(r)));
                    assertThat(r.toMm()).as(name + " " + engine[e] + ": separator after col " + c + " must end at the remark box top (" + RuleScanner.describe(r) + ")")
                        .isLessThanOrEqualTo(boxTop + EXTENT_TOL_MM);
                }
            }
            // the box edges and the outer rules run through to the bottom rule
            for (double x : new double[]{pageLeft, left, right, pageRight}) {
                boolean reaches = false;
                for (Rule r : rules) {
                    if (r.horizontal() || Math.abs(r.posMm() - x) > SEARCH_MM) continue;
                    if (r.fromMm() <= boxTop + EXTENT_TOL_MM && r.toMm() >= boxBottom - EXTENT_TOL_MM) reaches = true;
                }
                assertThat(reaches).as(name + " " + engine[e] + ": vertical rule at x=" + String.format(Locale.US, "%.2f", x)
                    + "mm must run from the remark box top to its bottom").isTrue();
            }
            // top rule B..H, bottom rule A..I
            Rule top = null, bottom = null;
            for (Rule r : rules) {
                if (!r.horizontal()) continue;
                if (Math.abs(r.posMm() - boxTop) <= SEARCH_MM && (top == null || r.extentMm() > top.extentMm())) top = r;
                if (Math.abs(r.posMm() - boxBottom) <= SEARCH_MM && (bottom == null || r.extentMm() > bottom.extentMm())) bottom = r;
            }
            assertThat(top).as(name + " " + engine[e] + ": remark box top rule").isNotNull();
            assertThat(bottom).as(name + " " + engine[e] + ": remark box bottom rule").isNotNull();
            rep.append("  ").append(engine[e]).append(" top    ").append(RuleScanner.describe(top)).append(underTitle ? "  (under the repeated title row)" : "").append('\n');
            rep.append("  ").append(engine[e]).append(" bottom ").append(RuleScanner.describe(bottom)).append('\n');
            if (!underTitle) {
                assertThat(top.fromMm()).as(name + " " + engine[e] + ": top rule starts at B").isCloseTo(left, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
                assertThat(top.toMm()).as(name + " " + engine[e] + ": top rule ends at H").isCloseTo(right, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
            }
            assertThat(bottom.fromMm()).as(name + " " + engine[e] + ": bottom rule starts at A").isCloseTo(pageLeft, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
            assertThat(bottom.toMm()).as(name + " " + engine[e] + ": bottom rule ends at I").isCloseTo(pageRight, org.assertj.core.data.Offset.offset(EXTENT_TOL_MM));
        }
        Files.writeString(outDir.resolve(name + "-remarkbox.txt"), rep.toString(), StandardCharsets.UTF_8);
    }

    /** 1-based page holding sheet row {@code row} as a body row (repeated title rows excluded). */
    static int pageOf(SheetPlan plan, int row) {
        for (SheetPlan.Page page : plan.pages) {
            List<Integer> rows = page.rowIndices();
            if (rows.subList(page.repeatedRowCount(), rows.size()).contains(row)) return page.number();
        }
        throw new AssertionError("row " + row + " is on no page");
    }

    /** The item blocks of the item zone, from the plan's own cells: an underlined heading row
     * belongs to the item below it; a numbered row (ลำดับ) starts an item; every following
     * unnumbered text row is that item's continuation line. */
    static List<List<Integer>> itemBlocks(SheetPlan plan, Sections s) {
        List<List<Integer>> blocks = new ArrayList<>();
        List<Integer> pending = new ArrayList<>();
        List<Integer> current = null;
        for (int r = s.titleRow + 1; r < s.remarkRow; r++) {
            SheetPlan.PlannedCell a = plan.cells.get(SheetPlan.key(r, 0));
            SheetPlan.PlannedCell b = plan.cells.get(SheetPlan.key(r, 1));
            boolean numbered = a != null && a.numeric() && a.text() != null && !a.text().isBlank();
            boolean hasText = b != null && b.text() != null && !b.text().isBlank();
            boolean heading = hasText && b.facets().underline() && !numbered;
            if (heading) { pending.add(r); continue; }
            if (numbered) {
                current = new ArrayList<>(pending);
                pending.clear();
                current.add(r);
                blocks.add(current);
                continue;
            }
            if (current != null && hasText) current.add(r);
        }
        return blocks;
    }

    /** A planned rule on one page, in page mm. */
    record Segment(boolean horizontal, double posMm, double fromMm, double toMm) {
        double lengthMm() { return toMm - fromMm; }
        public String toString() {
            return String.format(Locale.US, "%s at %.2fmm extent %.2f..%.2fmm", horizontal ? "hline" : "vline", posMm, fromMm, toMm);
        }
    }

    /** Every drawn edge on {@code page}, coalesced into maximal collinear runs: the outer left
     * rule of the box is ONE segment from the title row to the box bottom, so a break anywhere
     * along it is a gap, not two shorter rules. */
    static List<Segment> plannedRules(SheetPlan plan, SheetPlan.Page page) {
        Edges e = new Edges(plan, page);
        double ox = plan.originXHmm() / 100.0;
        double oy = plan.originYHmm(page) / 100.0;
        Map<Integer, Double> rowTop = new java.util.HashMap<>();
        double y = oy;
        for (int r : page.rowIndices()) { rowTop.put(r, y); y += plan.rows.get(r).scaledHmm() / 100.0; }
        // key: orientation + position in 1/100 mm → runs
        Map<String, List<double[]>> raw = new TreeMap<>();
        for (int r : page.rowIndices()) {
            double top = rowTop.get(r);
            double bottom = top + plan.rows.get(r).scaledHmm() / 100.0;
            for (int c = plan.firstCol; c <= plan.lastCol; c++) {
                double left = ox + plan.columnLeftHmm(c) / 100.0;
                double right = left + plan.column(c).scaledHmm() / 100.0;
                if (e.top(r, c)) add(raw, true, top, left, right);
                if (e.bottom(r, c)) add(raw, true, bottom, left, right);
                if (e.left(r, c)) add(raw, false, left, top, bottom);
                if (e.right(r, c)) add(raw, false, right, top, bottom);
            }
        }
        List<Segment> out = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> en : raw.entrySet()) {
            boolean horizontal = en.getKey().charAt(0) == 'H';
            double pos = Integer.parseInt(en.getKey().substring(1)) / 100.0;
            List<double[]> runs = en.getValue();
            runs.sort((p, q) -> Double.compare(p[0], q[0]));
            double from = runs.get(0)[0], to = runs.get(0)[1];
            for (int k = 1; k < runs.size(); k++) {
                double[] run = runs.get(k);
                if (run[0] <= to + 0.05) { to = Math.max(to, run[1]); continue; }
                out.add(new Segment(horizontal, pos, from, to));
                from = run[0]; to = run[1];
            }
            out.add(new Segment(horizontal, pos, from, to));
        }
        return out;
    }

    private static void add(Map<String, List<double[]>> raw, boolean horizontal, double pos, double from, double to) {
        String key = (horizontal ? "H" : "V") + (int) Math.round(pos * 100);
        raw.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{from, to});
    }

    /** Longest ink-free stretch (mm) along {@code s} in the rasterised page: at every pixel step
     * along the rule's extent (ends trimmed by {@link #SEARCH_MM} so a corner's rounding does not
     * count), some pixel within ±{@link #SEARCH_MM} of the planned position must be dark. */
    static double longestGapMm(int[][] g, Segment s) {
        double pxPerMm = 1.0 / RuleScanner.PX_TO_MM;
        int win = (int) Math.ceil(SEARCH_MM * pxPerMm);
        int pos = (int) Math.round(s.posMm() * pxPerMm);
        int from = (int) Math.ceil((s.fromMm() + SEARCH_MM) * pxPerMm);
        int to = (int) Math.floor((s.toMm() - SEARCH_MM) * pxPerMm);
        if (to <= from) return 0;
        int h = g.length, w = g[0].length;
        int gap = 0, longest = 0;
        for (int i = from; i <= to; i++) {
            boolean ink = false;
            for (int d = -win; d <= win && !ink; d++) {
                int x = s.horizontal() ? i : pos + d;
                int yy = s.horizontal() ? pos + d : i;
                if (x < 0 || x >= w || yy < 0 || yy >= h) continue;
                ink = g[yy][x] < RuleScanner.DARK;
            }
            gap = ink ? 0 : gap + 1;
            longest = Math.max(longest, gap);
        }
        return longest * RuleScanner.PX_TO_MM;
    }

    /** §7 continuity on EVERY page of both renders, against the plan they were drawn from. Also
     * appends the per-page segment count and the worst gap to {@code <name>-continuity.txt}. */
    static void assertPlannedRulesContinuous(SheetPlan plan, byte[] loPdf, byte[] htmlPdf, String name) throws IOException {
        StringBuilder rep = new StringBuilder("=== " + name + " continuity (gap tolerance " + GAP_TOL_MM + " mm) ===\n");
        List<String> failures = new ArrayList<>();
        for (SheetPlan.Page page : plan.pages) {
            int idx = page.number() - 1;
            int[][] ga = RuleScanner.gray(RuleScanner.rasterize(loPdf, idx));
            int[][] gb = RuleScanner.gray(RuleScanner.rasterize(htmlPdf, idx));
            List<Segment> segments = plannedRules(plan, page);
            double worstA = 0, worstB = 0;
            for (Segment s : segments) {
                double a = longestGapMm(ga, s);
                double b = longestGapMm(gb, s);
                worstA = Math.max(worstA, a);
                worstB = Math.max(worstB, b);
                if (a > GAP_TOL_MM) failures.add("page " + page.number() + " LibreOffice: " + s + " gap " + String.format(Locale.US, "%.2f", a) + "mm");
                if (b > GAP_TOL_MM) failures.add("page " + page.number() + " Chromium: " + s + " gap " + String.format(Locale.US, "%.2f", b) + "mm");
            }
            rep.append(String.format(Locale.US, "page %d: %d planned rules, worst gap A=%.2fmm B=%.2fmm%n",
                page.number(), segments.size(), worstA, worstB));
            assertThat(segments).as(name + " page " + page.number() + ": no planned rules").isNotEmpty();
        }
        for (String f : failures) rep.append("  BROKEN ").append(f).append('\n');
        Files.writeString(outDir.resolve(name + "-continuity.txt"), rep.toString(), StandardCharsets.UTF_8);
        assertThat(failures).as(name + ": planned rules with a gap\n" + rep).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────

    /** The 5-item single-page reference: two location headings, three description lines per item
     * (the calculation line wraps inside column B), 10%/Net discounts, all 8 remark lines, real
     * signatory names and an approver signature picture. */
    static QuotationRenderModel fiveItemReference() {
        return reference(5, "QT-2026-0005");
    }

    /** {@link #fiveItemReference()} with {@code n} items — 12 and 30 paginate. The number is
     * product-shaped ({@code DealQuotationRepository#nextQuotationCode}: {@code QT-<year>-<seq>}):
     * a longer, fixture-only number spilt past column I's right edge, where the two engines clip
     * a run of glyphs at different points — a difference the text comparison must not be asked
     * to explain away. */
    static QuotationRenderModel reference(int n, String number) {
        List<RenderItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            boolean net = i == 3;
            BigDecimal qty = BigDecimal.valueOf(32L * i);
            BigDecimal price = new BigDecimal("300.00");
            BigDecimal netPrice = net ? price : new BigDecimal("270.00");
            items.add(new RenderItem(i <= 2 ? "โซนที่ 1" : "โซนที่ 2",
                List.of("กระเบื้อง รุ่น Model-" + i + " สี Color-" + i + " ผิว Matt No.SKU-" + i,
                    "ขนาด 60x60 cm. (ขนาดโดยประมาณ)",
                    "(พื้นที่ " + (10 * i) + " ตร.ม.ๆละ 2.78 แผ่น รวม " + (28 * i) + " แผ่น + เผื่อ 10% และปัดลงกล่อง = "
                        + (32 * i) + " แผ่น) (บรรจุ 4 แผ่น/กล่อง)"),
                qty, "แผ่น", price, net ? "Net" : "10%", netPrice, netPrice.multiply(qty)));
        }
        List<String> remarks = List.of(
            "1.จำนวนที่เสนอข้างต้นเป็นจำนวนที่ได้รับมาเมื่อวันที่  16/07/2569",
            "2.บริษัทฯ ขอรับมัดจำ 30% เมื่อสั่งซื้อสินค้า ส่วนที่เหลือขอรับก่อนส่งมอบสินค้า",
            "3.กำหนดส่งมอบสินค้า : รายการที่ 1-" + n + " ระยะเวลานำเข้า 75-90 วัน",
            "4.ราคาที่เสนอยังไม่รวมค่าขนส่ง",
            "5.สินค้าตัวอย่างอาจมีความแตกต่างจากสินค้าจริงเล็กน้อย",
            "6.บริษัทขอสงวนสิทธิ์ในการเปลี่ยนแปลงราคาโดยไม่ต้องแจ้งให้ทราบล่วงหน้า",
            "7.กำหนดยืนยันราคา 30 วัน นับจากวันที่ในใบเสนอราคา",
            "8.ราคานี้ยังไม่รวมภาษีมูลค่าเพิ่ม");
        return new QuotationRenderModel(LocalDate.of(2026, 7, 16), number, "P003", "D002",
            "Sales/สมชาย ใจดี T.081-234-5678",
            "คุณลูกค้า   /   Test Customer Co., Ltd.   เลขที่ผู้เสียภาษี : 0105542000000",
            "โทร. 02-000-0000", "Showroom V2 Project", items, remarks,
            // Owner feedback pass 1: slot 4 carries the ผู้สั่งซื้อ name (F2) and the dates row is
            // filled for the three staff slots (F4) — both engines must print them identically.
            new Signatories("จินตนา", "จุฑาทิพ", "ผึ้ง", "สมหญิง ใจดี", signaturePng(), "image/png",
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16)), true);
    }

    /** A hand-drawn squiggle, 300x120 px — no straight run long enough to read as a rule. */
    static byte[] signaturePng() {
        BufferedImage img = new BufferedImage(300, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 30, 120));
        g.setStroke(new BasicStroke(5f));
        int prevX = 20, prevY = 80;
        for (int x = 30; x <= 280; x += 10) {
            int y = 60 + (int) (35 * Math.sin(x / 18.0)) - (x / 12);
            g.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The owner's workbook's B4 ({@code =TODAY()}, cached on the day it was saved), as the serial
     * {@link #fitToOnePageCopy} pins it to. */
    static final int WORKBOOK_DATE_SERIAL = 46178; // 2026-06-05

    /**
     * The single known text difference, on the owner's real workbook only: B4 carries Excel's
     * THAI-LOCALE date format {@code d ดดดด bbbb} (ดดดด = Thai month name, bbbb = Buddhist-era
     * year), which NEITHER engine understands. LibreOffice prints the unknown codes literally
     * ("5 ดดดด bbbb"); POI's DataFormatter does not recognise the format as a date at all and
     * prints the raw serial ("46178.0"). Excel would print "5 มิถุนายน 2569". The product's own
     * XLS path never writes such a cell (it writes the date as text), so this is a fixture
     * property, not a renderer defect to paper over — it is declared here, asserted present in
     * both renders, and everything else on the page must still agree. Pinned to a fixed serial
     * so the gate does not move with the calendar.
     */
    static final KnownTextDifference WORKBOOK_DATE_CELL = new KnownTextDifference(
        "B4 d ดดดด bbbb (Thai-locale date format)",
        LocalDate.of(2026, 6, 5).getDayOfMonth() + "ดดดดbbbb",
        WORKBOOK_DATE_SERIAL + ".0");

    /** Spec §6: the owner's workbook is not set to fit-to-page (LibreOffice spilt its signature
     * rows to page 2); Excel prints it on one page. Fit 1×1 on a copy, and pin B4's
     * {@code =TODAY()} to the value cached in the file ({@link #WORKBOOK_DATE_SERIAL}) so both
     * engines print the same day — nothing else touched.
     * {@code setAutobreaks(true)} is what LibreOffice actually reads as "fit to pages" (see
     * SheetPlan#computeZoom) — without it LibreOffice keeps the workbook's 86% scale and two
     * pages, whatever the fit counts say. */
    static byte[] fitToOnePageCopy(ClassPathResource xls) throws IOException {
        try (InputStream in = xls.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheet("Update");
            if (sh == null) sh = wb.getSheetAt(0);
            sh.setFitToPage(true);
            sh.setAutobreaks(true);
            PrintSetup ps = sh.getPrintSetup();
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 1);
            org.apache.poi.ss.usermodel.Cell dateCell = sh.getRow(3).getCell(1);
            assertThat(dateCell.getCellFormula()).as("B4 is the workbook's =TODAY() cell").isEqualTo("TODAY()");
            assertThat((int) dateCell.getNumericCellValue()).as("B4's cached serial").isEqualTo(WORKBOOK_DATE_SERIAL);
            // HSSF's setCellValue on a FORMULA cell only updates the cached result and keeps the
            // formula (and so does removeFormula() once the sheet is written back) — and TODAY()
            // is volatile, so BOTH engines would recompute it on load. Blank the cell first: that
            // replaces the formula record outright; then restore the style (its date format).
            org.apache.poi.ss.usermodel.CellStyle dateStyle = dateCell.getCellStyle();
            dateCell.setBlank();
            dateCell.setCellValue((double) WORKBOOK_DATE_SERIAL);
            dateCell.setCellStyle(dateStyle);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

}
