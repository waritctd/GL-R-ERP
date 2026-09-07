package th.co.glr.hr.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;
import th.co.glr.hr.attendance.daily.AttendanceDayStatus;

/**
 * Unit coverage for the AutoFilter metadata {@link AttendanceMonthlySummaryExporter} now writes on
 * both sheets' header rows. Purely a workbook-SHAPE concern -- it never touches
 * {@code AttendanceMonthlySummaryService}'s aggregation rules, which is what
 * {@link AttendanceMonthlySummaryServiceTest} already covers -- so no database and no Mockito: the
 * two records the exporter reads ({@link AttendanceMonthlySummaryResult}, {@link
 * AttendanceMonthlySummaryRow}) are plain data, package-private, and constructible directly from
 * this test in the same package.
 *
 * <p>Every assertion below reads the workbook back from its serialized bytes (see {@link
 * #toWorkbook}), never the in-memory {@code XSSFWorkbook} the exporter builds -- an autofilter is,
 * among other things, a hidden {@code _xlnm._FilterDatabase} defined name that POI's own writer has
 * to serialize correctly, so only the round-tripped copy is evidence about the file a user actually
 * downloads, the same way {@code PayrollDetailExporterTest} round-trips its own workbook.
 */
class AttendanceMonthlySummaryExporterTest {
    private static final YearMonth MONTH = YearMonth.of(2026, 8);
    private static final String SUMMARY_SHEET = "สรุปรายเดือน";
    private static final String DAILY_SHEET = "รายวัน";
    // AttendanceMonthlySummaryExporter#writeSummaryTitleBlock always returns 4 -- title block on
    // rows 0-2, row 3 blank, header on row 4 -- regardless of how many rows follow it.
    private static final int SUMMARY_HEADER_ROW = 4;
    private static final AttendanceMonthlySummaryExporter EXPORTER = new AttendanceMonthlySummaryExporter();

    private static AttendanceMonthlySummaryResult result(
            List<AttendanceMonthlySummaryRow> summaryRows, List<AttendanceDailyDto> dailyRows) {
        return new AttendanceMonthlySummaryResult(
            MONTH, "ทั้งหมด", OffsetDateTime.now(), summaryRows, dailyRows, Map.of());
    }

    private static AttendanceMonthlySummaryRow summaryRow(long employeeId) {
        return new AttendanceMonthlySummaryRow(
            employeeId, "E" + employeeId, "พนักงาน ทดสอบ", "เทส", "พนักงานทั่วไป",
            22, 20, 1, 5, 0, 0, 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, BigDecimal.valueOf(160), BigDecimal.ZERO);
    }

    private static AttendanceDailyDto dailyRow(LocalDate date) {
        return new AttendanceDailyDto(
            501L, "E501", "พนักงาน ทดสอบ", "เทส", "พนักงานทั่วไป", date, true,
            null, null, null, 0, 0, 0, 0, null, AttendanceDayStatus.PRESENT, List.of(), false, null);
    }

