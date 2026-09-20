package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One snapshotted line of a ใบวางบิล (billing note, GLA-99 step 3, {@code sales.billing_note_line}).
 * {@code sourceType} is {@code REMAINING_INVOICE}/{@code DEPOSIT_NOTICE} (a real, already-ISSUED
 * document snapshotted in) or {@code MANUAL} (a caller-typed freight/external-invoice line, no
 * {@code sourceId}/{@code ticketId}). {@code amount} is the VAT-inclusive amount actually being
 * billed on this line — for an ERP-sourced line that is the source document's own OUTSTANDING
 * balance at the moment it was added (its total minus payments already recorded against it, see
 * {@code BillingNoteService.resolveLine} — a private method, hence {@code @code} rather than a
 * broken {@code @link}), not necessarily its original face value.
 */
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
