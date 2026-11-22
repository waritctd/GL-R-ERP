package th.co.glr.hr.importrequest;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.importrequest.ImportSheetModels.ImportSheetException;
import th.co.glr.hr.importrequest.ImportSheetModels.ParsedSheet;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetBlock;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetFacts;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetIr;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetLine;

/**
 * Reads the import team's hand kept sheet into shipment blocks (spec 0001, "Sheet to portal mapping"). Only the columns
 * named below are ever touched, so the five cost columns are never read. A block is a run of non blank rows; a row with an
 * IR number starts an IR and a row without one adds a line to the IR above it.
 */
final class ImportSheetParser {

    private static final String IR_NO = "IR NO.";
    private static final String RECEIVED = "วันที่ได้รับ IR";
    private static final String DEPOSIT = "วันที่ได้รับมัดจำ";
    private static final String ORDERED = "วันที่สั่งของ";
    private static final String BRAND = "ยี่ห้อ";
    private static final String SALE = "Sale";
    private static final String PROJECT = "PROJECT";
    private static final String ITEMS = "รายชื่อสินค้าและจำนวน";
    private static final String PROFORMA = "Proforma invoice no";
    private static final String INVOICE = "Invoice No.";
    private static final String READY = "วันที่ตกลงกันว่าของพร้อม";
    private static final String VESSEL = "ชื่อพาหนะ";
    private static final String ETD = "วันที่คาดว่าเรือลำแรกออก ETD";
    private static final String ATD = "วันที่เรือลำแรกออกจริง";
    private static final String TRANSSHIP = "ETA SINGAPORE/ เรือลำสอง";
    private static final String INSURED = "ทำประกัน";
    private static final String ETA = "วันที่คาดว่าจะถึง ETA bkk";
    private static final String ARRIVED = "วันที่ของถึงจริง";
    private static final String RELEASED = "วันที่ออกของ";
    private static final String GR = "สินค้าเข้าตรงตามInvoice(เลขที่ใบตรวจรับ)";
    private static final String WANTED = "กำหนดการที่ลูกค้าต้องการของ";
    private static final String REMARK = "หมายเหตุ";

    static final List<String> REQUIRED_HEADERS = List.of(IR_NO, RECEIVED, DEPOSIT, ORDERED, BRAND, SALE, PROJECT, ITEMS,
        PROFORMA, INVOICE, READY, VESSEL, ETD, ATD, TRANSSHIP, INSURED, ETA, ARRIVED, RELEASED, GR, WANTED, REMARK);

