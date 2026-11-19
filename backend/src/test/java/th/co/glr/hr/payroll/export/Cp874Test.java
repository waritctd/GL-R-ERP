package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Cp874Test {
    @Test
    void thaiCharacterIsOneByteAndRightPaddedToWidth() {
        byte[] out = Cp874.rpad("กัลยาณี", 50); // 7 Thai chars
        assertThat(out).hasSize(50);
        assertThat(new String(out, Cp874.CHARSET)).isEqualTo("กัลยาณี" + " ".repeat(43));
    }

}
