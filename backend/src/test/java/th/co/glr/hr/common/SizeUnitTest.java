package th.co.glr.hr.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SizeUnitTest {

    /** Guard: a missing size prints nothing -- never "null cm" or a bare " cm". */
    @Test
    void nullAndBlankSizesGetNoUnit() {
        assertThat(SizeUnit.withCmUnit(null)).isNull();
        assertThat(SizeUnit.withCmUnit("")).isEmpty();
        assertThat(SizeUnit.withCmUnit("  ")).isEqualTo("  ");
        assertThat(SizeUnit.hasUnit(null)).isFalse();
    }

    @Test
    void unitlessGetsCm_unitCarryingIsUntouched() {
        assertThat(SizeUnit.withCmUnit("20x30.5")).isEqualTo("20x30.5 cm");
        assertThat(SizeUnit.withCmUnit("60x60 ซม.")).isEqualTo("60x60 ซม.");
        assertThat(SizeUnit.withCmUnit("600x600 mm")).isEqualTo("600x600 mm");
    }
}
