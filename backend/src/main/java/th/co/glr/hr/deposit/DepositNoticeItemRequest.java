package th.co.glr.hr.deposit;

import java.math.BigDecimal;

public record DepositNoticeItemRequest(
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice,
    String     discountLabel,
    BigDecimal netUnitPrice
) {}
