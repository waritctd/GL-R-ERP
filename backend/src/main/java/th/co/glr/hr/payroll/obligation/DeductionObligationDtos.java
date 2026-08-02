package th.co.glr.hr.payroll.obligation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTO shapes for the deduction-obligation HR CRUD + progress read + employee read-only view. */
public final class DeductionObligationDtos {
    private DeductionObligationDtos() {}

    public record DeductionObligationDto(
        long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        DeductionObligationKind kind,
        BigDecimal monthlyInstructedAmount,
        // Nullable -- see V106's header. NULL means this obligation never auto-stops.
        BigDecimal instructedTotal,
        String authorityReference,
        // The date ON THE AUTHORITY'S DOCUMENT (the กยศ notification / LED order date) -- NOT "the
        // first payroll month to deduct". Drives payroll from the CALENDAR MONTH CONTAINING this
        // date onward: a document dated 2026-01-15 drives January in full, not February (see
        // DeductionObligationRepository#findDrivingObligation's month-truncated comparison, fixed
        // after an Opus review caught the raw-date off-by-one). This reading is a judgment call --
        // there is no UI yet to confirm it matches how HR actually enters it. UI-phase handoff: the
        // entry form must label this field unambiguously (e.g. "วันที่ตามหนังสือแจ้ง" rather than a
        // bare "วันที่เริ่ม"), since the alternative reading ("first month to actually deduct") would
        // change which month a mid-month document drives.
        LocalDate startDate,
        DeductionObligationStatus status,
        OffsetDateTime completedAt,
        Long completionAcknowledgedById,
        OffsetDateTime completionAcknowledgedAt,
        boolean overrideContinuePastTotal,
        Long overrideById,
        OffsetDateTime overrideAt,
        String overrideReason,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public record DeductionObligationCreateRequest(
        @NotNull Long employeeId,
        @NotNull DeductionObligationKind kind,
        @NotNull @PositiveOrZero BigDecimal monthlyInstructedAmount,
        // Deliberately nullable and NOT defaulted -- an absent total must never be read as zero.
        @PositiveOrZero BigDecimal instructedTotal,
        @NotBlank String authorityReference,
        // See DeductionObligationDto#startDate's own comment: the authority's document date, not a
        // "first month to deduct" field -- it drives payroll from the calendar month containing it.
        @NotNull LocalDate startDate,
        String notes
    ) {}

    /**
     * HR correcting the instruction on file (the authority raised/lowered the monthly figure or
     * total, or HR fixes a typo in the reference). Deliberately does NOT touch status/override --
     * those go through their own dedicated actions below, each with its own audit trail.
     */
    public record DeductionObligationUpdateRequest(
        @NotNull @PositiveOrZero BigDecimal monthlyInstructedAmount,
        @PositiveOrZero BigDecimal instructedTotal,
        @NotBlank String authorityReference,
        String notes
    ) {}

    /** Decision 3's required mitigation: an explicit, recorded reason to continue past the total. */
    public record DeductionObligationOverrideRequest(
        @NotBlank String reason
    ) {}

    public record RemittanceLedgerRowDto(
        long id,
        LocalDate payrollMonth,
        BigDecimal amount,
        Long payrollPeriodId
    ) {}

    /**
     * The shape both the HR progress read and the employee read-only view return -- "show progress,
     * not a number" (issue #373's UX principle). {@code remaining}/{@code estimatedPeriodsRemaining}/
     * {@code estimatedEndMonth} are all {@code null} when {@code instructedTotal} is null: an
     * uncapped obligation has no remaining balance or end date to show, and that must render as
     * "not stated by the authority", never as zero/now.
     */
    public record DeductionObligationProgressDto(
        DeductionObligationDto obligation,
        BigDecimal totalRemitted,
        BigDecimal remaining,
        Integer periodsRemitted,
        Integer estimatedPeriodsRemaining,
        LocalDate estimatedEndMonth,
        List<RemittanceLedgerRowDto> ledger
    ) {}

    public record DeductionObligationListResponse(List<DeductionObligationDto> items) {}

    public record MyDeductionObligationsResponse(List<DeductionObligationProgressDto> items) {}
}
