package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.deposit.DepositNoticeBackfill;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeDto;
import th.co.glr.hr.deposit.DepositNoticeRepository;

/** The startup backfill refreshes label + size line on untouched pre-release DRAFT notices only. */
class DepositNoticeBackfillIntegrationTest extends DirectQuotationDealTestBase {
    private DepositNoticeBackfill backfill;
    private DepositNoticeRepository repo;
    private DepositNoticeDto notice;
    private DealQuotationDto quotation;

    @BeforeEach
    void draftWithLegacyItems() {
        repo = new DepositNoticeRepository(jdbc);
        backfill = new DepositNoticeBackfill(jdbc, repo);
        long ticketId = quotationOnlyTicket(salesActor);
        quotation = approvedDirectQuotation(ticketId, salesActor);
        confirmer.confirmOrderFromDirectQuotation(quotation.id(), salesActor);
        notice = depositNoticeService.createDraft(ticketId,
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor);
        jdbc.update("UPDATE sales.deposit_notice_item SET discount_label = 'ส่วนลด 9 ต่อหน่วย', description = 'legacy'"
            + " WHERE deposit_notice_id = :id", Map.of("id", notice.id()));
    }

    private List<Map<String, Object>> items() {
        return jdbc.queryForList("SELECT seq, description, discount_label, amount FROM sales.deposit_notice_item"
            + " WHERE deposit_notice_id = :id ORDER BY seq", Map.of("id", notice.id()));
    }

    @Test
    void aMatchingDraft_getsTheQuotationLabelAndDescription() {
        List<DepositNoticeRepository.QuotationInvoiceLine> lines = repo.findQuotationInvoiceLines(quotation.id());

        assertThat(backfill.refresh()).isEqualTo(lines.size());

        List<Map<String, Object>> items = items();
        for (int i = 0; i < lines.size(); i++) {
            assertThat(items.get(i).get("description")).isEqualTo(lines.get(i).description());
            assertThat(items.get(i).get("discount_label")).isEqualTo(lines.get(i).discountLabel());
        }
    }

    @Test
    void aDraftWithOneAmountChanged_isLeftAsIs() {
        jdbc.update("UPDATE sales.deposit_notice_item SET amount = amount + 1 WHERE deposit_notice_id = :id AND seq = 1",
            Map.of("id", notice.id()));
        List<Map<String, Object>> before = items();

        assertThat(backfill.refresh()).isZero();

        assertThat(items()).isEqualTo(before);
    }

    @Test
    void anIssuedNotice_isNeverTouched() {
        jdbc.update("UPDATE sales.deposit_notice SET status = 'ISSUED', doc_number = 'BACKFILL-1' WHERE deposit_notice_id = :id",
            Map.of("id", notice.id()));
        List<Map<String, Object>> before = items();

        assertThat(backfill.refresh()).isZero();

        assertThat(items()).isEqualTo(before);
    }

    @Test
    void aSecondRun_changesNothing() {
        backfill.refresh();
        List<Map<String, Object>> after = items();

        assertThat(backfill.refresh()).isZero();

        assertThat(items()).isEqualTo(after);
    }
}
