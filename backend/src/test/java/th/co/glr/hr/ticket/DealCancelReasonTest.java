package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The cancel-reason vocabulary after the owner ruling of 2026-11-16: new cancellations offer the drawn four; the
 * three earlier codes stay valid (already-cancelled deals carry them, and the API keeps accepting them). Additive
 * only — no code is removed. */
class DealCancelReasonTest {

    private static final List<String> RETIRED = List.of("OWNER_CANCELLED", "PROJECT_SUSPENDED", "BUDGET_CANCELLED");

    @Test
    void offeredIsExactlyTheDrawnFourWithOtherLast() {
        assertThat(DealCancelReason.OFFERED).containsExactly(
            DealCancelReason.DUPLICATE_DEAL, DealCancelReason.WRONG_CUSTOMER_OR_PROJECT,
            DealCancelReason.NO_REAL_PROJECT, DealCancelReason.OTHER);
    }

    @Test
    void retiredCodesAreStillValid_soOldRowsAndOldClientsKeepWorking() {
        for (String code : RETIRED) {
            assertThat(DealCancelReason.isValid(code)).as(code).isTrue();
        }
    }

    @Test
    void newCodesAreValid_andNothingElseIs() {
        for (String code : DealCancelReason.OFFERED) {
            assertThat(DealCancelReason.isValid(code)).as(code).isTrue();
        }
        assertThat(DealCancelReason.isValid("PRICE")).isFalse();
        assertThat(DealCancelReason.isValid(null)).isFalse();
        assertThat(DealCancelReason.VALID).hasSize(7);
    }

    /** Every valid code — retired ones included — renders with a Thai label, never as its raw code. */
    @Test
    void everyValidCodeHasALabel() {
        for (String code : DealCancelReason.VALID) {
            assertThat(DealCancelReason.label(code)).as(code).isNotEqualTo(code).isNotBlank();
        }
        assertThat(DealCancelReason.label(DealCancelReason.DUPLICATE_DEAL)).isEqualTo("เปิดซ้ำกับงานอื่น");
        assertThat(DealCancelReason.label(DealCancelReason.OWNER_CANCELLED)).isEqualTo("เจ้าของยกเลิกโครงการ");
    }
}
