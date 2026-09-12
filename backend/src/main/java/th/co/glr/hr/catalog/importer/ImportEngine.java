package th.co.glr.hr.catalog.importer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Prefix marking a code this engine synthesised rather than read from the price list.
     * Distinct from every real factory code, so a surrogate can never be mistaken for one.
     */
    static final String SURROGATE_PREFIX = "AUTO-";

    /** 6 bytes → 12 hex chars. Collision odds stay negligible at catalogue scale. */
    private static final int SURROGATE_HASH_BYTES = 6;

    private static final Pattern NUM_PATTERN =
        Pattern.compile("[\\d]+(?:[.,][\\d]+)?");

    private static final Pattern APOSTROPHE_DECIMAL =
        Pattern.compile("(\\d)'(\\d)");

    /**
     * Trailing thickness token WITH an explicit unit letter — "9MM", "12MM", and the truncated
     * single-M forms Excel column clipping produces ("9M", "9,4M"). The owner confirmed a bare
     * trailing "M" here is always a clipped "MM", never metres — this is a millimetre thickness
     * suffix, full stop. Comma decimal supported ("9,4M" = 9.4 mm).
     */
    private static final Pattern THICKNESS_SUFFIX =
        Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*[Mm][Mm]?$");

    /** The only legitimate dimension separators across all ten source price lists. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s*[×xX]\\s*");

    /** No default, no magnitude fallback — see {@link ImportProfile#sizeUnit}. */
    private static final Set<String> VALID_SIZE_UNITS = Set.of("mm", "cm");

    /** No default, no magnitude fallback — see {@link ImportProfile#thicknessUnit}. "none" is a
     * legitimate declared value (no thickness data in this source), not a placeholder. */
    private static final Set<String> VALID_THICKNESS_UNITS = Set.of("mm", "cm", "none");

    private static final BigDecimal SQ_MM_PER_SQM = new BigDecimal("1000000");
    private static final BigDecimal MM_PER_M      = BigDecimal.valueOf(1000);

    /**
     * Reconciliation tolerance for m²/box ÷ pcs/box vs parsed width×height, both expressed per
     * piece. 2% — chosen from the four price lists this was measured against (Padana 98.6%
     * agreement, LEA 85.3%, CDE 97.9%, plus DealQuotationService's catalogue-wide 8.6% disagreement
     * figure, up to 14x on trims): 2% comfortably absorbs box-figure rounding (pcs/box and m²/box
     * are typically given to 2-4 significant figures, and nominal tile sizes vs as-manufactured
     * sizes drift by a percent or two) while remaining an order of magnitude below any real
     * disagreement this exists to catch — a wrong declared unit is never a 2% error, it is ~10x or
     * ~100x.
     */
    private static final BigDecimal SQM_TOLERANCE = new BigDecimal("0.02");

    // ── public API ────────────────────────────────────────────────────────────

    public ImportResult parse(InputStream in, ImportProfile prof, long factoryId) {
        List<PriceRow> rows   = new ArrayList<>();
        List<String>   errors = new ArrayList<>();
        List<ImportResult.QuarantinedRow> quarantined = new ArrayList<>();

        String sizeUnit = normalizeSizeUnit(prof.sizeUnit);
        if (sizeUnit == null) {
            errors.add("โปรไฟล์นำเข้าของ factory id=" + factoryId + " ไม่ได้ระบุหน่วยขนาด (size_unit) "
                + "ที่ถูกต้อง — ต้องระบุ \"mm\" หรือ \"cm\" อย่างชัดเจนในโปรไฟล์ก่อนนำเข้า "
                + "ระบบจะไม่เดาหน่วยจากตัวเลขขนาดอีกต่อไป (พบค่า: "
                + (prof.sizeUnit == null ? "ไม่ได้ระบุ" : "\"" + prof.sizeUnit + "\"") + ")");
            return new ImportResult(rows, errors, quarantined);
        }
        // Same discipline, same failure shape, as sizeUnit above — see ImportProfile#thicknessUnit.
        // "none" IS a valid declared value (Bode/Vives/Equipe genuinely carry no thickness data);
        // only a MISSING or UNRECOGNISED declaration fails the import.
        String thicknessUnit = normalizeThicknessUnit(prof.thicknessUnit);
        if (thicknessUnit == null) {
            errors.add("โปรไฟล์นำเข้าของ factory id=" + factoryId + " ไม่ได้ระบุหน่วยความหนา (thickness_unit) "
                + "ที่ถูกต้อง — ต้องระบุ \"mm\", \"cm\" หรือ \"none\" (ไม่มีข้อมูลความหนา) "
                + "อย่างชัดเจนในโปรไฟล์ก่อนนำเข้า ระบบจะไม่เดาหน่วยจากตัวเลขความหนาอีกต่อไป (พบค่า: "
                + (prof.thicknessUnit == null ? "ไม่ได้ระบุ" : "\"" + prof.thicknessUnit + "\"") + ")");
            return new ImportResult(rows, errors, quarantined);
        }

        try (Workbook wb = WorkbookFactory.create(in)) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            for (ImportProfile.SheetConfig sh : prof.sheets) {
                Sheet sheet = wb.getSheet(sh.name);
                if (sheet == null) {
                    errors.add("[" + sh.name + "] ไม่พบชีตในไฟล์");
                    continue;
                }
                processSheet(sheet, sh, prof, factoryId, sizeUnit, thicknessUnit, evaluator, rows, errors, quarantined);
            }
        } catch (Exception e) {
            errors.add("เปิดไฟล์ไม่ได้: " + e.getMessage()
                + " (ถ้าเป็นไฟล์ Padana ให้ re-save ด้วย LibreOffice ก่อน)");
        }
        return new ImportResult(rows, errors, quarantined);
    }

    private static String normalizeSizeUnit(String raw) {
        if (raw == null) return null;
        String u = raw.strip().toLowerCase(Locale.ROOT);
        return VALID_SIZE_UNITS.contains(u) ? u : null;
    }

    private static String normalizeThicknessUnit(String raw) {
        if (raw == null) return null;
        String u = raw.strip().toLowerCase(Locale.ROOT);
        return VALID_THICKNESS_UNITS.contains(u) ? u : null;
    }

    // ── sheet processing ──────────────────────────────────────────────────────

    private void processSheet(
        Sheet sheet, ImportProfile.SheetConfig sh, ImportProfile prof,
        long factoryId, String sizeUnit, String thicknessUnit, FormulaEvaluator evaluator,
        List<PriceRow> rows, List<String> errors, List<ImportResult.QuarantinedRow> quarantined
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
                rec, rule, priceColIdx, prof, factoryId, sizeUnit, thicknessUnit, sheetName, r + 1, rows, quarantined
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
        String sizeUnit,
        String thicknessUnit,
        String sheetName, int sourceRow,
        List<PriceRow> out,
        List<ImportResult.QuarantinedRow> quarantined
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

        // ── size ──────────────────────────────────────────────────────────────
        // Resolved before product_code because the surrogate code below derives from it.
        String sizeRaw = stringify(rec.get("size_raw"));
        if (sizeRaw == null && prof.sizeFrom != null) {
            sizeRaw = stringify(rec.get(prof.sizeFrom));
        }
        BigDecimal[] dims = parseSize(sizeRaw, prof.sizeFormat, sizeUnit, thicknessUnit);

        // ── product_code ──────────────────────────────────────────────────────
        String code = blankToNull(stringify(rec.get("product_code")));
        if (code == null && !prof.allowMissingCode) {
            return "ไม่มีรหัสสินค้า";
        }

        List<String> codes = code != null
            // split_column (Bode: 2 codes in one cell)
            ? splitCodes(code, prof.splitColumn.get("code"))
            // no code column at all — synthesise a stable one (see surrogateCode)
            : List.of(surrogateCode(sheetName, rec, sizeRaw));

        // ── unit ──────────────────────────────────────────────────────────────
        String unit = unitOverride != null
            ? unitOverride
            : canonUnit(stringify(rec.get("unit")), prof.defaults.get("unit"));

        // ── currency ──────────────────────────────────────────────────────────
        String cur = stringify(rec.get("currency"));
        if (cur == null || cur.isBlank()) cur = prof.defaults.get("currency");
        if (cur != null) cur = cur.strip().toUpperCase();
        if (cur != null && cur.length() > 3) cur = cur.substring(0, 3);

        // ── box data + reconciliation ────────────────────────────────────────────
        // Owner ruling (catalogue accuracy, 2026-09): reconcile m²/box ÷ pcs/box against the
        // parsed WIDTH×HEIGHT wherever both exist, so a wrong declared unit or a corrupt cell is
        // CAUGHT here, at import, rather than silently priced downstream. per_linear_m rows are
        // excluded from this comparison entirely — their "sqm_per_box" column holds LINEAR METRES,
        // not area (V153's own finding on ~1,500+ real rows), so comparing it to width×height would
        // be comparing two different physical quantities, not a real disagreement.
        BigDecimal pcs    = toDecimal(rec.get("pcs_per_box"), fmt);
        BigDecimal sqmBox = toDecimal(rec.get("sqm_per_box"), fmt);
        BigDecimal sqmFromBox = (pcs != null && sqmBox != null && pcs.compareTo(BigDecimal.ZERO) > 0)
            ? sqmBox.divide(pcs, 6, RoundingMode.HALF_UP)
            : null;

        BigDecimal areaFromDims = (dims[0] != null && dims[1] != null
                && dims[0].compareTo(BigDecimal.ZERO) > 0 && dims[1].compareTo(BigDecimal.ZERO) > 0)
            ? dims[0].multiply(dims[1]).divide(SQ_MM_PER_SQM, 6, RoundingMode.HALF_UP)
            : null;

        BigDecimal sqmPerPiece   = null;
        BigDecimal sqmPerLinearM = null;
        String     sqmProvenance;
        String     quarantineReason = null;

        if ("per_linear_m".equals(unit)) {
            sqmProvenance = "linear_metre_not_area";
            if (dims[0] != null && dims[1] != null
                    && dims[0].compareTo(BigDecimal.ZERO) > 0 && dims[1].compareTo(BigDecimal.ZERO) > 0) {
                // Profile height = the SHORTER parsed dimension (V153's own derivation, mirrored
                // here so a freshly-imported per_linear_m row is priceable immediately instead of
                // only after a one-off SQL backfill).
                sqmPerLinearM = dims[0].min(dims[1]).divide(MM_PER_M, 6, RoundingMode.HALF_UP);
            }
            // sqmPerPiece deliberately stays null — see PriceRow#sqmProvenance's javadoc. Never
            // written from sqmFromBox for this unit: that figure is linear metres, not area, and
            // nothing downstream may be able to read it back as one.
        } else if (sqmFromBox != null && areaFromDims != null) {
            BigDecimal diffPct = sqmFromBox.subtract(areaFromDims).abs()
                .divide(areaFromDims, 6, RoundingMode.HALF_UP);
            if (diffPct.compareTo(SQM_TOLERANCE) <= 0) {
                sqmPerPiece   = sqmFromBox;
                sqmProvenance = "box_reconciled";
            } else {
                sqmProvenance = "mismatch_quarantined";
                quarantineReason = String.format(Locale.ROOT,
                    "ขนาดกับข้อมูลกล่องไม่ตรงกัน: จากขนาด %s ตร.ม./ชิ้น, จากกล่อง (m²/box ÷ pcs/box) %s "
                        + "ตร.ม./ชิ้น (ต่างกัน %.1f%% เกินเกณฑ์ %.0f%%)",
                    areaFromDims.toPlainString(), sqmFromBox.toPlainString(),
                    diffPct.movePointRight(2), SQM_TOLERANCE.movePointRight(2));
            }
        } else if (sqmFromBox != null) {
            // No parsed dimensions to check the box figure against — accept it, but do not claim
            // it was reconciled.
            sqmPerPiece   = sqmFromBox;
            sqmProvenance = "box_only_no_dims";
        } else if (areaFromDims != null) {
            // Bode's shape: no box columns at all — import on the declared unit, unreconciled.
            sqmPerPiece   = areaFromDims;
            sqmProvenance = "computed_from_dimensions";
        } else {
            sqmProvenance = "unavailable";
        }

        // Owner ruling (Padana, 2026-09-12, verbatim "ใช้คอลัมน์ Spessore"): a dedicated thickness
        // COLUMN wins over a size-string-embedded token when a row carries both — see
        // #resolveThicknessFromColumn's own Javadoc for the measured data behind this. Every other
        // profile in production uses exactly one of the two sources, so this ordering changes
        // nothing for them.
        BigDecimal thicknessFromColumn = resolveThicknessFromColumn(rec.get("thickness_mm"), fmt, thicknessUnit);
        BigDecimal thickness = thicknessFromColumn != null ? thicknessFromColumn : dims[2];

        // ── attributes (barcode, …) ───────────────────────────────────────────
        Map<String, String> attributes = null;
        String barcode = blankToNull(stringify(rec.get("barcode")));
        if (barcode != null) {
            attributes = Map.of("barcode", barcode);
        }

        // emit one row per code (Bode split) — quarantined rows are STILL emitted (and therefore
        // still staged) so the operator sees them with a reason, rather than a row disappearing
        // the way a hard "ไม่มีราคา"-style error does.
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
                sqmPerPiece, pcs, sqmBox,
                toDecimal(rec.get("kg_per_box"), fmt),
                priceVariants, attributes,
                sheetName, sourceRow,
                sizeUnit, sqmProvenance, sqmPerLinearM, quarantineReason,
                thicknessUnit
            ));
            if (quarantineReason != null) {
                quarantined.add(new ImportResult.QuarantinedRow(sheetName, sourceRow, c, quarantineReason));
            }
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

    /**
     * Parses a free-text size cell into {@code [widthMm, heightMm, thicknessMm]}.
     *
     * <p><b>{@code sizeUnit} is REQUIRED and never guessed</b> — see {@link ImportProfile#sizeUnit}.
     * Width and height are converted to millimetres from the DECLARED unit only; there is no
     * magnitude-based fallback of any kind.
     *
     * <p><b>Thickness has TWO shapes, only one of which needs {@code thicknessUnit}:</b>
     * <ul>
     *   <li>an explicit "9MM"-style suffix (or the truncated single-M forms Excel's column
     *       clipping produces, "9M"/"9,4M") is SELF-DESCRIBING — the owner confirmed a bare
     *       trailing "M" here is always a clipped "MM", never metres — and is read as millimetres
     *       regardless of {@code thicknessUnit};</li>
     *   <li>a bare, un-suffixed third {@code WxHxT} value ({@code "598X598X18"}) is AMBIGUOUS: Bode
     *       writes it already in millimetres, but the Chinese "2026 GENERAL EXPORT" list writes it
     *       in CENTIMETRES ({@code "60X120X1.0"} = a 9 mm tile — confirmed by that workbook's own
     *       "2CM" tab name for the 20 mm slabs). There is nothing in the two shapes that lets code
     *       tell them apart, so this bare form is converted from the DECLARED {@code
     *       thicknessUnit} — same discipline as width/height and {@code sizeUnit}, never guessed.
     * </ul>
     * {@code thicknessUnit = "none"} (a legitimate declared value, see {@link
     * ImportProfile#thicknessUnit}) suppresses ALL thickness extraction from this string, both
     * shapes — a source with no reliable thickness data gets {@code null}, not a stray number
     * mistaken for one.
     *
     * <p>Cleaning, in order:
     * <ol>
     *   <li>apostrophe-decimal style (Vives: {@code 15'8X31'6} → 15.8×31.6);</li>
     *   <li>strip trailing free-text tokens carrying no digit at all ("MOD", "CORBEL NAVAL",
     *       "S/AD", …) — BEFORE the suffix check below, so a product-code suffix like "MOD" can
     *       never be misread as a clipped millimetre marker (a naive un-anchored scan for "digits
     *       then M" would read the "M" of "MOD" in "60x120 MOD" as a thickness token and pull "120"
     *       out as if it were one; stripping first removes "MOD" entirely, and {@link
     *       #THICKNESS_SUFFIX} is anchored to the end of the string in any case);</li>
     *   <li>extract an explicit millimetre thickness suffix — "9MM", "12MM", and the truncated
     *       single-M forms;</li>
     *   <li>split the remainder on {@code x}/{@code X}/{@code ×} into 2 or 3 clean numeric tokens
     *       (European decimal commas supported: {@code 36,1x57,6}); a bare 3rd token, when no
     *       explicit suffix was found, is thickness, converted per {@code thicknessUnit};</li>
     *   <li>if that split is not clean (e.g. embedded free text: {@code "120X50  h.15"}), fall back
     *       to scanning for up to three numeric runs anywhere in the string, unchanged from the
     *       engine's long-standing behaviour for messy cells — just without the magnitude guess.</li>
     * </ol>
     */
    static BigDecimal[] parseSize(String raw, String style, String sizeUnit, String thicknessUnit) {
        if (raw == null || raw.isBlank()) return new BigDecimal[]{null, null, null};
        if (sizeUnit == null || !VALID_SIZE_UNITS.contains(sizeUnit)) {
            throw new IllegalArgumentException(
                "parseSize requires a declared sizeUnit of \"mm\" or \"cm\", got: " + sizeUnit);
        }
        if (thicknessUnit == null || !VALID_THICKNESS_UNITS.contains(thicknessUnit)) {
            throw new IllegalArgumentException(
                "parseSize requires a declared thicknessUnit of \"mm\", \"cm\" or \"none\", got: " + thicknessUnit);
        }
        boolean noThickness = "none".equals(thicknessUnit);
        String s = raw.strip();
        if ("apostrophe_decimal".equals(style)) {
            s = APOSTROPHE_DECIMAL.matcher(s).replaceAll("$1.$2");
        }

        s = stripTrailingNonNumericTokens(s);

        // Detected/stripped regardless of thicknessUnit -- including "none" -- because the STRIP
        // is what keeps width/height parsing clean (a suffix left glued on would otherwise corrupt
        // the x/X split, or merge into an adjacent number once scanNumbers() drops whitespace).
        // "none" only suppresses ASSIGNING the extracted value to thickness below; the stripping
        // itself is unconditional.
        BigDecimal thicknessFromSuffix = null;
        Matcher tm = THICKNESS_SUFFIX.matcher(s);
        if (tm.find()) {
            thicknessFromSuffix = parseNum(tm.group(1));
            s = s.substring(0, tm.start()).strip();
        }

        List<BigDecimal> nums = null;
        String[] tokens = s.isBlank() ? new String[0] : SEPARATOR.split(s);
        if (tokens.length == 2 || tokens.length == 3) {
            List<BigDecimal> clean = new ArrayList<>(tokens.length);
            boolean ok = true;
            for (String t : tokens) {
                BigDecimal n = parseNum(t.strip());
                if (n == null) { ok = false; break; }
                clean.add(n);
            }
            if (ok) nums = clean;
        }
        if (nums == null) {
            // Messier cell (embedded free text, unexpected token count, …) — same scanning
            // fallback the engine has always used, minus the magnitude guess.
            nums = scanNumbers(s);
        }
        if (nums.isEmpty()) return new BigDecimal[]{null, null, null};

        BigDecimal w = nums.size() > 0 ? toMm(nums.get(0), sizeUnit) : null;
        BigDecimal h = nums.size() > 1 ? toMm(nums.get(1), sizeUnit) : null;
        BigDecimal t;
        if (noThickness) {
            // "none" (Bode/Vives/Equipe): a genuinely thickness-less source. Even a bare 3rd
            // numeric token (Bode's own one-off "598X598X18" anomaly) is dropped here, never
            // treated as thickness -- see ImportProfile#thicknessUnit's Javadoc.
            t = null;
        } else if (thicknessFromSuffix != null) {
            t = thicknessFromSuffix.setScale(2, RoundingMode.HALF_UP);
        } else if (nums.size() > 2) {
            // Bare 3-number form ("598X598X18", "60X120X1.0") -- 3rd value is thickness, but its
            // UNIT is ambiguous (see this method's own Javadoc) -- converted from the DECLARED
            // thicknessUnit, never assumed millimetres.
            t = toThicknessMm(nums.get(2), thicknessUnit);
        } else {
            t = null;
        }
        return new BigDecimal[]{w, h, t};
    }

    static String canonUnit(String u, String defaultUnit) {
        if (u == null || u.isBlank()) return defaultUnit != null ? defaultUnit : "unknown";
        String mapped = UNIT_MAP.get(u.strip().toLowerCase());
        return mapped != null ? mapped : (defaultUnit != null ? defaultUnit : "unknown");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Converts a value already known to be in {@code sizeUnit} to millimetres. Never guesses. */
    private static BigDecimal toMm(BigDecimal value, String sizeUnit) {
        BigDecimal mm = "cm".equals(sizeUnit) ? value.multiply(BigDecimal.TEN) : value;
        return mm.setScale(2, RoundingMode.HALF_UP);
    }

    /** Converts a bare (un-suffixed) thickness value already known to be in {@code thicknessUnit}
     * to millimetres. Never guesses; never called for {@code thicknessUnit = "none"} (callers
     * short-circuit first) or for a self-describing suffixed/"MM"-tagged value, which is already
     * millimetres regardless of the declared unit. */
    private static BigDecimal toThicknessMm(BigDecimal value, String thicknessUnit) {
        BigDecimal mm = "cm".equals(thicknessUnit) ? value.multiply(BigDecimal.TEN) : value;
        return mm.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * A dedicated thickness COLUMN's raw cell value, resolved to millimetres — {@code null} when
     * the cell is blank/unparseable or {@code thicknessUnit} is {@code "none"}.
     *
     * <p>Owner ruling (Padana, 2026-09-12, verbatim <i>"ใช้คอลัมน์ Spessore"</i>): when a row
     * carries BOTH a dedicated thickness column and a size-embedded thickness token, THE COLUMN
     * WINS — see {@code #processRow}'s caller, which only falls back to the size-embedded value
     * ({@code dims[2]}) when this returns {@code null}. Measured on the real Padana file (9,076
     * rows): 2,954 rows carry both (2,882 agree, 72 disagree — nominal vs actual, e.g. an embedded
     * "9MM" against a column "8,3MM"), 3,687 rows have ONLY the column, and ZERO rows have ONLY the
     * embedded token — the column strictly dominates on coverage, so preferring it loses no data.
     *
     * <p>Self-describing first, exactly like the size string: a value already carrying an explicit
     * unit letter (Padana's own {@code "8MM"}/{@code "9MM"} column text, matched by the SAME
     * {@link #THICKNESS_SUFFIX} pattern used on the size string) is read directly as millimetres,
     * regardless of {@code thicknessUnit}. A bare numeric column value (REFIN/CITY/CDE's plain
     * {@code "9"} — already labelled "(mm)" in their own header text — or Panaria's unlabelled
     * {@code "SPESSORE"}) is converted from the DECLARED {@code thicknessUnit}, never guessed.
     */
    private static BigDecimal resolveThicknessFromColumn(Object raw, String fmt, String thicknessUnit) {
        if ("none".equals(thicknessUnit)) return null;
        String s = stringify(raw);
        if (s == null || s.isBlank()) return null;
        Matcher tm = THICKNESS_SUFFIX.matcher(s.strip());
        if (tm.find()) {
            BigDecimal n = parseNum(tm.group(1));
            return n == null ? null : n.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal n = toDecimal(s, fmt);
        return n == null ? null : toThicknessMm(n, thicknessUnit);
    }

    /** Comma-as-decimal aware; {@code null} (not an exception) on anything not a plain number. */
    private static BigDecimal parseNum(String tok) {
        if (tok == null || tok.isBlank()) return null;
        try {
            return new BigDecimal(tok.strip().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Legacy scan: up to 3 numeric runs anywhere in the string, in order. */
    private static List<BigDecimal> scanNumbers(String s) {
        List<BigDecimal> vals = new ArrayList<>();
        Matcher m = NUM_PATTERN.matcher(s.replace(" ", ""));
        while (m.find() && vals.size() < 3) {
            BigDecimal n = parseNum(m.group());
            if (n != null) vals.add(n);
        }
        return vals;
    }

    /**
     * Strips trailing whitespace-separated tokens that carry no digit at all — real trailing junk
     * observed in the owner's price lists ("MOD", "CORBEL NAVAL", "S/AD"). Stops at the first
     * (rightmost) token that DOES contain a digit, so a numeric thickness/size token is never
     * touched.
     */
    private static String stripTrailingNonNumericTokens(String s) {
        String trimmed = s.strip();
        if (trimmed.isEmpty()) return trimmed;
        String[] words = trimmed.split("\\s+");
        int end = words.length;
        while (end > 0 && !containsDigit(words[end - 1])) end--;
        if (end == words.length) return trimmed;
        return String.join(" ", Arrays.copyOfRange(words, 0, end));
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Deterministic surrogate product code for price-list sheets that carry no code column
     * (REFIN {@code Trim-Tiles} and {@code Balneo-Project}, and any future factory whose list
     * identifies products by description alone).
     *
     * <p>Without this the row is stored with {@code product_code}, {@code grade} and
     * {@code surface} all NULL. Because {@code uq_price} is
     * {@code UNIQUE NULLS NOT DISTINCT (version_id, product_code, grade, size_raw, surface)},
     * NULL compares equal to NULL and the key degenerates to {@code (version_id, size_raw)} —
     * so every product sharing a size silently overwrites the others. That collapsed REFIN's
     * Trim-Tiles from 74 rows to 17 and Balneo-Project from 19 to 13, losing distinct products
     * at distinct prices.
     *
     * <p>The code is a pure function of the row's identifying fields, so re-importing the same
     * file always yields the same codes and the incremental-merge step in
     * {@code PriceImportService} keeps matching old rows to new ones.
     */
    private static String surrogateCode(String sheetName, Map<String, Object> rec, String sizeRaw) {
        String key = String.join("|",
            normKeyPart(sheetName),
            normKeyPart(stringify(rec.get("collection"))),
            normKeyPart(stringify(rec.get("product_name"))),
            normKeyPart(sizeRaw).replace(" ", ""),
            normKeyPart(stringify(rec.get("surface")))
        );
        return SURROGATE_PREFIX + sha1Hex(key, SURROGATE_HASH_BYTES);
    }

    /** Case- and whitespace-insensitive, so cosmetic re-formatting does not change the code. */
    private static String normKeyPart(String s) {
        if (s == null) return "";
        return s.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String sha1Hex(String s, int bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes * 2);
            for (int i = 0; i < bytes; i++) sb.append(String.format("%02X", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required but unavailable", e);
        }
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
