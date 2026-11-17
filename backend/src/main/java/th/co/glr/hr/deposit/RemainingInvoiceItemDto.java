package th.co.glr.hr.deposit;

import java.math.BigDecimal;

/** One ใบแจ้งหนี้ส่วนที่เหลือ line. */
public record RemainingInvoiceItemDto(
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice,
    String     discountLabel,
    BigDecimal netUnitPrice,
    BigDecimal amount
) {}
