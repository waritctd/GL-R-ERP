package th.co.glr.hr.catalog.importer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * C4: verifies ImportEngine handles every special case from the 9 factory profiles.
 * All tests are pure-unit (no Spring context, no DB).
 */
class ImportEngineTest {

    private ImportEngine engine;

    @BeforeEach
    void setUp() { engine = new ImportEngine(); }

    // ── normHeader ────────────────────────────────────────────────────────────

    @Nested @DisplayName("normHeader")
    class NormHeaderTests {

        @Test @DisplayName("trims whitespace and lowercases")
        void trimsAndLowercases() {
            assertThat(ImportEngine.normHeader("  ITEM ")).isEqualTo("item");
        }

        @Test @DisplayName("collapses internal whitespace (CDE trailing-space headers)")
        void collapsesCde() {
            // CDE has "ITEM ", "PRICE ", "FINISH " — after trim they match "item"/"price"/"finish"
            assertThat(ImportEngine.normHeader("ITEM ")).isEqualTo("item");
            assertThat(ImportEngine.normHeader("PRICE ")).isEqualTo("price");
        }

        @Test @DisplayName("handles null")
        void handlesNull() {
            assertThat(ImportEngine.normHeader(null)).isEqualTo("");
        }
    }

    // ── toDecimal ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("toDecimal")
    class ToDecimalTests {

        @Test @DisplayName("EU format: comma=decimal dot=thousands")
        void euCommaSeparator() {
            // " 14,240" in EU: comma is decimal → 14.240 = 14.24 (Vives per_sqm price)
            assertThat(ImportEngine.toDecimal(" 14,240", "eu")).isEqualByComparingTo("14.2400");
            // "1.120,00" in EU: dot=thousands, comma=decimal → 1120.00
            assertThat(ImportEngine.toDecimal("1.120,00", "eu")).isEqualByComparingTo("1120.0000");
        }

        @Test @DisplayName("US format: dot=decimal comma=thousands")
        void usFormat() {
            // Bode uses "USD" numbers like "23.50"
            assertThat(ImportEngine.toDecimal("1,234.56", "us")).isEqualByComparingTo("1234.5600");
        }

        @Test @DisplayName("float rounding error is truncated (55.9000000000005)")
        void floatError() {
            assertThat(ImportEngine.toDecimal(55.90000000000005, "eu"))
                .isEqualByComparingTo("55.9000");
        }

        @Test @DisplayName("numeric cell value (double) is handled")
        void numericDouble() {
            assertThat(ImportEngine.toDecimal(43.0, "eu")).isEqualByComparingTo("43.0000");
        }

        @Test @DisplayName("null/empty returns null")
        void nullEmpty() {
            assertThat(ImportEngine.toDecimal(null, "eu")).isNull();
            assertThat(ImportEngine.toDecimal("", "eu")).isNull();
        }

        @Test @DisplayName("non-numeric text returns null")
        void nonNumeric() {
            assertThat(ImportEngine.toDecimal("N/A", "eu")).isNull();
        }
    }

    // ── parseSize ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("parseSize")
    class ParseSizeTests {

        // ── the defect this whole change exists to remove ───────────────────────

