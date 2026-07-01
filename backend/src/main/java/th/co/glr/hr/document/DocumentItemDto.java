package th.co.glr.hr.document;

import java.math.BigDecimal;

public record DocumentItemDto(
    long       id,
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice,
    String     discountLabel,
    BigDecimal netUnitPrice,
    BigDecimal amount
) {}
