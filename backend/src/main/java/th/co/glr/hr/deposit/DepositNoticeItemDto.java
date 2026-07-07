package th.co.glr.hr.deposit;

import java.math.BigDecimal;

public record DepositNoticeItemDto(
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
