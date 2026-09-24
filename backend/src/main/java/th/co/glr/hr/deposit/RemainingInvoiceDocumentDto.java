package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A STORED, versioned ใบแจ้งหนี้ส่วนที่เหลือ (GLA-99 step 2, {@code sales.remaining_invoice}) —
 * DRAFT -> ISSUED -> SUPERSEDED, replacing the old fully-stateless computation
 * ({@code DepositNoticeService#getRemainingInvoiceOptions}/{@code #getRemainingInvoiceXlsx}, which
 * stay in place as the SOURCE this document is snapshotted from — see {@link RemainingInvoiceService}
 * — never as a second, independently-computed rendering of the same document).
 *
 * <p>{@code baseNumber} is minted once at first issue ({@code "GLR" + Thai-year%100 +
 * 5-digit-seq}, e.g. {@code "GLR6900001"}) and carried forward unchanged across every revision;
 * {@code docNumber} is {@code baseNumber + "-" + version} (e.g. {@code "GLR6900001-1"} then
 * {@code "GLR6900001-2"}) — see the V188 migration's own header comment. Both are {@code null}
 * while {@code status} is {@code DRAFT}.
 *
 * <p>Every field from {@code reference} down to {@code items} is the FROZEN SNAPSHOT this
 * document's own file is always rendered from ({@link RemainingInvoiceService#toRenderable}) —
 * never recomputed live once issued, so editing the deal or the deposit notice it was sourced from
 * cannot silently change an already-issued document's printed content.
 */
public record RemainingInvoiceDocumentDto(
    long              id,
    long              ticketId,
    Long              customerQuotationId,
    Long              depositNoticeId,
    String            baseNumber,
    int               version,
    String            docNumber,
    String            status,
    Long              supersededById,

    String            reference,
    String            depositReference,
    LocalDate         docDate,
    List<String>      notes,

    String            customerName,
    String            customerTaxId,
    String            customerBranch,
    String            customerAddress,
    String            projectName,

    BigDecimal        itemsTotal,
    BigDecimal        depositDeduction,
    BigDecimal        netAmount,
    BigDecimal        vatAmount,
    BigDecimal        grandTotal,

    Long              createdById,
    String            createdByName,
    OffsetDateTime    createdAt,
    OffsetDateTime    updatedAt,
    Long              issuedById,
    String            issuedByName,
    OffsetDateTime    issuedAt,

    List<RemainingInvoiceDocumentItemDto> items
) {}
