package th.co.glr.hr.salesboard.pipelineimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/** Reading the template (xlsx or UTF-8 CSV) into rows. All data is invented. */
class PipelineSheetTest {

    static final String[] HEADERS = PipelineSheet.TEMPLATE_HEADERS.toArray(String[]::new);

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
