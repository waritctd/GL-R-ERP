package th.co.glr.hr.salesboard.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Candidate;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Match;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Reason;
import th.co.glr.hr.salesboard.receipts.ReceiptMatcher.Rule;

/** The pure matching rules (plan task 3.3): invoice no, then quotation no, then customer + amount — and never a guess. */
class ReceiptMatcherTest {

    private static ParsedReceipt row(String customer, String amount, String reference) {
        return new ParsedReceipt(2, "RE-6911-0057", "RE", LocalDate.of(2026, 11, 12), customer,
            new BigDecimal(amount), reference);
    }

    private static Candidate deal(long id, String customer, Set<String> invoices, Set<String> quotations,
                                  String... openExpected) {
        return new Candidate(id, Set.of(customer), invoices, quotations,
            java.util.Arrays.stream(openExpected).map(BigDecimal::new).toList());
    }

    // ── rule 1: invoice / deposit-notice number ──────────────────────────────

    @Test
    void invoiceNumberInReferenceWins_evenOverAnotherDealsQuotationAndCustomer() {
        Candidate byInvoice = deal(1, "บริษัท อื่น จำกัด", Set.of("DN6900023"), Set.of());
        Candidate byQuote = deal(2, "ลุมพินี เรสซิเดนซ์", Set.of(), Set.of("QN6900031"), "372000");
        Match m = ReceiptMatcher.match(row("ลุมพินี เรสซิเดนซ์", "372000", " dn6900023 "), List.of(byInvoice, byQuote));
        assertThat(m.ticketId()).isEqualTo(1L);
        assertThat(m.rule()).isEqualTo(Rule.INVOICE_NO);
    }

    @Test
    void sameInvoiceNumberOnTwoDealsIsAmbiguous_notAGuess_andDoesNotFallThrough() {
        Candidate a = deal(1, "ลุมพินี", Set.of("DN1"), Set.of(), "100");
        Candidate b = deal(2, "ลุมพินี", Set.of("DN1"), Set.of());
        Match m = ReceiptMatcher.match(row("ลุมพินี", "100", "DN1"), List.of(a, b));
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(Reason.AMBIGUOUS);
    }

    // ── rule 3: customer + amount ────────────────────────────────────────────

    @Test
    void amountToleranceIsOnePercentOrTenBaht_whicheverIsWider() {
        Candidate d = deal(3, "ลูกค้า", Set.of(), Set.of(), "100000");
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "100999", null), List.of(d)).matched()).isTrue();  // 0.999%
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "101001", null), List.of(d)).matched()).isFalse(); // > 1%
        Candidate small = deal(4, "ลูกค้า", Set.of(), Set.of(), "500");
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "509.99", null), List.of(small)).matched()).isTrue(); // ฿10
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "510.01", null), List.of(small)).matched()).isFalse();
    }

}
