package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Single shared implementation of "resolve a pinnable FX rate" used by any part of the sales
 * pricing chain that needs to convert a foreign-currency figure to/from THB and record exactly
 * which rate it used (Step 2's {@code PricingCostingService}, Step 3's
 * {@code PricingDecisionService}).
 *
 * <p>Extracted (Step 3, design correction 6: "Pin the FX") from what used to be a private
 * {@code PricingCostingService.resolveFx} method — behaviour is unchanged, only the location
 * moved, so {@code PricingCostingService} now delegates here instead of keeping its own copy.
 * THB itself resolves to rate 1 without requiring a {@code sales.fx_rates} row (a THB-denominated
 * pricing chain has nothing to convert); any other currency MUST have a rate on file whose
 * {@code source} is exactly {@code "BOT"} (Bank of Thailand) and whose {@code effectiveDate} is
 * no older than 7 days — a manually-entered or stale rate is refused rather than silently used,
 * since it would let a landed-cost or selling-price figure be pinned against a number nobody can
 * audit back to an official source.
 */
public final class FxResolver {
    private static final BigDecimal ONE = BigDecimal.ONE;

    private FxResolver() {}

    /**
     * @param currencyValue may be null/blank, in which case THB is assumed.
     * @throws ApiException 422 if a non-THB currency has no rate on file, is not sourced from
     *         BOT, or its {@code effectiveDate} is more than 7 days old.
     */
    public static FxRateDto resolve(FxRateRepository fxRates, String currencyValue) {
        String currency = firstText(currencyValue, "THB").toUpperCase();
        if ("THB".equals(currency)) {
            return fxRates.findByCurrency("THB")
                .orElseGet(() -> new FxRateDto(0L, "THB", ONE, LocalDate.now(), null, "THB", null));
        }
        FxRateDto rate = fxRates.findByCurrency(currency)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ไม่พบอัตราแลกเปลี่ยนสำหรับสกุลเงิน " + currency));
        if (!"BOT".equalsIgnoreCase(rate.source()) || rate.fetchedAt() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "อัตราแลกเปลี่ยน " + currency + " ต้องมาจาก BOT ก่อนคำนวณต้นทุน");
        }
        if (rate.effectiveDate() == null || rate.effectiveDate().isBefore(LocalDate.now().minusDays(7))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "อัตราแลกเปลี่ยน BOT สำหรับ " + currency + " เก่าเกินไป");
        }
        return rate;
    }

    private static String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }
}
