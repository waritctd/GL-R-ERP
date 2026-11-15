package th.co.glr.hr.salesboard.receipts;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Reads an Express receipts export (RE / AI / IV) into {@link ParsedReceipt} rows.
 *
 * <p><b>Limitation — read before changing.</b> No real Express export has been supplied yet (spec 2026-11-15 §11), so
 * this reads a DOCUMENTED COLUMN TEMPLATE (plan decision 3), not Express's actual layout. Columns are found by header
 * name through {@link #ALIASES}, matched case-, space- and punctuation-insensitively, on the first row (of the first
 * {@value #HEADER_SCAN_ROWS}) that carries every required column — so a report title above the header is fine. When a
 * real export arrives, the expected change is to that alias table only. {@link #templateCsv} is the downloadable
 * template account fills (or exports into).
 *
 * <ul>
 *   <li>.csv — UTF-8 (a BOM is allowed), comma-separated, RFC 4180 quoting;</li>
 *   <li>.xlsx — the first sheet; real date and number cells are read as such.</li>
 * </ul>
 * Required: เลขที่เอกสาร, วันที่, จำนวนเงิน. Optional: ลูกค้า, อ้างอิง. Dates: ISO {@code yyyy-MM-dd} or Thai-order
 * {@code d/M/yyyy} (a year above 2400 is Buddhist era). Amounts: commas, ฿ and spaces stripped; must be &gt; 0 with at
 * most 2 decimals. A row missing a required value, or with a bad one, is reported by line and skipped — it never
 * blocks the rest of the file. Fully blank rows are ignored.
 */
public final class ReceiptFileParser {
    private ReceiptFileParser() {}

    public record RowError(int line, String message) {}

    public record ParseResult(List<ParsedReceipt> rows, List<RowError> errors) {}

    public enum Column { DOC_NO, DATE, CUSTOMER, AMOUNT, REFERENCE }

    static final int HEADER_SCAN_ROWS = 15;
    static final int MAX_ROWS = 5000;
    static final int MAX_BYTES = 5 * 1024 * 1024;

    /** The template's own header first (also the Thai label used in messages), then accepted aliases. */
    static final Map<Column, List<String>> ALIASES = new EnumMap<>(Map.of(
        Column.DOC_NO, List.of("เลขที่เอกสาร", "doc no", "document no", "doc number", "document number", "เลขที่"),
        Column.DATE, List.of("วันที่", "date", "doc date", "document date", "วันที่เอกสาร", "วันที่รับเงิน"),
        Column.CUSTOMER, List.of("ลูกค้า", "customer", "customer name", "ชื่อลูกค้า"),
        Column.AMOUNT, List.of("จำนวนเงิน", "amount", "ยอดเงิน", "total", "ยอดรับ"),
        Column.REFERENCE, List.of("อ้างอิง", "reference", "ref", "เลขที่อ้างอิง")));

    private static final List<Column> REQUIRED = List.of(Column.DOC_NO, Column.DATE, Column.AMOUNT);
    private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$");

    public static ParseResult parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw bad("ไฟล์ว่างเปล่า");
        }
        if (bytes.length > MAX_BYTES) {
            throw bad("ไฟล์ใหญ่เกิน 5 MB");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT).trim();
        List<List<Object>> table;
        if (name.endsWith(".csv")) {
            table = readCsv(bytes);
        } else if (name.endsWith(".xlsx")) {
            table = readXlsx(bytes);
        } else {
            throw bad("รองรับเฉพาะไฟล์ .xlsx หรือ .csv");
        }
        return map(table);
    }

    /** UTF-8 with a BOM (so Excel opens the Thai header correctly), header row only. */
    public static byte[] templateCsv() {
        String header = String.join(",", List.of(Column.values()).stream().map(c -> ALIASES.get(c).get(0)).toList());
        return ("\uFEFF" + header + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ── mapping ────────────────────────────────────────────────────────────

    private static ParseResult map(List<List<Object>> table) {
        int headerRow = -1;
        Map<Column, Integer> index = null;
        for (int r = 0; r < Math.min(HEADER_SCAN_ROWS, table.size()); r++) {
            Map<Column, Integer> found = headerIndex(table.get(r));
            if (found.keySet().containsAll(REQUIRED)) {
                headerRow = r;
                index = found;
                break;
            }
        }
        if (index == null) {
            throw bad("ไม่พบหัวคอลัมน์ที่ต้องมี: " + String.join(", ",
                REQUIRED.stream().map(c -> ALIASES.get(c).get(0)).toList())
                + " — ดาวน์โหลดแม่แบบเพื่อดูรูปแบบ");
        }
        List<ParsedReceipt> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int r = headerRow + 1; r < table.size(); r++) {
            List<Object> cells = table.get(r);
            if (cells.stream().allMatch(c -> c == null || c.toString().isBlank())) {
                continue;
            }
            if (rows.size() + errors.size() >= MAX_ROWS) {
                throw bad("ไฟล์มีมากกว่า " + MAX_ROWS + " รายการ แบ่งเป็นหลายไฟล์");
            }
            int line = r + 1;
            String docNo = text(cell(cells, index.get(Column.DOC_NO)));
            if (docNo == null) {
                errors.add(new RowError(line, "ไม่มีเลขที่เอกสาร"));
                continue;
            }
            LocalDate date = date(cell(cells, index.get(Column.DATE)));
            if (date == null) {
                errors.add(new RowError(line, "วันที่ไม่ถูกต้อง (" + docNo + ")"));
                continue;
            }
            BigDecimal amount = amount(cell(cells, index.get(Column.AMOUNT)));
            if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
                errors.add(new RowError(line, "จำนวนเงินต้องมากกว่า 0 (" + docNo + ")"));
                continue;
            }
            rows.add(new ParsedReceipt(line, docNo, date, text(cell(cells, index.get(Column.CUSTOMER))),
                amount.setScale(2, java.math.RoundingMode.UNNECESSARY), text(cell(cells, index.get(Column.REFERENCE)))));
        }
        return new ParseResult(rows, errors);
    }

    private static Map<Column, Integer> headerIndex(List<Object> cells) {
        Map<String, Column> lookup = new LinkedHashMap<>();
        ALIASES.forEach((column, names) -> names.forEach(n -> lookup.put(normaliseHeader(n), column)));
        Map<Column, Integer> found = new EnumMap<>(Column.class);
        for (int i = 0; i < cells.size(); i++) {
            Object c = cells.get(i);
            Column column = c == null ? null : lookup.get(normaliseHeader(c.toString()));
            if (column != null) {
                found.putIfAbsent(column, i);
            }
        }
        return found;
    }

    static String normaliseHeader(String header) {
        return header.replace("\uFEFF", "").toLowerCase(Locale.ROOT).replaceAll("[\\s._:#/\\-]", "");
    }

    private static Object cell(List<Object> cells, Integer i) {
        return i == null || i >= cells.size() ? null : cells.get(i);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : value.toString();
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        String s = text(value);
        if (s == null) {
            return null;
        }
        try {
            Matcher iso = ISO.matcher(s);
            if (iso.matches()) {
                return LocalDate.of(year(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
            }
            Matcher dmy = DMY.matcher(s);
            if (dmy.matches()) {
                return LocalDate.of(year(dmy.group(3)), Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    /** A year above 2400 is Buddhist era (2569 → 2026). */
    private static int year(String y) {
        int year = Integer.parseInt(y);
        return year > 2400 ? year - 543 : year;
    }

    private static BigDecimal amount(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        String s = text(value);
        if (s == null) {
            return null;
        }
        s = s.replace(",", "").replace("฿", "").replace("บาท", "").replaceAll("\\s", "");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── readers ────────────────────────────────────────────────────────────

    /** RFC 4180: commas, double-quoted fields with "" escapes and embedded newlines, CRLF or LF. */
    static List<List<Object>> readCsv(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        List<List<Object>> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<List<Object>> readXlsx(byte[] bytes) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) {
                throw bad("ไฟล์ไม่มีชีต");
            }
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<Object>> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<Object> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        cells.add(value(row.getCell(c), formatter));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("อ่านไฟล์ .xlsx ไม่ได้");
        }
    }

    private static Object value(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell);
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
