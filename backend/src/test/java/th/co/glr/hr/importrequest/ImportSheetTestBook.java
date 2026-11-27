package th.co.glr.hr.importrequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds an import sheet in memory from made up values. The repo is public: no test may read the real file. */
final class ImportSheetTestBook {
    static final String COST = "ราคาของ(USD/EUR)";

    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<CellRangeAddress> merges = new ArrayList<>();

    /** A row of header, value pairs. */
    ImportSheetTestBook row(Object... pairs) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            r.put((String) pairs[i], pairs[i + 1]);
        }
        rows.add(r);
        return this;
    }

    ImportSheetTestBook blank() {
        rows.add(Map.of());
        return this;
    }

    /** Merges one column over two 1-based sheet rows. */
    ImportSheetTestBook merge(String header, int firstRow, int lastRow) {
        int col = headers().indexOf(header);
        merges.add(new CellRangeAddress(firstRow - 1, lastRow - 1, col, col));
        return this;
    }

    private static List<String> headers() {
        List<String> h = new ArrayList<>(ImportSheetParser.REQUIRED_HEADERS);
        h.add(COST);
        return h;
    }

    byte[] bytes() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("ignored");
            Sheet sheet = wb.createSheet("Sheet1");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("d/m/yyyy"));
            List<String> headers = headers();
            Row head = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                head.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (Map.Entry<String, Object> e : rows.get(r).entrySet()) {
                    Cell cell = row.createCell(headers.indexOf(e.getKey()));
                    Object v = e.getValue();
                    if (v instanceof LocalDate d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateStyle);
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            merges.forEach(sheet::addMergedRegion);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
