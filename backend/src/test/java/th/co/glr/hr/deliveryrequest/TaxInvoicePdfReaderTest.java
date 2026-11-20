package th.co.glr.hr.deliveryrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** The reader pulls number, date (Buddhist year converted) and total off a text PDF; anything else reads as nothing. */
public class TaxInvoicePdfReaderTest {
    @Test
    void readsNumberDateAndTotalFromASyntheticInvoice() throws Exception {
        var read = TaxInvoicePdfReader.read(pdf("Tax Invoice No. IV2611-0042", "Date 19/11/2569",
            "Item 1  1,000.00", "Grand Total 85,065.00", "VAT 5,565.00"));
        assertThat(read.invoiceNumber()).isEqualTo("IV2611-0042");
        assertThat(read.invoiceDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(read.total()).isEqualByComparingTo(new BigDecimal("85065.00"));
        assertThat(read.readOk()).isTrue();
    }

    @Test
    void theGrandTotalBeatsALineAbove_aNoteIsNotTheNumber_andAGuessedTotalIsMarkedWeak() throws Exception {
        var read = TaxInvoicePdfReader.read(pdf("Note IV2611-0099", "Tax Invoice No. IV2611-0042", "Date 19/11/2569",
            "Total before VAT 79,500.00", "VAT 5,565.00", "Grand Total 85,065.00"));
        assertThat(read.invoiceNumber()).isEqualTo("IV2611-0042");
        assertThat(read.total()).isEqualByComparingTo("85065.00");
        assertThat(read.totalWeak()).isFalse();
        var guess = TaxInvoicePdfReader.read(pdf("No. IV2611-0042", "Date 19/11/2569", "Amount 79,500.00", "Amount 85,065.00"));
        assertThat(guess.total()).isEqualByComparingTo("85065.00");
        assertThat(guess.totalWeak()).isTrue();
    }

    @Test
    void garbageAndEmptyPdfsReadAsNothingWithoutThrowing() throws Exception {
        assertThat(TaxInvoicePdfReader.read("not a pdf".getBytes()).readOk()).isFalse();
        var blank = TaxInvoicePdfReader.read(pdf("no figures here"));
        assertThat(blank.readOk()).isFalse();
        assertThat(blank.invoiceNumber()).isNull();
    }

    /** A one-page text PDF of invented lines (no real document), shared with the round integration test. */
    public static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(16);
                cs.newLineAtOffset(40, 700);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
