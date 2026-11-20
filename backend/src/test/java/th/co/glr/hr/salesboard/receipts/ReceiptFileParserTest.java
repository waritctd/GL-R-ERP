package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.receipts.ReceiptFileParser.ParseResult;

/**
 * The documented column template (plan decision 3): header aliases matched case/space-insensitively, .csv (UTF-8) and
 * .xlsx. Every fixture is synthetic — no real Express export exists (spec §11); see express-synthetic.csv.
 */
class ReceiptFileParserTest {

    @Test
    void aFileWithoutTheRequiredColumnsIsRefusedWholesale() {
        byte[] csv = "ชื่อ,ยอด\nก,1\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ReceiptFileParser.parse("x.csv", csv))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("เลขที่เอกสาร").contains("จำนวนเงิน");
            });
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

}
