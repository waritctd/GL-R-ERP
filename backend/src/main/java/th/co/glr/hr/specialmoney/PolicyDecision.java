package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of {@link SpecialMoneyPolicyEvaluator#evaluate}. {@code violations} is never used to
 * short-circuit -- the evaluator accumulates every rule failure it finds so the caller (and the
 * requester) can see the whole picture at once, not one error at a time.
 *
 * @param eligibleAmount what the policy would pay if the request were otherwise clean. Zeroed by
 *     the evaluator for a hard block (excluded per-diem province, medical balance exhausted);
 *     otherwise reflects the computed/capped amount even when {@code violations} is non-empty, so
 *     a reviewer can see both "what the policy allows" and "why this particular request doesn't
 *     get it".
 * @param policyVersion the {@link PolicyAmounts#version()} that was evaluated against; snapshot
 *     this onto {@code hr.special_money_request.policy_version}.
 */
public record PolicyDecision(
    BigDecimal eligibleAmount, SpecialMoneyBucket bucket, int policyVersion, List<String> violations) {

    public boolean isEligible() {
        return violations.isEmpty();
    }
}
