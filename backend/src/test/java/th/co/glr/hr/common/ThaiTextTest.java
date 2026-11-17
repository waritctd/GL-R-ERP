package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    // Columns: case name, from, to, expected rendering.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "dateRangeWithinSameMonthAndYearIsCompactWithNoSpacesAroundTheEnDash,"
            + " 2026-08-09, 2026-08-12, 9–12 สิงหาคม 2569",
        "dateRangeAcrossMonthsInTheSameYearShowsEachSideAndOneYearAtTheEndWithSpacedEnDash,"
            + " 2026-07-30, 2026-08-02, 30 กรกฎาคม – 2 สิงหาคม 2569",
        // Deliberate per the approved copy: a two-way split (same month+year vs. everything else), not a third
        // cross-year case -- see ThaiText#dateRange's Javadoc. The FROM side never shows its own year in the
        // "otherwise" branch, even when it differs from TO's year.
        "dateRangeAcrossYearsStillUsesTheOtherwiseFormAndOmitsTheFromYear,"
            + " 2026-12-30, 2027-01-02, 30 ธันวาคม – 2 มกราคม 2570",
    })
    void dateRangeOfTwoDistinctDates(String caseName, String from, String to, String expected) {
        assertThat(ThaiText.dateRange(LocalDate.parse(from), LocalDate.parse(to))).isEqualTo(expected);
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

    // ── bahtText (GLA-99 step 3, ใบวางบิล) ─────────────────────────────────────────────────────

    @Test
    void bahtTextOfNullIsDash() {
        assertThat(ThaiText.bahtText(null)).isEqualTo("-");
    }

    @Test
    void bahtTextOfZeroIsSoonBahtThuan() {
        // The owner's own sample sheet reads zero exactly this way.
        assertThat(ThaiText.bahtText(BigDecimal.ZERO)).isEqualTo("ศูนย์บาทถ้วน");
    }

    @Test
    void bahtTextOfExactlyOneBahtDoesNotUseEd() {
        // "เอ็ด" only replaces "หนึ่ง" when something else was already read -- a lone 1 stays "หนึ่ง".
        assertThat(ThaiText.bahtText(new BigDecimal("1.00"))).isEqualTo("หนึ่งบาทถ้วน");
    }

    @Test
    void bahtTextHasSatangWhenThereIsAFraction() {
        assertThat(ThaiText.bahtText(new BigDecimal("1.50"))).isEqualTo("หนึ่งบาทห้าสิบสตางค์");
    }

    // Cases pinned as a PAIR of amounts each. Columns: case name, first amount, its expected text, second amount, its
    // expected text.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "bahtTextTensAndTeensUseSipNotNuengSip,   10.00,      สิบบาทถ้วน,           15.00,       สิบห้าบาทถ้วน",
        "bahtTextMillionsChunkByGroupsOfSix,      1000001.00, หนึ่งล้านเอ็ดบาทถ้วน,    10000000.00, สิบล้านบาทถ้วน",
        // Second pair: 1.005 -> HALF_UP -> 1.01: a lone satang digit of 1 reads "หนึ่ง", not "เอ็ด" -- the same "a lone 1
        // is never เอ็ด" rule bahtTextOfExactlyOneBahtDoesNotUseEd pins for the baht side.
        "bahtTextRoundsToTwoDecimalPlacesHalfUp,  99.999,     หนึ่งร้อยบาทถ้วน,       1.005,       หนึ่งบาทหนึ่งสตางค์",
    })
    void bahtTextPairs(String caseName, String firstAmount, String firstExpected, String secondAmount,
                       String secondExpected) {
        assertThat(ThaiText.bahtText(new BigDecimal(firstAmount))).isEqualTo(firstExpected);
        assertThat(ThaiText.bahtText(new BigDecimal(secondAmount))).isEqualTo(secondExpected);
    }

    @Test
    void bahtTextTwentyUsesYiSipNotSongSip() {
        assertThat(ThaiText.bahtText(new BigDecimal("20.00"))).isEqualTo("ยี่สิบบาทถ้วน");
    }

    @Test
    void bahtTextTwentyOneUsesEdForTheOnesDigit() {
        assertThat(ThaiText.bahtText(new BigDecimal("21.00"))).isEqualTo("ยี่สิบเอ็ดบาทถ้วน");
    }

    @Test
    void bahtTextHundredsReadNuengRoiNotJustRoi() {
        assertThat(ThaiText.bahtText(new BigDecimal("100.00"))).isEqualTo("หนึ่งร้อยบาทถ้วน");
    }

    @Test
    void bahtTextHundredAndOneStillUsesEdEvenWithAZeroTensDigit() {
        assertThat(ThaiText.bahtText(new BigDecimal("101.00"))).isEqualTo("หนึ่งร้อยเอ็ดบาทถ้วน");
    }

    // เอ็ด was wrong for every NON-FINAL ล้าน group's own ones digit, and a group two or more ล้าน-levels above
    // the base only ever got ONE ล้าน regardless of how many it actually represented. Both fixed in
    // ThaiText#readNumber/ #appendGroup; these pin the exact regressions the review named.

    // Columns: case name, amount, expected text.
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // Before the fix: "สิบหนึ่งล้าน" -- the "11" group's own ones digit read หนึ่ง because the เอ็ด check only
        // ever looked at the number's OWN FINAL six-digit group.
        "bahtTextElevenMillionUsesEdForTheNonFinalGroupsOwnOnesDigit,     11000000.00,      สิบเอ็ดล้านบาทถ้วน",
        "bahtTextTwentyOneMillionUsesEdForTheNonFinalGroupsOwnOnesDigit,  21000000.00,      ยี่สิบเอ็ดล้านบาทถ้วน",
        "bahtTextOneHundredOneMillionUsesEdAcrossAZeroTensDigitToo,       101000000.00,     หนึ่งร้อยเอ็ดล้านบาทถ้วน",
        // Before the fix: "หนึ่งล้าน" -- a group two ล้าน-levels above the base (10^12) only ever got ONE ล้าน
        // appended, the same as a group one level up, dropping a level whenever an intervening group (here, the
        // 10^6..10^11 group) was entirely zero.
        "bahtTextOneTrillionRepeatsLanOncePerMagnitudeLevel,              1000000000000.00, หนึ่งล้านล้านบาทถ้วน",
    })
    void bahtTextNonFinalLanGroupRegressions(String caseName, String amount, String expected) {
        assertThat(ThaiText.bahtText(new BigDecimal(amount))).isEqualTo(expected);
    }

    @Test
    void bahtTextMatchesTheOwnersSampleSheetExactly() {
        // The exact figure and exact string the owner's ฟอร์มใบวางบิล.xls sample reads via its own BAHTTEXT formula
        // cell -- the ground truth this whole method exists to reproduce in Java.
        assertThat(ThaiText.bahtText(new BigDecimal("126977.20")))
            .isEqualTo("หนึ่งแสนสองหมื่นหกพันเก้าร้อยเจ็ดสิบเจ็ดบาทยี่สิบสตางค์");
    }
}
