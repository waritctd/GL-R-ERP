package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** A STORED, versioned ใบวางบิล — DRAFT -> ISSUED -> SUPERSEDED | CANCELLED | SETTLED. revisionOfId (B1) is the
 * ISSUED note THIS row was created to correct — set only by BillingNoteService#revise, null for a brand-new
 * draft starting its own chain. */
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
