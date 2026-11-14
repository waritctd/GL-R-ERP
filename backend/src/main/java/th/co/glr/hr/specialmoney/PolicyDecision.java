package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of SpecialMoneyPolicyEvaluator#evaluate. violations is never used to short-circuit -- the evaluator
 * accumulates every rule failure it finds so the caller (and the requester) can see the whole picture at once,
 * not one error at a time. */
public record PolicyDecision(
    BigDecimal eligibleAmount, SpecialMoneyBucket bucket, int policyVersion, List<String> violations) {

    public boolean isEligible() {
        return violations.isEmpty();
    }
}
