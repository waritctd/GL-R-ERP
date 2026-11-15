package th.co.glr.hr.salesboard.receipts;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Places one Express receipt row on a deal — or refuses to (plan task 3.3, spec §7.8). Pure: no I/O, so every rule is
 * unit-tested ({@code ReceiptMatcherTest}). Rules, in order; the first rule that finds ANY deal decides:
 * <ol>
 *   <li>{@link Rule#INVOICE_NO} — the row's reference equals a deposit-notice / remaining-invoice / billing-note
 *       number on the deal (case and spaces ignored, a trailing "-n" version ignored on both sides);</li>
 *   <li>{@link Rule#QUOTATION_NO} — the reference equals one of the deal's quotation numbers, same normalisation;</li>
 *   <li>{@link Rule#CUSTOMER_AMOUNT} — the deal's customer equals the row's ({@link #normaliseCustomer}) AND the deal
 *       has an OPEN expected payment within ±1% or ±฿10 of the amount, whichever is wider.</li>
 * </ol>
 * Exactly one deal → matched. Two or more at the deciding rule → {@link Reason#AMBIGUOUS}, never a guess, and the
 * later rules are NOT consulted (they would only pick among the same doubt more arbitrarily). None at any rule →
 * {@link Reason#NO_CANDIDATE}; the customer's deals (same normalised name) are returned as {@code candidateIds} so a
 * person can attach it in one tap. A blank customer name never matches by amount alone.
 *
 * <p>Provisional: no real Express export has been seen (spec §11). The rules are the plan's; only the parser's header
 * aliases are expected to change when a sample arrives.
 */
public final class ReceiptMatcher {
    private ReceiptMatcher() {}

    public enum Rule { INVOICE_NO, QUOTATION_NO, CUSTOMER_AMOUNT }

    /** Why a row was left unmatched. OVERPAYMENT / DEAL_NOT_ACTIVE are decided by the import service, not here. */
    public enum Reason { NO_CANDIDATE, AMBIGUOUS, OVERPAYMENT, DEAL_NOT_ACTIVE }

    /** A deal the row could belong to: its customer, its document numbers and its OPEN expected payment amounts. */
    public record Candidate(long ticketId, Set<String> customerNames, Set<String> invoiceNumbers,
                            Set<String> quotationNumbers, List<BigDecimal> openExpectedAmounts) {}

    /** {@code ticketId} set ⇔ matched. Unmatched carries a {@code reason} and the deals worth offering. */
    public record Match(Long ticketId, Rule rule, Reason reason, List<Long> candidateIds) {
        public boolean matched() {
            return ticketId != null;
        }
    }

    static final BigDecimal TOLERANCE_RATE = new BigDecimal("0.01");
    static final BigDecimal TOLERANCE_FLOOR = new BigDecimal("10");

    // A revision suffix is short ("-2"); a 3+ digit tail belongs to the number itself ("QT-2026-0201") and is never
    // stripped — stripping it would make every QT-2026-* number look alike.
    private static final Pattern VERSION_SUFFIX = Pattern.compile("-\\d{1,2}$");
    private static final Pattern NON_DOC = Pattern.compile("[\\s]");
    // Company-form words dropped before comparing names. Thai ones as substrings (Thai has no spaces between
    // words), longest first so "(มหาชน)" goes before "มหาชน"; Latin ones only as whole words, so "inc" never eats
    // the middle of "princess".
    private static final List<String> THAI_COMPANY_WORDS = List.of(
        "(มหาชน)", "มหาชน", "ห้างหุ้นส่วนจำกัด", "ห้างหุ้นส่วน", "บริษัท", "บจก.", "บจก", "บมจ.", "บมจ", "จำกัด",
        "หจก.", "หจก");
    private static final Pattern LATIN_COMPANY_WORDS = Pattern.compile(
        "(?<![a-z0-9])(co|ltd|limited|company|public|plc|inc|corp|corporation)(?![a-z0-9])");

    public static Match match(ParsedReceipt row, List<Candidate> candidates) {
        String ref = normaliseDoc(row.reference());
        if (ref != null) {
            List<Long> byInvoice = idsWhere(candidates, c -> containsDoc(c.invoiceNumbers(), ref));
            if (!byInvoice.isEmpty()) {
                return decide(byInvoice, Rule.INVOICE_NO);
            }
            List<Long> byQuote = idsWhere(candidates, c -> containsDoc(c.quotationNumbers(), ref));
            if (!byQuote.isEmpty()) {
                return decide(byQuote, Rule.QUOTATION_NO);
            }
        }
        String customer = normaliseCustomer(row.customerName());
        if (customer.isEmpty()) {
            return new Match(null, null, Reason.NO_CANDIDATE, List.of());
        }
        List<Candidate> sameCustomer = candidates.stream()
            .filter(c -> c.customerNames().stream().anyMatch(n -> customer.equals(normaliseCustomer(n)))).toList();
        List<Long> byAmount = idsWhere(sameCustomer, c -> c.openExpectedAmounts().stream()
            .anyMatch(expected -> withinTolerance(row.amount(), expected)));
        if (!byAmount.isEmpty()) {
            return decide(byAmount, Rule.CUSTOMER_AMOUNT);
        }
        return new Match(null, null, Reason.NO_CANDIDATE, sameCustomer.stream().map(Candidate::ticketId).toList());
    }

    /** |amount − expected| ≤ max(1% of expected, ฿10). */
    public static boolean withinTolerance(BigDecimal amount, BigDecimal expected) {
        if (amount == null || expected == null) {
            return false;
        }
        BigDecimal tolerance = expected.abs().multiply(TOLERANCE_RATE).max(TOLERANCE_FLOOR);
        return amount.subtract(expected).abs().compareTo(tolerance) <= 0;
    }

    /**
     * Trim, lower-case, NFC, drop company-form words (บริษัท / จำกัด / (มหาชน) / บจก. / Co.,Ltd. …), drop punctuation
     * left behind, collapse spaces. "" for a blank name.
     */
    public static String normaliseCustomer(String name) {
        if (name == null) {
            return "";
        }
        String s = " " + Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ").trim() + " ";
        for (String word : THAI_COMPANY_WORDS) {
            s = s.replace(word, " ");
        }
        s = s.replaceAll("[.,()&]", " ");
        s = LATIN_COMPANY_WORDS.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Upper-case, no spaces, no trailing "-n" version. Null for a blank reference. */
    static String normaliseDoc(String value) {
        if (value == null) {
            return null;
        }
        String s = NON_DOC.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
        s = VERSION_SUFFIX.matcher(s).replaceAll("");
        return s.isEmpty() ? null : s;
    }

    private static boolean containsDoc(Set<String> numbers, String ref) {
        return numbers != null && numbers.stream().anyMatch(n -> ref.equals(normaliseDoc(n)));
    }

    private static Match decide(List<Long> ids, Rule rule) {
        return ids.size() == 1
            ? new Match(ids.get(0), rule, null, ids)
            : new Match(null, rule, Reason.AMBIGUOUS, ids);
    }

    private static List<Long> idsWhere(List<Candidate> candidates, java.util.function.Predicate<Candidate> test) {
        List<Long> ids = new ArrayList<>();
        for (Candidate c : candidates) {
            if (test.test(c) && !ids.contains(c.ticketId())) {
                ids.add(c.ticketId());
            }
        }
        return ids;
    }
}
