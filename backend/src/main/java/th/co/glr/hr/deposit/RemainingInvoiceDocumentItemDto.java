package th.co.glr.hr.deposit;

import java.math.BigDecimal;

/** One snapshotted line of a STORED remaining invoice (GLA-99 step 2, sales.remaining_invoice_item). */
public record RemainingInvoiceDocumentItemDto(
    long       id,
    long       remainingInvoiceId,
    int        seq,
    String     description,
    BigDecimal qty,
    String     unit,
    BigDecimal unitPrice,
    String     discountLabel,
    BigDecimal netUnitPrice,
    BigDecimal amount
) {}
