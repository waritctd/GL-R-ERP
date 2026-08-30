package th.co.glr.hr.attendance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDayFlag;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;
import th.co.glr.hr.attendance.daily.EmployeeDay;
import th.co.glr.hr.common.ThaiText;

/**
 * Builds the monthly attendance summary workbook (xlsx): {@code สรุปรายเดือน} (one row per
 * employee) and {@code รายวัน} (one row per employee per day). Every cell here is copied verbatim
 * from an already-computed {@link AttendanceMonthlySummaryResult} -- no aggregation happens in this
 * class; see {@code AttendanceMonthlySummaryService}'s javadoc for the rules that decided what a
 * cell contains. Follows {@code th.co.glr.hr.payroll.export.PayrollDetailExporter}'s structure (a
 * {@code Column} record of header + extractor, styles built once, {@code ByteArrayOutputStream}) --
 * generalised to a type parameter here because this workbook has two sheets with two different
 * row shapes, where that class only ever had one.
 *
 * <p>Thai text in xlsx needs no embedded font -- that is a PDF concern only ({@code
 * th.co.glr.hr.deposit}/{@code th.co.glr.hr.ticket} renderers), and PDF is explicitly out of scope
 * for this report (owner decision, 2026-08-30: Excel only).
 *
 * <h2>§76</h2>
 * late/early figures are for reporting only -- Thai Labour Protection Act §76 forbids deducting
 * wages as a penalty for lateness or absence (see {@code AttendanceDayFlag}'s own javadoc). This
 * class carries that warning into the workbook itself as a footer line on Sheet 1, so a reader who
 * never opens the source code still sees it before turning a column into a deduction.
 */
