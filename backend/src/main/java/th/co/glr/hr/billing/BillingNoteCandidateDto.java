package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One ISSUED remaining invoice or deposit notice eligible (or not) to go on a NEW billing note line for a
 * given customer — see BillingNoteService#candidates. outstandingAmount is VAT-inclusive doc total minus
 * payments already recorded against it (GLA-107 answer 2). */
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
