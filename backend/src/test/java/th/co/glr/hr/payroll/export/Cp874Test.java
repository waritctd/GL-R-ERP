package th.co.glr.hr.payroll.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class Cp874Test {
    @Test
    void thaiCharacterIsOneByteAndRightPaddedToWidth() {
        byte[] out = Cp874.rpad("กัลยาณี", 50); // 7 Thai chars
        assertThat(out).hasSize(50);
        assertThat(new String(out, Cp874.CHARSET)).isEqualTo("กัลยาณี" + " ".repeat(43));
    }

    @Test
    void rpadTruncatesOnByteBoundary() {
        byte[] out = Cp874.rpad("abcdef", 4);
        assertThat(out).hasSize(4);
        assertThat(new String(out, Cp874.CHARSET)).isEqualTo("abcd");
    }

    @Test
    void zpadLeftPadsWithZeros() {
        assertThat(new String(Cp874.zpad(30, 18), Cp874.CHARSET)).isEqualTo("000000000000000030");
    }

    @Test
    void zpadRejectsOverflow() {
        assertThatThrownBy(() -> Cp874.zpad("123456", 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void satangDropsDecimalPointAndZeroPads() {
        assertThat(new String(Cp874.satang(new BigDecimal("145000.00"), 15), Cp874.CHARSET))
            .isEqualTo("000000014500000");
        assertThat(new String(Cp874.satang(new BigDecimal("1021524.00"), 15), Cp874.CHARSET))
            .isEqualTo("000000102152400");
    }

    @Test
    void decimal2KeepsTwoDecimals() {
        assertThat(Cp874.decimal2(new BigDecimal("150000"))).isEqualTo("150000.00");
        assertThat(Cp874.decimal2(new BigDecimal("2929.005"))).isEqualTo("2929.01"); // HALF_UP
    }

    @Test
    void recordEnforcesExactWidth() {
        assertThatThrownBy(() -> Cp874.record(5, Cp874.bytes("abc")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fileJoinsWithCrlfIncludingTrailing() {
        byte[] out = Cp874.file(List.of(Cp874.bytes("A"), Cp874.bytes("B")));
        assertThat(new String(out, Cp874.CHARSET)).isEqualTo("A\r\nB\r\n");
    }
}
