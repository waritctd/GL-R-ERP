package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ข้อ 11's monthly commission base is ยอดรับเงินรวม "ในแต่ละเดือน" — per CALENDAR month. The owner
 * proposed shifting the earning window to a 26th–25th cycle (to line up with the payroll pay
 * date) and that was REJECTED, 2026-07-30: the window boundary is commission MATH — which tier
 * bracket a receipt lands in, and whether the ฿50,000 monthly floor is cleared — not a scheduling
 * knob. See the three-concept table ("Earning window / Documentation cutoff / Payment date") in
 * this branch's PR body for the full reasoning.
 *
 * <p>{@link CommissionCalculator#progressiveCommission} and {@link
 * CommissionCalculator#monthlyTierBase} never see a date — they only ever operate on an
 * already-aggregated monthly total. The grouping into "which calendar month" happens in {@link
 * CommissionService#submit}/{@code #createFromDeal} (via the private {@code
 * saleCommissionPayrollMonth}), pinned separately by {@code
 * CommissionMonthlyEarningWindowIntegrationTest} (real DB, real service). What THIS class pins is
 * the consequence if that grouping were ever changed: the exact same receipts, bucketed
 * differently, pay a different amount — sometimes zero.
 */
class CommissionMonthlyEarningWindowTest {
    private final CommissionCalculator calculator = new CommissionCalculator();
    private final List<TierConfig> tiers = TierConfig.defaults();

    @Test
    void splittingOneCalendarMonthAcrossAWindowBoundary_paysLessThanKeepingItWhole() {
        // Owner's worked example (2026-07-30): a rep receives ฿240,000 on 20 Aug and ฿30,000 on
        // 28 Aug — both real calendar-August receipts. Kept whole (the correct, current
        // behavior): both sum into ONE monthly tier base. Wrongly split at a 26th boundary (the
        // REJECTED proposal): the 20 Aug receipt would fall in a "26 Jul–25 Aug" window and the
        // 28 Aug receipt would be pushed into "26 Aug–25 Sep" — two separate, smaller monthly
        // bases instead of one, taxed at lower/zero progressive rates.
        BigDecimal receipt20Aug = new BigDecimal("240000.00");
        BigDecimal receipt28Aug = new BigDecimal("30000.00");

        BigDecimal combinedBase = calculator.monthlyTierBase(receipt20Aug.add(receipt28Aug));
        BigDecimal combinedCommission = calculator.progressiveCommission(combinedBase, tiers);

        BigDecimal windowOneBase = calculator.monthlyTierBase(receipt20Aug);
        BigDecimal windowTwoBase = calculator.monthlyTierBase(receipt28Aug);
        BigDecimal splitCommission = calculator.progressiveCommission(windowOneBase, tiers)
            .add(calculator.progressiveCommission(windowTwoBase, tiers));

        assertThat(combinedCommission)
            .as("Same ฿270,000 (240,000 on 20 Aug + 30,000 on 28 Aug), same calendar month. Kept "
                + "whole (correct, calendar-month bucketing): ฿%s. Wrongly split at a 26th/25th "
                + "boundary instead of the month edge: ฿%s. Shifting the earning window pays a "
                + "DIFFERENT amount for the exact same sales — this is why ข้อ 11's \"ในแต่ละเดือน\" "
                + "base must stay bound to the calendar month, never to the 26th payroll date.",
                combinedCommission, splitCommission)
            .isGreaterThan(splitCommission);
    }

    @Test
    void fiftyFiveThousandInOneCalendarMonth_clearsTheFloor_paidNormally() {
        // ฿55,000 received within ONE calendar month is above the ฿50,000 monthly floor once
        // VAT-stripped (55,000 / 1.07 ≈ 51,401.87) — paid, not zeroed.
        BigDecimal monthTotal = new BigDecimal("55000.00");
        BigDecimal base = calculator.monthlyTierBase(monthTotal);
        BigDecimal commission = calculator.progressiveCommission(base, tiers);

        assertThat(base).isGreaterThan(new BigDecimal("50000"));
        assertThat(commission).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void sameFiftyFiveThousand_splitAcrossAWindowBoundary_wronglyForfeitsTheWholeCommission() {
        // The exact same ฿55,000 as above, but split across a shifted 26th/25th-style boundary
        // instead of staying in one calendar month — e.g. ฿30,000 before the boundary and
        // ฿25,000 after it. EACH half, taken alone, falls under the ฿50,000 monthly floor and
        // pays zero — so a genuinely-earned, floor-clearing month would pay NOTHING, not merely a
        // smaller amount. This is the sharper risk the tier-bracket example above doesn't show: a
        // shifted window doesn't just underpay, it can erase an entire month's commission.
        BigDecimal beforeBoundary = new BigDecimal("30000.00");
        BigDecimal afterBoundary = new BigDecimal("25000.00");
        assertThat(beforeBoundary.add(afterBoundary)).isEqualByComparingTo(new BigDecimal("55000.00"));

        BigDecimal wholeMonthCommission = calculator.progressiveCommission(
            calculator.monthlyTierBase(beforeBoundary.add(afterBoundary)), tiers);
        BigDecimal splitWindowOneCommission = calculator.progressiveCommission(
            calculator.monthlyTierBase(beforeBoundary), tiers);
        BigDecimal splitWindowTwoCommission = calculator.progressiveCommission(
            calculator.monthlyTierBase(afterBoundary), tiers);

        assertThat(wholeMonthCommission)
            .as("The whole ฿55,000 calendar month clears the floor and pays ฿%s; split across a "
                + "shifted window, BOTH halves (฿30,000 and ฿25,000) individually fall under the "
                + "฿50,000 floor and pay ฿0.00 each — a genuinely-earned month's commission would "
                + "vanish entirely, not just shrink.", wholeMonthCommission)
            .isGreaterThan(BigDecimal.ZERO);
        assertThat(splitWindowOneCommission).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(splitWindowTwoCommission).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
