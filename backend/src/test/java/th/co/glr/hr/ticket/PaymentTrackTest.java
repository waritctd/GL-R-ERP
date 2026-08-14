package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PaymentTrack} — the payment-track state machine — with no database
 * involved. Mirrors {@code th.co.glr.hr.pricingrequest.PricingRequestStatusTest}'s own shape:
 * exercise the decision function ({@link PaymentTrack#canTransition}) and the multi-hop walker
 * ({@link PaymentTrack#stepsBetween}) in isolation. {@code PaymentTrackIntegrationTest} separately
 * proves {@code TicketRepository.advancePaymentStatus} enforces this machine against a real
 * Postgres row.
 */
class PaymentTrackTest {

    private static final List<String> BYPASS_POLICIES =
        List.of(DepositPolicy.NOT_REQUIRED, DepositPolicy.WAIVED, DepositPolicy.CREDIT_CUSTOMER);

    // ── REQUIRED path: every adjacent hop walks ──────────────────────────────

    @Test
    void requiredPath_everyAdjacentHop_isAllowed() {
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, null, PaymentTrack.CUSTOMER_CONFIRMED)).isTrue();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isTrue();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID)).isTrue();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT)).isTrue();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID)).isTrue();
    }

    @Test
    void requiredPath_fullyPaid_isTerminal() {
        for (String to : PaymentTrack.VALUES) {
            assertThat(PaymentTrack.canTransition(DepositPolicy.REQUIRED, PaymentTrack.FULLY_PAID, to)).isFalse();
        }
    }

    // ── Bypass paths (NOT_REQUIRED / WAIVED / CREDIT_CUSTOMER): every adjacent hop walks ─────

    @Test
    void bypassPaths_everyAdjacentHop_isAllowed() {
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.CUSTOMER_CONFIRMED))
                .as("policy=%s", policy).isTrue();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.AWAITING_FINAL_PAYMENT))
                .as("policy=%s", policy).isTrue();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID))
                .as("policy=%s", policy).isTrue();
        }
    }

    @Test
    void bypassPaths_refuseDepositNoticeIssuedAndDepositPaid_entirely() {
        for (String policy : BYPASS_POLICIES) {
            // Neither DEPOSIT_NOTICE_ISSUED nor DEPOSIT_PAID is ever a legal "to" on a bypass
            // policy, from any starting point that would be legal on the REQUIRED path.
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.DEPOSIT_PAID))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_PAID))
                .as("policy=%s", policy).isFalse();
            // Nor are they ever a legal "from" — getOrDefault(..., Set.of()) refuses everything.
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.AWAITING_FINAL_PAYMENT))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT))
                .as("policy=%s", policy).isFalse();
            // Self-loop is off-path here too — DEPOSIT_NOTICE_ISSUED simply isn't a bypass state.
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
                .as("policy=%s", policy).isFalse();
        }
    }

    // ── Self-loop: legal ONLY for DEPOSIT_NOTICE_ISSUED, and ONLY on the REQUIRED policy ─────

    @Test
    void depositNoticeIssued_selfLoop_isLegalOnRequiredPolicyOnly() {
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
            .isTrue();
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.canTransition(
                    policy, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
                .as("policy=%s", policy).isFalse();
        }
    }

    @Test
    void noOtherSelfLoop_isEverLegal() {
        for (String status : PaymentTrack.VALUES) {
            if (PaymentTrack.DEPOSIT_NOTICE_ISSUED.equals(status)) continue;
            assertThat(PaymentTrack.canTransition(DepositPolicy.REQUIRED, status, status))
                .as("REQUIRED self-loop on %s", status).isFalse();
            for (String policy : BYPASS_POLICIES) {
                assertThat(PaymentTrack.canTransition(policy, status, status))
                    .as("policy=%s self-loop on %s", policy, status).isFalse();
            }
        }
    }

    // ── null "from": legal ONLY as the entry edge to CUSTOMER_CONFIRMED ──────────────────────

    @Test
    void nullFrom_permitsOnlyCustomerConfirmed() {
        List<String> allPolicies = List.of(
            DepositPolicy.REQUIRED, DepositPolicy.NOT_REQUIRED, DepositPolicy.WAIVED, DepositPolicy.CREDIT_CUSTOMER);
        for (String policy : allPolicies) {
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.CUSTOMER_CONFIRMED))
                .as("policy=%s", policy).isTrue();
            for (String to : PaymentTrack.VALUES) {
                if (PaymentTrack.CUSTOMER_CONFIRMED.equals(to)) continue;
                assertThat(PaymentTrack.canTransition(policy, null, to))
                    .as("policy=%s to=%s", policy, to).isFalse();
            }
        }
    }

    // ── Every reverse edge is refused ─────────────────────────────────────────────────────────

    @Test
    void everyReverseEdge_onRequiredPath_isRefused() {
        List<String> path = PaymentTrack.path(DepositPolicy.REQUIRED);
        for (int fromIdx = 0; fromIdx < path.size(); fromIdx++) {
            for (int toIdx = 0; toIdx <= fromIdx; toIdx++) {
                String from = path.get(fromIdx);
                String to = path.get(toIdx);
                // The one legal same-state pair (the DEPOSIT_NOTICE_ISSUED self-loop) is excluded
                // — everything else at or behind "from" must be refused.
                if (from.equals(to) && PaymentTrack.DEPOSIT_NOTICE_ISSUED.equals(from)) continue;
                assertThat(PaymentTrack.canTransition(DepositPolicy.REQUIRED, from, to))
                    .as("%s -> %s", from, to).isFalse();
            }
        }
    }

    @Test
    void everyReverseEdge_onBypassPaths_isRefused() {
        for (String policy : BYPASS_POLICIES) {
            List<String> path = PaymentTrack.path(policy);
            for (int fromIdx = 0; fromIdx < path.size(); fromIdx++) {
                for (int toIdx = 0; toIdx <= fromIdx; toIdx++) {
                    assertThat(PaymentTrack.canTransition(policy, path.get(fromIdx), path.get(toIdx)))
                        .as("policy=%s %s -> %s", policy, path.get(fromIdx), path.get(toIdx)).isFalse();
                }
            }
        }
    }

    // ── Every (single-hop) skip is refused at the machine level ──────────────────────────────
    // NOTE: PaymentTrack.canTransition is a STRICT single-hop check by design (see its own
    // Javadoc) — a forward multi-state jump is refused HERE, even though the very same jump is
    // legal as a WALK via stepsBetween (tested separately below). This is the deliberate
    // "walk, don't skip" split: the machine never grows a skip edge; a caller wanting to advance
    // several states in one call must go through stepsBetween, not expect canTransition itself to
    // permit the jump.

    @Test
    void everySkip_onRequiredPath_isRefusedAtTheMachineLevel() {
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_PAID)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.AWAITING_FINAL_PAYMENT)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.FULLY_PAID)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.AWAITING_FINAL_PAYMENT)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.FULLY_PAID)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_PAID, PaymentTrack.FULLY_PAID)).isFalse();
        assertThat(PaymentTrack.canTransition(
            DepositPolicy.REQUIRED, null, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isFalse();
    }

    @Test
    void everySkip_onBypassPath_isRefusedAtTheMachineLevel() {
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.canTransition(policy, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.FULLY_PAID))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.AWAITING_FINAL_PAYMENT))
                .as("policy=%s", policy).isFalse();
            assertThat(PaymentTrack.canTransition(policy, null, PaymentTrack.FULLY_PAID))
                .as("policy=%s", policy).isFalse();
        }
    }

    // ── Unknown / null policy is never allowed ────────────────────────────────────────────────

    @Test
    void unknownOrNullPolicy_isNeverAllowed() {
        assertThat(PaymentTrack.canTransition(null, null, PaymentTrack.CUSTOMER_CONFIRMED)).isFalse();
        assertThat(PaymentTrack.canTransition("BOGUS_POLICY", null, PaymentTrack.CUSTOMER_CONFIRMED)).isFalse();
        assertThat(PaymentTrack.canTransition(
            null, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isFalse();
        assertThat(PaymentTrack.canTransition(
            "BOGUS_POLICY", PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isFalse();
    }

    // ── isValid ────────────────────────────────────────────────────────────────────────────────

    @Test
    void isValid_acceptsExactlyTheFiveConstants() {
        for (String status : PaymentTrack.VALUES) {
            assertThat(PaymentTrack.isValid(status)).as(status).isTrue();
        }
        assertThat(PaymentTrack.isValid(null)).isFalse();
        assertThat(PaymentTrack.isValid("")).isFalse();
        assertThat(PaymentTrack.isValid("BOGUS")).isFalse();
        assertThat(PaymentTrack.isValid("fully_paid")).isFalse(); // case-sensitive
    }

    // ── path() ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void path_required_isTheFiveStateOrderedList() {
        assertThat(PaymentTrack.path(DepositPolicy.REQUIRED)).containsExactly(
            PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID,
            PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
    }

    @Test
    void path_bypassPolicies_isTheThreeStateOrderedList() {
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.path(policy)).as("policy=%s", policy).containsExactly(
                PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
        }
    }

    @Test
    void path_unknownOrNullPolicy_throws() {
        assertThatThrownBy(() -> PaymentTrack.path("BOGUS_POLICY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.path(null)).isInstanceOf(IllegalStateException.class);
    }

    // ── stepsBetween(): the multi-hop walker ──────────────────────────────────────────────────

    @Test
    void stepsBetween_requiredPath_walksEndToEnd() {
        assertThat(PaymentTrack.stepsBetween(DepositPolicy.REQUIRED, null, PaymentTrack.FULLY_PAID))
            .containsExactly(
                PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID,
                PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
    }

    @Test
    void stepsBetween_bypassPath_walksEndToEnd() {
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.stepsBetween(policy, null, PaymentTrack.FULLY_PAID))
                .as("policy=%s", policy)
                .containsExactly(
                    PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
        }
    }

    /** The exact multi-hop walk {@code TicketService.confirmFinalPayment} relies on (REQUIRED). */
    @Test
    void stepsBetween_depositPaidToFullyPaid_walksThroughAwaitingFinalPayment() {
        assertThat(PaymentTrack.stepsBetween(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_PAID, PaymentTrack.FULLY_PAID))
            .containsExactly(PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
    }

    /** The exact multi-hop walk {@code TicketService.confirmFinalPayment} relies on (bypass). */
    @Test
    void stepsBetween_bypassCustomerConfirmedToFullyPaid_walksThroughAwaitingFinalPayment() {
        for (String policy : BYPASS_POLICIES) {
            assertThat(PaymentTrack.stepsBetween(policy, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.FULLY_PAID))
                .as("policy=%s", policy)
                .containsExactly(PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
        }
    }

    /** The exact multi-hop walk site 5 (reconcilePaymentStatus -> FULLY_PAID) can present. */
    @Test
    void stepsBetween_depositNoticeIssuedToFullyPaid_walksThroughDepositPaidAndAwaitingFinalPayment() {
        assertThat(PaymentTrack.stepsBetween(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.FULLY_PAID))
            .containsExactly(PaymentTrack.DEPOSIT_PAID, PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
    }

    @Test
    void stepsBetween_everyReturnedHop_independentlySatisfiesCanTransition() {
        List<String> walk = PaymentTrack.stepsBetween(DepositPolicy.REQUIRED, null, PaymentTrack.FULLY_PAID);
        String prev = null;
        for (String hop : walk) {
            assertThat(PaymentTrack.canTransition(DepositPolicy.REQUIRED, prev, hop))
                .as("%s -> %s", prev, hop).isTrue();
            prev = hop;
        }
    }

    @Test
    void stepsBetween_selfLoop_returnsExactlyTheSingleElementList() {
        assertThat(PaymentTrack.stepsBetween(
            DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
            .containsExactly(PaymentTrack.DEPOSIT_NOTICE_ISSUED);
    }

    @Test
    void stepsBetween_selfLoop_onABypassPolicy_throws() {
        // DEPOSIT_NOTICE_ISSUED is off-path entirely for a bypass policy, so even the otherwise-
        // legal self-loop pair must still be refused.
        for (String policy : BYPASS_POLICIES) {
            assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                    policy, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
                .as("policy=%s", policy)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void stepsBetween_sameStatePairOtherThanDepositNoticeIssued_throws() {
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.CUSTOMER_CONFIRMED))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.FULLY_PAID, PaymentTrack.FULLY_PAID))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepsBetween_reverse_throws() {
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.FULLY_PAID, PaymentTrack.DEPOSIT_PAID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.DEPOSIT_PAID, PaymentTrack.CUSTOMER_CONFIRMED))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepsBetween_offPathTarget_forThePolicy_throws() {
        // A WAIVED deal can never reach DEPOSIT_NOTICE_ISSUED — it simply is not on that path.
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.WAIVED, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.NOT_REQUIRED, null, PaymentTrack.DEPOSIT_PAID))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepsBetween_invalidTargetStatus_throws() {
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, "BOGUS_STATUS"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(DepositPolicy.REQUIRED, PaymentTrack.CUSTOMER_CONFIRMED, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepsBetween_invalidFromStatus_throws() {
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                DepositPolicy.REQUIRED, "BOGUS_STATUS", PaymentTrack.FULLY_PAID))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepsBetween_unknownOrNullPolicy_throws() {
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(
                "BOGUS_POLICY", PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.FULLY_PAID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(null, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.FULLY_PAID))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PaymentTrack.stepsBetween(null, null, PaymentTrack.CUSTOMER_CONFIRMED))
            .isInstanceOf(IllegalStateException.class);
    }
}
