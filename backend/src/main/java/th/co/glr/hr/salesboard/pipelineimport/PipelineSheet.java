package th.co.glr.hr.salesboard.pipelineimport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Reads the one-time pipeline template — the FIRST sheet of an .xlsx/.xls, or a UTF-8 .csv (BOM optional) — into
 * rows keyed by {@link Column}. The header row is the first non-blank row; headers match case- and
 * space-insensitively, ignoring anything in parentheses and '%', against the Thai template labels and a few English
 * aliases. Blank rows are dropped; row numbers are the sheet's own (header = 1 on a template).
 */
final class PipelineSheet {

    enum Column { REP, PROJECT, DESIGNER, OWNER, BUYER, QUOTATION_NO, VALUE, STATUS, PROBABILITY, QUARTER, NOTE }

    /** The template's header row, in order (plan 2026-11-15 Task 4.1). */
    static final List<String> TEMPLATE_HEADERS = List.of(
        "พนักงานขาย (รหัส/อีเมล)", "โครงการ", "ผู้ออกแบบ", "เจ้าของโครงการ", "ผู้ซื้อ", "เลขที่ใบเสนอราคา", "มูลค่า",
        "สถานะ (S1–S9)", "โอกาสขาย %", "ช่วงเวลา (เช่น 2026-Q4)", "หมายเหตุ");

    private static final Map<String, Column> ALIASES = aliases();

    private static Map<String, Column> aliases() {
        Map<String, Column> m = new LinkedHashMap<>();
        Column[] order = Column.values();
        for (int i = 0; i < order.length; i++) {
            m.put(headerKey(TEMPLATE_HEADERS.get(i)), order[i]);
        }
        put(m, Column.REP, "พนักงานขาย", "เซลส์", "sales", "rep", "salesrep", "อีเมล", "email", "รหัสพนักงาน");
        put(m, Column.PROJECT, "ชื่อโครงการ", "project", "projectname");
        put(m, Column.DESIGNER, "designer");
        put(m, Column.OWNER, "owner", "projectowner");
        put(m, Column.BUYER, "buyer", "ผู้รับเหมา", "contractor");
        put(m, Column.QUOTATION_NO, "เลขที่qt", "quotationno", "quotation", "qtno", "qt", "qn", "เลขที่อ้างอิง");
        put(m, Column.VALUE, "value", "amount", "มูลค่าบาท", "มูลค่าใบเสนอราคา");
        put(m, Column.STATUS, "สถานะ", "status", "stage", "สถานะงานขาย");
        put(m, Column.PROBABILITY, "โอกาสขาย", "probability", "winprobability", "จะขายได้", "โอกาส");
        put(m, Column.QUARTER, "ช่วงเวลา", "ไตรมาส", "quarter", "expectedquarter");
        put(m, Column.NOTE, "note", "notes", "remark");
        return Map.copyOf(m);
    }

    private static void put(Map<String, Column> m, Column column, String... names) {
        for (String n : names) {
            m.put(headerKey(n), column);
        }
    }

