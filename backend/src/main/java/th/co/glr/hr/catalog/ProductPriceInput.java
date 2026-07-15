package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public record ProductPriceInput(
    Long factoryId,
    String productCode,
    String grade,
    String collection,
    String productName,
    String color,
    String surface,
    String sizeRaw,
    BigDecimal price,
    String currency,
    String priceUnit
) {}
