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
        // the pre-release shape: the quotation's bare description (no size line) and no label (V228 already nulled the legacy shapes)
        for (var line : repo.findQuotationInvoiceLines(quotation.id())) {
            jdbc.update("UPDATE sales.deposit_notice_item SET discount_label = NULL, description = :d"
                + " WHERE deposit_notice_id = :id AND seq = :seq",
                Map.of("d", line.description().split("\n", 2)[0], "id", notice.id(), "seq", line.seq()));
        }
        // untouched since the release: updated_at is at or before V228's installed_on
        jdbc.update("UPDATE sales.deposit_notice SET updated_at = (SELECT installed_on FROM hr.flyway_schema_history"
            + " WHERE version = '228') - interval '1 day' WHERE deposit_notice_id = :id", Map.of("id", notice.id()));
    }

    private Object updatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM sales.deposit_notice WHERE deposit_notice_id = :id",
            Map.of("id", notice.id()), java.sql.Timestamp.class);
    }

    private List<Map<String, Object>> items() {
        return jdbc.queryForList("SELECT seq, description, discount_label, amount FROM sales.deposit_notice_item"
            + " WHERE deposit_notice_id = :id ORDER BY seq", Map.of("id", notice.id()));
    }

    @Test
    void aMatchingDraft_getsTheQuotationLabelAndDescription() {
        List<DepositNoticeRepository.QuotationInvoiceLine> lines = repo.findQuotationInvoiceLines(quotation.id());

        Object stamp = updatedAt();

        assertThat(backfill.refresh()).isEqualTo(lines.size());

        assertThat(updatedAt()).as("the backfill leaves the one-shot marker alone").isEqualTo(stamp);

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

    /** A description hand-edited after the release is no longer a pre-release value: the next boot must not reset it. */
    @Test
    void aDraftWhoseDescriptionWasHandEdited_isNotTouched() {
        jdbc.update("UPDATE sales.deposit_notice_item SET description = 'แก้เอง' WHERE deposit_notice_id = :id AND seq = 1",
            Map.of("id", notice.id()));
        List<Map<String, Object>> before = items();

        backfill.refresh();

        assertThat(items().get(0)).isEqualTo(before.get(0));
        assertThat(items().get(0).get("description")).isEqualTo("แก้เอง");
    }

    /** A label the user typed (not null, not a legacy shape) is a hand edit too. */
    @Test
    void aDraftItemWithAHandTypedLabel_isNotTouched() {
        jdbc.update("UPDATE sales.deposit_notice_item SET discount_label = 'ลดพิเศษ 5 บาท' WHERE deposit_notice_id = :id AND seq = 1",
            Map.of("id", notice.id()));

        backfill.refresh();

        assertThat(items().get(0).get("discount_label")).isEqualTo("ลดพิเศษ 5 บาท");
    }

    /** A label the user cleared and saved after the release is null again, like a pre-release draft: it must stay
     * cleared on the next boot (the save bumped updated_at past V228's installed_on). */
    @Test
    void aDraftSavedAfterTheRelease_isNotTouched_evenWithANullLabel() {
        jdbc.update("UPDATE sales.deposit_notice SET updated_at = now() WHERE deposit_notice_id = :id",
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
