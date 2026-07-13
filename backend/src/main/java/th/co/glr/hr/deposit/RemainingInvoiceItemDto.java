package th.co.glr.hr.deposit;

import java.math.BigDecimal;

public record RemainingInvoiceItemDto(
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice
) {}
