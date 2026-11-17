package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** A STORED, versioned ใบแจ้งหนี้ส่วนที่เหลือ — DRAFT -> ISSUED -> SUPERSEDED, replacing the old
 * fully-stateless computation. Both are null while status is DRAFT. */
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
