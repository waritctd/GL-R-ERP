package th.co.glr.hr.catalog.importer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

/** C4: verifies ImportEngine handles every special case from the 9 factory profiles. All tests are pure-unit
 * (no Spring context, no DB). */
class ImportEngineTest {

    private ImportEngine engine;

    @BeforeEach
    void setUp() { engine = new ImportEngine(); }

    // ── parseSize ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("parseSize")
    class ParseSizeTests {

        // ── the defect this whole change exists to remove ───────────────────────

        @Test @DisplayName("no magnitude guess: declared unit alone decides the conversion")
        void declaredUnitAloneDecidesConversion() {
            // Same string, two declared units, two DIFFERENT (both correct) results — the magnitude-based "< 300 => cm"
            // guess this replaces would have picked ONE answer for both and been wrong for whichever profile disagreed
            // with it.
            BigDecimal[] asCm = ImportEngine.parseSize("150x600", null, "cm", "mm");
            assertThat(asCm[0]).isEqualByComparingTo("1500.00");
            assertThat(asCm[1]).isEqualByComparingTo("6000.00");

            BigDecimal[] asMm = ImportEngine.parseSize("150x600", null, "mm", "mm");
            assertThat(asMm[0]).isEqualByComparingTo("150.00");
            assertThat(asMm[1]).isEqualByComparingTo("600.00");
        }

        @Test @DisplayName("normalize-on-write: same physical tile from two declared units stores "
            + "byte-identical mm dimensions")
        void sameTileFromTwoDeclaredUnitsConverges() {
            // A 60x120 cm tile (Padana-style, declared cm) and a 600x1200 mm tile (LEA-style, declared mm) are the SAME
            // physical tile and must store identical width_mm/height_mm.
            BigDecimal[] fromCmProfile = ImportEngine.parseSize("60x120", null, "cm", "mm");
            BigDecimal[] fromMmProfile = ImportEngine.parseSize("600x1200", null, "mm", "mm");

            assertThat(fromCmProfile[0]).isEqualByComparingTo(fromMmProfile[0]);
            assertThat(fromCmProfile[1]).isEqualByComparingTo(fromMmProfile[1]);
            // "byte-identical", not just numerically equal: same scale, same text.
            assertThat(fromCmProfile[0].toPlainString()).isEqualTo(fromMmProfile[0].toPlainString())
                .isEqualTo("600.00");
            assertThat(fromCmProfile[1].toPlainString()).isEqualTo(fromMmProfile[1].toPlainString())
                .isEqualTo("1200.00");
        }

