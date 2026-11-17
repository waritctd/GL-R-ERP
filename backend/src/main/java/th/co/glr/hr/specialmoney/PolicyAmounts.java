package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/** A lookup of hr.special_money_policy.policy_key -> amount for a single request_type, plus the row-set version
 * snapshotted onto hr.special_money_request.policy_version at submission time. See that migration for the full
 * seed list. */
public record PolicyAmounts(Map<String, BigDecimal> amountsByKey, Map<String, String> textByKey, int version) {

    public PolicyAmounts(Map<String, BigDecimal> amountsByKey, int version) {
        this(amountsByKey, Map.of(), version);
    }

    public BigDecimal amount(String key) {
        return amountsByKey.get(key);
    }

    /** A non-numeric setting from hr.special_money_policy.text_value — currently only the sales-support
     * department's source_code, which is a VARCHAR and need not be numeric, so it cannot be carried in amount.
     * Returns null when unset. */
    public String text(String key) {
        return textByKey.get(key);
    }

    public BigDecimal amountOrZero(String key) {
        return amountsByKey.getOrDefault(key, BigDecimal.ZERO);
    }

    public int intAmountOrZero(String key) {
        return amountOrZero(key).intValue();
    }
}
