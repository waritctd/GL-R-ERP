package th.co.glr.hr.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.ticket.QuietRule;

/** The digest queries on a small fixture: only the deal waiting on a deposit confirmation reaches account's list,
 * only the long-quiet deal reaches its owner's stale list, and receipts are summed per deal owner in the window. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class WeeklyDigestIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    @Test
    void queriesReturnTheRightRows() {
        WeeklyDigestRepository repo = new WeeklyDigestRepository(jdbc, new QuietRule(jdbc, 14));
        long waiting = deal(salesActor);
        jdbc.update("UPDATE sales.ticket SET status = 'quotation_issued', payment_status = 'DEPOSIT_NOTICE_ISSUED',"
            + " created_at = now() - interval '20 days' WHERE ticket_id = :t", Map.of("t", waiting));
        jdbc.update("UPDATE sales.ticket_event SET created_at = now() - interval '20 days' WHERE ticket_id = :t",
            Map.of("t", waiting));
        jdbc.update("UPDATE sales.deal_activity SET created_at = now() - interval '20 days' WHERE ticket_id = :t",
            Map.of("t", waiting));
        long fresh = deal(salesActor);

        var items = repo.accountItems();
        assertThat(items).extracting(WeeklyDigestRepository.AccountItem::ticketId).contains(waiting)
            .doesNotContain(fresh);
        assertThat(items.stream().filter(i -> i.ticketId() == waiting).findFirst().orElseThrow().daysWaiting())
            .isGreaterThanOrEqualTo(20);

        assertThat(repo.staleDeals(salesActor.id())).extracting(WeeklyDigestRepository.StaleDeal::ticketId)
            .contains(waiting).doesNotContain(fresh);
        assertThat(repo.openDealCounts()).containsKey(salesActor.id());

        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, received_at, recorded_by)
            VALUES (:t, 'DEPOSIT', 500, :at, :by)
            """, Map.of("t", waiting, "at", Timestamp.from(Instant.parse("2026-11-24T03:00:00Z")),
                "by", salesManagerActor.id()));
        assertThat(repo.receivedByOwner(Instant.parse("2026-11-22T17:00:00Z"), Instant.parse("2026-11-29T17:00:00Z")))
            .containsEntry(salesActor.id(), new BigDecimal("500.00"));
    }
}