    /** Round-trips the exporter's output through its serialized bytes -- see this class's own
     * javadoc for why the in-memory workbook the exporter builds is never what gets asserted on. */
    private static XSSFWorkbook toWorkbook(AttendanceMonthlySummaryResult result) throws Exception {
        byte[] bytes = EXPORTER.export(result);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static CellRangeAddress autoFilterRange(Sheet sheet) {
        return CellRangeAddress.valueOf(((XSSFSheet) sheet).getCTWorksheet().getAutoFilter().getRef());
    }

    /** The last column index actually written on {@code headerRowIdx}, derived from the row itself
     * rather than hand-counted from {@code summaryColumns()}/{@code dailyColumns()} (both private,
     * and both something a later column addition would silently drift out of sync with) -- this way
     * the assertion tracks whatever the exporter really wrote, not a copy of today's column count. */
    private static int lastWrittenColumn(Sheet sheet, int headerRowIdx) {
        return sheet.getRow(headerRowIdx).getLastCellNum() - 1;
    }

    /** Scans every cell on the sheet for one whose text starts with {@code prefix} -- the same way a
     * human opening the file would find it -- rather than assuming it landed on a row number this
     * test predicts. Used to locate the §76 footer without depending on the exporter's private
     * {@code SECTION_76_FOOTER} constant. */
    private static int findRowStartingWith(Sheet sheet, String prefix) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().startsWith(prefix)) {
                    return row.getRowNum();
                }
            }
        }
        throw new AssertionError("no cell on sheet '" + sheet.getSheetName() + "' starts with: " + prefix);
    }

    @Test
    void summarySheetAutoFilterCoversHeaderThroughLastDataRowAllColumns() throws Exception {
        List<AttendanceMonthlySummaryRow> rows = List.of(summaryRow(501), summaryRow(502), summaryRow(503));
        try (XSSFWorkbook workbook = toWorkbook(result(rows, List.of()))) {
            Sheet sheet = workbook.getSheet(SUMMARY_SHEET);
            CellRangeAddress range = autoFilterRange(sheet);

            assertThat(range.getFirstRow()).isEqualTo(SUMMARY_HEADER_ROW);
            assertThat(range.getLastRow()).isEqualTo(SUMMARY_HEADER_ROW + rows.size());
            assertThat(range.getFirstColumn()).isEqualTo(0);
            assertThat(range.getLastColumn()).isEqualTo(lastWrittenColumn(sheet, SUMMARY_HEADER_ROW));
        }
    }

    /** The wrong-way-round assertion that actually matters: an Excel autofilter can only ever hide a
     * row that sits INSIDE its own range, never one outside it, so the §76 footer (a legal warning --
     * see {@code AttendanceMonthlySummaryExporter}'s "§76" javadoc section) staying visible under
     * every filter state depends entirely on it sitting strictly below {@code range.getLastRow()}.
     * "the footer got a row of its own" is not the claim worth making; "the filter can never reach
     * the footer" is -- so this asserts the row-index inequality directly, not just presence. */
    @Test
    void section76FooterAndItsBlankSpacerSitStrictlyBelowTheAutoFilterRange() throws Exception {
        List<AttendanceMonthlySummaryRow> rows = List.of(summaryRow(501), summaryRow(502));
        try (XSSFWorkbook workbook = toWorkbook(result(rows, List.of()))) {
            Sheet sheet = workbook.getSheet(SUMMARY_SHEET);
            CellRangeAddress range = autoFilterRange(sheet);
            int footerRowIdx = findRowStartingWith(sheet, "หมายเหตุ:");
            int blankSpacerRowIdx = footerRowIdx - 1;

            assertThat(footerRowIdx).isGreaterThan(range.getLastRow());
            assertThat(blankSpacerRowIdx).isGreaterThan(range.getLastRow());
            // Named "blank spacer", so prove it IS one rather than only that it clears the range:
            // writeSummarySheet advances past this index without ever calling createRow, so the row
            // is absent from the sheet XML entirely and POI materialises no Row for it.
            assertThat(sheet.getRow(blankSpacerRowIdx)).isNull();
        }
    }

    @Test
    void dailySheetAutoFilterCoversHeaderThroughLastDataRowAllColumns() throws Exception {
        List<AttendanceDailyDto> rows = List.of(
            dailyRow(LocalDate.of(2026, 8, 3)), dailyRow(LocalDate.of(2026, 8, 4)),
            dailyRow(LocalDate.of(2026, 8, 5)), dailyRow(LocalDate.of(2026, 8, 6)));
        try (XSSFWorkbook workbook = toWorkbook(result(List.of(), rows))) {
            Sheet sheet = workbook.getSheet(DAILY_SHEET);
            CellRangeAddress range = autoFilterRange(sheet);

            assertThat(range.getFirstRow()).isEqualTo(0);
            assertThat(range.getLastRow()).isEqualTo(rows.size());
            assertThat(range.getFirstColumn()).isEqualTo(0);
            assertThat(range.getLastColumn()).isEqualTo(lastWrittenColumn(sheet, 0));
        }
    }

    @Test
    void zeroRowsOnBothSheetsStillWritesAValidWorkbookWithHeaderOnlyRanges() throws Exception {
        byte[] bytes = EXPORTER.export(result(List.of(), List.of()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet summarySheet = workbook.getSheet(SUMMARY_SHEET);
            CellRangeAddress summaryRange = autoFilterRange(summarySheet);
            assertThat(summaryRange.getFirstRow()).isEqualTo(SUMMARY_HEADER_ROW);
            assertThat(summaryRange.getLastRow()).isEqualTo(SUMMARY_HEADER_ROW); // header row alone

            Sheet dailySheet = workbook.getSheet(DAILY_SHEET);
            CellRangeAddress dailyRange = autoFilterRange(dailySheet);
            assertThat(dailyRange.getFirstRow()).isEqualTo(0);
            assertThat(dailyRange.getLastRow()).isEqualTo(0); // header row alone

            // Even with zero employees the footer must still clear the (now single-row) range.
            int footerRowIdx = findRowStartingWith(summarySheet, "หมายเหตุ:");
            assertThat(footerRowIdx).isGreaterThan(summaryRange.getLastRow());
        }
    }
}
