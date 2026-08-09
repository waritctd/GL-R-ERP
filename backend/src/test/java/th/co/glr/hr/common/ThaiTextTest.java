package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ThaiTextTest {

    // ── date ────────────────────────────────────────────────────────────────────────────────

    @Test
    void dateRendersDayThaiMonthAndBuddhistYearWithNoLeadingZero() {
        assertThat(ThaiText.date(LocalDate.of(2026, 8, 9))).isEqualTo("9 สิงหาคม 2569");
    }

    @Test
    void dateHandlesEveryMonthName() {
        assertThat(ThaiText.date(LocalDate.of(2026, 1, 1))).isEqualTo("1 มกราคม 2569");
        assertThat(ThaiText.date(LocalDate.of(2026, 12, 31))).isEqualTo("31 ธันวาคม 2569");
    }

    @Test
    void dateOfNullIsEmptyString() {
        assertThat(ThaiText.date(null)).isEqualTo("");
    }

    // ── dateRange ───────────────────────────────────────────────────────────────────────────

    @Test
    void dateRangeOfEqualDatesIsJustTheSingleDate() {
        LocalDate d = LocalDate.of(2026, 8, 9);
        assertThat(ThaiText.dateRange(d, d)).isEqualTo("9 สิงหาคม 2569");
    }

    @Test
    void dateRangeOfNullToFallsBackToFromAlone() {
        assertThat(ThaiText.dateRange(LocalDate.of(2026, 8, 9), null)).isEqualTo("9 สิงหาคม 2569");
    }

    @Test
    void dateRangeWithinSameMonthAndYearIsCompactWithNoSpacesAroundTheEnDash() {
        assertThat(ThaiText.dateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 12)))
            .isEqualTo("9–12 สิงหาคม 2569");
    }

    @Test
    void dateRangeAcrossMonthsInTheSameYearShowsEachSideAndOneYearAtTheEndWithSpacedEnDash() {
        assertThat(ThaiText.dateRange(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 2)))
            .isEqualTo("30 กรกฎาคม – 2 สิงหาคม 2569");
    }

    @Test
    void dateRangeAcrossYearsStillUsesTheOtherwiseFormAndOmitsTheFromYear() {
        // Deliberate per the approved copy: a two-way split (same month+year vs. everything else),
        // not a third cross-year case -- see ThaiText#dateRange's Javadoc. The FROM side never shows
        // its own year in the "otherwise" branch, even when it differs from TO's year.
        assertThat(ThaiText.dateRange(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)))
            .isEqualTo("30 ธันวาคม – 2 มกราคม 2570");
    }

    // ── money ───────────────────────────────────────────────────────────────────────────────

    @Test
    void moneyDropsTrailingZerosForAWholeAmount() {
        assertThat(ThaiText.money(new BigDecimal("1500"))).isEqualTo("1,500");
        assertThat(ThaiText.money(new BigDecimal("1500.00"))).isEqualTo("1,500");
    }

    @Test
    void moneyKeepsTwoDecimalPlacesForAFractionalAmount() {
        assertThat(ThaiText.money(new BigDecimal("1500.50"))).isEqualTo("1,500.50");
    }

    @Test
    void moneyGroupsThousands() {
        assertThat(ThaiText.money(new BigDecimal("1234567"))).isEqualTo("1,234,567");
    }

    @Test
    void moneyNeverPrependsACurrencySymbol() {
        assertThat(ThaiText.money(new BigDecimal("1500"))).doesNotContain("฿");
    }

    @Test
    void moneyOfNullIsDash() {
        assertThat(ThaiText.money(null)).isEqualTo("-");
    }

    @Test
    void moneyOfZeroIsZeroNotDash() {
        // Only null renders "-"; zero is a real, whole amount.
        assertThat(ThaiText.money(BigDecimal.ZERO)).isEqualTo("0");
    }

    // ── hours ───────────────────────────────────────────────────────────────────────────────

    @Test
    void hoursOfAWholeNumberOfHoursDropsTheMinutes() {
        assertThat(ThaiText.hours(180)).isEqualTo("3 ชม.");
    }

    @Test
    void hoursWithARemainderShowsBoth() {
        assertThat(ThaiText.hours(210)).isEqualTo("3 ชม. 30 นาที");
    }

    @Test
    void hoursUnderAnHourShowsOnlyMinutes() {
        assertThat(ThaiText.hours(45)).isEqualTo("45 นาที");
    }

    @Test
    void hoursOfZeroIsDash() {
        assertThat(ThaiText.hours(0)).isEqualTo("-");
    }

    @Test
    void hoursOfANegativeValueIsDash() {
        assertThat(ThaiText.hours(-15)).isEqualTo("-");
    }
}
