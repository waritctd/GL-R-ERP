package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.receipts.ReceiptFileParser.ParseResult;

/**
 * The documented column template (plan decision 3): header aliases matched case/space-insensitively, .csv (UTF-8) and
 * .xlsx. Every fixture is synthetic — no real Express export exists (spec §11); see express-synthetic.csv.
 */
class ReceiptFileParserTest {

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = ReceiptFileParserTest.class.getResourceAsStream("/receipts/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void csv_findsTheHeaderBelowATitle_mapsAliases_andReportsBadRowsByLine() throws Exception {
        ParseResult result = ReceiptFileParser.parse("RE-พ.ย.-สัปดาห์2.csv", resource("express-synthetic.csv"));

        assertThat(result.rows()).hasSize(3);
        ParsedReceipt first = result.rows().get(0);
        assertThat(first.line()).isEqualTo(4);
        assertThat(first.docNo()).isEqualTo("RE-6911-0001");
        assertThat(first.docDate()).as("dd/MM with a Buddhist-era year").isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(first.customerName()).isEqualTo("บจก. ลูกค้าสมมติ หนึ่ง");
        assertThat(first.amount()).isEqualByComparingTo("372000.00");
        assertThat(first.reference()).isEqualTo("DN6900001");

        ParsedReceipt second = result.rows().get(1);
        assertThat(second.docDate()).as("ISO date").isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(second.reference()).as("blank reference is null").isNull();

        ParsedReceipt last = result.rows().get(2);
        assertThat(last.customerName()).as("quoted comma").isEqualTo("ลูกค้า, มีจุลภาค");
        assertThat(last.amount()).as("฿ and spaces stripped").isEqualByComparingTo("2.5");
        assertThat(last.docDate()).as("dd/MM with a CE year").isEqualTo(LocalDate.of(2026, 11, 14));

        assertThat(result.errors()).extracting(ReceiptFileParser.RowError::line).containsExactly(6, 7, 8);
        assertThat(result.errors().get(0).message()).contains("เลขที่เอกสาร");
        assertThat(result.errors().get(1).message()).contains("วันที่");
        assertThat(result.errors().get(2).message()).contains("จำนวนเงิน");
    }

    @Test
    void xlsx_readsRealDateAndNumberCells_withOtherAliases() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("RE");
            Row h = sheet.createRow(0);
            String[] headers = {"เลขที่เอกสาร", "DATE", "ลูกค้า", "Amount", "อ้างอิง"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("d/m/yyyy"));
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("AI-6911-0010");
            r.createCell(1).setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 11, 30)));
            r.getCell(1).setCellStyle(dateStyle);
            r.createCell(2).setCellValue("ลูกค้าสมมติ เอ็กซ์");
            r.createCell(3).setCellValue(456150.5);
            r.createCell(4).setCellValue("QN6900176");
            wb.write(out);
            bytes = out.toByteArray();
        }

        ParseResult result = ReceiptFileParser.parse("export.xlsx", bytes);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.line()).isEqualTo(2);
            assertThat(row.docNo()).isEqualTo("AI-6911-0010");
            assertThat(row.docDate()).isEqualTo(LocalDate.of(2026, 11, 30));
            assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("456150.50"));
            assertThat(row.reference()).isEqualTo("QN6900176");
        });
    }

    @Test
    void aFileWithoutTheRequiredColumnsIsRefusedWholesale() {
        byte[] csv = "ชื่อ,ยอด\nก,1\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ReceiptFileParser.parse("x.csv", csv))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("เลขที่เอกสาร").contains("จำนวนเงิน");
            });
    }

    @Test
    void onlyCsvAndXlsxAreAccepted() {
        assertThatThrownBy(() -> ReceiptFileParser.parse("x.pdf", new byte[] {1, 2}))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> ReceiptFileParser.parse("x.xlsx", "not a workbook".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Review H4: a document type from its own column, else the document number's leading letters. */
    @Test
    void docTypeComesFromItsColumnElseTheNumbersPrefix() {
        byte[] csv = ("Doc No,Doc Type,Date,Amount\n"
            + "RE-6911-1,,2026-11-12,10\n"
            + "IV6911-2,,2026-11-12,10\n"
            + "X-3,re,2026-11-12,10\n"
            + "4455,,2026-11-12,10\n"
            + "X-5, ใบเสร็จรับเงิน ,2026-11-12,10\n"
            + "X-6,ใบกำกับภาษี,2026-11-12,10\n").getBytes(StandardCharsets.UTF_8);
        ParseResult result = ReceiptFileParser.parse("a.csv", csv);
        // Review N4: the Thai name of a receipt reads as RE; any other text stays non-cash.
        assertThat(result.rows()).extracting(ParsedReceipt::docType)
            .containsExactly("RE", "IV", "RE", null, "RE", "ใบกำกับภาษี");
    }

    /** Review M2 / M3: column limits are checked here, so an over-long row is invalid with its reason, not a crash. */
    @Test
    void overLongDocNumbersAndOverLargeAmountsAreInvalidRows() {
        byte[] csv = ("เลขที่เอกสาร,วันที่,จำนวนเงิน\n"
            + "RE-" + "9".repeat(58) + ",2026-11-12,10\n"
            + "RE-OK,2026-11-12,1000000000000\n"
            + "RE-OK2,2026-11-12,999999999999.99\n").getBytes(StandardCharsets.UTF_8);
        ParseResult result = ReceiptFileParser.parse("a.csv", csv);
        assertThat(result.rows()).extracting(ParsedReceipt::docNo).containsExactly("RE-OK2");
        assertThat(result.errors()).extracting(ReceiptFileParser.RowError::line).containsExactly(2, 3);
        assertThat(result.errors().get(0).message()).contains("60");
        assertThat(result.errors().get(1).message()).contains("จำนวนเงิน");
    }

    @Test
    void filesOverTwoMegabytesAreRefused() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> ReceiptFileParser.parse("x.csv", big))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("2 MB"));
    }

    @Test
    void theDownloadableTemplateIsAHeaderOnlyFileThisParserAccepts() {
        byte[] template = ReceiptFileParser.templateCsv();
        assertThat(new String(template, StandardCharsets.UTF_8)).startsWith("\uFEFFเลขที่เอกสาร,ประเภทเอกสาร,วันที่,ลูกค้า,จำนวนเงิน,อ้างอิง");
        ParseResult parsed = ReceiptFileParser.parse("template.csv", template);
        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).isEmpty();
    }
}
