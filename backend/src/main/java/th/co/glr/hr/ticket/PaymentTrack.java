package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The customer-payment-track state machine (dual-track ข้อ 13). PaymentStage is a derived DISPLAY value
 * (DEPOSIT_PENDING/BALANCE_PENDING/…) computed fresh on every read by TicketRepository.derivePaymentStage from
 * payable/paid amounts — it is never persisted and never validated as a state machine. */
public final class PaymentTrack {
    public static final String CUSTOMER_CONFIRMED    = "CUSTOMER_CONFIRMED";
    public static final String DEPOSIT_NOTICE_ISSUED = "DEPOSIT_NOTICE_ISSUED";
    public static final String DEPOSIT_PAID          = "DEPOSIT_PAID";
    public static final String AWAITING_FINAL_PAYMENT = "AWAITING_FINAL_PAYMENT";
    public static final String FULLY_PAID            = "FULLY_PAID";

    /** Exactly the five literals every write site has ever used; mirrored by V142's CHECK. */
    public static final Set<String> VALUES = Set.of(
        CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT, FULLY_PAID);

    // The REQUIRED path, in order. DEPOSIT_NOTICE_ISSUED and DEPOSIT_PAID exist ONLY here — a bypass policy's
    // payment track never visits either (see BYPASS_PATH below).
    private static final List<String> REQUIRED_PATH = List.of(
        CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT, FULLY_PAID);
    // NOT_REQUIRED / WAIVED / CREDIT_CUSTOMER: the deposit half of the REQUIRED path is skipped entirely —
    // CUSTOMER_CONFIRMED goes straight to AWAITING_FINAL_PAYMENT.
    private static final List<String> BYPASS_PATH = List.of(
        CUSTOMER_CONFIRMED, AWAITING_FINAL_PAYMENT, FULLY_PAID);

    // Single-hop forward/lateral transitions for the REQUIRED path. DEPOSIT_NOTICE_ISSUED's own entry is the ONE
    // place a self-loop is legal anywhere in this machine.
    private static final Map<String, Set<String>> REQUIRED_ALLOWED = Map.of(
        CUSTOMER_CONFIRMED,     Set.of(DEPOSIT_NOTICE_ISSUED),
        DEPOSIT_NOTICE_ISSUED,  Set.of(DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID),
        DEPOSIT_PAID,           Set.of(AWAITING_FINAL_PAYMENT),
        AWAITING_FINAL_PAYMENT, Set.of(FULLY_PAID),
        FULLY_PAID,             Set.<String>of());

    // Single-hop forward/lateral transitions for the bypass path.
    private static final Map<String, Set<String>> BYPASS_ALLOWED = Map.of(
        CUSTOMER_CONFIRMED,     Set.of(AWAITING_FINAL_PAYMENT),
        AWAITING_FINAL_PAYMENT, Set.of(FULLY_PAID),
        FULLY_PAID,             Set.<String>of());

    /** Strict single-hop check: from == null is legal and permits ONLY CUSTOMER_CONFIRMED (the one entry edge —
     * sales.ticket.payment_status starts NULL, V6/V39/V44). */
    public static boolean canTransition(String policy, String from, String to) {
        if (!DepositPolicy.isValid(policy) || !isValid(to)) {
            return false;
        }
        if (from == null) {
            return CUSTOMER_CONFIRMED.equals(to);
        }
        if (!isValid(from)) {
            return false;
        }
        Map<String, Set<String>> allowed =
            DepositPolicy.bypassesDepositNotice(policy) ? BYPASS_ALLOWED : REQUIRED_ALLOWED;
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isValid(String status) {
        return status != null && VALUES.contains(status);
    }

    /** The ordered real-state path for a policy (never includes null — the universal pre-entry condition every
     * policy shares alike). Used by tests and by #stepsBetween. */
    public static List<String> path(String policy) {
        if (!DepositPolicy.isValid(policy)) {
            throw new IllegalStateException("Unknown deposit policy: " + policy);
        }
        return DepositPolicy.bypassesDepositNotice(policy) ? BYPASS_PATH : REQUIRED_PATH;
    }

    /** The ordered list of hops from from (exclusive) to to (inclusive) — the "walk, don't skip" primitive
     * described in this class's own Javadoc. Every returned hop independently satisfies #canTransition. from ==
     * null walks from the very start of the resolved policy's path. */
    public static List<String> stepsBetween(String policy, String from, String to) {
        if (!isValid(to)) {
            throw new IllegalStateException("Invalid target payment status: " + to);
        }
        if (from != null && from.equals(to)) {
            if (!canTransition(policy, from, to)) {
                throw new IllegalStateException(
                    "Illegal payment-track self-loop: " + from + " -> " + to + " under policy " + policy);
            }
            return List.of(to);
        }
        List<String> path = path(policy); // throws IllegalStateException on an unrecognised policy
        int toIndex = path.indexOf(to);
        if (toIndex < 0) {
            throw new IllegalStateException(
                "Payment status " + to + " is not on the " + policy + " payment-track path");
        }
        int fromIndex;
        if (from == null) {
            fromIndex = -1; // one position before the path's first real state
        } else {
            fromIndex = path.indexOf(from);
            if (fromIndex < 0) {
                throw new IllegalStateException(
                    "Payment status " + from + " is not on the " + policy + " payment-track path");
            }
        }
        if (toIndex <= fromIndex) {
            throw new IllegalStateException(
                "No forward payment-track path from " + from + " to " + to + " under policy " + policy);
        }
        List<String> hops = path.subList(fromIndex + 1, toIndex + 1);
        // Defense in depth: re-validate every hop individually against canTransition rather than trusting the
        // path-index arithmetic alone.
        String prev = from;
        for (String hop : hops) {
            if (!canTransition(policy, prev, hop)) {
                throw new IllegalStateException(
                    "Illegal payment-track hop: " + prev + " -> " + hop + " under policy " + policy);
            }
            prev = hop;
        }
        return List.copyOf(hops);
    }

    private PaymentTrack() {}
}