@Component
public class AttendanceMonthlySummaryExporter {
    private static final String SUMMARY_SHEET_NAME = "สรุปรายเดือน";
    private static final String DAILY_SHEET_NAME = "รายวัน";
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("d MMM uuuu HH:mm");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** No shared Thai day-of-week label exists elsewhere in the backend (only date formatters, e.g.
     * {@link ThaiText#date}) -- this stays local to Sheet 2's วัน column. Index 0 = Monday, matching
     * {@link java.time.DayOfWeek#getValue()}'s 1-based Monday-first numbering minus one. */
    private static final String[] THAI_WEEKDAY = {
        "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์"
    };

    /**
     * Mirrors {@code frontend/src/utils/format.js#attendanceStatusLabel} -- Sheet 2's สถานะ column
     * and the day-view page's status badge should read as the same vocabulary to an HR user who has
     * both open, so this is a deliberate copy of that map's labels, not an independent wording
     * choice. {@link AttendanceDayStatus#NO_RECORD} is intentionally absent: the day-view page
     * renders it as a bare "-" with no badge at all, which {@link #statusLabel} falls back to below.
     */
    private static final Map<AttendanceDayStatus, String> STATUS_LABEL = Map.of(
        AttendanceDayStatus.PRESENT, "ปกติ",
        AttendanceDayStatus.LATE, "มาสาย",
        AttendanceDayStatus.WFH, "WFH",
        AttendanceDayStatus.MISSING_CHECK_IN, "ขาดสแกนเข้า",
        AttendanceDayStatus.MISSING_CHECK_OUT, "ขาดสแกนออก",
        AttendanceDayStatus.NON_WORKDAY, "วันหยุด",
        AttendanceDayStatus.HOLIDAY, "วันหยุดนักขัตฤกษ์"
    );

    private static final String SECTION_76_FOOTER =
        "หมายเหตุ: ตัวเลขสาย/ออกก่อนเวลาข้างต้นมีไว้เพื่อการรายงานเท่านั้น "
        + "ห้ามนำไปใช้หักค่าจ้างพนักงานตามมาตรา 76 แห่งพระราชบัญญัติคุ้มครองแรงงาน";

    /** One column: a header label plus how to pull its cell value out of a row's context. Generic
     * (unlike {@code PayrollDetailExporter}'s single-shape {@code Column}) because this workbook has
     * two sheets with two different row types. */
    private record Column<T>(String header, Function<T, Object> extract) {}

    public byte[] export(AttendanceMonthlySummaryResult result) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook);
            CellStyle footerStyle = footerStyle(workbook);

            writeSummarySheet(workbook, result, titleStyle, headerStyle, numberStyle, dateStyle, footerStyle);
            writeDailySheet(workbook, result, headerStyle, numberStyle, dateStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Sheet 1: สรุปรายเดือน ----

    private void writeSummarySheet(
            XSSFWorkbook workbook, AttendanceMonthlySummaryResult result, CellStyle titleStyle,
            CellStyle headerStyle, CellStyle numberStyle, CellStyle dateStyle, CellStyle footerStyle) {
        Sheet sheet = workbook.createSheet(SUMMARY_SHEET_NAME);
        List<Column<AttendanceMonthlySummaryRow>> columns = summaryColumns();

        int headerRowIdx = writeSummaryTitleBlock(sheet, result, titleStyle);
        writeSummaryHeaderRow(sheet, columns, headerStyle, headerRowIdx);

        int rowIdx = headerRowIdx + 1;
        int sequence = 1;
        for (AttendanceMonthlySummaryRow summaryRow : result.summaryRows()) {
            Row row = sheet.createRow(rowIdx++);
            writeCell(row, 0, sequence++, numberStyle, dateStyle);
            for (int c = 0; c < columns.size(); c++) {
                writeCell(row, c + 1, columns.get(c).extract().apply(summaryRow), numberStyle, dateStyle);
            }
        }

        // A totals row is deliberately NOT built here rather than half-built: most of these
        // columns (e.g. ตำแหน่ง, or a ครั้ง count) do not sum meaningfully across employees the way
        // payroll's money columns do, and a row that totals only some of them invites misreading.
        rowIdx++; // blank line before the footer, so it reads as a note rather than a data row
        Cell footerCell = sheet.createRow(rowIdx).createCell(0);
        footerCell.setCellValue(SECTION_76_FOOTER);
        footerCell.setCellStyle(footerStyle);

        // Rows only, per the plan ("freeze panes below the header row") -- no column freeze, unlike
        // PayrollDetailExporter's createFreezePane(1, 1): that report reads left-to-right against one
        // pinned identity column; this one is read mostly top-to-bottom per employee.
        sheet.createFreezePane(0, headerRowIdx + 1);
        sheet.setColumnWidth(0, 6 * 256);
        for (int c = 0; c < columns.size(); c++) {
            sheet.setColumnWidth(c + 1, columnWidth(columns.get(c).header()));
        }
    }

    /** Writes the title/filter/generated-at lines above the header row and returns the row index the
     * header itself belongs on. */
    private int writeSummaryTitleBlock(Sheet sheet, AttendanceMonthlySummaryResult result, CellStyle titleStyle) {
        Cell titleCell = sheet.createRow(0).createCell(0);
        titleCell.setCellValue("สรุปเวลาทำงานประจำเดือน " + ThaiText.monthYear(result.month()));
        titleCell.setCellStyle(titleStyle);
        sheet.createRow(1).createCell(0).setCellValue("ขอบเขตที่แสดง: " + result.appliedFilterDescription());
        sheet.createRow(2).createCell(0).setCellValue(
            "สร้างเมื่อ: " + result.generatedAt().format(GENERATED_AT_FORMAT) + " น. (เวลาไทย)");
        // Row 3 is left blank on purpose -- a visual gap between the title block and the header row.
        return 4;
    }

    private void writeSummaryHeaderRow(
            Sheet sheet, List<Column<AttendanceMonthlySummaryRow>> columns, CellStyle headerStyle, int rowIdx) {
        Row header = sheet.createRow(rowIdx);
        writeHeaderCell(header, 0, "ลำดับ", headerStyle);
        for (int c = 0; c < columns.size(); c++) {
            writeHeaderCell(header, c + 1, columns.get(c).header(), headerStyle);
        }
    }

    private List<Column<AttendanceMonthlySummaryRow>> summaryColumns() {
        List<Column<AttendanceMonthlySummaryRow>> columns = new ArrayList<>();
        columns.add(new Column<>("รหัสพนักงาน", AttendanceMonthlySummaryRow::employeeCode));
        columns.add(new Column<>("ชื่อ-สกุล", AttendanceMonthlySummaryRow::employeeName));
        columns.add(new Column<>("ชื่อเล่น", AttendanceMonthlySummaryRow::nickName));
        columns.add(new Column<>("ตำแหน่ง", AttendanceMonthlySummaryRow::positionTh));
        columns.add(new Column<>("วันทำงานตามปฏิทิน (วัน)", AttendanceMonthlySummaryRow::calendarWorkdays));
        columns.add(new Column<>("มาทำงาน (วัน)", AttendanceMonthlySummaryRow::daysPresent));
        columns.add(new Column<>("สาย (ครั้ง)", AttendanceMonthlySummaryRow::lateCount));
        columns.add(new Column<>("สาย (นาที)", AttendanceMonthlySummaryRow::lateMinutes));
        columns.add(new Column<>("ออกก่อนเวลา (ครั้ง)", AttendanceMonthlySummaryRow::earlyLeaveCount));
        columns.add(new Column<>("ออกก่อนเวลา (นาที)", AttendanceMonthlySummaryRow::earlyLeaveMinutes));
        columns.add(new Column<>("ลืมสแกนเข้า (ครั้ง)", AttendanceMonthlySummaryRow::missingCheckInCount));
        columns.add(new Column<>("ลืมสแกนออก (ครั้ง)", AttendanceMonthlySummaryRow::missingCheckOutCount));
        columns.add(new Column<>("ลาป่วย (วัน)", AttendanceMonthlySummaryRow::sickDays));
        columns.add(new Column<>("ลากิจ (วัน)", AttendanceMonthlySummaryRow::personalDays));
        columns.add(new Column<>("ลาพักร้อน (วัน)", AttendanceMonthlySummaryRow::vacationDays));
        columns.add(new Column<>("ลาไม่รับค่าจ้าง (วัน)", AttendanceMonthlySummaryRow::unpaidLeaveDays));
        columns.add(new Column<>("ลาอื่นๆ (วัน)", AttendanceMonthlySummaryRow::otherLeaveDays));
        columns.add(new Column<>("ลารวม (วัน)", AttendanceMonthlySummaryRow::totalLeaveDays));
        columns.add(new Column<>("ขาดงาน (วัน)", AttendanceMonthlySummaryRow::absentDays));
        columns.add(new Column<>("ทำงานรวม (ชม.)", AttendanceMonthlySummaryRow::totalHours));
        columns.add(new Column<>("OT อนุมัติ (ชม.)", AttendanceMonthlySummaryRow::approvedOtHours));
        return columns;
    }

    // ---- Sheet 2: รายวัน ----

    private void writeDailySheet(
            XSSFWorkbook workbook, AttendanceMonthlySummaryResult result,
            CellStyle headerStyle, CellStyle numberStyle, CellStyle dateStyle) {
        Sheet sheet = workbook.createSheet(DAILY_SHEET_NAME);
        List<Column<AttendanceDailyDto>> columns = dailyColumns(result.leaveByDay());

        Row header = sheet.createRow(0);
        for (int c = 0; c < columns.size(); c++) {
            writeHeaderCell(header, c, columns.get(c).header(), headerStyle);
        }

        int rowIdx = 1;
        for (AttendanceDailyDto day : result.dailyRows()) {
            Row row = sheet.createRow(rowIdx++);
            for (int c = 0; c < columns.size(); c++) {
                writeCell(row, c, columns.get(c).extract().apply(day), numberStyle, dateStyle);
            }
        }

        sheet.createFreezePane(0, 1);
        for (int c = 0; c < columns.size(); c++) {
            sheet.setColumnWidth(c, columnWidth(columns.get(c).header()));
        }
    }

    private List<Column<AttendanceDailyDto>> dailyColumns(Map<EmployeeDay, List<LeaveContribution>> leaveByDay) {
        List<Column<AttendanceDailyDto>> columns = new ArrayList<>();
        columns.add(new Column<>("วันที่", AttendanceDailyDto::workDate));
        columns.add(new Column<>("วัน", day -> THAI_WEEKDAY[day.workDate().getDayOfWeek().getValue() - 1]));
        columns.add(new Column<>("รหัสพนักงาน", AttendanceDailyDto::employeeCode));
        columns.add(new Column<>("ชื่อ-สกุล", AttendanceDailyDto::employeeName));
        columns.add(new Column<>("สถานะ", day -> statusLabel(day.status())));
        columns.add(new Column<>("เวลาเข้า", day -> formatTime(day.checkIn())));
        columns.add(new Column<>("เวลาออก", day -> formatTime(day.checkOut())));
        columns.add(new Column<>("รวม (ชม.)", day -> day.totalMinutes() == null ? null : toHours(day.totalMinutes())));
        columns.add(new Column<>("สาย (นาที)", day -> day.lateMinutes() > 0 ? day.lateMinutes() : null));
        columns.add(new Column<>("ออกก่อนเวลา (นาที)", day -> day.earlyLeaveMinutes() > 0 ? day.earlyLeaveMinutes() : null));
        // Gated on the SAME AttendanceDayFlag#OVERTIME_APPROVED flag AttendanceMonthlySummaryService's
        // OT-hours aggregate uses (see that class's javadoc, "OT hours") -- so this column can never
        // show a minutes value Sheet 1's OT hours total did not also count.
        columns.add(new Column<>("OT (นาที)",
            day -> day.flags().contains(AttendanceDayFlag.OVERTIME_APPROVED) ? day.overtimeMinutes() : null));
        columns.add(new Column<>("ลา",
            day -> leaveLabel(leaveByDay.get(new EmployeeDay(day.employeeId(), day.workDate())))));
        columns.add(new Column<>("หมายเหตุ", this::notesFor));
        return columns;
    }

    /** {@code หมายเหตุ}: the stored note, plus a marker when HR hand-corrected the row -- so a
     * reader can tell an ordinary scanner-derived day from one someone had to intervene on. */
    private String notesFor(AttendanceDailyDto day) {
        if (!day.manualOverride()) {
            return day.notes();
        }
        String marker = "(แก้ไขโดย HR)";
        return day.notes() == null || day.notes().isBlank() ? marker : (day.notes() + " " + marker);
    }

    /** One date's ลา cell: every {@link LeaveContribution} covering it, joined -- normally exactly
     * one, but see {@link LeaveContribution}'s own javadoc for why this handles more than one rather
     * than silently keeping only the first. The Thai label always comes from the ledger (ultimately
     * {@code hr.leave_type.name_th}), never a hardcoded string here. */
    private String leaveLabel(List<LeaveContribution> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return null;
        }
        return contributions.stream()
            .map(contribution -> {
                BigDecimal fraction = contribution.fraction().setScale(2, RoundingMode.HALF_UP);
                return fraction.compareTo(BigDecimal.ONE) == 0
                    ? contribution.leaveTypeNameTh()
                    : contribution.leaveTypeNameTh() + " (" + fraction + ")";
            })
            .collect(Collectors.joining(", "));
    }

    private String statusLabel(AttendanceDayStatus status) {
        return STATUS_LABEL.getOrDefault(status, "-");
    }

    /** Bangkok local time as "HH:mm" -- explicitly re-zoned rather than trusting the stored offset,
     * the same defensive choice {@code frontend/src/utils/format.js#formatBangkokTime} makes by
     * pinning {@code timeZone: 'Asia/Bangkok'} in its own Intl formatter. */
    private String formatTime(OffsetDateTime value) {
        if (value == null) {
            return "-";
        }
        return value.atZoneSameInstant(AttendanceService.DEFAULT_WORK_DATE_ZONE).toLocalTime().format(TIME_FORMAT);
    }

    private static BigDecimal toHours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private static int columnWidth(String header) {
        return Math.min(Math.max(header.length() * 2, 10), 32) * 256;
    }

    // ---- cell writing ----

    private void writeHeaderCell(Row row, int col, String header, CellStyle headerStyle) {
        Cell cell = row.createCell(col);
        cell.setCellValue(header);
        cell.setCellStyle(headerStyle);
    }

    private void writeCell(Row row, int col, Object value, CellStyle numberStyle, CellStyle dateStyle) {
        Cell cell = row.createCell(col);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            cell.setCellStyle(numberStyle);
        } else if (value instanceof Integer i) {
            cell.setCellValue(i);
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    // ---- styles ----

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setWrapText(true);
        return style;
    }

    private CellStyle numberStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private CellStyle dateStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        return style;
    }

    /** Italic, muted -- reads as a note rather than another data row (no fill, no border). */
    private CellStyle footerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }
}