    private static final int MAX_ROWS = 5000;
    private static final int NOTE_LIMIT = 1000;
    private static final Pattern IR_NUMBER = Pattern.compile("^\\d{5,6}(-\\d{1,2})?$");
    private static final Pattern DATE_TOKEN = Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})/(\\d{2,4})(?!\\d)");
    private static final Pattern GR_NUMBER = Pattern.compile("GR-\\d{4}-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_AFTER_EQUALS = Pattern.compile("^\\s*([\\d,]+(?:\\.\\d+)?)\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern QTY_AT_END = Pattern.compile("^(.+?)\\s+([\\d,]+(?:\\.\\d+)?)\\s+([^\\d\\s=]+)\\s*$");
    private static final Pattern FLIGHT = Pattern.compile("\\b[A-Za-z]{2}\\d{3,4}\\b");
    private static final Pattern TRACKING = Pattern.compile("^\\d{10,}$");
    private static final Pattern AIR_WORD = Pattern.compile("\\b(fedex|dhl|air)\\b|แอร์", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANNER = Pattern.compile("^(january|february|march|april|may|june|july|august|september|october|november|december"
        + "|มกราคม|กุมภาพันธ์|มีนาคม|เมษายน|พฤษภาคม|มิถุนายน|กรกฎาคม|สิงหาคม|กันยายน|ตุลาคม|พฤศจิกายน|ธันวาคม)(\\s|\\(|$).*",
        Pattern.CASE_INSENSITIVE);
    private static final List<String> PORTS = List.of("แหลมฉบัง", "คลองเตย");
    private static final List<String> NOT_COMPLETE = List.of("แตก", "ตำหนิ", "ขาด", "ไม่ครบ");

    private final LocalDate today;
    private final Map<Long, Cell> merged = new HashMap<>();
    private Map<String, Integer> columns;

    private ImportSheetParser(LocalDate today) {
        this.today = today;
    }

    static ParsedSheet parse(InputStream in, LocalDate today) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            return new ImportSheetParser(today).read(workbook);
        }
    }

    private ParsedSheet read(Workbook workbook) {
        Sheet sheet = findSheet(workbook);
        Row header = sheet.getRow(0);
        columns = headerIndex(header);
        List<String> missing = REQUIRED_HEADERS.stream().filter(h -> !columns.containsKey(h)).toList();
        if (!missing.isEmpty()) {
            throw new ImportSheetException("ไม่พบหัวคอลัมน์: " + String.join(", ", missing));
        }
        if (sheet.getLastRowNum() > MAX_ROWS) {
            throw new ImportSheetException("ไฟล์มีเกิน " + MAX_ROWS + " แถว");
        }
        indexMergedCells(sheet);

        List<List<Row>> runs = new ArrayList<>();
        List<Row> run = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isBlank(row) || isBanner(row)) {
                if (!run.isEmpty()) {
                    runs.add(run);
                    run = new ArrayList<>();
                }
            } else {
                run.add(row);
            }
        }
        if (!run.isEmpty()) {
            runs.add(run);
        }

        Map<String, Integer> keyCounts = new HashMap<>();
        List<SheetBlock> blocks = new ArrayList<>();
        for (List<Row> rows : runs) {
            blocks.add(readBlock(rows, keyCounts));
        }
        return new ParsedSheet(sheet.getSheetName(), sheet.getLastRowNum() + 1, blocks);
    }

    private static Sheet findSheet(Workbook workbook) {
        for (Sheet sheet : workbook) {
            Row first = sheet.getRow(0);
            if (first == null) {
                continue;
            }
            for (Cell c : first) {
                if (c.getCellType() == CellType.STRING && IR_NO.equals(clean(c.getStringCellValue()))) {
                    return sheet;
                }
            }
        }
        throw new ImportSheetException("ไม่พบแท็บที่มีหัวคอลัมน์ " + IR_NO);
    }

    private static Map<String, Integer> headerIndex(Row header) {
        Map<String, Integer> index = new HashMap<>();
        for (Cell c : header) {
            if (c.getCellType() == CellType.STRING) {
                String name = clean(c.getStringCellValue());
                if (REQUIRED_HEADERS.contains(name)) {
                    index.putIfAbsent(name, c.getColumnIndex());
                }
            }
        }
        return index;
    }

    private void indexMergedCells(Sheet sheet) {
        Set<Integer> wanted = Set.copyOf(columns.values());
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            Row top = sheet.getRow(range.getFirstRow());
            Cell source = top == null ? null : top.getCell(range.getFirstColumn());
            if (source == null) {
                continue;
            }
            for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                    if (wanted.contains(c)) {
                        merged.put(key(r, c), source);
                    }
                }
            }
        }
    }

    private static long key(int row, int column) {
        return ((long) row << 16) | column;
    }

    /** Blankness looks at the cells themselves, so a merged value never glues two blocks across a separator row. */
    private boolean isBlank(Row row) {
        for (int col : columns.values()) {
            if (value(row.getCell(col)) != null) {
                return false;
            }
        }
        return true;
    }

    private boolean isBanner(Row row) {
        String only = null;
        for (int col : columns.values()) {
            Object v = value(row.getCell(col));
            if (v != null) {
                if (only != null) {
                    return false;
                }
                only = String.valueOf(v);
            }
        }
        return only != null && BANNER.matcher(only).matches();
    }

    /** The value of a column on a row; a merged cell's value applies to every row it spans. */
    private Object at(Row row, String header) {
        int col = columns.get(header);
        Cell own = row.getCell(col);
        Object v = value(own);
        if (v != null) {
            return v;
        }
        return value(merged.get(key(row.getRowNum(), col)));
    }

    private String text(Row row, String header) {
        Object v = at(row, header);
        return v == null ? null : asText(v);
    }

    private static String asText(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    private static Object value(Cell c) {
        if (c == null) {
            return null;
        }
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        switch (type) {
            case STRING: {
                String s = cleanKeepingLines(c.getStringCellValue());
                return s.isEmpty() ? null : s;
            }
            case NUMERIC:
                return DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toLocalDate()
                    : BigDecimal.valueOf(c.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(c.getBooleanCellValue());
            default:
                return null;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String cleanKeepingLines(String s) {
        List<String> lines = new ArrayList<>();
        for (String line : s.replace(' ', ' ').split("\\R")) {
            String c = line.replaceAll("[ \\t]+", " ").trim();
            if (!c.isEmpty()) {
                lines.add(c);
            }
        }
        return String.join("\n", lines);
    }

    // ── one block ───────────────────────────────────────────────────────────────────────────────────────

    private final class DateReader {
        final List<String> warnings;
        final List<String> notes;
        final Set<String> ports;

        DateReader(List<String> warnings, List<String> notes, Set<String> ports) {
            this.warnings = warnings;
            this.notes = notes;
            this.ports = ports;
        }

        /** A real date as it is; a text cell by its first d/m/y token. Unreadable text goes to the round's note. */
        LocalDate read(Row row, String header) {
            Object v = at(row, header);
            if (v == null) {
                return null;
            }
            if (v instanceof LocalDate d) {
                return d;
            }
            String raw = asText(v);
            String port = PORTS.stream().filter(raw::contains).findFirst().orElse(null);
            if (port != null) {
                ports.add(port);
            }
            LocalDate parsed = parseDateText(raw, today);
            if (parsed == null && port == null) {
                warnings.add("แถว " + (row.getRowNum() + 1) + ": อ่านวันที่ไม่ได้ (" + header + ")");
                String entry = header + ": " + clean(raw);
                if (!notes.contains(entry)) {
                    notes.add(entry);
                }
            }
            return parsed;
        }
    }

    /** The first d/m/y token. Two digit years are 20yy, a Buddhist year loses 543, and anything over two years from today is refused. */
    static LocalDate parseDateText(String raw, LocalDate today) {
        Matcher m = DATE_TOKEN.matcher(raw);
        if (!m.find()) {
            return null;
        }
        int day = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int year = Integer.parseInt(m.group(3));
        if (year < 100) {
            year += 2000;
        } else if (year > 2400) {
            year -= 543;
        }
        try {
            LocalDate d = LocalDate.of(year, month, day);
            return d.isBefore(today.minusYears(2)) || d.isAfter(today.plusYears(2)) ? null : d;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private SheetBlock readBlock(List<Row> rows, Map<String, Integer> keyCounts) {
        int firstRow = rows.get(0).getRowNum() + 1;
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> ports = new LinkedHashSet<>();
        DateReader dates = new DateReader(warnings, notes, ports);
        String blocked = null;

        Map<String, IrBuilder> irs = new LinkedHashMap<>();
        IrBuilder current = null;
        Set<String> brands = new LinkedHashSet<>();
        Set<String> proformas = new LinkedHashSet<>();
        Set<String> invoices = new LinkedHashSet<>();
        List<String> grTexts = new ArrayList<>();
        List<String> remarks = new ArrayList<>();
        Map<String, LocalDate> firstDates = new HashMap<>();
        String vessel = null;

        for (Row row : rows) {
            String no = row.getCell(columns.get(IR_NO)) == null ? null : text(row, IR_NO);
            if (no != null) {
                String normal = no.trim().toUpperCase(Locale.ROOT).replaceFirst("^IR", "").trim();
                if (!IR_NUMBER.matcher(normal).matches()) {
                    blocked = "เลข \"" + no + "\" ไม่ใช่รูปแบบเลขใบขอซื้อ (แถว " + (row.getRowNum() + 1) + ")";
                    current = null;
                } else {
                    String doc = "IR" + normal;
                    current = irs.computeIfAbsent(doc, d -> new IrBuilder(d, row.getRowNum() + 1));
                }
            }
            String brand = text(row, BRAND);
            if (brand != null && (no != null || brands.isEmpty())) {
                brands.add(clean(brand));
            }
            splitInto(proformas, text(row, PROFORMA));
            splitInto(invoices, text(row, INVOICE));
            if (vessel == null) {
                String v = text(row, VESSEL);
                vessel = v == null ? null : clean(v);
            }
            String gr = text(row, GR);
            if (gr != null && !grTexts.contains(clean(gr))) {
                grTexts.add(clean(gr));
            }
            String remark = text(row, REMARK);
            if (remark != null && !remarks.contains(clean(remark))) {
                remarks.add(clean(remark));
            }
            for (String h : List.of(ORDERED, READY, ETD, ATD, TRANSSHIP, INSURED, ETA, ARRIVED, RELEASED)) {
                LocalDate d = dates.read(row, h);
                if (d != null) {
                    firstDates.putIfAbsent(h, d);
                }
            }
            if (current != null) {
                current.absorb(row, no != null, dates);
            }
        }
        if (irs.isEmpty() && blocked == null) {
            blocked = "ไม่มีเลขใบขอซื้อในกลุ่มแถวนี้";
        }

        List<SheetIr> built = new ArrayList<>();
        String firstProject = irs.values().stream().map(b -> b.project).filter(p -> p != null).findFirst().orElse(null);
        for (IrBuilder b : irs.values()) {
            built.add(b.build(firstProject, firstRow, warnings));
        }
        built.forEach(ir -> ir.lines().stream().filter(l -> l.warning() != null)
            .forEach(l -> warnings.add(ir.docNumber() + ": " + l.warning())));

        SheetFacts facts = facts(firstDates, built, vessel, ports, proformas, grTexts, remarks, notes, warnings);
        String firstDoc = built.isEmpty() ? "row" + firstRow : built.get(0).docNumber();
        int count = keyCounts.merge(firstDoc, 1, Integer::sum);
        return new SheetBlock(firstRow, firstDoc + "#" + count, List.copyOf(brands), facts, List.copyOf(proformas),
            List.copyOf(invoices), built, warnings, blocked);
    }

    private static void splitInto(Set<String> out, String raw) {
        if (raw != null) {
            for (String part : raw.split("\\R")) {
                String c = clean(part);
                if (!c.isEmpty()) {
                    out.add(c.length() > 100 ? c.substring(0, 100) : c);
                }
            }
        }
    }

    private SheetFacts facts(Map<String, LocalDate> d, List<SheetIr> irs, String vessel, Set<String> ports, Set<String> proformas,
                             List<String> grTexts, List<String> remarks, List<String> notes, List<String> warnings) {
        String grText = String.join(" | ", grTexts);
        Matcher grMatch = GR_NUMBER.matcher(grText);
        String grNumber = null;
        String completeNote = grText.isEmpty() ? null : grText;
        if (grMatch.find()) {
            grNumber = grMatch.group().toUpperCase(Locale.ROOT);
            String rest = (grText.substring(0, grMatch.start()) + grText.substring(grMatch.end()))
                .replaceAll("^[\\s|]+|[\\s|]+$", "").replaceAll("\\s+\\|\\s+\\|\\s+", " | ");
            completeNote = rest.isEmpty() ? null : rest;
        } else {
            // a remark can only mention a GR number (for example a cancelled one): it counts when a customs or arrival date backs it
            Matcher inRemark = GR_NUMBER.matcher(String.join(" ", remarks));
            if ((d.get(RELEASED) != null || d.get(ARRIVED) != null) && inRemark.find()) {
                grNumber = inRemark.group().toUpperCase(Locale.ROOT);
            }
        }
        Boolean complete = grText.contains("ครบ") && NOT_COMPLETE.stream().noneMatch(grText::contains) ? Boolean.TRUE : null;

        LocalDate irReceived = irs.stream().map(SheetIr::receivedOn).filter(x -> x != null).min(LocalDate::compareTo).orElse(null);
        LocalDate orderedDate = d.get(ORDERED);
        LocalDate released = d.get(RELEASED);
        LocalDate arrived = d.get(ARRIVED);
        LocalDate atd = d.get(ATD);
        LocalDate etd = d.get(ETD);
        LocalDate eta = d.get(ETA);

        String step;
        if (grNumber != null || released != null) {
            step = ImportRequestStep.RECEIVED;
        } else if (arrived != null) {
            step = ImportRequestStep.AWAITING_CUSTOMS;
        } else if (atd != null) {
            step = ImportRequestStep.IN_TRANSIT;
        } else if (orderedDate != null || !proformas.isEmpty() || etd != null || vessel != null) {
            step = ImportRequestStep.ORDERED;
        } else {
            step = ImportRequestStep.CONTACTED;
        }

        LocalDate receivedOn = null;
        if (ImportRequestStep.RECEIVED.equals(step)) {
            receivedOn = released != null ? released : arrived;
            if (receivedOn == null) {
                receivedOn = java.util.stream.Stream.of(eta, atd, etd, orderedDate, irReceived).filter(x -> x != null)
                    .max(LocalDate::compareTo).orElse(today);
                warnings.add("ไม่มีวันที่ของถึงหรือวันที่ออกของ ใช้วันที่ " + ThaiText.date(receivedOn) + " แทน");
            }
            if (grNumber == null) {
                warnings.add("ไม่มีเลขใบตรวจรับ");
            }
        }

        Map<String, Object> columnsOut = new LinkedHashMap<>();
        put(columnsOut, "vessel", cut(vessel, 200));
        put(columnsOut, "port", ports.isEmpty() ? null : ports.iterator().next());
        put(columnsOut, "goods_ready_on", d.get(READY));
        put(columnsOut, "etd_planned_on", etd);
        put(columnsOut, "etd_actual_on", atd);
        put(columnsOut, "transship_eta_on", d.get(TRANSSHIP));
        put(columnsOut, "insured_on", d.get(INSURED));
        put(columnsOut, "eta_on", eta);
        put(columnsOut, "arrived_on", arrived);
        put(columnsOut, "customs_released_on", released);
        put(columnsOut, "gr_number", grNumber);
        put(columnsOut, "complete_per_invoice", complete);
        put(columnsOut, "complete_note", cut(completeNote, 500));
        List<String> noteParts = new ArrayList<>(remarks);
        noteParts.addAll(notes);
        put(columnsOut, "note", noteParts.isEmpty() ? null : cut(String.join("\n", noteParts), NOTE_LIMIT));
        put(columnsOut, "contacted_on", irReceived);
        boolean pastContacted = !ImportRequestStep.CONTACTED.equals(step);
        put(columnsOut, "ordered_on", pastContacted ? (orderedDate != null ? orderedDate : irReceived) : null);
        put(columnsOut, "in_transit_on", atd);
        put(columnsOut, "awaiting_customs_on", arrived);
        put(columnsOut, "received_on", receivedOn);

        String projects = irs.stream().map(SheetIr::projectText).filter(x -> x != null).reduce("", (a, b) -> a + " " + b);
        return new SheetFacts(transportMode(vessel, projects), step, columnsOut);
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    static String transportMode(String vessel, String projectText) {
        String v = vessel == null ? "" : vessel;
        boolean air = AIR_WORD.matcher(v).find() || FLIGHT.matcher(v).find() || TRACKING.matcher(v).matches()
            || AIR_WORD.matcher(projectText == null ? "" : projectText.replace('_', ' ')).find();
        return air ? "AIR" : "SEA";
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** One line: "name = 12 pcs", else "name 12 pcs", else quantity 1 with a warning. */
    static SheetLine parseLine(String raw) {
        String line = clean(raw);
        int eq = line.lastIndexOf('=');
        if (eq > 0) {
            Matcher m = QTY_AFTER_EQUALS.matcher(line.substring(eq + 1));
            if (m.matches()) {
                BigDecimal qty = quantity(m.group(1));
                if (qty != null) {
                    String rest = m.group(2).trim();
                    int space = rest.indexOf(' ');
                    String unit = space < 0 ? rest : rest.substring(0, space);
                    String note = space < 0 ? null : rest.substring(space + 1).trim();
                    return line(line.substring(0, eq).trim(), qty, unit, note);
                }
            }
        }
        Matcher end = QTY_AT_END.matcher(line);
        if (end.matches()) {
            BigDecimal qty = quantity(end.group(2));
            if (qty != null) {
                return line(end.group(1).trim(), qty, end.group(3), null);
            }
        }
        return new SheetLine(cut(line, 255), BigDecimal.ONE, "รายการ", "ไม่มีจำนวนในตาราง", "อ่านจำนวนไม่ได้: " + cut(line, 60));
    }

    private static SheetLine line(String code, BigDecimal qty, String unit, String note) {
        String u = unit.replaceAll("\\.+$", "");
        return new SheetLine(cut(code.isEmpty() ? "ไม่ระบุสินค้า" : code, 255), qty, cut(u.isEmpty() ? "รายการ" : u, 30),
            note == null || note.isEmpty() ? null : cut(note, 255), null);
    }

    private static BigDecimal quantity(String raw) {
        try {
            BigDecimal q = new BigDecimal(raw.replace(",", "")).stripTrailingZeros();
            if (q.signum() <= 0 || q.scale() > 2 || q.compareTo(new BigDecimal("9999999999.99")) > 0) {
                return null;
            }
            return q.scale() < 0 ? q.setScale(0) : q;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final class IrBuilder {
        final String doc;
        final int firstRow;
        final List<SheetLine> lines = new ArrayList<>();
        String sale;
        String project;
        LocalDate received;
        LocalDate deposit;
        LocalDate wantedBy;
        String wantedText;

        IrBuilder(String doc, int firstRow) {
            this.doc = doc;
            this.firstRow = firstRow;
        }

        void absorb(Row row, boolean startsIr, DateReader dates) {
            if (sale == null) {
                sale = cleanOrNull(text(row, SALE));
            }
            String rowProject = cleanOrNull(text(row, PROJECT));
            if (startsIr && project == null) {
                project = rowProject;
            }
            if (received == null) {
                received = dates.read(row, RECEIVED);
            }
            if (deposit == null) {
                deposit = dates.read(row, DEPOSIT);
            }
            if (wantedBy == null && wantedText == null) {
                Object w = at(row, WANTED);
                if (w instanceof LocalDate d) {
                    wantedBy = d;
                    wantedText = ThaiText.date(d);
                } else if (w != null) {
                    wantedText = cut(clean(asText(w)), 200);
                    wantedBy = parseDateText(asText(w), today);
                }
            }
            String items = text(row, ITEMS);
            int before = lines.size();
            if (items != null) {
                for (String part : items.split("\\R")) {
                    if (!clean(part).isEmpty()) {
                        lines.add(parseLine(part));
                    }
                }
            }
            if (!startsIr && rowProject != null && !lines.isEmpty()) {
                int at = lines.size() > before ? before : lines.size() - 1;
                SheetLine l = lines.get(at);
                String note = l.note() == null ? rowProject : l.note() + " | " + rowProject;
                lines.set(at, new SheetLine(l.code(), l.qty(), l.unit(), cut(note, 255), l.warning()));
            }
        }

        SheetIr build(String blockProject, int blockFirstRow, List<String> warnings) {
            String p = project != null ? project : blockProject;
            if (p == null) {
                p = "ตารางของเข้า แถว " + blockFirstRow;
                warnings.add(doc + ": ไม่มีชื่อโครงการ");
            }
            if (lines.isEmpty()) {
                lines.add(new SheetLine("ไม่ระบุสินค้า", BigDecimal.ONE, "รายการ", "ไม่มีจำนวนในตาราง", "ไม่มีรายการสินค้า"));
            }
            return new SheetIr(doc, firstRow, sale, cut(p, 255), received, deposit, wantedBy, wantedText, List.copyOf(lines));
        }

        private String cleanOrNull(String s) {
            if (s == null) {
                return null;
            }
            String c = clean(s);
            return c.isEmpty() ? null : c;
        }
    }
}
