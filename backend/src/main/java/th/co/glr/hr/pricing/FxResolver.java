package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;

/**
 * Single shared implementation of "resolve a pinnable FX rate" used by any part of the sales
 * pricing chain that needs to convert a foreign-currency figure to/from THB and record exactly
 * which rate it used ({@code PricingCostingService}, {@code PricingDecisionService}).
 *
 * <p>THB itself resolves to rate 1 without requiring a {@code sales.fx_rates} row (a
 * THB-denominated pricing chain has nothing to convert).
 *
 * <h2>A CEO-entered MANUAL rate is accepted; there is no {@code source == "BOT"}
 * requirement.</h2>
 * Requiring a non-THB rate's {@code source} to be exactly {@code "BOT"} with a non-null
 * {@code fetchedAt} would refuse every CEO-entered rate: the only writer a CEO can reach,
 * {@link FxRateController#upsert} (the CEO settings FX section) via
 * {@link FxRateRepository#upsert}, hardcodes {@code source = 'MANUAL'} and
 * {@code fetched_at = NULL} <b>unconditionally</b> — there is no code path by which a CEO's own
 * entry is ever written {@code source = 'BOT'}. Such a check would block
 * {@code PricingDecisionService#startReview} (the CEO's "เริ่มพิจารณาราคาขาย" after entering
 * อัตรากำไรเริ่มต้น) and {@code recalculateCost} for every non-THB pricing request.
 * (The BOT-sourced writers are {@code BotFxFetchService#fetchDailyRates} and
 * {@code POST /api/fx-rates/fetch-now}, via {@link FxRateRepository#upsertFromBot}; they no-op
 * when {@code BOT_FX_API_TOKEN} is unset, and whether it is set in a given environment is not
 * something this class relies on.)
 *
 * <p><b>Why refusing MANUAL would buy nothing.</b> The property actually worth protecting is
 * traceability — being able to tell, later, exactly which rate priced a deal and who entered it —
 * and that is already fully satisfied for a MANUAL rate, downstream of this class, with no
 * BOT-only gate needed: {@code PricingDecisionService#startReview} pins {@code fx_source} AND
 * {@code fx_effective_date} onto the decision itself
 * ({@code decisions.createDraft(..., fx.rateToThb(), fx.source(), fx.effectiveDate(), ...)});
 * the frontend renders "อัตราแลกเปลี่ยน {fxRateUsed} ({fxSource}, {fxEffectiveDate})" verbatim;
 * {@code LandedCostCalculator} writes the FX source/effective date/fetched-at into every costing
 * row's own snapshot; and {@code sales.fx_rates.updated_by} records which CEO entered the rate in
 * the first place. A MANUAL rate is therefore stamped MANUAL everywhere it is used and fully
 * auditable back to a named CEO on a named date — refusing it protected nothing reachable, and
 * cost the entire non-THB pricing chain its only way to price at all.
 *
 * <p><b>Enforced for BOTH sources:</b> the staleness guard.
 * {@code effectiveDate} must be non-null and no older than {@value #MAX_RATE_AGE_DAYS} days, or
 * this throws 422 — that is the guard that actually protects the money (a rate nobody has
 * looked at in a week pricing a live deal), and it applies uniformly regardless of who or what
 * entered the rate.
 *
 * <p>Every error message below names the fix the CEO can actually perform — ตั้งค่า CEO →
 * อัตราแลกเปลี่ยน — rather than an integration the CEO has no way to invoke.
 */
public final class FxResolver {
    private static final BigDecimal ONE = BigDecimal.ONE;
    // Package-private, not private: FxResolverTest reads this constant directly to build its
    // expected message text rather than hardcoding "7", so the constant and the Thai
    // message below cannot silently drift apart.
    static final int MAX_RATE_AGE_DAYS = 7;

    private FxResolver() {}

    /**
     * @param currencyValue may be null/blank, in which case THB is assumed.
     * @throws ApiException 422 if a non-THB currency has no rate on file, or its
     *         {@code effectiveDate} is missing or more than {@value #MAX_RATE_AGE_DAYS} days old.
     *         Either {@code MANUAL} or {@code BOT} as the rate's {@code source} is accepted — see
     *         the class Javadoc for why there is no BOT-only requirement.
     */
    public static FxRateDto resolve(FxRateRepository fxRates, String currencyValue) {
        String currency = firstText(currencyValue, "THB").toUpperCase();
        if ("THB".equals(currency)) {
            return fxRates.findByCurrency("THB")
                .orElseGet(() -> new FxRateDto(0L, "THB", ONE, LocalDate.now(), null, "THB", null));
        }
        FxRateDto rate = fxRates.findByCurrency(currency)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ยังไม่มีอัตราแลกเปลี่ยนสำหรับสกุลเงิน " + currency
                    + " — กรุณาตั้งค่าที่ ตั้งค่า CEO → อัตราแลกเปลี่ยน ก่อนคำนวณต้นทุน"));
        if (rate.effectiveDate() == null) {
            // A null date can't be printed usefully — its own branch/wording rather than folding
            // it into the "stale" message below, which names an actual date.
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "อัตราแลกเปลี่ยน " + currency + " ไม่มีวันที่มีผล (effective date)"
                    + " — กรุณาปรับปรุงที่ ตั้งค่า CEO → อัตราแลกเปลี่ยน ก่อนคำนวณต้นทุน");
        }
        if (rate.effectiveDate().isBefore(LocalDate.now().minusDays(MAX_RATE_AGE_DAYS))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "อัตราแลกเปลี่ยน " + currency + " มีผล ณ วันที่ " + rate.effectiveDate()
                    + " ซึ่งเก่าเกิน " + MAX_RATE_AGE_DAYS + " วัน — กรุณาปรับปรุงที่ ตั้งค่า CEO → อัตราแลกเปลี่ยน ก่อนคำนวณต้นทุน");
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
