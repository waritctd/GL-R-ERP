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

    // ── bahtText (GLA-99 step 3, ใบวางบิล) ─────────────────────────────────────────────────────

    @Test
    void bahtTextTwentyUsesYiSipNotSongSip() {
        assertThat(ThaiText.bahtText(new BigDecimal("20.00"))).isEqualTo("ยี่สิบบาทถ้วน");
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
