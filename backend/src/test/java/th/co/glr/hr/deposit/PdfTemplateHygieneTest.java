package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static th.co.glr.hr.deposit.PdfRendererTestSupport.*;

import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

/**
 * The repo is PUBLIC: the committed deposit/remaining templates must be a clean shell -- exactly one
 * sheet ("Update") and none of the filled-in example cells the owner's sample files carry (the
 * deposit sample's Sheet1 is a real customer list). Pins the cells the design names (0-based).
 */
class PdfTemplateHygieneTest {
    private static final String DEPOSIT = "/templates/deposit_notice_template.xls";
    private static final String REMAINING = "/templates/remaining_invoice_template.xls";

    /** No author/user name of the person who built the file survives in any stream, including WRITEACCESS. */
    @Test
    void noUserNameSurvivesInEitherTemplate() throws Exception {
        for (String resource : List.of(DEPOSIT, REMAINING)) {
            var streams = oleStreams(resource);
            for (var e : streams.entrySet())
                assertThat(containsText(e.getValue(), "ploy")).as("'ploy' in %s of %s", e.getKey(), resource).isFalse();
            assertThat(writeAccessUser(streams.get("Workbook"))).as("WRITEACCESS user of " + resource).isIn("", "GLR");
        }
    }
}
