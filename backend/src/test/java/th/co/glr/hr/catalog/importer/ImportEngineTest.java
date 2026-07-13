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

        @Test @DisplayName("mm format (>=300) kept as-is")
        void mmFormat() {
            BigDecimal[] r = ImportEngine.parseSize("600x1200", null);
            assertThat(r[0]).isEqualByComparingTo("600.00");   // 600 mm
            assertThat(r[1]).isEqualByComparingTo("1200.00");  // 1200 mm
            assertThat(r[2]).isNull();
        }

        @Test @DisplayName("cm format (<300) multiplied by 10 → mm")
        void cmFormat() {
            BigDecimal[] r = ImportEngine.parseSize("60x120", null);
            assertThat(r[0]).isEqualByComparingTo("600.00");   // 60 cm → 600 mm
            assertThat(r[1]).isEqualByComparingTo("1200.00");  // 120 cm → 1200 mm
        }

        @Test @DisplayName("leading space is ignored")
        void leadingSpace() {
            BigDecimal[] r = ImportEngine.parseSize(" 150x600", null);
            assertThat(r[0]).isEqualByComparingTo("1500.00"); // 150 cm (<300) → ×10 = 1500 mm
            assertThat(r[1]).isEqualByComparingTo("600.00");  // 600 mm (≥300) → stays 600 mm
        }

        @Test @DisplayName("uppercase X separator")
        void upperX() {
            BigDecimal[] r = ImportEngine.parseSize("120X120", null);
            assertThat(r[0]).isEqualByComparingTo("1200.00");
            assertThat(r[1]).isEqualByComparingTo("1200.00");
        }

        @Test @DisplayName("3D: WxHxT — 3rd value is thickness in mm")
        void threeDimensions() {
            BigDecimal[] r = ImportEngine.parseSize("598X598X18", null);
            assertThat(r[0]).isEqualByComparingTo("598.00"); // 598 mm (≥300) → stays 598 mm
            assertThat(r[1]).isEqualByComparingTo("598.00");
            assertThat(r[2]).isEqualByComparingTo("18");     // thickness as-is
        }

        @Test @DisplayName("apostrophe decimal (Vives): 15'8X31'6 → 158×316 mm")
        void apostropheDecimalVives() {
            BigDecimal[] r = ImportEngine.parseSize("15'8X31'6", "apostrophe_decimal");
            // 15.8 cm < 300 → ×10 = 158 mm
            assertThat(r[0]).isEqualByComparingTo("158.00");
            // 31.6 cm < 300 → ×10 = 316 mm
            assertThat(r[1]).isEqualByComparingTo("316.00");
        }

        @Test @DisplayName("null/blank returns all nulls")
        void blank() {
            BigDecimal[] r = ImportEngine.parseSize(null, null);
            assertThat(r).containsExactly(null, null, null);
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

            ImportResult r = engine.parse(makeWorkbook("Sheet1", data), prof, 1L);
            assertThat(r.errors()).isEmpty();
            assertThat(r.rows()).hasSize(2);
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

        @Test @DisplayName("REFIN Trim-Tiles — missing code accepted when allow_missing_code=true")
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
            assertThat(r.rows().get(0).productCode()).isNull(); // accepted
            assertThat(r.rows().get(0).priceUnit()).isEqualTo("per_sqm");
            assertThat(r.rows().get(1).priceUnit()).isEqualTo("per_piece");
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

            ImportResult r = engine.parse(makeWorkbook("Collections", data), prof, 1L);
            assertThat(r.rows()).hasSize(1);
            // 1.44 / 4 = 0.36
            assertThat(r.rows().get(0).sqmPerPiece())
                .isEqualByComparingTo(new BigDecimal("0.360000"));
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
    }
}
