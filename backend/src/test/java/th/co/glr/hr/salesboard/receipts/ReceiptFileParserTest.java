package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

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

}
