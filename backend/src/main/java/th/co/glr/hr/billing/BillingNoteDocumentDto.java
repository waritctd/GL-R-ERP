package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A STORED, versioned ใบวางบิล (billing note, GLA-99 step 3, {@code sales.billing_note}) —
 * DRAFT -&gt; ISSUED -&gt; {@code SUPERSEDED} (revised) | {@code CANCELLED} (voided, lines released
 * for re-billing) | {@code SETTLED} (every referenced source document fully paid — owner ruling
 * B1/B2, 2026-09-20; reached automatically, never by a caller action, see {@link
 * BillingNoteRepository#reconcileSettlementForCustomer}). {@code baseNumber}/{@code version}/
 * {@code docNumber} follow exactly the same shape {@link
 * th.co.glr.hr.deposit.RemainingInvoiceDocumentDto} documents (V188) — minted once at first issue
 * on the SAME shared {@code AR_GLR} sequence, carried forward across a revision.
 *
 * <p>{@code revisionOfId} (B1) is the ISSUED note THIS row was created to correct — set only by
 * {@link BillingNoteService#revise}, {@code null} for a brand-new draft starting its own chain.
 * Live-uniqueness (at most one live DRAFT and one live ISSUED) is scoped to the CHAIN this points
 * into, not to {@code (customerId, type)} — many ISSUED notes of the same customer+type coexist
 * across credit cycles (V189's own header comment has the full reasoning).
 *
 * <p>{@code totalAmount} is the VAT-inclusive sum of every line's own {@code amount} — this
 * document carries no separate VAT line (every source it can reference is already VAT-inclusive,
 * matching the owner's own form footer "ราคานี้เป็นราคาที่รวมภาษีแล้ว").
 *
 * <p>{@code settledById}/{@code settledByName}/{@code settledAt} (owner ruling C1, 2026-09-20) are
 * non-null ONLY when {@link BillingNoteService#markSettled} recorded an explicit settlement (the
 * all-MANUAL-note case, which can never auto-settle) — the automatic recompute-on-read path
 * deliberately leaves all three {@code null} even on a {@code SETTLED} row.
 */
public record BillingNoteDocumentDto(
    long              id,
    long              customerId,
    String            type,
    String            baseNumber,
    int               version,
    String            docNumber,
    String            status,
    Long              supersededById,
    Long              revisionOfId,
    String            cancelReason,
    Long              cancelledById,
    String            cancelledByName,
    OffsetDateTime    cancelledAt,

    LocalDate         billDate,
    String            paymentDueNote,
    LocalDate         paymentAppointmentDate,
    String            receivedByName,
    OffsetDateTime    receivedAt,
    String            note,

    String            customerName,
    String            customerTaxId,
    String            customerBranch,
    String            customerAddress,

    BigDecimal        totalAmount,

    Long              createdById,
    String            createdByName,
    OffsetDateTime    createdAt,
    OffsetDateTime    updatedAt,
    Long              issuedById,
    String            issuedByName,
    OffsetDateTime    issuedAt,

    Long              settledById,
    String            settledByName,
    OffsetDateTime    settledAt,

    List<BillingNoteLineDto> lines
) {}
