package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * {@code TicketSummaryDto.depositDue}: the deposit-notice total the customer is asked to pay now
 * (VAT-inclusive {@code total_payable} of the newest ISSUED notice) while the deal is waiting on
 * that deposit ({@code DEPOSIT_NOTICE_ISSUED}); {@code null} otherwise and for the import role.
 * Account's รอรับมัดจำ dashboard tile shows this, not the whole-deal outstanding.
 */
class TicketDepositDueIntegrationTest extends AbstractPostgresIntegrationTest {
    private TicketRepository tickets;
    private long repId;

    @BeforeEach
    void seed() {
        tickets = new TicketRepository(jdbc);
        long divisionId = jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th) VALUES ('SA-DD', 'Sales DD') RETURNING division_id
            """, Map.of(), Number.class).longValue();
        repId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, division_id, is_active)
            VALUES ('EMP-DD', 'EMP-DD', :divisionId, TRUE) RETURNING employee_id
            """, Map.of("divisionId", divisionId), Number.class).longValue();
    }

    @Test
    void waitingOnTheDeposit_serves_theIssuedNoticeTotalPayable_onTheListRowAndTheSingleRead() {
        long id = deal("DEPOSIT_NOTICE_ISSUED");
        notice(id, 1, "ISSUED", "85418.10");

        assertThat(listRow(id).depositDue()).isEqualByComparingTo("85418.10");
        assertThat(tickets.findSummaryById(id).orElseThrow().depositDue()).isEqualByComparingTo("85418.10");
    }

    @Test
    void severalNotices_serveTheNewestIssuedOne_notADraftOrSupersededOne() {
        long id = deal("DEPOSIT_NOTICE_ISSUED");
        notice(id, 1, "SUPERSEDED", "90000.00");
        notice(id, 2, "ISSUED", "85418.10");
        notice(id, 3, "DRAFT", "70000.00");

        assertThat(listRow(id).depositDue()).isEqualByComparingTo("85418.10");
    }

    @Test
    void isNull_whenThereIsNoIssuedNotice() {
        long onlyDraft = deal("DEPOSIT_NOTICE_ISSUED");
        notice(onlyDraft, 1, "DRAFT", "85418.10");
        long none = deal("DEPOSIT_NOTICE_ISSUED");

        assertThat(listRow(onlyDraft).depositDue()).isNull();
        assertThat(listRow(none).depositDue()).isNull();
    }

    @Test
    void isNull_onceTheDealIsPastTheDepositStep_evenWithAnIssuedNoticeOnFile() {
        for (String status : new String[] {"DEPOSIT_PAID", "AWAITING_FINAL_PAYMENT", "FULLY_PAID", "CUSTOMER_CONFIRMED"}) {
            long id = deal(status);
            notice(id, 1, "ISSUED", "85418.10");

            assertThat(listRow(id).depositDue()).as(status).isNull();
            assertThat(tickets.findSummaryById(id).orElseThrow().depositDue()).as(status).isNull();
        }
    }

    @Test
    void importRoleSummary_hasNoDepositDue_butTheSourceRowDid() {
        long id = deal("DEPOSIT_NOTICE_ISSUED");
        notice(id, 1, "ISSUED", "85418.10");
        TicketSummaryDto row = listRow(id);

        assertThat(row.depositDue()).isNotNull();
        assertThat(row.withoutMoney().depositDue()).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private long deal(String paymentStatus) {
        long id = tickets.create(
            new CreateTicketRequest("ดีลทดสอบ", "NORMAL", "ลูกค้าทดสอบ", null, null, null, null, null, List.of()),
            tickets.nextTicketCode(), repId, "EMP-DD");
        tickets.updatePaymentStatusUnchecked(id, paymentStatus);
        return id;
    }

    /** 30% deposit on a 213,545.25 deal would be 64,063.58; the figure that matters is the stored total_payable. */
    private void notice(long ticketId, int version, String status, String totalPayable) {
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, :v, :s, 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, :total)""",
            new MapSqlParameterSource("id", ticketId).addValue("v", version).addValue("s", status)
                .addValue("total", new BigDecimal(totalPayable)));
    }

    private TicketSummaryDto listRow(long ticketId) {
        return tickets.findSummaries(null, null).stream().filter(r -> r.id() == ticketId).findFirst().orElseThrow();
    }
}
