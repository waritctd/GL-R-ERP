package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import th.co.glr.hr.dealquotation.AbstractDealQuotationSlice2IntegrationTest;
import th.co.glr.hr.salesboard.CashRepository;

/**
 * The money read by everything downstream — including commission — must not depend on WHERE a receipt came from
 * (MANUAL typed by account vs EXPRESS uploaded). Two halves:
 * <ol>
 *   <li>Structural: the commission package does not read {@code sales.payment_receipt} at all (it reads its own
 *       {@code commission_record.actual_received}, typed by account per invoice, and the deal's CLOSED_PAID stage). If
 *       someone couples commission to receipts later, this fails and makes them decide about {@code source}
 *       deliberately — a receipt-reading commission path must give the same answer for both sources.</li>
 *   <li>Behavioural (real Postgres): the same receipts as MANUAL or as EXPRESS give the same paid amount, the same
 *       deal summary, and the same month cash — the inputs CLOSED_PAID, and so commission submission, keys on.</li>
 * </ol>
 * That the upload's dedupe never drops or rewrites an existing receipt is pinned in
 * {@code ReceiptImportIntegrationTest#aReceiptAlreadyTypedByHandWithTheSameNumberIsCountedOnce_andIsNotTouched}.
 * Annotated itself — @EnabledIf is not inherited.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class ReceiptSourceInvarianceIntegrationTest extends AbstractDealQuotationSlice2IntegrationTest {

    @Test
    void theCommissionPackageReadsNoPaymentReceipts() throws IOException {
        Path dir = Path.of("src/main/java/th/co/glr/hr/commission");
        assertThat(dir).isDirectory();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    return src.contains("payment_receipt") || src.contains("PaymentReceipt")
                        || src.contains("sumPaid") || src.contains("findReceiptsByTicket");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).toList();
            assertThat(offenders).as("commission must stay source-agnostic; see this class's Javadoc").isEmpty();
        }
    }

    @Test
    void sameReceiptsGiveTheSamePaidSummaryAndCash_whateverTheirSource() {
        long manual = deal(salesActor);
        long express = deal(otherSalesActor);
        for (long d : List.of(manual, express)) {
            jdbc.update("UPDATE sales.ticket SET sales_stage = 'DELIVERED', deposit_policy = 'REQUIRED',"
                + " payment_status = 'AWAITING_FINAL_PAYMENT' WHERE ticket_id = :t", Map.of("t", d));
        }
        receipts(manual, "MANUAL");
        receipts(express, "EXPRESS");

        assertThat(tickets.sumPaid(express)).isEqualByComparingTo(tickets.sumPaid(manual)).isEqualByComparingTo("990");
        assertThat(summary(express).amountPaid()).isEqualByComparingTo(summary(manual).amountPaid());
        assertThat(summary(express).amountOutstanding()).isEqualByComparingTo(summary(manual).amountOutstanding());
        assertThat(summary(express).paymentStatus()).isEqualTo(summary(manual).paymentStatus());

        CashRepository cash = new CashRepository(jdbc);
        BigDecimal manualCash = monthReceived(cash, manual);
        BigDecimal expressCash = monthReceived(cash, express);
        assertThat(expressCash).isEqualByComparingTo(manualCash).isEqualByComparingTo("990");

        // Flipping the source of existing rows changes nothing either.
        BigDecimal before = tickets.sumPaid(manual);
        jdbc.update("UPDATE sales.payment_receipt SET source = 'EXPRESS', receipt_ref = receipt_ref || '-X'"
            + " WHERE ticket_id = :t", Map.of("t", manual));
        assertThat(tickets.sumPaid(manual)).isEqualByComparingTo(before);
    }

    /**
     * Behavioural half of §11 (review LOW): an Express receipt — the only write an upload makes — can never move a deal
     * to CLOSED_PAID, the stage commission submission keys on (CommissionService#resolveDealLinkage), and creates no
     * commission record. The same full payment typed by hand DOES close it: the difference is the source alone.
     */
    @Test
    void anExpressReceiptNeverClosesTheDealOrMakesItCommissionEligible_theManualOneDoes() {
        th.co.glr.hr.auth.UserPrincipal account = actor(createEmployee("บัญชี", "acc-inv@glr.co.th", "ACC", "บัญชี",
            null), "account");
        long express = deliveredOwing(salesActor);
        long manual = deliveredOwing(otherSalesActor);

        ticketService.recordExpressReceipt(express, new BigDecimal("1000"), java.time.Instant.now(), "RE-INV-1", null,
            account);
        ticketService.recordPayment(manual, new th.co.glr.hr.ticket.RecordPaymentRequest("BALANCE",
            new BigDecimal("1000"), java.time.Instant.parse("2026-01-01T00:00:00Z"), null, null, "MAN-INV-1", false),
            account);

        assertThat(tickets.sumPaid(express)).isEqualByComparingTo(tickets.sumPaid(manual));
        assertThat(summary(express).salesStage()).isEqualTo("DELIVERED");
        assertThat(summary(express).paymentStatus()).isNotEqualTo("FULLY_PAID");
        assertThat(summary(manual).salesStage()).isEqualTo("CLOSED_PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.commission_record WHERE source_ticket_id = :t",
            Map.of("t", express), Integer.class)).isZero();
    }

    /** DELIVERED and fully delivered, deposit settled, owing exactly ฿1,000 (an EN deposit notice is the payable). */
    private long deliveredOwing(th.co.glr.hr.auth.UserPrincipal owner) {
        long d = deal(owner);
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:t, 1, 'ISSUED', 'x', 1, 1000, 1000, 0, 0, 1000)""", Map.of("t", d));
        jdbc.update("""
            UPDATE sales.ticket SET sales_stage = 'DELIVERED', deposit_policy = 'NOT_REQUIRED',
                   payment_status = 'AWAITING_FINAL_PAYMENT', fulfillment_status = 'FULLY_DELIVERED'
             WHERE ticket_id = :t""", Map.of("t", d));
        return d;
    }

    private void receipts(long ticketId, String source) {
        String[][] rows = {{"DEPOSIT", "500"}, {"BALANCE", "600"}, {"ADJUSTMENT", "110"}};
        int i = 0;
        for (String[] r : rows) {
            jdbc.update("""
                INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by, receipt_ref, source,
                                                   received_at)
                VALUES (:t, :k, :a, :by, :ref, :src, '2026-11-10T05:00:00Z')
                """, Map.of("t", ticketId, "k", r[0], "a", new BigDecimal(r[1]), "by", salesManagerActor.id(),
                    "ref", source + "-" + ticketId + "-" + (i++), "src", source));
        }
    }

    /** Each deal has its own owner, so the owner's month is exactly that deal's cash. */
    private BigDecimal monthReceived(CashRepository cash, long ticketId) {
        long owner = summary(ticketId).createdById();
        return cash.receivedByDay(owner, YearMonth.of(2026, 11).atDay(1), LocalDate.of(2026, 11, 30)).stream()
            .map(CashRepository.ReceivedDayRow::received).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