        @Test @DisplayName("no magnitude guess: declared unit alone decides the conversion")
        void declaredUnitAloneDecidesConversion() {
            // Same string, two declared units, two DIFFERENT (both correct) results — the
            // magnitude-based "< 300 => cm" guess this replaces would have picked ONE answer for
            // both and been wrong for whichever profile disagreed with it.
            BigDecimal[] asCm = ImportEngine.parseSize("150x600", null, "cm");
            assertThat(asCm[0]).isEqualByComparingTo("1500.00");
            assertThat(asCm[1]).isEqualByComparingTo("6000.00");

            BigDecimal[] asMm = ImportEngine.parseSize("150x600", null, "mm");
            assertThat(asMm[0]).isEqualByComparingTo("150.00");
            assertThat(asMm[1]).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("REGRESSION (mutation-checked): CDE '150x600' under an mm-declared profile "
            + "must be 150x600 mm, never guessed as 1500x6000 mm")
        void mmDeclaredIsNeverGuessedAsCentimetres() {
            // CDE's real defect: the old x<300 guess would read this as centimetres (10x wrong).
            // Reinstating that guess in ImportEngine#toMm must turn this test red — verified by
            // hand during implementation (see PR body), then reverted.
            BigDecimal[] r = ImportEngine.parseSize("150x600", null, "mm");
            assertThat(r[0]).isEqualByComparingTo("150.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
        }

        @Test @DisplayName("normalize-on-write: same physical tile from two declared units stores "
            + "byte-identical mm dimensions")
        void sameTileFromTwoDeclaredUnitsConverges() {
            // A 60x120 cm tile (Padana-style, declared cm) and a 600x1200 mm tile (LEA-style,
            // declared mm) are the SAME physical tile and must store identical width_mm/height_mm.
            BigDecimal[] fromCmProfile = ImportEngine.parseSize("60x120", null, "cm");
            BigDecimal[] fromMmProfile = ImportEngine.parseSize("600x1200", null, "mm");

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
            assertThatThrownBy(() -> ImportEngine.parseSize("60x120", null, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ImportEngine.parseSize("60x120", null, "inches"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        // ── basic mechanics, now with a declared unit instead of a guess ─────────

        @Test @DisplayName("uppercase X separator")
        void upperX() {
            BigDecimal[] r = ImportEngine.parseSize("120X120", null, "mm");
            assertThat(r[0]).isEqualByComparingTo("120.00");
            assertThat(r[1]).isEqualByComparingTo("120.00");
        }

        @Test @DisplayName("leading space is ignored")
        void leadingSpace() {
            BigDecimal[] r = ImportEngine.parseSize(" 150x600", null, "mm");
            assertThat(r[0]).isEqualByComparingTo("150.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
        }

        @Test @DisplayName("3-number bare form WxHxT — 3rd value is thickness, already mm, "
            + "never converted")
        void threeDimensionsBareForm() {
            BigDecimal[] r = ImportEngine.parseSize("598X598X18", null, "mm");
            assertThat(r[0]).isEqualByComparingTo("598.00");
            assertThat(r[1]).isEqualByComparingTo("598.00");
            assertThat(r[2]).isEqualByComparingTo("18.00");
        }

        @Test @DisplayName("3-number bare form with spaces: '20 x 20 x 9'")
        void threeDimensionsWithSpaces() {
            BigDecimal[] r = ImportEngine.parseSize("20 x 20 x 9", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("200.00"); // 20 cm -> 200 mm
            assertThat(r[1]).isEqualByComparingTo("200.00");
            assertThat(r[2]).isEqualByComparingTo("9.00");   // bare thickness, never converted
        }

        @Test @DisplayName("apostrophe decimal (Vives, declared cm per the profile's own notes): "
            + "15'8X31'6 -> 158x316 mm")
        void apostropheDecimalVives() {
            BigDecimal[] r = ImportEngine.parseSize("15'8X31'6", "apostrophe_decimal", "cm");
            assertThat(r[0]).isEqualByComparingTo("158.00");
            assertThat(r[1]).isEqualByComparingTo("316.00");
        }

        @Test @DisplayName("apostrophe decimal, second form: 9'2X59'3")
        void apostropheDecimalSecondForm() {
            BigDecimal[] r = ImportEngine.parseSize("9'2X59'3", "apostrophe_decimal", "cm");
            assertThat(r[0]).isEqualByComparingTo("92.00");  // 9.2 cm -> 92 mm
            assertThat(r[1]).isEqualByComparingTo("593.00"); // 59.3 cm -> 593 mm
        }

        @Test @DisplayName("null/blank returns all nulls")
        void blank() {
            BigDecimal[] r = ImportEngine.parseSize(null, null, "mm");
            assertThat(r).containsExactly(null, null, null);
        }

        // ── European decimal commas ──────────────────────────────────────────────

        @Test @DisplayName("European decimal comma: 36,1x57,6")
        void europeanDecimalComma() {
            BigDecimal[] r = ImportEngine.parseSize("36,1x57,6", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("361.00"); // 36.1 cm -> 361 mm
            assertThat(r[1]).isEqualByComparingTo("576.00"); // 57.6 cm -> 576 mm
        }

        @Test @DisplayName("European decimal comma with spaces around separator: 2,5 x 10")
        void europeanDecimalCommaWithSpaces() {
            BigDecimal[] r = ImportEngine.parseSize("2,5 x 10", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("25.00"); // 2.5 cm -> 25 mm
            assertThat(r[1]).isEqualByComparingTo("100.00"); // 10 cm -> 100 mm
        }

        // ── thickness glued onto the size string (real production shapes) ───────

        @Test @DisplayName("thickness embedded with explicit MM suffix: '60x120 9MM' (874 real rows)")
        void thicknessEmbeddedMM_60x120() {
            BigDecimal[] r = ImportEngine.parseSize("60x120 9MM", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("600.00");
            assertThat(r[1]).isEqualByComparingTo("1200.00");
            assertThat(r[2]).isEqualByComparingTo("9.00"); // never converted, already mm
        }

        @Test @DisplayName("thickness embedded with explicit MM suffix: '60x60 9MM' (867 real rows)")
        void thicknessEmbeddedMM_60x60() {
            BigDecimal[] r = ImportEngine.parseSize("60x60 9MM", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("600.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
            assertThat(r[2]).isEqualByComparingTo("9.00");
        }

        @Test @DisplayName("thickness embedded with explicit MM suffix: '20x20 12MM'")
        void thicknessEmbeddedMM_20x20() {
            BigDecimal[] r = ImportEngine.parseSize("20x20 12MM", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("200.00");
            assertThat(r[1]).isEqualByComparingTo("200.00");
            assertThat(r[2]).isEqualByComparingTo("12.00");
        }

        @Test @DisplayName("TRUNCATED thickness token '9M' is a clipped '9MM', never metres "
            + "(245 real rows: '120x120 9M')")
        void truncatedThicknessTokenSingleM() {
            BigDecimal[] r = ImportEngine.parseSize("120x120 9M", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("1200.00");
            assertThat(r[1]).isEqualByComparingTo("1200.00");
            assertThat(r[2]).isEqualByComparingTo("9.00"); // 9 mm, NOT 9 metres
        }

        @Test @DisplayName("TRUNCATED decimal thickness token: '60x60 9,4M' is a clipped '9,4MM'")
        void truncatedThicknessTokenWithDecimalComma() {
            BigDecimal[] r = ImportEngine.parseSize("60x60 9,4M", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("600.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
            assertThat(r[2]).isEqualByComparingTo("9.40");
        }

        // ── trailing free-text junk ───────────────────────────────────────────────

        @Test @DisplayName("trailing junk token 'MOD' is stripped")
        void trailingJunkMod() {
            BigDecimal[] r = ImportEngine.parseSize("60X120 MOD", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("600.00");
            assertThat(r[1]).isEqualByComparingTo("1200.00");
        }

        @Test @DisplayName("trailing junk tokens 'CORBEL NAVAL' (two words) are stripped")
        void trailingJunkTwoWords() {
            BigDecimal[] r = ImportEngine.parseSize("20X20 CORBEL NAVAL", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("200.00");
            assertThat(r[1]).isEqualByComparingTo("200.00");
        }

        @Test @DisplayName("trailing junk token 'S/AD' is stripped")
        void trailingJunkSlashAd() {
            BigDecimal[] r = ImportEngine.parseSize("30X60 S/AD", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("300.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
        }

        // ── messy fallback (embedded free text mid-string) ───────────────────────

        @Test @DisplayName("embedded free text mid-string ('120X50  h.15') falls back to numeric "
            + "scanning rather than throwing or dropping the row")
        void embeddedFreeTextFallsBackToScanning() {
            BigDecimal[] r = ImportEngine.parseSize("120X50  h.15", null, "cm");
            assertThat(r[0]).isEqualByComparingTo("1200.00");
            assertThat(r[1]).isEqualByComparingTo("500.00");
            assertThat(r[2]).isEqualByComparingTo("15.00");
        }

        // ── zero-padded (CDE) ─────────────────────────────────────────────────────

        @Test @DisplayName("zero-padded CDE-style size: '080x600'")
        void zeroPadded() {
            BigDecimal[] r = ImportEngine.parseSize("080x600", null, "mm");
            assertThat(r[0]).isEqualByComparingTo("80.00");
            assertThat(r[1]).isEqualByComparingTo("600.00");
        }
    }

    // ── canonUnit ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("canonUnit")
    class CanonUnitTests {

        @Test @DisplayName("Italian variants → per_sqm")
        void sqmVariants() {
            assertThat(ImportEngine.canonUnit("MQ", null)).isEqualTo("per_sqm");
            assertThat(ImportEngine.canonUnit("mq", null)).isEqualTo("per_sqm");
            assertThat(ImportEngine.canonUnit("M2", null)).isEqualTo("per_sqm");
            assertThat(ImportEngine.canonUnit("m²", null)).isEqualTo("per_sqm");
        }

        @Test @DisplayName("piece variants → per_piece")
        void pieceVariants() {
            assertThat(ImportEngine.canonUnit("PC", null)).isEqualTo("per_piece");
            assertThat(ImportEngine.canonUnit("PZ", null)).isEqualTo("per_piece");
            assertThat(ImportEngine.canonUnit("Pieza", null)).isEqualTo("per_piece");
            assertThat(ImportEngine.canonUnit("pcs", null)).isEqualTo("per_piece");
        }

        @Test @DisplayName("box variants → per_box")
        void boxVariants() {
            assertThat(ImportEngine.canonUnit("Caja", null)).isEqualTo("per_box");
            assertThat(ImportEngine.canonUnit("CJ", null)).isEqualTo("per_box");
        }

        @Test @DisplayName("linear metre variants → per_linear_m")
        void linearVariants() {
            assertThat(ImportEngine.canonUnit("ml", null)).isEqualTo("per_linear_m");
            assertThat(ImportEngine.canonUnit("LM", null)).isEqualTo("per_linear_m");
        }

        @Test @DisplayName("unknown unit falls back to default then 'unknown'")
        void unknownFallback() {
            assertThat(ImportEngine.canonUnit("XYZ", null)).isEqualTo("unknown");
            assertThat(ImportEngine.canonUnit("XYZ", "per_sqm")).isEqualTo("per_sqm");
        }
    }

    // ── Full parse with in-memory workbooks ───────────────────────────────────

    @Nested @DisplayName("Full parse — factory special cases")
    class FullParseTests {

        // ── helpers ───────────────────────────────────────────────────────────

        private ByteArrayInputStream makeWorkbook(String sheetName, Object[][] headerAndRows)
            throws Exception {
            // NOT try-with-resources: close() must happen after write() to avoid
            // try-with-resources suppressing the return value if close() throws
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
            // sizeUnit is REQUIRED (ImportEngine#parse fails the whole import otherwise) — these
            // fixtures don't exercise real-factory unit facts, so "mm" is an arbitrary but valid
            // default; tests that care about the actual conversion override it explicitly.
            p.sizeUnit = "mm";
            return p;
        }

        private ImportProfile.SheetConfig sheetConf(String name, int headerRow) {
            ImportProfile.SheetConfig s = new ImportProfile.SheetConfig();
            s.name = name; s.headerRow = headerRow;
            return s;
        }

        // ── Padana: grade separates duplicate codes ───────────────────────────

        @Test @DisplayName("Padana — A01/A02 grades produce separate rows, different prices")
        void padana_gradesProduceSeparateRows() throws Exception {
            Object[][] data = {
                {"Articolo", "Scelta", "Formato",   "Prezzo", "Unità", "MQ/SC", "PZ/SC"},
                {"0400012",  "A01",    "60x120",     43.0,    "MQ",    1.44,    2.0},
                {"0400012",  "A02",    "60x120",     21.5,    "MQ",    1.44,    2.0},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Articolo",
                "grade",        "Scelta",
                "size_raw",     "Formato",
                "price",        "Prezzo",
                "unit",         "Unità",
                "sqm_per_box",  "MQ/SC",
                "pcs_per_box",  "PZ/SC"
            ), Map.of("currency", "EUR"), "Sheet1", 1);
            prof.sizeUnit = "cm"; // Padana's real declared unit (60x120 cm reconciles with 1.44/2 sqm-per-box)

            ImportResult r = engine.parse(makeWorkbook("Sheet1", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
            assertThat(r.rows().get(0).sqmProvenance()).isEqualTo("box_reconciled");
            assertThat(r.rows().get(0).grade()).isEqualTo("A01");
            assertThat(r.rows().get(0).price()).isEqualByComparingTo("43.0000");
            assertThat(r.rows().get(1).grade()).isEqualTo("A02");
            assertThat(r.rows().get(1).price()).isEqualByComparingTo("21.5000");
        }

        // ── Equipe: price_column_rule type=choose ─────────────────────────────

        @Test @DisplayName("Equipe — 'choose' rule selects Pallet price, stores all variants")
        void equipe_choosePriceColumn() throws Exception {
            Object[][] data = {
                {"Artículo", "Descripción",      "Precio Pallet", "Precio Picking", "Precio Sueltas", "Unidad"},
                {"EQ-001",   "1,2X20 JOLLY ASH", 25.5,           28.0,            35.0,             "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Artículo",
                "product_name", "Descripción",
                "unit",         "Unidad"
            ), Map.of("currency", "EUR"), "EXTRACOMUNITARIOS", 1);
            ImportProfile.PriceColumnRule rule = new ImportProfile.PriceColumnRule();
            rule.type     = "choose";
            rule.options  = List.of("Precio Pallet", "Precio Picking", "Precio Sueltas");
            rule.selected = "Precio Pallet";
            rule.keepAllAs = "price_variants";
            prof.priceColumnRule = rule;

            ImportResult r = engine.parse(makeWorkbook("EXTRACOMUNITARIOS", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            PriceRow row = r.rows().get(0);
            assertThat(row.price()).isEqualByComparingTo("25.5000");  // Pallet
            assertThat(row.priceVariants()).containsKey("Precio Pallet");
            assertThat(row.priceVariants()).containsKey("Precio Picking");
        }

        // ── Vives: price_column_rule type=first_non_empty ─────────────────────

        @Test @DisplayName("Vives — PREPIEZA sets per_piece unit; PREMETRO sets per_sqm")
        void vives_firstNonEmptyUnit() throws Exception {
            Object[][] data = {
                {"MODELO", "NOMBRE",  "FORMATO",   "PREPIEZA", "PREMETRO"},
                {"VV-001", "Tile A",  "15'8X31'6", 5.5,        ""},      // per_piece
                {"VV-002", "Tile B",  "60x120",    "",         22.0},    // per_sqm
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "MODELO",
                "product_name", "NOMBRE",
                "size_raw",     "FORMATO"
            ), Map.of("currency", "EUR"), "Hoja1", 1);
            prof.sizeFormat = "apostrophe_decimal";
            prof.sizeUnit = "cm"; // Vives' apostrophe-decimal sizes are centimetres (profile's own notes)
            ImportProfile.PriceColumnRule rule = new ImportProfile.PriceColumnRule();
            rule.type = "first_non_empty";
            rule.map  = Map.of("PREPIEZA", "per_piece", "PREMETRO", "per_sqm");
            prof.priceColumnRule = rule;

            ImportResult r = engine.parse(makeWorkbook("Hoja1", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
            assertThat(r.rows().get(0).priceUnit()).isEqualTo("per_piece");
            assertThat(r.rows().get(0).price()).isEqualByComparingTo("5.5000");
            assertThat(r.rows().get(0).widthMm()).isEqualByComparingTo("158.00"); // apostrophe
            assertThat(r.rows().get(1).priceUnit()).isEqualTo("per_sqm");
            assertThat(r.rows().get(1).price()).isEqualByComparingTo("22.0000");
        }

        // ── Bode: fill_down + split_column ────────────────────────────────────

        @Test @DisplayName("Bode — fill-down 'series', comma-split code → 2 rows per cell")
        void bode_fillDownAndSplitCode() throws Exception {
            Object[][] data = {
                {"series",      "code",                      "size",   "finish", "USD/M2, FOB WITHOUT ORC/THC"},
                {"Limestone",   "BVLE10426KGA, BVLE20326KGA","600x600","Honed",  23.5},
                {"",            "BVLE30426KGA",              "600x600","Polished",25.0},  // fill-down
            };
            ImportProfile prof = profileWith(Map.of(
                "collection",   "series",
                "product_code", "code",
                "size_raw",     "size",
                "surface",      "finish",
                "price",        "USD/M2, FOB WITHOUT ORC/THC"
            ), Map.of("currency", "USD", "unit", "per_sqm"), "工作表1", 1);
            prof.fillDown   = List.of("series");
            prof.splitColumn = Map.of("code", ",");

            ImportResult r = engine.parse(makeWorkbook("工作表1", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(3); // 2 from split + 1 normal
            assertThat(r.rows().get(0).productCode()).isEqualTo("BVLE10426KGA");
            assertThat(r.rows().get(1).productCode()).isEqualTo("BVLE20326KGA");
            assertThat(r.rows().get(0).collection()).isEqualTo("Limestone");
            assertThat(r.rows().get(2).collection()).isEqualTo("Limestone"); // fill-down
            assertThat(r.rows().get(0).currency()).isEqualTo("USD");
        }

        // ── REFIN: allow_missing_code ─────────────────────────────────────────

        @Test @DisplayName("REFIN Trim-Tiles — missing code accepted, surrogate code synthesised")
        void refin_allowMissingCode() throws Exception {
            Object[][] data = {
                {"Collection", "Item",     "Size (cm)", "Um", "PRICE 2025"},
                {"Terraço",    "L-Trim",   "10x60",     "MQ", 38.0},
                {"Terraço",    "Corner",   "10x10",     "PZ", 55.0},
            };
            ImportProfile prof = profileWith(Map.of(
                "collection",   "Collection",
                "product_name", "Item",
                "size_raw",     "Size (cm)",
                "unit",         "Um",
                "price",        "PRICE 2025"
            ), Map.of("currency", "EUR"), "Trim-Tiles", 1);
            prof.allowMissingCode = true;

            ImportResult r = engine.parse(makeWorkbook("Trim-Tiles", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
            // Previously these were left NULL, which made uq_price degenerate to
            // (version_id, size_raw). They now carry a synthesised code instead.
            assertThat(r.rows().get(0).productCode()).startsWith(ImportEngine.SURROGATE_PREFIX);
            assertThat(r.rows().get(1).productCode()).startsWith(ImportEngine.SURROGATE_PREFIX);
            assertThat(r.rows().get(0).priceUnit()).isEqualTo("per_sqm");
            assertThat(r.rows().get(1).priceUnit()).isEqualTo("per_piece");
        }

        // ── surrogate code: the REFIN row-loss regression ─────────────────────

        @Test @DisplayName("Surrogate — same size, different product ⇒ different codes (Trim-Tiles loss)")
        void surrogate_sameSizeDifferentProductGetsDistinctCodes() throws Exception {
            // The exact shape that collapsed 74 Trim-Tiles rows to 17: no Code column,
            // several distinct products sharing one size at different prices.
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

        @Test @DisplayName("Surrogate — surface distinguishes Balneo MATT/SOFT from LUCIDO")
        void surrogate_surfaceDistinguishesVariants() throws Exception {
            Object[][] data = {
                {"COLLECTION",     "ITEM",                "SIZE (cm)",     "SURFACE",   "PRICE 2025", "UM"},
                {"BALNEO PROJECT", "LAVABO SOSPESO VISTA", "120X50  h.15", "MATT/SOFT", 3930.0,       "pcs"},
                {"",               "",                     "",             "LUCIDO",    4175.5,       "pcs"},
            };
            ImportProfile prof = new ImportProfile();
            prof.sizeUnit = "cm"; // column is literally "SIZE (cm)"
            prof.columns = Map.of(
                "collection",   "COLLECTION",
                "product_name", "ITEM",
                "size_raw",     "SIZE (cm)",
                "surface",      "SURFACE",
                "price",        "PRICE 2025",
                "unit",         "UM"
            );
            prof.defaults = Map.of("currency", "EUR");
            prof.sheets = List.of(sheetConf("Balneo-Project", 1));
            prof.allowMissingCode = true;
            prof.fillDownPerSheet = Map.of(
                "Balneo-Project", List.of("COLLECTION", "ITEM", "SIZE (cm)"));

            ImportResult r = engine.parse(makeWorkbook("Balneo-Project", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
            // Identical collection/item/size after fill-down — only SURFACE differs.
            assertThat(r.rows().get(0).productCode())
                .isNotEqualTo(r.rows().get(1).productCode());
        }

        @Test @DisplayName("Surrogate — deterministic across re-imports of the same file")
        void surrogate_isStableAcrossReimports() throws Exception {
            Object[][] data = {
                {"Item",    "Size (cm)", "PRICE 2026", "UM"},
                {"GRADINO", "33X150",    244.0,        "pcs"},
            };
            // Same profile shape both times; re-parsed from a fresh stream.
            ImportProfile prof1 = profileWith(Map.of(
                "product_name", "Item", "size_raw", "Size (cm)",
                "price", "PRICE 2026", "unit", "UM"
            ), Map.of("currency", "EUR"), "Trim-Tiles", 1);
            prof1.allowMissingCode = true;
            ImportProfile prof2 = profileWith(Map.of(
                "product_name", "Item", "size_raw", "Size (cm)",
                "price", "PRICE 2026", "unit", "UM"
            ), Map.of("currency", "EUR"), "Trim-Tiles", 1);
            prof2.allowMissingCode = true;

            String first  = engine.parse(makeWorkbook("Trim-Tiles", data), prof1, 1L)
                                  .rows().get(0).productCode();
            String second = engine.parse(makeWorkbook("Trim-Tiles", data), prof2, 1L)
                                  .rows().get(0).productCode();

            // Assert a code was actually synthesised before comparing — otherwise this
            // passes vacuously when both sides are NULL (verified by mutation check).
            assertThat(first).startsWith(ImportEngine.SURROGATE_PREFIX);
            // Stability is what keeps PriceImportService's incremental merge matching.
            assertThat(second).isEqualTo(first);
        }

        @Test @DisplayName("Surrogate — real codes are never overwritten by a synthesised one")
        void surrogate_doesNotTouchRealCodes() throws Exception {
            Object[][] data = {
                {"Code", "Item",    "Size (cm)", "PRICE 2026", "UM"},
                {"OG65", "CALCE R", "120X120",   96.5,          "mq"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code", "product_name", "Item", "size_raw", "Size (cm)",
                "price", "PRICE 2026", "unit", "UM"
            ), Map.of("currency", "EUR"), "Collections", 1);
            prof.allowMissingCode = true;

            ImportResult r = engine.parse(makeWorkbook("Collections", data), prof, 1L);
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0).productCode()).isEqualTo("OG65");
        }

        // ── REFIN: fill_down_per_sheet (Balneo-Project) ───────────────────────

        @Test @DisplayName("REFIN Balneo-Project — fill_down_per_sheet fills empty COLLECTION cells")
        void refin_fillDownPerSheet() throws Exception {
            Object[][] data = {
                {"COLLECTION", "ITEM",      "SIZE (cm)", "UM", "PRICE 2026"},
                {"Terracina",  "Floor Tile","60x60",     "MQ", 42.0},
                {"",           "Wall Tile", "30x60",     "MQ", 38.0}, // fill-down
            };
            ImportProfile prof = new ImportProfile();
            prof.sizeUnit = "cm"; // column is literally "SIZE (cm)"
            prof.columns = Map.of(
                "collection",   "COLLECTION",
                "product_name", "ITEM",
                "size_raw",     "SIZE (cm)",
                "unit",         "UM",
                "price",        "PRICE 2026"
            );
            prof.defaults = Map.of("currency", "EUR");
            prof.sheets = List.of(sheetConf("Balneo-Project", 1));
            prof.allowMissingCode = true;
            prof.fillDownPerSheet = Map.of("Balneo-Project", List.of("COLLECTION", "ITEM"));

            ImportResult r = engine.parse(makeWorkbook("Balneo-Project", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
            assertThat(r.rows().get(1).collection()).isEqualTo("Terracina"); // filled down
        }

        // ── CDE: header with trailing space ──────────────────────────────────

        @Test @DisplayName("CDE — header 'ITEM ' (trailing space) is matched after normHeader trim")
        void cde_trailingSpaceHeader() throws Exception {
            Object[][] data = {
                {"ITEM ", "RANGE", "SIZE", "PRICE ", "DIVISA", "UOM"},
                {"CDE-1", "Stone", "60x60",18.0,    "EUR",    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "ITEM ",
                "collection",   "RANGE",
                "size_raw",     "SIZE",
                "price",        "PRICE ",
                "currency",     "DIVISA",
                "unit",         "UOM"
            ), Map.of(), "LIST E1_E20 2026", 1);

            ImportResult r = engine.parse(makeWorkbook("LIST E1_E20 2026", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0).productCode()).isEqualTo("CDE-1");
            assertThat(r.rows().get(0).price()).isEqualByComparingTo("18.0000");
        }

        // ── Panaria: header_row=2 ─────────────────────────────────────────────

        @Test @DisplayName("Panaria — header at row 2 (0-indexed row 1 skipped)")
        void panaria_headerAtRow2() throws Exception {
            Object[][] data = {
                {"PANARIA PRICE LIST 2026"},  // row 1 (title — skipped)
                {"CODICE ART", "SERIE", "FORMATO", "PRZ_1SCE_EST", "DIVISA", "UM_VEN"},
                {"PAN-001",    "Stone", "60x120",   55.0,          "EUR",    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "CODICE ART",
                "collection",   "SERIE",
                "size_raw",     "FORMATO",
                "price",        "PRZ_1SCE_EST",
                "currency",     "DIVISA",
                "unit",         "UM_VEN"
            ), Map.of(), "PAN", 2);  // header_row = 2

            ImportResult r = engine.parse(makeWorkbook("PAN", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            assertThat(r.rows().get(0).productCode()).isEqualTo("PAN-001");
        }

        // ── sqm_per_piece calculation ─────────────────────────────────────────

        @Test @DisplayName("sqm_per_piece = sqm_per_box / pcs_per_box")
        void sqmPerPieceCalculation() throws Exception {
            Object[][] data = {
                {"Code", "Size",   "Price", "Um", "m²/Box", "Pcs/Box"},
                {"R-01", "60x60",  30.0,    "MQ", 1.44,     4.0},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um",
                "sqm_per_box",  "m²/Box",
                "pcs_per_box",  "Pcs/Box"
            ), Map.of("currency", "EUR"), "Collections", 1);
            prof.allowMissingCode = false;
            // 60x60 cm = 0.36 m^2/piece, which is exactly what the box figures below reconcile to
            // — declaring "mm" here would make 60x60 MM (0.0036 m^2) disagree by ~100x and
            // quarantine the row instead, which is the point of the reconciliation feature this
            // test now also exercises.
            prof.sizeUnit = "cm";

            ImportResult r = engine.parse(makeWorkbook("Collections", data), prof, 1L);
            assertThat(r.rows()).hasSize(1);
            // 1.44 / 4 = 0.36, and it agrees with 60cm x 60cm -> box-reconciled, not quarantined.
            assertThat(r.rows().get(0).sqmPerPiece())
                .isEqualByComparingTo(new BigDecimal("0.360000"));
            assertThat(r.rows().get(0).sqmProvenance()).isEqualTo("box_reconciled");
            assertThat(r.rows().get(0).quarantineReason()).isNull();
        }

        // ── blank rows skipped ────────────────────────────────────────────────

        @Test @DisplayName("blank rows in middle of sheet are skipped")
        void blankRowsSkipped() throws Exception {
            Object[][] data = {
                {"Code", "Size",  "Price", "Um"},
                {"A-01", "60x60", 20.0,    "MQ"},
                {null, null, null, null},            // blank row
                {"A-02", "30x30", 15.0,    "MQ"},
            };
            ImportProfile prof = profileWith(Map.of(
                "product_code", "Code",
                "size_raw",     "Size",
                "price",        "Price",
                "unit",         "Um"
            ), Map.of("currency", "EUR"), "Sheet", 1);

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.rows()).hasSize(2);
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

        @Test @DisplayName("an unrecognised size_unit value also fails loudly, not silently defaulted")
        void invalidSizeUnitValueFailsTheWholeImportLoudly() throws Exception {
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
            prof.sizeUnit = "inches"; // not "mm" or "cm"

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.rows()).isEmpty();
            assertThat(r.errors()).hasSize(1);
        }

        // ── reconciliation: agree / disagree / unavailable ───────────────────────

        @Test @DisplayName("reconciliation DISAGREE quarantines the row (staged, excluded from "
            + "commit) rather than importing a wrong figure silently — CDE-shaped defect")
        void reconciliationDisagreementQuarantinesTheRow() throws Exception {
            Object[][] data = {
                // Declared mm. Box figures say 0.36 m^2/piece (60cm x 60cm); the size string
                // says "60x60" which under mm is 60mm x 60mm = 0.0036 m^2/piece -- ~100x apart.
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

            // Quarantined, not dropped: it IS staged (so the operator can see and fix it), just
            // excluded from commit and reported.
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

        @Test @DisplayName("reconciliation AGREE within tolerance imports normally")
        void reconciliationAgreementImportsNormally() throws Exception {
            Object[][] data = {
                // 60cm x 60cm = 0.36 exactly; box figure rounds to 0.3564 (~1% off) — within the
                // 2% tolerance.
                {"Code", "Size",  "Price", "Um", "m²/Box", "Pcs/Box"},
                {"P-1", "60x60", 18.0,     "MQ", 1.4256,   4.0},
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
            assertThat(r.quarantined()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            PriceRow row = r.rows().get(0);
            assertThat(row.sqmProvenance()).isEqualTo("box_reconciled");
            assertThat(row.quarantineReason()).isNull();
            assertThat(row.sqmPerPiece()).isEqualByComparingTo(new BigDecimal("0.3564"));
        }

        @Test @DisplayName("no box columns at all (Bode's shape) -> import on the declared unit, "
            + "marked unreconciled rather than agreeing/disagreeing with nothing")
        void noBoxColumnsImportsUnreconciled() throws Exception {
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

            ImportResult r = engine.parse(makeWorkbook("Sheet", data), prof, 1L);
            assertThat(r.quarantined()).isEmpty();
            assertThat(r.rows()).hasSize(1);
            PriceRow row = r.rows().get(0);
            assertThat(row.sqmProvenance()).isEqualTo("computed_from_dimensions");
            assertThat(row.sqmPerPiece()).isEqualByComparingTo(new BigDecimal("0.360000"));
        }

        // ── per_linear_m is classified, never quarantined as an area mismatch ────

        @Test @DisplayName("per_linear_m rows are NEVER quarantined by the area check, even when "
            + "the box 'sqm' figure (really linear metres) wildly disagrees with width x height")
        void perLinearMIsNeverQuarantinedAsAnAreaMismatch() throws Exception {
            Object[][] data = {
                // A 7x60 cm trim: box column holds LINEAR METRES per box (per V153's own finding),
                // not m^2 -- comparing 0.750 "linear metres/piece" against width*height=0.042 m^2
                // would be a ~18x "mismatch" if it were ever compared as area. It must not be.
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
            // sqmPerPiece must stay null -- 15.0/20.0 = 0.750 linear metres/piece is NOT an area,
            // and nothing may write it where downstream code reads an area.
            assertThat(row.sqmPerPiece()).isNull();
            // The profile height (shorter side, 70mm) IS captured, correctly labelled.
            assertThat(row.sqmPerLinearM()).isEqualByComparingTo(new BigDecimal("0.070000"));
        }
    }
}
