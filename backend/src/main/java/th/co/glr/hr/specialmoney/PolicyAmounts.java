package th.co.glr.hr.specialmoney;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A lookup of {@code hr.special_money_policy.policy_key -> amount} for a single {@code
 * request_type}, plus the row-set version snapshotted onto {@code
 * hr.special_money_request.policy_version} at submission time.
 *
 * <p>Keys are whatever {@code V66__special_money_request_schema.sql} seeded for the type in
 * question -- e.g. {@code "cap"}, {@code "per_piece_shirt"}, {@code "max_pieces"}, {@code
 * "rate_driver"}. See that migration for the full seed list.
 */
public record PolicyAmounts(Map<String, BigDecimal> amountsByKey, Map<String, String> textByKey, int version) {

    public PolicyAmounts(Map<String, BigDecimal> amountsByKey, int version) {
        this(amountsByKey, Map.of(), version);
    }

    public BigDecimal amount(String key) {
        return amountsByKey.get(key);
    }

    /**
     * A non-numeric setting from {@code hr.special_money_policy.text_value} — currently only the
     * sales-support department's {@code source_code}, which is a {@code VARCHAR} and need not be
     * numeric, so it cannot be carried in {@code amount}. Returns null when unset.
     */
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
