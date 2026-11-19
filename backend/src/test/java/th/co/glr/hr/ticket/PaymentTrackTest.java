package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for PaymentTrack — the payment-track state machine — with no database involved. Mirrors
 * th.co.glr.hr.pricingrequest.PricingRequestStatusTest's own shape: exercise the decision function
 * (PaymentTrack#canTransition) and the multi-hop walker (PaymentTrack#stepsBetween) in isolation. */
class PaymentTrackTest {

    private static final List<String> BYPASS_POLICIES =
        List.of(DepositPolicy.NOT_REQUIRED, DepositPolicy.WAIVED, DepositPolicy.CREDIT_CUSTOMER);

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
    void bypassPaths_refuseDepositNoticeIssuedAndDepositPaid_entirely() {
        for (String policy : BYPASS_POLICIES) {
            // Neither DEPOSIT_NOTICE_ISSUED nor DEPOSIT_PAID is ever a legal "to" on a bypass policy, from any starting
            // point that would be legal on the REQUIRED path.
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

    @Test
    void everyReverseEdge_onRequiredPath_isRefused() {
        List<String> path = PaymentTrack.path(DepositPolicy.REQUIRED);
        for (int fromIdx = 0; fromIdx < path.size(); fromIdx++) {
            for (int toIdx = 0; toIdx <= fromIdx; toIdx++) {
                String from = path.get(fromIdx);
                String to = path.get(toIdx);
                // The one legal same-state pair (the DEPOSIT_NOTICE_ISSUED self-loop) is excluded — everything else at or
                // behind "from" must be refused.
                if (from.equals(to) && PaymentTrack.DEPOSIT_NOTICE_ISSUED.equals(from)) continue;
                assertThat(PaymentTrack.canTransition(DepositPolicy.REQUIRED, from, to))
                    .as("%s -> %s", from, to).isFalse();
            }
        }
    }

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
    void unknownOrNullPolicy_isNeverAllowed() {
        assertThat(PaymentTrack.canTransition(null, null, PaymentTrack.CUSTOMER_CONFIRMED)).isFalse();
        assertThat(PaymentTrack.canTransition("BOGUS_POLICY", null, PaymentTrack.CUSTOMER_CONFIRMED)).isFalse();
        assertThat(PaymentTrack.canTransition(
            null, PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isFalse();
        assertThat(PaymentTrack.canTransition(
            "BOGUS_POLICY", PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED)).isFalse();
    }

    @Test
    void stepsBetween_requiredPath_walksEndToEnd() {
        assertThat(PaymentTrack.stepsBetween(DepositPolicy.REQUIRED, null, PaymentTrack.FULLY_PAID))
            .containsExactly(
                PaymentTrack.CUSTOMER_CONFIRMED, PaymentTrack.DEPOSIT_NOTICE_ISSUED, PaymentTrack.DEPOSIT_PAID,
                PaymentTrack.AWAITING_FINAL_PAYMENT, PaymentTrack.FULLY_PAID);
    }

}
