package th.co.glr.hr.billing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One line the caller wants on a billing note's DRAFT — either a reference to an already-ISSUED ERP document
 * or a caller-typed line. */
public record BillingNoteLineSelection(
    @NotBlank String sourceType,
    Long sourceId,
    @Size(max = 30) String manualDocNumber,
    LocalDate manualDocDate,
    LocalDate manualDueDate,
    @DecimalMin(value = "0.00", inclusive = false) BigDecimal manualAmount,
    @Size(max = 2000) String manualNote
) {}
