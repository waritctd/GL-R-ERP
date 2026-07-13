package th.co.glr.hr.catalog.importer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Profile-driven Excel importer — no factory-specific logic in code;
 * all mapping/rules come from the ImportProfile JSON loaded from DB.
 *
 * Mirrors the Python reference engine in docs/Catalouge/import_engine.py.
 */
@Component
public class ImportEngine {

    private static final Map<String, String> UNIT_MAP = Map.ofEntries(
        Map.entry("mq",      "per_sqm"),
        Map.entry("m2",      "per_sqm"),
        Map.entry("sqm",     "per_sqm"),
        Map.entry("m²",      "per_sqm"),
        Map.entry("pc",      "per_piece"),
        Map.entry("pz",      "per_piece"),
        Map.entry("pieza",   "per_piece"),
        Map.entry("pcs",     "per_piece"),
        Map.entry("caja",    "per_box"),
        Map.entry("box",     "per_box"),
        Map.entry("cj",      "per_box"),
        Map.entry("ml",      "per_linear_m"),
        Map.entry("lm",      "per_linear_m")
    );

    private static final Pattern NUM_PATTERN =
        Pattern.compile("[\\d]+(?:[.,][\\d]+)?");

    private static final Pattern APOSTROPHE_DECIMAL =
        Pattern.compile("(\\d)'(\\d)");

    // ── public API ────────────────────────────────────────────────────────────

