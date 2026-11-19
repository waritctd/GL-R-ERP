package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;

/**
 * Slice 6.3 (drawn details): {@code TicketSummaryDto.expectedQuarter} (V209 {@code sales.ticket.expected_quarter}, the
 * deal page's คาดปิด) and {@code TicketSummaryDto.receiptSource} (the finance worklist's Express / บันทึกเอง chip,
 * from {@code payment_receipt.source} V213 and {@code express_doc_no} V214), through the real {@link TicketService},
 * {@link TicketRepository} and Postgres.
 *
 * <p>Written wrong-way-round: the import role's no-money summary ({@link TicketSummaryDto#withoutMoney()}) must NOT
 * gain either field, and a rep's list must never carry another rep's deal. account / ceo / sales_manager / the owning
 * rep receive what they could already read. Annotated itself — {@code @EnabledIf} is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class SummaryDrawnDetailsIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    private UserPrincipal importActor;
    private UserPrincipal accountActor;
    private UserPrincipal ceoActor;

    @BeforeEach
    void roles() {
        importActor = actor(createEmployee("นำเข้า s6", "import-s6@glr.co.th", "IMP", "นำเข้า", null), "import");
        accountActor = actor(createEmployee("บัญชี s6", "account-s6@glr.co.th", "ACC", "บัญชี", null), "account");
        ceoActor = actor(createEmployee("ซีอีโอ s6", "ceo-s6@glr.co.th", "EXE", "ผู้บริหาร", null), "ceo");
    }

    @Test
    void importsListRowNeverCarriesExpectedQuarterOrReceiptSource() {
        long id = orderDeal(salesActor);
        tickets.updateExpectedQuarter(id, "2026-Q4");
        receipt(id, "EXPRESS", null);
        // The source row carries both — so a null on import's row is the projection, not missing data.
        TicketSummaryDto ceoRow = row(ticketService.list(null, ceoActor), id);
        assertThat(ceoRow.expectedQuarter()).isEqualTo("2026-Q4");
        assertThat(ceoRow.receiptSource()).isEqualTo("EXPRESS");

        TicketSummaryDto importRow = row(ticketService.list(null, importActor), id);

        assertThat(importRow.expectedQuarter()).isNull();
        assertThat(importRow.receiptSource()).isNull();
        // what that view already withheld stays withheld; what it already showed (the buyer's name) stays shown
        assertThat(importRow.amountPayable()).isNull();
        assertThat(importRow.paymentDueDate()).isNull();
    }

    @Test
    void aRepsListNeverCarriesAnotherRepsDeal_withItsQuarterOrReceipts() {
        long theirs = orderDeal(otherSalesActor);
        tickets.updateExpectedQuarter(theirs, "2026-Q4");
        receipt(theirs, "EXPRESS", null);
        long mine = deal(salesActor);

        List<TicketSummaryDto> list = ticketService.list(null, salesActor);

        assertThat(list).extracting(TicketSummaryDto::id).contains(mine).doesNotContain(theirs);
    }

    /** A deal at ORDER_RECEIVED: inside both import's and account's list scope (TicketRepository#appendRoleScope). */
    private long orderDeal(UserPrincipal owner) {
        long id = deal(owner);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :id", Map.of("id", id));
        return id;
    }

    private void receipt(long ticketId, String source, String expressDocNo) {
        receipt(ticketId, source, expressDocNo, "DEPOSIT");
    }

    /** Zero-amount receipts cannot exist (V53: CHECK amount > 0) and there is no void/reversal column, so ADJUSTMENT
     * is the only "money not received" kind a test can build. */
    private void receipt(long ticketId, String source, String expressDocNo, String kind) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, source, express_doc_no, receipt_ref)
            VALUES (:t, :kind, :a, :by, :source, :doc, :ref)""", new MapSqlParameterSource()
            .addValue("kind", kind)
            .addValue("t", ticketId).addValue("a", new BigDecimal("1000.00")).addValue("by", salesActor.id())
            .addValue("source", source).addValue("doc", expressDocNo)
            .addValue("ref", "S6-" + ticketId + "-" + System.nanoTime()));
    }

    private static TicketSummaryDto row(List<TicketSummaryDto> rows, long id) {
        return rows.stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new AssertionError("deal " + id + " not in the list"));
    }
}
