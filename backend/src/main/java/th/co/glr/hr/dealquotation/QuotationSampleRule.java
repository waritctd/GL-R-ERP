package th.co.glr.hr.dealquotation;

import java.math.BigDecimal;
import java.util.List;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;

/** Does this quotation look like a sample? Only a hint for the editor: it never sets the mark. */
final class QuotationSampleRule {
    private static final BigDecimal SINGLE_PIECE_QTY = BigDecimal.ONE;
    private static final BigDecimal SMALL_QTY = new BigDecimal("5");
    private static final BigDecimal SMALL_TOTAL_BEFORE_VAT = new BigDecimal("3000");

    private QuotationSampleRule() {}

    static boolean looksLikeSample(List<DealQuotationItemDto> lines, BigDecimal totalBeforeVat) {
        if (lines == null) {
            return false;
        }
        List<BigDecimal> tileQuantities = lines.stream()
            .filter(line -> line.lineType() == null || WastageCalculator.LINE_TYPE_TILE.equals(line.lineType()))
            .map(DealQuotationItemDto::quantity)
            .toList();
        if (tileQuantities.isEmpty() || tileQuantities.stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        boolean allSinglePieces = tileQuantities.stream().allMatch(qty -> qty.compareTo(SINGLE_PIECE_QTY) <= 0);
        boolean smallAndCheap = totalBeforeVat != null && totalBeforeVat.compareTo(SMALL_TOTAL_BEFORE_VAT) < 0
            && tileQuantities.stream().allMatch(qty -> qty.compareTo(SMALL_QTY) <= 0);
        return allSinglePieces || smallAndCheap;
    }
}