    public ImportResult parse(InputStream in, ImportProfile prof, long factoryId) {
        List<PriceRow> rows   = new ArrayList<>();
        List<String>   errors = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(in)) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            for (ImportProfile.SheetConfig sh : prof.sheets) {
                Sheet sheet = wb.getSheet(sh.name);
                if (sheet == null) {
                    errors.add("[" + sh.name + "] ไม่พบชีตในไฟล์");
                    continue;
                }
                processSheet(sheet, sh, prof, factoryId, evaluator, rows, errors);
            }
        } catch (Exception e) {
            errors.add("เปิดไฟล์ไม่ได้: " + e.getMessage()
                + " (ถ้าเป็นไฟล์ Padana ให้ re-save ด้วย LibreOffice ก่อน)");
        }
        return new ImportResult(rows, errors);
    }

    // ── sheet processing ──────────────────────────────────────────────────────

    private void processSheet(
        Sheet sheet, ImportProfile.SheetConfig sh, ImportProfile prof,
        long factoryId, FormulaEvaluator evaluator,
        List<PriceRow> rows, List<String> errors
    ) {
        String sheetName = sh.name;
        int headerRowIdx = sh.headerRow - 1; // convert 1-based → 0-based

        Row headerRow = sheet.getRow(headerRowIdx);
        if (headerRow == null) {
            errors.add("[" + sheetName + "] ไม่พบแถว header ที่ row " + sh.headerRow);
            return;
        }

        List<String> header = readHeader(headerRow, evaluator);
        Map<String, Integer> idx = buildIndex(header, prof, sheetName);

        // price column rule: pre-compute price-col indices
        ImportProfile.PriceColumnRule rule = prof.priceColumnRule;
        Map<String, Integer> priceColIdx = buildPriceColIdx(rule, header);

        // fill-down: columns to fill-down for this sheet
        List<String> sheetFillDown = new ArrayList<>(prof.fillDown);
        if (prof.fillDownPerSheet.containsKey(sheetName)) {
            sheetFillDown = prof.fillDownPerSheet.get(sheetName);
        }
        // map fill-down col names → field targets
        List<FillDown> fillDownTargets = resolveFillDown(sheetFillDown, prof.columns);

        // last-seen values for fill-down
        Map<String, Object> lastSeen = new HashMap<>();

        int lastRowNum = sheet.getLastRowNum();
        for (int r = headerRowIdx + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (isBlankRow(row)) continue;

            Map<String, Object> rec = readRow(row, idx, header.size(), evaluator);
            injectPriceVariants(row, priceColIdx, evaluator, rec);
            applyFillDown(rec, fillDownTargets, lastSeen);

            String rowErr = processRow(
                rec, rule, priceColIdx, prof, factoryId, sheetName, r + 1, rows
            );
            if (rowErr != null) errors.add("[" + sheetName + "] r" + (r + 1) + ": " + rowErr);
        }
    }

    // ── per-row logic ─────────────────────────────────────────────────────────

    private String processRow(
        Map<String, Object> rec,
        ImportProfile.PriceColumnRule rule,
        Map<String, Integer> priceColIdx,
        ImportProfile prof,
        long factoryId,
        String sheetName, int sourceRow,
        List<PriceRow> out
    ) {
        String fmt = prof.numberFormat;

        // ── price ─────────────────────────────────────────────────────────────
        BigDecimal price;
        String unitOverride = null;
        Map<String, String> priceVariants = null;

        if (rule == null) {
            price = toDecimal(rec.get("price"), fmt);
        } else if ("choose".equals(rule.type)) {
            // use selected column; store all as variants
            Object raw = rec.get("__price_" + rule.selected);
            price = toDecimal(raw, fmt);
            priceVariants = new LinkedHashMap<>();
            for (String opt : rule.options) {
                Object v = rec.get("__price_" + opt);
                if (v != null && !v.toString().isBlank())
                    priceVariants.put(opt, v.toString());
            }
        } else if ("first_non_empty".equals(rule.type)) {
            price = null;
            for (Map.Entry<String, String> e : rule.map.entrySet()) {
                Object v = rec.get("__price_" + e.getKey());
                BigDecimal d = toDecimal(v, fmt);
                if (d != null && d.compareTo(BigDecimal.ZERO) > 0) {
                    price = d;
                    unitOverride = e.getValue();
                    break;
                }
            }
        } else {
            price = toDecimal(rec.get("price"), fmt);
        }

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return "ไม่มีราคา";
        }

        // ── product_code ──────────────────────────────────────────────────────
        String code = blankToNull(stringify(rec.get("product_code")));
        if (code == null && !prof.allowMissingCode) {
            return "ไม่มีรหัสสินค้า";
        }

        // split_column (Bode: 2 codes in one cell)
        List<String> codes = splitCodes(code, prof.splitColumn.get("code"));

        // ── size ──────────────────────────────────────────────────────────────
        String sizeRaw = stringify(rec.get("size_raw"));
        if (sizeRaw == null && prof.sizeFrom != null) {
            sizeRaw = stringify(rec.get(prof.sizeFrom));
        }
        BigDecimal[] dims = parseSize(sizeRaw, prof.sizeFormat);

        // ── unit ──────────────────────────────────────────────────────────────
        String unit = unitOverride != null
            ? unitOverride
            : canonUnit(stringify(rec.get("unit")), prof.defaults.get("unit"));

        // ── currency ──────────────────────────────────────────────────────────
        String cur = stringify(rec.get("currency"));
        if (cur == null || cur.isBlank()) cur = prof.defaults.get("currency");
        if (cur != null) cur = cur.strip().toUpperCase();
        if (cur != null && cur.length() > 3) cur = cur.substring(0, 3);

        // ── box data ──────────────────────────────────────────────────────────
        BigDecimal pcs = toDecimal(rec.get("pcs_per_box"), fmt);
        BigDecimal sqm = toDecimal(rec.get("sqm_per_box"), fmt);
        BigDecimal sqmPerPiece = null;
        if (pcs != null && sqm != null && pcs.compareTo(BigDecimal.ZERO) > 0) {
            sqmPerPiece = sqm.divide(pcs, 6, RoundingMode.HALF_UP);
        }

        BigDecimal thickness = dims[2] != null ? dims[2]
            : toDecimal(rec.get("thickness_mm"), fmt);

        // ── attributes (barcode, …) ───────────────────────────────────────────
        Map<String, String> attributes = null;
        String barcode = blankToNull(stringify(rec.get("barcode")));
        if (barcode != null) {
            attributes = Map.of("barcode", barcode);
        }

        // emit one row per code (Bode split)
        for (String c : codes) {
            out.add(new PriceRow(
                factoryId,
                c,
                blankToNull(stringify(rec.get("grade"))),
                blankToNull(stringify(rec.get("collection"))),
                blankToNull(stringify(rec.get("product_name"))),
                blankToNull(stringify(rec.get("color"))),
                blankToNull(stringify(rec.get("surface"))),
                blankToNull(sizeRaw),
                dims[0], dims[1], thickness,
                price, cur != null ? cur : "EUR", unit,
                sqmPerPiece, pcs, sqm,
                toDecimal(rec.get("kg_per_box"), fmt),
                priceVariants, attributes,
                sheetName, sourceRow
            ));
        }
        return null;
    }

    // ── header / index ────────────────────────────────────────────────────────

    private List<String> readHeader(Row row, FormulaEvaluator ev) {
        List<String> h = new ArrayList<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            h.add(normHeader(cellString(row.getCell(c), ev)));
        }
        return h;
    }

    private Map<String, Integer> buildIndex(
        List<String> header, ImportProfile prof, String sheetName
    ) {
        Map<String, Integer> idx = new HashMap<>();
        Map<String, List<String>> aliases = prof.columnAliases;

        for (Map.Entry<String, String> e : prof.columns.entrySet()) {
            String tgt    = e.getKey();
            String col    = e.getValue();
            List<String>  cands = new ArrayList<>();
            cands.add(col);
            cands.addAll(aliases.getOrDefault(tgt, List.of()));
            for (String c : cands) {
                int i = header.indexOf(normHeader(c));
                if (i >= 0) { idx.put(tgt, i); break; }
            }
        }
        // alias-only targets (e.g. REFIN "surface" only in aliases, not in columns)
        for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
            String tgt = e.getKey();
            if (idx.containsKey(tgt)) continue;
            for (String c : e.getValue()) {
                int i = header.indexOf(normHeader(c));
                if (i >= 0) { idx.put(tgt, i); break; }
            }
        }
        return idx;
    }

    private Map<String, Integer> buildPriceColIdx(
        ImportProfile.PriceColumnRule rule, List<String> header
    ) {
        if (rule == null) return Map.of();
        Map<String, Integer> m = new LinkedHashMap<>();
        List<String> keys = rule.options != null
            ? rule.options
            : (rule.map != null ? new ArrayList<>(rule.map.keySet()) : List.of());
        for (String k : keys) {
            int i = header.indexOf(normHeader(k));
            if (i >= 0) m.put(k, i);
        }
        return m;
    }

    // ── row reading ───────────────────────────────────────────────────────────

    private Map<String, Object> readRow(
        Row row, Map<String, Integer> idx, int headerSize, FormulaEvaluator ev
    ) {
        Map<String, Object> rec = new HashMap<>();
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            int ci = e.getValue();
            if (ci < row.getLastCellNum()) {
                rec.put(e.getKey(), cellValue(row.getCell(ci), ev));
            }
        }
        return rec;
    }

    // ── fill-down ─────────────────────────────────────────────────────────────

    private record FillDown(String colName, String fieldTarget) {}

    private List<FillDown> resolveFillDown(
        List<String> fillDownCols, Map<String, String> columns
    ) {
        List<FillDown> result = new ArrayList<>();
        for (String colName : fillDownCols) {
            String normCol = normHeader(colName);
            String tgt = columns.entrySet().stream()
                .filter(e -> normHeader(e.getValue()).equals(normCol))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
            if (tgt != null) result.add(new FillDown(colName, tgt));
        }
        return result;
    }

    private void applyFillDown(
        Map<String, Object> rec,
        List<FillDown> targets,
        Map<String, Object> lastSeen
    ) {
        for (FillDown fd : targets) {
            Object val = rec.get(fd.fieldTarget());
            if (val == null || val.toString().isBlank()) {
                rec.put(fd.fieldTarget(), lastSeen.get(fd.fieldTarget()));
            } else {
                lastSeen.put(fd.fieldTarget(), val);
            }
        }
    }

    // ── price variant injection into rec ──────────────────────────────────────
    // called after readRow() to inject price-column values under virtual keys

    private void injectPriceVariants(
        Row row, Map<String, Integer> priceColIdx, FormulaEvaluator ev,
        Map<String, Object> rec
    ) {
        for (Map.Entry<String, Integer> e : priceColIdx.entrySet()) {
            int ci = e.getValue();
            if (ci < row.getLastCellNum()) {
                rec.put("__price_" + e.getKey(), cellValue(row.getCell(ci), ev));
            }
        }
    }

    // ── normalizers ───────────────────────────────────────────────────────────

    static String normHeader(String s) {
        if (s == null) return "";
        return s.strip().replaceAll("\\s+", " ").toLowerCase();
    }

    static BigDecimal toDecimal(Object v, String fmt) {
        if (v == null) return null;
        if (v instanceof Number n) {
            try { return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP); }
            catch (NumberFormatException e) { return null; }
        }
        String s = v.toString().strip()
            .replace(" ", "").replace(" ", "");
        s = s.replaceAll("[^\\d.,\\-]", "");
        if (s.isEmpty()) return null;
        if (s.contains(",") && s.contains(".")) {
            // both separators: the one appearing last is decimal
            if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                s = s.replace(".", "").replace(",", ".");
            } else {
                s = s.replace(",", "");
            }
        } else if (s.contains(",")) {
            s = "eu".equals(fmt) ? s.replace(",", ".") : s.replace(",", "");
        }
        try {
            return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal[] parseSize(String raw, String style) {
        if (raw == null || raw.isBlank()) return new BigDecimal[]{null, null, null};
        String s = raw.strip();
        if ("apostrophe_decimal".equals(style)) {
            s = APOSTROPHE_DECIMAL.matcher(s).replaceAll("$1.$2");
        }
        Matcher m = NUM_PATTERN.matcher(s.replace(" ", ""));
        List<Double> vals = new ArrayList<>();
        while (m.find() && vals.size() < 3) {
            try { vals.add(Double.parseDouble(m.group().replace(",", "."))); }
            catch (NumberFormatException ignored) {}
        }
        if (vals.isEmpty()) return new BigDecimal[]{null, null, null};

        // values < 300 are cm → convert to mm
        BigDecimal w = vals.size() > 0 ? toMm(vals.get(0)) : null;
        BigDecimal h = vals.size() > 1 ? toMm(vals.get(1)) : null;
        // 3rd dimension = thickness, already in mm
        BigDecimal t = vals.size() > 2 ? BigDecimal.valueOf(vals.get(2)) : null;
        return new BigDecimal[]{w, h, t};
    }

    static String canonUnit(String u, String defaultUnit) {
        if (u == null || u.isBlank()) return defaultUnit != null ? defaultUnit : "unknown";
        String mapped = UNIT_MAP.get(u.strip().toLowerCase());
        return mapped != null ? mapped : (defaultUnit != null ? defaultUnit : "unknown");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal toMm(double x) {
        return BigDecimal.valueOf(x < 300 ? x * 10 : x).setScale(2, RoundingMode.HALF_UP);
    }

    private static List<String> splitCodes(String code, String delimiter) {
        if (code == null) return Collections.singletonList(null);
        if (delimiter == null || delimiter.isBlank()) return List.of(code);
        String[] parts = code.split(Pattern.quote(delimiter));
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (!t.isBlank()) result.add(t);
        }
        return result.isEmpty() ? List.of(code) : result;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static String stringify(Object v) {
        return v == null ? null : v.toString().strip();
    }

    private boolean isBlankRow(Row row) {
        if (row == null) return true;
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String s = cellString(cell, null);
                if (s != null && !s.isBlank()) return false;
            }
        }
        return true;
    }

    private Object cellValue(Cell cell, FormulaEvaluator ev) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA && ev != null) {
            try { type = ev.evaluateFormulaCell(cell); }
            catch (Exception ignored) {}
        }
        return switch (type) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING  -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            default      -> null;
        };
    }

    private String cellString(Cell cell, FormulaEvaluator ev) {
        Object v = cellValue(cell, ev);
        return v == null ? null : v.toString();
    }

}
