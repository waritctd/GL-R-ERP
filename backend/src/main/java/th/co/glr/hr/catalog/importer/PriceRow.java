package th.co.glr.hr.catalog.importer;

import java.math.BigDecimal;
import java.util.Map;

public record PriceRow(
    long factoryId,
    String productCode,
    String grade,
    String collection,
    String productName,
    String color,
    String surface,
    String sizeRaw,
    BigDecimal widthMm,
    BigDecimal heightMm,
    BigDecimal thicknessMm,
    BigDecimal price,
    String currency,
    String priceUnit,
    BigDecimal sqmPerPiece,
    BigDecimal pcsPerBox,
    BigDecimal sqmPerBox,
    BigDecimal kgPerBox,
    Map<String, String> priceVariants,
    Map<String, String> attributes,
    String sourceSheet,
    int sourceRow
) {}
