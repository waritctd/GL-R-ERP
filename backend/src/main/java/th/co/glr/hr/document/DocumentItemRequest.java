package th.co.glr.hr.document;

import java.math.BigDecimal;

public record DocumentItemRequest(
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice,
    String     discountLabel,
    BigDecimal netUnitPrice
) {}
