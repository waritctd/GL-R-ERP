package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pins the exact printed-line strings from docs/sales/quotation-v2-plan.md's "Printed lines" section. */
class DealQuotationLinesTest {

    @Test
    void descriptionLine_fullExample_withThickness_sizeGoesOnItsOwnLineInstead() {
        assertThat(DealQuotationLines.descriptionLine("Marvel Pro", "Calacatta", "Polished", "MP-001",
            "60x120", new BigDecimal("2")))
            .isEqualTo("กระเบื้อง รุ่น Marvel Pro สี Calacatta ผิว Polished No.MP-001");
    }

    @Test
    void descriptionLine_omitsBlankComponentsAndProductCode() {
        assertThat(DealQuotationLines.descriptionLine("Marvel Pro", null, "", null, null, new BigDecimal("2")))
            .isEqualTo("กระเบื้อง รุ่น Marvel Pro");
    }

    /** layout-spec §2 / plan §"Printed lines" item 6: no thickness → size goes INLINE on the
     * description line, before "No.{productCode}", and there is no separate ขนาด row at all. */
    @Test
    void descriptionLine_noThickness_sizeGoesInlineBeforeProductCode() {
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, "BS66R13GP",
            "60x60", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 60x60 cm. No.BS66R13GP");
    }

    @Test
    void sizeLine_withThickness_integer() {
        assertThat(DealQuotationLines.sizeLine("60x120", new BigDecimal("2")))
            .isEqualTo("ขนาด 60x120x2 cm. (ขนาดโดยประมาณ)");
    }

    @Test
    void sizeLine_withThickness_fractional() {
        assertThat(DealQuotationLines.sizeLine("60x60", new BigDecimal("0.9")))
            .isEqualTo("ขนาด 60x60x0.9 cm. (ขนาดโดยประมาณ)");
    }

    /** layout-spec §2: no thickness → null (not a blank/dangling line) — the size already went
     * inline on {@link DealQuotationLines#descriptionLine} instead, so there is no separate row. */
    @Test
    void sizeLine_noThickness_returnsNull_noSeparateRow() {
        assertThat(DealQuotationLines.sizeLine("60x60", null)).isNull();
    }

    @Test
    void calculationLine_areaMode_percentWastage_withBox() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("87"), new BigDecimal("2.78"),
            242, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 268, 4);

        assertThat(line).isEqualTo(
            "(พื้นที่ 87 ตร.ม.ๆละ 2.78 แผ่น รวม 242 แผ่น + เผื่อ 10% และปัดลงกล่อง = 268 แผ่น) (บรรจุ 4 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_piecesQuantityMode() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_PIECES, null, null,
            100, WastageCalculator.WASTAGE_MODE_PIECES, new BigDecimal("5"), 108, 12);

        assertThat(line).isEqualTo("(จำนวน 100 แผ่น + เผื่อ 5 แผ่น และปัดลงกล่อง = 108 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_noneWastage_omitsWastagePhrase() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_NONE, null, 24, 12);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น และปัดลงกล่อง = 24 แผ่น) (บรรจุ 12 แผ่น/กล่อง)");
    }

    @Test
    void calculationLine_noPiecesPerBox_omitsRoundingAndBoxTail() {
        String line = DealQuotationLines.calculationLine(
            WastageCalculator.QUANTITY_MODE_AREA, new BigDecimal("10"), new BigDecimal("2"),
            20, WastageCalculator.WASTAGE_MODE_PERCENT, new BigDecimal("10"), 22, null);

        assertThat(line).isEqualTo("(พื้นที่ 10 ตร.ม.ๆละ 2 แผ่น รวม 20 แผ่น + เผื่อ 10% = 22 แผ่น)");
    }

    @Test
    void descriptionLine_doesNotAppendCmWhenTheSizeTextAlreadyCarriesAUnit() {
        // Review finding LOW-2: "600x1200 mm" printed as "ขนาด 600x1200 mm cm." on the
        // null-thickness branch.
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "600x1200 mm", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 600x1200 mm");
        assertThat(DealQuotationLines.descriptionLine("Reverso Cement", "Grigio", null, null, "60x60", null))
            .isEqualTo("กระเบื้อง รุ่น Reverso Cement สี Grigio ขนาด 60x60 cm.");
    }
}
