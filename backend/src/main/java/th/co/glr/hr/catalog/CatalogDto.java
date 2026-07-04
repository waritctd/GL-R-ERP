package th.co.glr.hr.catalog;

import java.math.BigDecimal;

public record CatalogDto(
    long       catalogId,
    String     brand,
    String     collection,
    String     color,
    String     surface,
    String     size,
    String     factory,
    BigDecimal sqmPerPiece
) {}
