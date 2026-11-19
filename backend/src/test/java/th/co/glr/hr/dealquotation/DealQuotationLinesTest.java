package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Size-text parser guards (ReDoS vector and length guard). A hand-edited ขนาด reaches the PDF through
 * DealQuotationLines#parseTwoDimensions, so a pathological input must not hang the render. */
class DealQuotationLinesTest {

    @Test
    void parseTwoDimensions_pathologicalWhitespace_248chars_doesNotCatastrophicallyBacktrack() {
        // The reviewer's own reported shape (measured pre-fix at 12,238ms on the reviewer's code, 5,026ms on this
        // machine).
        String attack = "30" + " ".repeat(80) + "x60" + " ".repeat(80) + "x1" + " ".repeat(80) + "!";
        assertThat(attack).hasSize(248);
        long start = System.nanoTime();
        DealQuotationLines.ParsedSize result = DealQuotationLines.parseTwoDimensions(attack);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result).isNull();
        assertThat(elapsedMs).as("must not catastrophically backtrack on pathological whitespace")
            .isLessThan(50);
    }

    @Test
    void parseTwoDimensions_longerThanTheLengthGuard_isRejectedOutright_evenForAGenuineShape() {
        // 65 chars of otherwise-perfectly-parseable text (the padding is INTERNAL, between the first number and the
        // separator, so String.trim() cannot shrink it away) -- proves the length guard itself, not just the regex
        // fix, independent of any pathological shape.
        String genuineButLong = "30" + " ".repeat(60) + "x60";
        assertThat(genuineButLong.length()).isEqualTo(65);
        assertThat(DealQuotationLines.parseTwoDimensions(genuineButLong)).as("parsing %s", genuineButLong).isNull();
    }
}