        @Test @DisplayName("missing/invalid declared unit is refused, not defaulted")
        void missingUnitIsRefused() {
            assertThatThrownBy(() -> ImportEngine.parseSize("60x120", null, null, "mm"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ImportEngine.parseSize("60x120", null, "inches", "mm"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        // ── width x height only, default format, mm-declared thickness ───────────
        // Each row: display name, size string, declared size unit, expected width mm, expected height mm.

        static Stream<Arguments> widthHeightCases() {
            return Stream.of(
                // CDE's real defect: the old x<300 guess would read this as centimetres (10x wrong). Reinstating that
                // guess in ImportEngine#toMm must turn this row red — verified by hand during implementation (see PR
                // body), then reverted.
                argumentSet("REGRESSION (mutation-checked): CDE '150x600' under an mm-declared profile "
                    + "must be 150x600 mm, never guessed as 1500x6000 mm", "150x600", "mm", "150.00", "600.00"),
                // basic mechanics, now with a declared unit instead of a guess
                argumentSet("uppercase X separator", "120X120", "mm", "120.00", "120.00"),
                argumentSet("leading space is ignored", " 150x600", "mm", "150.00", "600.00"),
                // European decimal commas
                argumentSet("European decimal comma: 36,1x57,6", "36,1x57,6", "cm",
                    "361.00", "576.00"), // 36.1 cm -> 361 mm, 57.6 cm -> 576 mm
                argumentSet("European decimal comma with spaces around separator: 2,5 x 10", "2,5 x 10", "cm",
                    "25.00", "100.00"), // 2.5 cm -> 25 mm, 10 cm -> 100 mm
                // trailing free-text junk
                argumentSet("trailing junk token 'MOD' is stripped", "60X120 MOD", "cm", "600.00", "1200.00"),
                argumentSet("trailing junk tokens 'CORBEL NAVAL' (two words) are stripped",
                    "20X20 CORBEL NAVAL", "cm", "200.00", "200.00"),
                argumentSet("trailing junk token 'S/AD' is stripped", "30X60 S/AD", "cm", "300.00", "600.00"),
                // zero-padded (CDE)
                argumentSet("zero-padded CDE-style size: '080x600'", "080x600", "mm", "80.00", "600.00"));
        }

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("widthHeightCases")
        void parsesWidthAndHeight(String size, String sizeUnit, String expectedWidth, String expectedHeight) {
            BigDecimal[] r = ImportEngine.parseSize(size, null, sizeUnit, "mm");
            assertThat(r[0]).isEqualByComparingTo(expectedWidth);
            assertThat(r[1]).isEqualByComparingTo(expectedHeight);
        }

        // ── width x height x thickness, default format, mm-declared thickness ────
        // Each row: display name, size string, declared size unit, expected width/height/thickness mm.

        static Stream<Arguments> widthHeightThicknessCases() {
            return Stream.of(
                argumentSet("3-number bare form WxHxT (Bode's shape) — 3rd value is thickness, "
                    + "an mm-declared thicknessUnit leaves it unconverted", "598X598X18", "mm",
                    "598.00", "598.00", "18.00"),
                // 20 cm -> 200 mm; mm-declared: bare thickness unconverted
                argumentSet("3-number bare form with spaces: '20 x 20 x 9', mm-declared thickness", "20 x 20 x 9", "cm",
                    "200.00", "200.00", "9.00"),
                // thickness glued onto the size string (real production shapes)
                argumentSet("thickness embedded with explicit MM suffix: '60x120 9MM' (874 real rows)", "60x120 9MM", "cm",
                    "600.00", "1200.00", "9.00"), // thickness never converted, already mm
                argumentSet("thickness embedded with explicit MM suffix: '60x60 9MM' (867 real rows)", "60x60 9MM", "cm",
                    "600.00", "600.00", "9.00"),
                argumentSet("thickness embedded with explicit MM suffix: '20x20 12MM'", "20x20 12MM", "cm",
                    "200.00", "200.00", "12.00"),
                argumentSet("TRUNCATED thickness token '9M' is a clipped '9MM', never metres "
                    + "(245 real rows: '120x120 9M')", "120x120 9M", "cm",
                    "1200.00", "1200.00", "9.00"), // 9 mm, NOT 9 metres
                argumentSet("TRUNCATED decimal thickness token: '60x60 9,4M' is a clipped '9,4MM'", "60x60 9,4M", "cm",
                    "600.00", "600.00", "9.40"),
                // messy fallback (embedded free text mid-string)
                argumentSet("embedded free text mid-string ('120X50  h.15') falls back to numeric "
                    + "scanning rather than throwing or dropping the row", "120X50  h.15", "cm",
                    "1200.00", "500.00", "15.00"));
        }

        @ParameterizedTest(name = "{argumentSetName}")
        @MethodSource("widthHeightThicknessCases")
        void parsesWidthHeightAndThickness(String size, String sizeUnit, String expectedWidth, String expectedHeight,
                                           String expectedThickness) {
            BigDecimal[] r = ImportEngine.parseSize(size, null, sizeUnit, "mm");
            assertThat(r[0]).isEqualByComparingTo(expectedWidth);
            assertThat(r[1]).isEqualByComparingTo(expectedHeight);
            assertThat(r[2]).isEqualByComparingTo(expectedThickness);
        }

        // (European decimal commas, thickness glued onto the size string, trailing free-text junk, the messy
        // embedded-free-text fallback and the zero-padded CDE shape are rows of parsesWidthAndHeight /
        // parsesWidthHeightAndThickness above.)
    }

    // ── Full parse with in-memory workbooks ───────────────────────────────────

    @Nested @DisplayName("Full parse — factory special cases")
    class FullParseTests {

        // ── helpers ───────────────────────────────────────────────────────────

        private ByteArrayInputStream makeWorkbook(String sheetName, Object[][] headerAndRows)
            throws Exception {
            // NOT try-with-resources: close() must happen after write() to avoid try-with-resources suppressing the return
            // value if close() throws
            XSSFWorkbook wb = new XSSFWorkbook();
            Sheet s = wb.createSheet(sheetName);
            for (int r = 0; r < headerAndRows.length; r++) {
                Row row = s.createRow(r);
                Object[] cols = headerAndRows[r];
                for (int c = 0; c < cols.length; c++) {
                    if (cols[c] == null) continue;
                    Cell cell = row.createCell(c);
                    if (cols[c] instanceof Number n) cell.setCellValue(n.doubleValue());
                    else if (cols[c] instanceof String str) cell.setCellValue(str);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            wb.close();
            return new ByteArrayInputStream(out.toByteArray());
        }

        private ImportProfile profileWith(Map<String, String> columns, Map<String, String> defaults,
                                          String sheet, int headerRow) {
            ImportProfile p = new ImportProfile();
            p.columns = columns;
            p.defaults = defaults != null ? defaults : Map.of();
            p.sheets = List.of(sheetConf(sheet, headerRow));
            // sizeUnit/thicknessUnit are REQUIRED (ImportEngine#parse fails the whole import otherwise) — these fixtures
            // don't exercise real-factory unit facts, so "mm" is an arbitrary but valid default; tests that care about the
            // actual conversion override it explicitly.
            p.sizeUnit = "mm";
            p.thicknessUnit = "mm";
            return p;
        }

        private ImportProfile.SheetConfig sheetConf(String name, int headerRow) {
            ImportProfile.SheetConfig s = new ImportProfile.SheetConfig();
            s.name = name; s.headerRow = headerRow;
            return s;
        }

        // ── surrogate code: the REFIN row-loss regression ─────────────────────

        @Test @DisplayName("Surrogate — same size, different product ⇒ different codes (Trim-Tiles loss)")
        void surrogate_sameSizeDifferentProductGetsDistinctCodes() throws Exception {
            // The exact shape that collapsed 74 Trim-Tiles rows to 17: no Code column, several distinct products sharing
            // one size at different prices.
            Object[][] data = {
                {"Thickness (mm)", "Item",               "Size (cm)", "PRICE 2026", "UM"},
                {9.0,              "GRADINO",            "33X150",    244.0,        "pcs"},
                {9.0,              "GRADINO ANG DX/SX",  "33X150",    318.5,        "pcs"},
                {9.0,              "SCALINO ELEMENTO L", "33X150",    177.5,        "pcs"},
            };
            ImportProfile prof = profileWith(Map.of(
                "thickness_mm", "Thickness (mm)",
                "product_name", "Item",
                "size_raw",     "Size (cm)",
                "price",        "PRICE 2026",
                "unit",         "UM"
            ), Map.of("currency", "EUR"), "Trim-Tiles", 1);
            prof.allowMissingCode = true;

            ImportResult r = engine.parse(makeWorkbook("Trim-Tiles", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(3);

            // Distinct codes are what stops uq_price collapsing these onto one row.
            assertThat(r.rows().stream().map(PriceRow::productCode).distinct())
                .as("three products sharing size 33X150 must not share a code")
                .hasSize(3);
            assertThat(r.rows().stream().map(PriceRow::price).distinct()).hasSize(3);
        }

        // ── missing price skipped with error ─────────────────────────────────

        @Test @DisplayName("row with no price is logged as error and skipped")
        void missingPriceIsError() throws Exception {
            Object[][] data = {
                {"Code", "Price", "Um"},
                {"A-01", null,    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "price",        "Price",
                "unit",         "Um"
            ), Map.of("currency", "EUR"), "Sheet", 1);

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.rows()).isEmpty();
            assertThat(r.errors()).hasSize(1);
            assertThat(r.errors().get(0)).contains("ไม่มีราคา");
        }

        // ── declared size_unit is required (no magnitude guess, ever) ───────────

        @Test @DisplayName("a profile with no declared size_unit fails the WHOLE import loudly, "
            + "naming the profile — no rows, no guessing")
        void missingSizeUnitFailsTheWholeImportLoudly() throws Exception {
            Object[][] data = {
                {"Code", "Size",  "Price", "Um"},
                {"A-01", "60x120", 20.0,    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um"
            ), Map.of("currency", "EUR"), "Sheet", 1);
            prof.sizeUnit = null; // the defect under test

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 42L);
            assertThat(r.rows()).isEmpty();
            assertThat(r.quarantined()).isEmpty();
            assertThat(r.errors()).hasSize(1);
            assertThat(r.errors().get(0))
                .as("must name the profile (by factory id) and must not guess a unit")
                .contains("size_unit")
                .contains("42");
        }

        // ── reconciliation: agree / disagree / unavailable ───────────────────────

        @Test @DisplayName("reconciliation DISAGREE quarantines the row (staged, excluded from "
            + "commit) rather than importing a wrong figure silently — CDE-shaped defect")
        void reconciliationDisagreementQuarantinesTheRow() throws Exception {
            Object[][] data = {
                // Declared mm. Box figures say 0.36 m^2/piece (60cm x 60cm); the size string says "60x60" which under mm is
                // 60mm x 60mm = 0.0036 m^2/piece -- ~100x apart.
                {"Code", "Size",  "Price", "Um", "m²/Box", "Pcs/Box"},
                {"CDE-1", "60x60", 18.0,   "MQ", 1.44,     4.0},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um",
                "sqm_per_box",  "m²/Box",
                "pcs_per_box",  "Pcs/Box"
            ), Map.of("currency", "EUR"), "Sheet", 1);
            prof.sizeUnit = "mm";

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 7L);

            // Quarantined, not dropped: it IS staged (so the operator can see and fix it), just excluded from commit and
            // reported.
            assertThat(r.rows()).hasSize(1);
            assertThat(r.errors()).isEmpty();
            PriceRow row = r.rows().get(0);
            assertThat(row.sqmProvenance()).isEqualTo("mismatch_quarantined");
            assertThat(row.sqmPerPiece()).isNull();
            assertThat(row.quarantineReason()).isNotNull()
                .as("reason must carry BOTH figures, not just say 'mismatch'")
                .contains("0.0036").contains("0.36");

            assertThat(r.quarantined()).hasSize(1);
            ImportResult.QuarantinedRow q = r.quarantined().get(0);
            assertThat(q.sourceSheet()).isEqualTo("Sheet");
            assertThat(q.productCode()).isEqualTo("CDE-1");
            assertThat(q.reason()).isEqualTo(row.quarantineReason());
        }

        // ── per_linear_m is classified, never quarantined as an area mismatch ────

        @Test @DisplayName("per_linear_m rows are NEVER quarantined by the area check, even when "
            + "the box 'sqm' figure (really linear metres) wildly disagrees with width x height")
        void perLinearMIsNeverQuarantinedAsAnAreaMismatch() throws Exception {
            Object[][] data = {
                // A 7x60 cm trim: box column holds LINEAR METRES per box (per V153's own finding), not m^2 -- comparing 0.750
                // "linear metres/piece" against width*height=0.042 m^2 would be a ~18x "mismatch" if it were ever compared as
                // area. It must not be.
                {"Code", "Size", "Price", "Um", "m²/Box", "Pcs/Box"},
                {"TRIM-1", "7x60", 15.0,  "ML", 15.0,      20.0},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um",
                "sqm_per_box",  "m²/Box",
                "pcs_per_box",  "Pcs/Box"
            ), Map.of("currency", "EUR"), "Sheet", 1);
            prof.sizeUnit = "cm";

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.quarantined()).as("per_linear_m must never be quarantined as an area mismatch").isEmpty();
            assertThat(r.rows()).hasSize(1);
            PriceRow row = r.rows().get(0);
            assertThat(row.priceUnit()).isEqualTo("per_linear_m");
            assertThat(row.sqmProvenance()).isEqualTo("linear_metre_not_area");
            assertThat(row.quarantineReason()).isNull();
            // sqmPerPiece must stay null -- 15.0/20.0 = 0.750 linear metres/piece is NOT an area, and nothing may write it
            // where downstream code reads an area.
            assertThat(row.sqmPerPiece()).isNull();
            // The profile height (shorter side, 70mm) IS captured, correctly labelled.
            assertThat(row.sqmPerLinearM()).isEqualByComparingTo(new BigDecimal("0.070000"));
        }

        // ── declared thickness_unit is required (no magnitude guess, ever) ───────

        @Test @DisplayName("a profile with no declared thickness_unit fails the WHOLE import "
            + "loudly, naming the profile — no rows, no guessing (thickness twin of the "
            + "size_unit test above)")
        void missingThicknessUnitFailsTheWholeImportLoudly() throws Exception {
            Object[][] data = {
                {"Code", "Size",  "Price", "Um"},
                {"A-01", "60x120", 20.0,    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um"
            ), Map.of("currency", "EUR"), "Sheet", 1);
            prof.thicknessUnit = null; // the defect under test

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 42L);
            assertThat(r.rows()).isEmpty();
            assertThat(r.quarantined()).isEmpty();
            assertThat(r.errors()).hasSize(1);
            assertThat(r.errors().get(0))
                .as("must name the profile (by factory id) and must not guess a unit")
                .contains("thickness_unit")
                .contains("42");
        }

        // ── thickness_unit="none": a genuinely thickness-less source imports fine ────

        @Test @DisplayName("thickness_unit=\"none\" (Bode/Vives/Equipe's real shape) imports "
            + "normally with thickness_mm NULL -- a legitimate recorded absence, NOT a "
            + "quarantine and NOT a reason to block the row")
        void noneThicknessUnitImportsWithNullThickness_notBlocked() throws Exception {
            Object[][] data = {
                {"Code", "Size",  "Price", "Um"},
                {"BD-1", "600x600", 23.5,  "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um"
            ), Map.of("currency", "USD"), "Sheet", 1);
            prof.sizeUnit = "mm";
            prof.thicknessUnit = "none";

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.quarantined()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            PriceRow row = r.rows().get(0);
            assertThat(row.thicknessMm()).isNull();
            assertThat(row.thicknessUnitDeclared()).isEqualTo("none");
            // Row still imports and prices normally -- "none" is about interpretation, not a precondition that a thickness
            // must exist.
            assertThat(row.sqmProvenance()).isEqualTo("computed_from_dimensions");
        }

    }
}
