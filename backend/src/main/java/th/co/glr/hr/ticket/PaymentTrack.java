package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The customer-payment-track state machine (dual-track ข้อ 13). Modeled closely on {@code
 * th.co.glr.hr.pricingrequest.PricingRequestStatus} — the house pattern: {@code public final
 * class}, private constructor, {@code String} constants, a {@code VALUES} set, private {@code
 * ALLOWED} maps, static predicates.
 *
 * <p><strong>Not the same thing as {@link PaymentStage}.</strong> {@link PaymentStage} is a
 * derived DISPLAY value ({@code DEPOSIT_PENDING}/{@code BALANCE_PENDING}/…) computed fresh on
 * every read by {@code TicketRepository.derivePaymentStage} from payable/paid amounts — it is
 * never persisted and never validated as a state machine. {@code PaymentTrack} is the persisted
 * {@code sales.ticket.payment_status} column's OWN state machine. The two happen to share a
 * "payment" vocabulary and an era (both are ข้อ 13 / dual-track concepts) but are otherwise
 * unrelated; do not conflate, rename, or merge them.
 *
 * <p>Two parallel paths, chosen by {@link DepositPolicy#bypassesDepositNotice}:
 *
 * <pre>
 * REQUIRED:  null -&gt; CUSTOMER_CONFIRMED -&gt; DEPOSIT_NOTICE_ISSUED -&gt; DEPOSIT_PAID
 *                  -&gt; AWAITING_FINAL_PAYMENT -&gt; FULLY_PAID
 *   plus the ONE legal self-loop: DEPOSIT_NOTICE_ISSUED -&gt; DEPOSIT_NOTICE_ISSUED (a revision —
 *   re-issuing a deposit notice does not move the payment track, it re-confirms the same state)
 *
 * NOT_REQUIRED / WAIVED / CREDIT_CUSTOMER (bypass — skips the deposit half entirely):
 *            null -&gt; CUSTOMER_CONFIRMED -&gt; AWAITING_FINAL_PAYMENT -&gt; FULLY_PAID
 * </pre>
 *
 * <p><strong>"Walk, don't skip":</strong> {@link #canTransition} is a STRICT single-hop check —
 * exactly like {@code PricingRequestStatus.canTransition}, it refuses any multi-state jump.
 * {@link #stepsBetween} is the multi-hop WALKER built on top of it: a caller asking to go from an
 * earlier real state straight to a later one (e.g. {@code DEPOSIT_PAID -> FULLY_PAID} on the
 * REQUIRED path, which real production code paths do ask for — see {@code
 * TicketService.confirmFinalPayment}) is legal there. {@code stepsBetween} returns every
 * intermediate hop, each independently re-checked against {@link #canTransition}, so the machine
 * never actually grows a new "skip" edge — the jump is simply walked, all inside one call.
 * {@code TicketRepository.advancePaymentStatus} calls {@link #stepsBetween} purely to VALIDATE
 * (throwing before any SQL runs on an illegal request); the actual UPDATE still moves the row
 * straight from {@code expected} to {@code next} in a single compare-and-set, so no observer ever
 * sees an intermediate value and the timeline (ticket_event) is unaffected.
 */
public final class PaymentTrack {
    public static final String CUSTOMER_CONFIRMED    = "CUSTOMER_CONFIRMED";
    public static final String DEPOSIT_NOTICE_ISSUED = "DEPOSIT_NOTICE_ISSUED";
    public static final String DEPOSIT_PAID          = "DEPOSIT_PAID";
    public static final String AWAITING_FINAL_PAYMENT = "AWAITING_FINAL_PAYMENT";
    public static final String FULLY_PAID            = "FULLY_PAID";

    /** Exactly the five literals every write site has ever used; mirrored by V142's CHECK. */
    public static final Set<String> VALUES = Set.of(
        CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT, FULLY_PAID);

    // The REQUIRED path, in order. DEPOSIT_NOTICE_ISSUED and DEPOSIT_PAID exist ONLY here — a
    // bypass policy's payment track never visits either (see BYPASS_PATH below).
    private static final List<String> REQUIRED_PATH = List.of(
        CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT, FULLY_PAID);
    // NOT_REQUIRED / WAIVED / CREDIT_CUSTOMER: the deposit half of the REQUIRED path is skipped
    // entirely — CUSTOMER_CONFIRMED goes straight to AWAITING_FINAL_PAYMENT.
    private static final List<String> BYPASS_PATH = List.of(
        CUSTOMER_CONFIRMED, AWAITING_FINAL_PAYMENT, FULLY_PAID);

    // Single-hop forward/lateral transitions for the REQUIRED path. DEPOSIT_NOTICE_ISSUED's own
    // entry is the ONE place a self-loop is legal anywhere in this machine.
    private static final Map<String, Set<String>> REQUIRED_ALLOWED = Map.of(
        CUSTOMER_CONFIRMED,     Set.of(DEPOSIT_NOTICE_ISSUED),
        DEPOSIT_NOTICE_ISSUED,  Set.of(DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID),
        DEPOSIT_PAID,           Set.of(AWAITING_FINAL_PAYMENT),
        AWAITING_FINAL_PAYMENT, Set.of(FULLY_PAID),
        FULLY_PAID,             Set.<String>of());

    // Single-hop forward/lateral transitions for the bypass path. Deliberately has NO entry at
    // all for DEPOSIT_NOTICE_ISSUED/DEPOSIT_PAID: getOrDefault(..., Set.of()) in canTransition
    // then refuses both as a "from" under a bypass policy, and neither ever appears as a "to" in
    // any bypass entry's own Set either — so a bypass deal can never reach them in either
    // direction, matching DepositNoticeService.issue's own guard (site 2 in the branch plan).
    private static final Map<String, Set<String>> BYPASS_ALLOWED = Map.of(
        CUSTOMER_CONFIRMED,     Set.of(AWAITING_FINAL_PAYMENT),
        AWAITING_FINAL_PAYMENT, Set.of(FULLY_PAID),
        FULLY_PAID,             Set.<String>of());

    /**
     * Strict single-hop check: {@code from == null} is legal and permits ONLY {@code
     * CUSTOMER_CONFIRMED} (the one entry edge — {@code sales.ticket.payment_status} starts NULL,
     * V6/V39/V44). Returns {@code false} — never throws — for any reverse, any multi-state skip,
     * any state off the resolved policy's path, any unrecognized/null policy or status, and any
     * self-loop except {@code DEPOSIT_NOTICE_ISSUED -> DEPOSIT_NOTICE_ISSUED}. A boolean
     * predicate should never throw on merely-uninteresting input; see {@link #path} and {@link
     * #stepsBetween} for the throwing counterparts used where an unrecognised policy is instead a
     * data-integrity bug to surface loudly.
     *
     * <p>See {@link #stepsBetween} for the multi-hop walk built on top of this — a caller wanting
     * to advance several states in one call does not call this directly.
     */
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

    /**
     * The ordered real-state path for a policy (never includes {@code null} — the universal
     * pre-entry condition every policy shares alike). Used by tests and by {@link #stepsBetween}.
     *
     * @throws IllegalStateException if {@code policy} is not a recognised {@link DepositPolicy}
     *     value. {@code sales.ticket.deposit_policy} is {@code NOT NULL DEFAULT 'REQUIRED'} (V51)
     *     — an unrecognised value reaching here is a data-integrity bug to surface loudly, never a
     *     case to silently default to REQUIRED and paper over.
     */
    public static List<String> path(String policy) {
        if (!DepositPolicy.isValid(policy)) {
            throw new IllegalStateException("Unknown deposit policy: " + policy);
        }
        return DepositPolicy.bypassesDepositNotice(policy) ? BYPASS_PATH : REQUIRED_PATH;
    }

    /**
     * The ordered list of hops from {@code from} (exclusive) to {@code to} (inclusive) — the
     * "walk, don't skip" primitive described in this class's own Javadoc. Every returned hop
     * independently satisfies {@link #canTransition}. {@code TicketRepository.advancePaymentStatus}
     * calls this purely to validate (mirrors {@code PricingRequestRepository.transition}'s own
     * {@code canTransition} pre-check), throwing {@link IllegalStateException} — never {@code
     * ApiException} — before any SQL runs on an illegal request: an illegal {@code expected ->
     * next} pair means the CALLING CODE asked for something this state machine does not
     * recognise, a programming error to catch at the source, not a normal concurrent-user race
     * (that case is the repository's own compare-and-set 0-rowcount, which the service layer
     * turns into a 409).
     *
     * <p>{@code from == null} walks from the very start of the resolved policy's path. The ONE
     * self-loop ({@code DEPOSIT_NOTICE_ISSUED -> DEPOSIT_NOTICE_ISSUED}) returns exactly {@code
     * [DEPOSIT_NOTICE_ISSUED]} — it is not a "walk" in the forward-index sense at all, so it is
     * checked before the path-index math below ever runs.
     *
     * @throws IllegalStateException if {@code policy} is unrecognised (see {@link #path}), if
     *     {@code to} is not a real status, if {@code to} is not on the resolved policy's path at
     *     all (off-path — e.g. {@code DEPOSIT_PAID} under a bypass policy), if {@code from} is
     *     non-null but not on the path either, or if {@code to} is not strictly forward of {@code
     *     from} (a reverse, or a same-state pair other than the one legal self-loop).
     */
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
        // Defense in depth: re-validate every hop individually against canTransition rather than
        // trusting the path-index arithmetic alone. REQUIRED_ALLOWED/BYPASS_ALLOWED and
        // REQUIRED_PATH/BYPASS_PATH are maintained by hand and must never silently diverge — if
        // they ever did, this loop (not the index math above) is what would catch it.
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