    /** Lower-case, no parentheses content, no '%', no whitespace, '.'/'_'/'-' dropped. */
    static String headerKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\([^)]*\\)", "").replaceAll("[%\\s._\\-–]", "").toLowerCase(Locale.ROOT);
    }

    /** One cell: {@code text} as displayed; {@code numeric} for a numeric (non-date) spreadsheet cell; {@code percent}
     * when its format is a percentage; {@code date} for a date-formatted cell. CSV cells carry text only. */
    record SheetCell(String text, Double numeric, boolean percent, LocalDate date) {
        static final SheetCell EMPTY = new SheetCell(null, null, false, null);

        boolean blank() {
            return numeric == null && date == null && (text == null || text.isBlank());
        }
    }

    record SheetRow(int rowNumber, Map<Column, SheetCell> cells) {
        SheetCell cell(Column column) {
            return cells.getOrDefault(column, SheetCell.EMPTY);
        }

        /** Trimmed display text, or null when blank. */
        String text(Column column) {
            return PipelineFields.text(cell(column).text());
        }
    }

    /** The rows, and the header labels no alias matched (their columns are ignored — reported as a file warning). */
    record ParsedSheet(List<SheetRow> rows, List<String> unknownHeaders) {}

    static List<SheetRow> read(String filename, byte[] bytes, int maxRows) {
        return readSheet(filename, bytes, maxRows).rows();
    }

    static ParsedSheet readSheet(String filename, byte[] bytes, int maxRows) {
        if (bytes == null || bytes.length == 0) {
            throw bad("ไฟล์ว่างเปล่า");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<List<SheetCell>> grid;
        if (name.endsWith(".csv")) {
            grid = csvGrid(bytes);
        } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            grid = workbookGrid(bytes);
        } else {
            throw bad("รองรับเฉพาะไฟล์ .xlsx หรือ .csv (UTF-8) — ใช้แม่แบบ");
        }
        return toRows(grid, maxRows);
    }

    private static ParsedSheet toRows(List<List<SheetCell>> grid, int maxRows) {
        int headerIndex = -1;
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i).stream().anyMatch(c -> !c.blank())) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            throw bad("ไฟล์ไม่มีข้อมูล");
        }
        Map<Integer, Column> columnAt = new LinkedHashMap<>();
        List<SheetCell> header = grid.get(headerIndex);
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            Column c = ALIASES.get(headerKey(header.get(i).text()));
            if (c != null && !columnAt.containsValue(c)) {
                columnAt.put(i, c);
            } else if (!header.get(i).blank()) {
                unknown.add(header.get(i).text().strip());
            }
        }
        List<String> missing = new ArrayList<>();
        for (Column required : List.of(Column.REP, Column.PROJECT, Column.STATUS)) {
            if (!columnAt.containsValue(required)) {
                missing.add(TEMPLATE_HEADERS.get(required.ordinal()));
            }
        }
        if (!missing.isEmpty()) {
            throw bad("ไม่พบคอลัมน์ " + String.join(", ", missing) + " ในแถวหัวตาราง — ใช้แม่แบบ");
        }
        List<SheetRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < grid.size(); i++) {
            List<SheetCell> line = grid.get(i);
            Map<Column, SheetCell> cells = new EnumMap<>(Column.class);
            for (Map.Entry<Integer, Column> e : columnAt.entrySet()) {
                if (e.getKey() < line.size()) {
                    cells.put(e.getValue(), line.get(e.getKey()));
                }
            }
            if (cells.values().stream().allMatch(SheetCell::blank)) {
                continue;
            }
            if (rows.size() >= maxRows) {
                throw bad("ไฟล์มีเกิน " + maxRows + " แถว — แบ่งเป็นหลายไฟล์");
            }
            rows.add(new SheetRow(i + 1, cells));
        }
        return new ParsedSheet(rows, List.copyOf(unknown));
    }

    // ── xlsx / xls ───────────────────────────────────────────────────────────

    private static List<List<SheetCell>> workbookGrid(byte[] bytes) {
        DataFormatter formatter = new DataFormatter(Locale.US);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) {
                throw bad("ไฟล์ไม่มีชีต");
            }
            Sheet sheet = wb.getSheetAt(0);
            List<List<SheetCell>> grid = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                List<SheetCell> line = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        line.add(cell(row.getCell(c), formatter));
                    }
                }
                grid.add(line);
            }
            return grid;
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw bad("อ่านไฟล์ Excel ไม่ได้ — บันทึกเป็น .xlsx จากแม่แบบแล้วลองใหม่");
        }
    }

    private static SheetCell cell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return SheetCell.EMPTY;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String text = formatter.formatCellValue(cell);
        if (cell.getCellType() == CellType.FORMULA) {
            text = type == CellType.NUMERIC ? null : cell.getStringCellValue();
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return new SheetCell(text, null, false, cell.getLocalDateTimeCellValue().toLocalDate());
            }
            String format = cell.getCellStyle() == null ? "" : cell.getCellStyle().getDataFormatString();
            double v = cell.getNumericCellValue();
            return new SheetCell(text == null ? String.valueOf(v) : text, v, format != null && format.contains("%"),
                null);
        }
        if (type == CellType.BOOLEAN) {
            return new SheetCell(String.valueOf(cell.getBooleanCellValue()), null, false, null);
        }
        if (type == CellType.STRING) {
            return new SheetCell(cell.getCellType() == CellType.FORMULA ? text : cell.getStringCellValue(), null,
                false, null);
        }
        return SheetCell.EMPTY;
    }

    // ── csv ──────────────────────────────────────────────────────────────────

    private static List<List<SheetCell>> csvGrid(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw bad("ไฟล์ CSV ต้องบันทึกเป็น UTF-8 (CSV UTF-8 ใน Excel)");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<SheetCell>> grid = new ArrayList<>();
        List<SheetCell> line = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
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
                line.add(textCell(field));
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                line.add(textCell(field));
                field.setLength(0);
                grid.add(line);
                line = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !line.isEmpty()) {
            line.add(textCell(field));
            grid.add(line);
        }
        return grid;
    }

    private static SheetCell textCell(StringBuilder field) {
        return new SheetCell(field.toString(), null, false, null);
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private PipelineSheet() {}
}
