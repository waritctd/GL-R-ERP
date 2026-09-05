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
 * {@code PricingCostingService.resolveFx} method — only the location moved, so
 * {@code PricingCostingService} now delegates here instead of keeping its own copy. THB itself
 * resolves to rate 1 without requiring a {@code sales.fx_rates} row (a THB-denominated pricing
 * chain has nothing to convert).
 *
 * <h2>P0 fix (2026-09, authorised sales-pricing-workflow change — CLAUDE.md's sales/CRM stack is
 * UNFROZEN): the {@code source == "BOT"} requirement is REMOVED. A CEO-entered MANUAL rate is now
 * accepted.</h2>
 * This class used to also require a non-THB rate's {@code source} be exactly {@code "BOT"} with a
 * non-null {@code fetchedAt}. That rule was GUARANTEED to refuse every CEO-entered rate,
 * regardless of whether any BOT-sourced row exists anywhere: the only writer a CEO can reach,
 * {@link FxRateController#upsert} (the CEO settings FX section) via
 * {@link FxRateRepository#upsert}, hardcodes {@code source = 'MANUAL'} and
 * {@code fetched_at = NULL} <b>unconditionally</b> — there is no code path by which a CEO's own
 * entry is ever written {@code source = 'BOT'}, so the old check refused it by construction, not
 * by circumstance. The UAT report that surfaced this (a CEO's manual entry refused in the running
 * system) is direct evidence the gate fired exactly as that reasoning predicts.
 *
 * <p>A separate, weaker argument for the same conclusion is deliberately NOT what this rests on,
 * and is named here only to correct it: {@code BotFxFetchService#fetchDailyRates} (the only writer
 * of a BOT-sourced row, via {@link FxRateRepository#upsertFromBot}) returns early whenever
 * {@code BOT_FX_API_TOKEN} is unset, and {@code render.yaml} marks that key {@code sync: false}
 * with its own checked-in comment recording both BOT fetchers as no-op'ing in this environment.
 * That comment is exactly the shape of claim CLAUDE.md warns against reasoning from: {@code sync:
 * false} means dashboard-set, not unset, and a checked-in comment is not a live read of the Render
 * dashboard's actual value — this class was never re-verified against the live dashboard. It does
 * not need to be: whether that token happens to be set there or not, the CEO's own path above
 * never produces a {@code source = 'BOT'} row either way, so the refusal was GUARANTEED for the
 * reason stated above, independent of this weaker, unverified one. This blocked
 * {@code PricingDecisionService#startReview} (the CEO's "เริ่มพิจารณาราคาขาย" after entering
 * อัตรากำไรเริ่มต้น) and {@code recalculateCost} for every non-THB pricing request.
 *
 * <p><b>Why refusing MANUAL bought nothing.</b> The property actually worth protecting is
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
 * <p><b>Why this was invisible in testing.</b> {@code frontend/src/api/mockApi.js} never enforced
 * the BOT-source rule at all — a mock more permissive than production, exactly the trap
 * CLAUDE.md's "Mock API contract" section warns about: clicking through the CEO FX flow under
 * {@code VITE_USE_MOCKS=true} looked fine and proved nothing about the real gate underneath.
 *
 * <p><b>What is UNCHANGED, and still enforced for BOTH sources:</b> the staleness guard.
 * {@code effectiveDate} must be non-null and no older than {@value #MAX_RATE_AGE_DAYS} days, or
 * this still throws 422 — that is the guard that actually protects the money (a rate nobody has
 * looked at in a week pricing a live deal), and it applies uniformly regardless of who or what
 * entered the rate. The BOT-source guard protected nothing reachable; the staleness guard does,
 * so only the former was removed.
 *
 * <p>Every error message below names the fix the CEO can actually perform — ตั้งค่า CEO →
 * อัตราแลกเปลี่ยน — rather than an integration the CEO has no way to invoke.
 */
public final class FxResolver {
    private static final BigDecimal ONE = BigDecimal.ONE;
    // Package-private, not private: FxResolverTest reads this constant directly to build its
    // expected message text (F6 fix) rather than hardcoding "7", so the constant and the Thai
    // message below cannot silently drift apart the way they used to (changing this value used
    // to leave the message's literal "7" lying).
    static final int MAX_RATE_AGE_DAYS = 7;

    private FxResolver() {}

    /**
     * @param currencyValue may be null/blank, in which case THB is assumed.
     * @throws ApiException 422 if a non-THB currency has no rate on file, or its
     *         {@code effectiveDate} is missing or more than {@value #MAX_RATE_AGE_DAYS} days old.
     *         Either {@code MANUAL} or {@code BOT} as the rate's {@code source} is accepted — see
     *         the class Javadoc for why the old BOT-only requirement was removed.
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
