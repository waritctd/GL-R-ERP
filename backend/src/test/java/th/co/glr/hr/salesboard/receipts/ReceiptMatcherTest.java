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
    void invoiceVersionSuffixIsIgnoredOnBothSides() {
        Candidate d = deal(1, "x", Set.of("GLR6900031-2"), Set.of());
        assertThat(ReceiptMatcher.match(row("y", "1", "GLR6900031"), List.of(d)).ticketId()).isEqualTo(1L);
        assertThat(ReceiptMatcher.match(row("y", "1", "GLR6900031-3"), List.of(d)).ticketId()).isEqualTo(1L);
    }

    @Test
    void sameInvoiceNumberOnTwoDealsIsAmbiguous_notAGuess_andDoesNotFallThrough() {
        Candidate a = deal(1, "ลุมพินี", Set.of("DN1"), Set.of(), "100");
        Candidate b = deal(2, "ลุมพินี", Set.of("DN1"), Set.of());
        Match m = ReceiptMatcher.match(row("ลุมพินี", "100", "DN1"), List.of(a, b));
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(Reason.AMBIGUOUS);
    }

    // ── rule 2: quotation number ─────────────────────────────────────────────

    @Test
    void aLongNumericTailIsPartOfTheNumber_soTwoNumbersOfTheSameYearNeverLookAlike() {
        Candidate d = deal(1, "x", Set.of(), Set.of("QT-2026-0188"));
        assertThat(ReceiptMatcher.match(row("y", "1", "QT-2026-0201"), List.of(d)).matched()).isFalse();
        assertThat(ReceiptMatcher.match(row("y", "1", "QT-2026-0188-2"), List.of(d)).ticketId()).isEqualTo(1L);
    }

    @Test
    void quotationNumberMatchesCaseInsensitivelyWithARevisionSuffix() {
        Candidate d = deal(7, "x", Set.of(), Set.of("QN6900188"));
        Match m = ReceiptMatcher.match(row("y", "1", "qn6900188-2"), List.of(d));
        assertThat(m.ticketId()).isEqualTo(7L);
        assertThat(m.rule()).isEqualTo(Rule.QUOTATION_NO);
    }

    @Test
    void aReferenceThatIsNotADocumentNumberFallsToCustomerAndAmount() {
        Candidate d = deal(7, "บริษัท ลุมพินี เรสซิเดนซ์ จำกัด", Set.of(), Set.of("QN1"), "372000");
        Match m = ReceiptMatcher.match(row("บจก. ลุมพินี เรสซิเดนซ์", "372000", "มัดจำ บ้านสวน"), List.of(d));
        assertThat(m.ticketId()).isEqualTo(7L);
        assertThat(m.rule()).isEqualTo(Rule.CUSTOMER_AMOUNT);
    }

    // ── rule 3: customer + amount ────────────────────────────────────────────

    @Test
    void customerNamesNormaliseCompanyWordsSpacesAndCase() {
        assertThat(ReceiptMatcher.normaliseCustomer("  บริษัท  ลุมพินี   เรสซิเดนซ์ จำกัด (มหาชน) "))
            .isEqualTo(ReceiptMatcher.normaliseCustomer("ลุมพินี เรสซิเดนซ์"));
        assertThat(ReceiptMatcher.normaliseCustomer("Siam Mall Co.,Ltd."))
            .isEqualTo(ReceiptMatcher.normaliseCustomer("siam mall"));
        assertThat(ReceiptMatcher.normaliseCustomer("บจก. ทวีทรัพย์"))
            .isEqualTo(ReceiptMatcher.normaliseCustomer("ทวีทรัพย์"));
    }

    @Test
    void amountToleranceIsOnePercentOrTenBaht_whicheverIsWider() {
        Candidate d = deal(3, "ลูกค้า", Set.of(), Set.of(), "100000");
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "100999", null), List.of(d)).matched()).isTrue();  // 0.999%
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "101001", null), List.of(d)).matched()).isFalse(); // > 1%
        Candidate small = deal(4, "ลูกค้า", Set.of(), Set.of(), "500");
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "509.99", null), List.of(small)).matched()).isTrue(); // ฿10
        assertThat(ReceiptMatcher.match(row("ลูกค้า", "510.01", null), List.of(small)).matched()).isFalse();
    }

    @Test
    void twoDealsOfTheSameCustomerBothExpectingThatAmountIsAmbiguous() {
        Candidate a = deal(1, "ลุมพินี เรสซิเดนซ์", Set.of(), Set.of(), "372000");
        Candidate b = deal(2, "ลุมพินี เรสซิเดนซ์", Set.of(), Set.of(), "372000");
        Match m = ReceiptMatcher.match(row("ลุมพินี เรสซิเดนซ์", "372000", null), List.of(a, b));
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(Reason.AMBIGUOUS);
        assertThat(m.candidateIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void sameCustomerWithNoOpenExpectedPaymentNearTheAmountIsNoCandidate_listingTheCustomersDeals() {
        Candidate a = deal(1, "ลุมพินี", Set.of(), Set.of(), "360000");
        Candidate other = deal(9, "คนอื่น", Set.of(), Set.of(), "372000");
        Match m = ReceiptMatcher.match(row("ลุมพินี", "372000", null), List.of(a, other));
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(Reason.NO_CANDIDATE);
        assertThat(m.candidateIds()).as("the customer's deals are offered for a one-tap match").containsExactly(1L);
    }

    @Test
    void noCustomerNameNeverMatchesByAmountAlone() {
        Candidate a = deal(1, "", Set.of(), Set.of(), "372000");
        Match m = ReceiptMatcher.match(row("", "372000", null), List.of(a));
        assertThat(m.matched()).isFalse();
        assertThat(m.candidateIds()).isEmpty();
    }

    @Test
    void emptyCandidatesIsNoCandidate() {
        Match m = ReceiptMatcher.match(row("ใคร", "1", "DN9"), List.of());
        assertThat(m.matched()).isFalse();
        assertThat(m.reason()).isEqualTo(Reason.NO_CANDIDATE);
    }
}
