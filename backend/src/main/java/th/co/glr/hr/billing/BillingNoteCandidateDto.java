package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One ISSUED remaining invoice or deposit notice eligible (or not) to go on a NEW billing note
 * line for a given customer — see {@link BillingNoteService#candidates}. {@code outstandingAmount}
 * is VAT-inclusive doc total minus payments already recorded against it (GLA-107 answer 2). {@code
 * blockedByNoteId}/{@code blockedByNoteNumber} are non-null only in {@link
 * BillingNoteCandidatesDto#alreadyBilled} — otherwise-eligible docs excluded from {@code
 * candidates} because a DIFFERENT live (DRAFT or ISSUED) billing note already claims them, named so
 * a caller can explain the exclusion rather than silently drop the row.
 */
public record BillingNoteCandidateDto(
    String     sourceType,
    long       sourceId,
    long       ticketId,
    String     docNumber,
    LocalDate  docDate,
    LocalDate  dueDate,
    BigDecimal outstandingAmount,
    Long       blockedByNoteId,
    String     blockedByNoteNumber
) {}
