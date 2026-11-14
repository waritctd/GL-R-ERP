package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One snapshotted line of a ใบวางบิล (billing note, GLA-99 step 3, sales.billing_note_line). sourceType is
 * REMAINING_INVOICE/DEPOSIT_NOTICE (a real, already-ISSUED document snapshotted in) or MANUAL (a caller-typed
 * freight/external-invoice line, no sourceId/ticketId). */
public record BillingNoteLineDto(
    long        id,
    long        billingNoteId,
    int         seq,
    String      sourceType,
    Long        sourceId,
    Long        ticketId,
    String      docNumber,
    LocalDate   docDate,
    LocalDate   dueDate,
    BigDecimal  amount,
    String      note
) {}
