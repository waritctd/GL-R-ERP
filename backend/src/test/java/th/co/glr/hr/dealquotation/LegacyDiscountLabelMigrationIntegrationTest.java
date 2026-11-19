package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.LibreOfficePdfConverter;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;

/** V228 + V229: an issued notice prints exactly as issued ("ลด X"); a DRAFT's legacy label is cleared. Re-runs the migration's
 * SQL over rows seeded in the pre-release shape (the test DB already ran it, on empty tables). */
class LegacyDiscountLabelMigrationIntegrationTest extends DirectQuotationDealTestBase {
    private DepositNoticeDto notice;

    @BeforeEach
    void legacyNotice() {
        Assumptions.assumeTrue(LibreOfficePdfConverter.isAvailable(), "LibreOffice (soffice) not installed");
        long ticketId = quotationOnlyTicket(salesActor);
        confirmer.confirmOrderFromDirectQuotation(approvedDirectQuotation(ticketId, salesActor).id(), salesActor);
        notice = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        // the pre-release shape: authored label stored, unit 5,000.00 -> net 3,900.50 (a 1,099.50 discount)
        jdbc.update("UPDATE sales.deposit_notice_item SET discount_label = 'ส่วนลด 1,099.50 ต่อหน่วย',"
            + " unit_price = 5000.00, net_unit_price = 3900.50 WHERE deposit_notice_id = :id AND seq = 1",
            Map.of("id", notice.id()));
    }

    private void runMigration() throws Exception {
        for (String file : new String[] {"V228__clear_legacy_discount_labels.sql", "V229__freeze_issued_discount_labels.sql"}) {
            try (var in = getClass().getResourceAsStream("/db/migration/" + file)) {
                jdbc.getJdbcOperations().execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private String label() {
        return jdbc.queryForObject("SELECT discount_label FROM sales.deposit_notice_item"
            + " WHERE deposit_notice_id = :id AND seq = 1", Map.of("id", notice.id()), String.class);
    }

    @Test
    void anIssuedNotice_stillPrintsItsOldComputedDiscount() throws Exception {
        jdbc.update("UPDATE sales.deposit_notice SET status = 'ISSUED', doc_number = 'V228-1' WHERE deposit_notice_id = :id",
            Map.of("id", notice.id()));

        runMigration();

        assertThat(label()).isEqualTo("ลด 1,099.50");
        try (PDDocument doc = Loader.loadPDF(depositNoticeService.getPdfBundle(notice.id(), salesActor).pdf())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("ลด 1,099.50");
        }
    }

    @Test
    void anIssuedItemThatPrintedNothing_getsNoLabel() throws Exception {
        jdbc.update("UPDATE sales.deposit_notice SET status = 'ISSUED', doc_number = 'V228-2' WHERE deposit_notice_id = :id",
            Map.of("id", notice.id()));
        jdbc.update("UPDATE sales.deposit_notice_item SET net_unit_price = unit_price WHERE deposit_notice_id = :id AND seq = 1",
            Map.of("id", notice.id()));

        runMigration();

        assertThat(label()).isNull();
    }

    @Test
    void aDraftItem_hasItsLegacyLabelCleared() throws Exception {
        runMigration();

        assertThat(label()).isNull();
    }
}
