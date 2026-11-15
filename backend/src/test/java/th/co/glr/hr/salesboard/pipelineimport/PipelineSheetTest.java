package th.co.glr.hr.salesboard.pipelineimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.pipelineimport.PipelineSheet.Column;

/** Reading the template (xlsx or UTF-8 CSV) into rows. All data is invented. */
class PipelineSheetTest {

    static final String[] HEADERS = PipelineSheet.TEMPLATE_HEADERS.toArray(String[]::new);

    @Test
    void readsTheTemplateHeaderAndNumericCells() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("pipeline");
            header(s);
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("EMP-T01");
            r.createCell(1).setCellValue("โรงแรมสมมุติ สาขาทดลอง");
            r.createCell(2).setCellValue("สตูดิโอสมมุติ");
            r.createCell(5).setCellValue("QT-TEST-0001");
            r.createCell(6).setCellValue(1240000d);
            r.createCell(7).setCellValue("S3");
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0%"));
            var p = r.createCell(8);
            p.setCellValue(0.6);
            p.setCellStyle(pct);
            r.createCell(9).setCellValue("2026-Q4");
        });

        List<PipelineSheet.SheetRow> rows = PipelineSheet.read("Pipeline.xlsx", xlsx, 2000);

        assertThat(rows).hasSize(1);
        PipelineSheet.SheetRow row = rows.get(0);
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.text(Column.REP)).isEqualTo("EMP-T01");
        assertThat(row.text(Column.PROJECT)).isEqualTo("โรงแรมสมมุติ สาขาทดลอง");
        assertThat(row.cell(Column.VALUE).numeric()).isEqualTo(1240000d);
        assertThat(row.cell(Column.PROBABILITY).numeric()).isEqualTo(0.6);
        assertThat(row.cell(Column.PROBABILITY).percent()).isTrue();
        assertThat(row.text(Column.OWNER)).isNull();
    }

    @Test
    void headersMatchCaseAndSpaceInsensitivelyAndInAnyOrder() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            Row h = s.createRow(0);
            String[] hs = {"  Status ", "PROJECT", "sales", "Quotation No"};
            for (int i = 0; i < hs.length; i++) {
                h.createCell(i).setCellValue(hs[i]);
            }
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("S1");
            r.createCell(1).setCellValue("โครงการ ก");
            r.createCell(2).setCellValue("rep@glr.co.th");
            r.createCell(3).setCellValue("QT-1");
        });

        PipelineSheet.SheetRow row = PipelineSheet.read("a.xlsx", xlsx, 10).get(0);

        assertThat(row.text(Column.STATUS)).isEqualTo("S1");
        assertThat(row.text(Column.REP)).isEqualTo("rep@glr.co.th");
        assertThat(row.text(Column.QUOTATION_NO)).isEqualTo("QT-1");
    }

    @Test
    void theRepsOwnWorkbookHeadersAreRecognised_andUnknownOnesAreListed() throws IOException {
        String[] real = {"ลำดับ", "พนักงานขาย", "โครงการ", "ผู้ออกแบบ (ชื่อ/ตำแหน่ง)", "เจ้าของโครงการ (ชื่อ/ตำแหน่ง)",
            "ผู้ซื้อ (ชื่อ/ตำแหน่ง)", "เลขที่อ้างอิง", "มูลค่าใบเสนอราคา", "สถานะงานขาย", "จะขายได้ %", "ช่วงเวลา (Q)",
            "หมายเหตุ", "เบอร์โทร"};
        String[] values = {"1", "EMP-T01", "โครงการสมมุติ", "สตูดิโอสมมุติ", "เจ้าของสมมุติ", "ผู้ซื้อสมมุติ", "QT-T-0001-1",
            "1,000", "S2", "30", "Q1/70", "ข้อมูลสมมุติ", "000"};
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            Row h = s.createRow(0);
            Row r = s.createRow(1);
            for (int i = 0; i < real.length; i++) {
                h.createCell(i).setCellValue(real[i]);
                r.createCell(i).setCellValue(values[i]);
            }
        });

        PipelineSheet.ParsedSheet sheet = PipelineSheet.readSheet("a.xlsx", xlsx, 10);

        PipelineSheet.SheetRow row = sheet.rows().get(0);
        assertThat(row.text(Column.DESIGNER)).isEqualTo("สตูดิโอสมมุติ");
        assertThat(row.text(Column.OWNER)).isEqualTo("เจ้าของสมมุติ");
        assertThat(row.text(Column.BUYER)).isEqualTo("ผู้ซื้อสมมุติ");
        assertThat(row.text(Column.QUOTATION_NO)).isEqualTo("QT-T-0001-1");
        assertThat(row.text(Column.VALUE)).isEqualTo("1,000");
        assertThat(row.text(Column.STATUS)).isEqualTo("S2");
        assertThat(row.text(Column.PROBABILITY)).isEqualTo("30");
        assertThat(row.text(Column.QUARTER)).isEqualTo("Q1/70");
        assertThat(sheet.unknownHeaders()).containsExactly("ลำดับ", "เบอร์โทร");
    }

    @Test
    void qnAndโอกาสAreAliasesToo() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            Row h = s.createRow(0);
            String[] hs = {"พนักงานขาย", "โครงการ", "สถานะ", "QN", "โอกาส"};
            for (int i = 0; i < hs.length; i++) {
                h.createCell(i).setCellValue(hs[i]);
            }
            Row r = s.createRow(1);
            String[] vs = {"EMP-T01", "โครงการ ก", "S1", "QT-9", "20"};
            for (int i = 0; i < vs.length; i++) {
                r.createCell(i).setCellValue(vs[i]);
            }
        });

        PipelineSheet.SheetRow row = PipelineSheet.read("a.xlsx", xlsx, 10).get(0);
        assertThat(row.text(Column.QUOTATION_NO)).isEqualTo("QT-9");
        assertThat(row.text(Column.PROBABILITY)).isEqualTo("20");
    }

    @Test
    void blankRowsAreIgnoredAndRowNumbersAreTheSheetsOwn() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            header(s);
            s.createRow(1).createCell(1).setCellValue("แถวสอง");
            s.createRow(2).createCell(1).setCellValue("  ");
            s.createRow(4).createCell(1).setCellValue("แถวห้า");
        });

        List<PipelineSheet.SheetRow> rows = PipelineSheet.read("a.xlsx", xlsx, 10);

        assertThat(rows).extracting(PipelineSheet.SheetRow::rowNumber).containsExactly(2, 5);
    }

    @Test
    void aDateCellInTheQuarterColumnIsKeptAsADate() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            header(s);
            Row r = s.createRow(1);
            r.createCell(1).setCellValue("โครงการ ข");
            var c = r.createCell(9);
            c.setCellValue(LocalDate.of(2027, 2, 1));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            c.setCellStyle(date);
        });

        assertThat(PipelineSheet.read("a.xlsx", xlsx, 10).get(0).cell(Column.QUARTER).date())
            .isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    void csvWithBomAndQuotedThousands() {
        String csv = "﻿" + String.join(",", HEADERS) + "\r\n"
            + "EMP-T01,\"โครงการ, มีจุลภาค\",สตูดิโอ,,,QT-9,\"1,240,000\",ทำใบเสนอราคาแล้ว,60%,Q1/70,\"บรรทัด \"\"ยกมา\"\"\"\r\n";

        List<PipelineSheet.SheetRow> rows = PipelineSheet.read("p.csv", csv.getBytes(StandardCharsets.UTF_8), 10);

        assertThat(rows).hasSize(1);
        PipelineSheet.SheetRow row = rows.get(0);
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.text(Column.REP)).isEqualTo("EMP-T01");
        assertThat(row.text(Column.PROJECT)).isEqualTo("โครงการ, มีจุลภาค");
        assertThat(row.text(Column.VALUE)).isEqualTo("1,240,000");
        assertThat(row.cell(Column.VALUE).numeric()).isNull();
        assertThat(row.text(Column.STATUS)).isEqualTo("ทำใบเสนอราคาแล้ว");
        assertThat(row.text(Column.NOTE)).isEqualTo("บรรทัด \"ยกมา\"");
    }

    @Test
    void missingRequiredHeaderIs400() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Row h = wb.createSheet("x").createRow(0);
            h.createCell(0).setCellValue("โครงการ");
            h.createCell(1).setCellValue("ผู้ออกแบบ");
        });

        assertThatThrownBy(() -> PipelineSheet.read("a.xlsx", xlsx, 10))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("พนักงานขาย").contains("สถานะ");
            });
    }

    @Test
    void moreRowsThanTheLimitIs400() throws IOException {
        byte[] xlsx = xlsx(wb -> {
            Sheet s = wb.createSheet("x");
            header(s);
            for (int i = 1; i <= 4; i++) {
                s.createRow(i).createCell(1).setCellValue("โครงการ " + i);
            }
        });

        assertThatThrownBy(() -> PipelineSheet.read("a.xlsx", xlsx, 3))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("3");
            });
    }

    @Test
    void notASpreadsheetIs400() {
        assertThatThrownBy(() -> PipelineSheet.read("a.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8), 10))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> PipelineSheet.read("a.pdf", new byte[] {1, 2, 3}, 10))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void emptyFileIs400() {
        assertThatThrownBy(() -> PipelineSheet.read("a.csv", new byte[0], 10))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    interface WorkbookBody {
        void fill(XSSFWorkbook wb);
    }

    static byte[] xlsx(WorkbookBody body) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            body.fill(wb);
            wb.write(out);
            return out.toByteArray();
        }
    }

    static void header(Sheet s) {
        Row h = s.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            h.createCell(i).setCellValue(HEADERS[i]);
        }
    }
}
