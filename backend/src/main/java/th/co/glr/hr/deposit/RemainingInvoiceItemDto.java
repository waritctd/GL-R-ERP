package th.co.glr.hr.deposit;

import java.math.BigDecimal;

/**
 * One ใบแจ้งหนี้ส่วนที่เหลือ line. {@code unitPrice}/{@code discountLabel}/{@code netUnitPrice}/
 * {@code amount} mirror the shape {@link DepositNoticeItemDto} already carries for the sibling
 * deposit-notice document, so a line sourced from either a {@code CustomerQuotationItemDto} or a
 * {@code DepositNoticeItemDto} maps onto this record without inventing a third convention:
 * {@code unitPrice} = the approved/raw unit price (column E), {@code discountLabel} = the
 * customer-facing discount text (column G, blank when there is no discount — never the stale
 * "Net"/"พิเศษ" placeholder the old renderer wrote unconditionally), {@code netUnitPrice} = the
 * post-discount unit price (column H), and {@code amount} = the line's own stored subtotal
 * (column I) — written DIRECTLY rather than recomputed as {@code netUnitPrice * qty}, because the
 * accepted quotation's {@code lineSubtotal} is the exact figure the customer already agreed to.
 */
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
