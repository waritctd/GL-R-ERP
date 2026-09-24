package th.co.glr.hr.billing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line the caller wants on a billing note's DRAFT — either a reference to an already-ISSUED
 * ERP document ({@code sourceType} {@code REMAINING_INVOICE}/{@code DEPOSIT_NOTICE}, {@code
 * sourceId} required, every {@code manual*} field ignored — the service resolves doc
 * number/date/due date/amount from the source itself, see {@code BillingNoteService.resolveLine} —
 * a private method, hence {@code @code} here rather than a broken {@code @link})
 * or a caller-typed line ({@code sourceType MANUAL}, {@code sourceId} must be {@code null}, every
 * {@code manual*} field required — freight charges, an external tax invoice, anything with no ERP
 * document of its own).
 */
public record BillingNoteLineSelection(
    @NotBlank String sourceType,
    Long sourceId,
    @Size(max = 30) String manualDocNumber,
    LocalDate manualDocDate,
    LocalDate manualDueDate,
    @DecimalMin(value = "0.00", inclusive = false) BigDecimal manualAmount,
    @Size(max = 2000) String manualNote
) {}
