package th.co.glr.hr.deposit;

import java.math.BigDecimal;

/**
 * One snapshotted line of a STORED remaining invoice (GLA-99 step 2, {@code
 * sales.remaining_invoice_item}). Field-for-field identical to {@link RemainingInvoiceItemDto} (the
 * stateless preview's own line shape) — see that record's Javadoc for what each column means — this
 * is a separate type only because it carries the row's own {@code id}/{@code remainingInvoiceId},
 * which a computed-on-the-fly preview line has no use for.
 */
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
