package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public record ProductPriceDto(
    long       priceId,
    long       factoryId,
    String     factoryName,
    String     productCode,
    String     grade,
    String     collection,
    String     productName,
    String     color,
    String     surface,
    String     sizeRaw,
    BigDecimal price,
    String     currency,
    String     priceUnit,
    BigDecimal sqmPerPiece
) {}
