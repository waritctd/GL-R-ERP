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

    // ── expectedQuarter (คาดปิด) ─────────────────────────────────────────────────────────────────

    @Test
    void expectedQuarterIsOnTheSingleReadAndTheListRow_forTheOwnerCeoAndManager() {
        long id = deal(salesActor);
        tickets.updateExpectedQuarter(id, "2026-Q4");

        assertThat(ticketService.get(id, salesActor).summary().expectedQuarter()).isEqualTo("2026-Q4");
        assertThat(row(ticketService.list(null, salesActor), id).expectedQuarter()).isEqualTo("2026-Q4");
        assertThat(row(ticketService.list(null, ceoActor), id).expectedQuarter()).isEqualTo("2026-Q4");
        assertThat(row(ticketService.list(null, salesManagerActor), id).expectedQuarter()).isEqualTo("2026-Q4");
    }

    @Test
    void expectedQuarterIsNullWhenTheRepNeverSetOne() {
        long id = deal(salesActor);

        assertThat(ticketService.get(id, salesActor).summary().expectedQuarter()).isNull();
    }

    @Test
    void accountReadsExpectedQuarterAndReceiptSource_onADealInItsScope() {
        long id = orderDeal(salesActor);
        tickets.updateExpectedQuarter(id, "2027-Q1");
        receipt(id, "MANUAL", null);

        TicketSummaryDto row = row(ticketService.list(null, accountActor), id);

        assertThat(row.expectedQuarter()).isEqualTo("2027-Q1");
        assertThat(row.receiptSource()).isEqualTo("MANUAL");
    }

    // ── wrong way round: import's no-money summary gains neither field ───────────────────────────

    @Test
    void importNeverReceivesExpectedQuarterOrReceiptSource_onADealItCanList() {
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
    void importsPagedListIsProjectedTheSameWay() {
        long id = orderDeal(salesActor);
        tickets.updateExpectedQuarter(id, "2026-Q4");
        receipt(id, "EXPRESS", null);

        TicketSummaryDto importRow = row(ticketService.listPage(null, importActor,
            th.co.glr.hr.common.PageRequest.resolve(0, 50)).items(), id);

        assertThat(importRow.expectedQuarter()).isNull();
        assertThat(importRow.receiptSource()).isNull();
    }

    // ── wrong way round: a rep's list never carries another rep's deal ──────────────────────────

    @Test
    void aRepsListNeverCarriesAnotherRepsDeal_withItsQuarterOrReceipts() {
        long theirs = orderDeal(otherSalesActor);
        tickets.updateExpectedQuarter(theirs, "2026-Q4");
        receipt(theirs, "EXPRESS", null);
        long mine = deal(salesActor);

        List<TicketSummaryDto> list = ticketService.list(null, salesActor);

        assertThat(list).extracting(TicketSummaryDto::id).contains(mine).doesNotContain(theirs);
    }

    // ── receiptSource ───────────────────────────────────────────────────────────────────────────

    @Test
    void receiptSourceIsExpress_whenEveryReceiptCameFromTheExpressFileOrIsLinkedToAnExpressDocument() {
        long id = orderDeal(salesActor);
        receipt(id, "EXPRESS", null);
        receipt(id, "MANUAL", "RE6900123"); // typed by hand, later matched to an Express document (V214)

        assertThat(summary(id).receiptSource()).isEqualTo("EXPRESS");
    }

    @Test
    void receiptSourceIsManual_whenNoReceiptCameFromExpress() {
        long id = orderDeal(salesActor);
        receipt(id, "MANUAL", null);
        receipt(id, "MANUAL", null);

        assertThat(summary(id).receiptSource()).isEqualTo("MANUAL");
    }

    @Test
    void receiptSourceIsMixed_whenSomeReceiptsAreExpressAndSomeAreNot() {
        long id = orderDeal(salesActor);
        receipt(id, "EXPRESS", null);
        receipt(id, "MANUAL", null);

        assertThat(summary(id).receiptSource()).isEqualTo("MIXED");
    }

    @Test
    void receiptSourceIsNull_whenTheDealHasNoReceiptAtAll() {
        long id = orderDeal(salesActor);

        assertThat(summary(id).receiptSource()).isNull();
    }

    @Test
    void receiptSourceIsComputedPerDeal_onOneListPage() {
        long express = orderDeal(salesActor);
        receipt(express, "EXPRESS", null);
        long manual = orderDeal(salesActor);
        receipt(manual, "MANUAL", null);
        long none = orderDeal(salesActor);

        List<TicketSummaryDto> list = ticketService.list(null, ceoActor);

        assertThat(row(list, express).receiptSource()).isEqualTo("EXPRESS");
        assertThat(row(list, manual).receiptSource()).isEqualTo("MANUAL");
        assertThat(row(list, none).receiptSource()).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A deal at ORDER_RECEIVED: inside both import's and account's list scope (TicketRepository#appendRoleScope). */
    private long orderDeal(UserPrincipal owner) {
        long id = deal(owner);
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED' WHERE ticket_id = :id", Map.of("id", id));
        return id;
    }

    private void receipt(long ticketId, String source, String expressDocNo) {
        jdbc.update("""
            INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, source, express_doc_no, receipt_ref)
            VALUES (:t, 'DEPOSIT', :a, :by, :source, :doc, :ref)""", new MapSqlParameterSource()
            .addValue("t", ticketId).addValue("a", new BigDecimal("1000.00")).addValue("by", salesActor.id())
            .addValue("source", source).addValue("doc", expressDocNo)
            .addValue("ref", "S6-" + ticketId + "-" + System.nanoTime()));
    }

    private static TicketSummaryDto row(List<TicketSummaryDto> rows, long id) {
        return rows.stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new AssertionError("deal " + id + " not in the list"));
    }
}
