package th.co.glr.hr.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs for the two payroll reconciliation features that are keyed by employee + tax year (C1 stored
 * tax allowances, C2 year-to-date backfill seed). Grouped in one file like {@link PayrollResponses}
 * since each is a small request/response pair rather than a standalone entity.
 */
public final class PayrollReconciliationDtos {
    private PayrollReconciliationDtos() {
    }

    // ---- C1: stored tax allowance declaration ------------------------------------------------

    public record EmployeeTaxAllowanceDto(
        long employeeId,
        String employeeCode,
        String employeeName,
        PayrollTaxAllowanceInput allowances,
        OffsetDateTime updatedAt
    ) {}

    public record EmployeeTaxAllowanceUpsertRequest(
        @NotNull Long employeeId,
        @PositiveOrZero BigDecimal spouseAllowance,
        @PositiveOrZero BigDecimal childAllowance,
        @PositiveOrZero BigDecimal parentCareAllowance,
        @PositiveOrZero BigDecimal disabledCareAllowance,
        @PositiveOrZero BigDecimal maternityAllowance,
        @PositiveOrZero BigDecimal lifeInsuranceAllowance,
        @PositiveOrZero BigDecimal healthInsuranceAllowance,
        @PositiveOrZero BigDecimal parentHealthInsuranceAllowance,
        @PositiveOrZero BigDecimal rmfAllowance,
        @PositiveOrZero BigDecimal ssfAllowance,
        @PositiveOrZero BigDecimal pensionInsuranceAllowance,
        @PositiveOrZero BigDecimal thaiEsgAllowance,
        @PositiveOrZero BigDecimal homeLoanInterestAllowance,
        @PositiveOrZero BigDecimal educationDonation,
        @PositiveOrZero BigDecimal generalDonation,
        @PositiveOrZero BigDecimal politicalDonation
    ) {}

    public record TaxAllowanceBulkUpsertRequest(
        @NotEmpty @Valid List<EmployeeTaxAllowanceUpsertRequest> items
    ) {}

    public record TaxAllowanceListResponse(int taxYear, List<EmployeeTaxAllowanceDto> items) {}

    // ---- C2: year-to-date backfill seed --------------------------------------------------------

    public record YtdSeedDto(
        long employeeId,
        String employeeCode,
        String employeeName,
        BigDecimal taxableIncome,
        BigDecimal socialSecurity,
        BigDecimal withholdingTax,
        String sourceNote,
        OffsetDateTime updatedAt
    ) {}

    public record YtdSeedUpsertRequest(
        @NotNull Long employeeId,
        @PositiveOrZero BigDecimal taxableIncome,
        @PositiveOrZero BigDecimal socialSecurity,
        @PositiveOrZero BigDecimal withholdingTax,
        String sourceNote
    ) {}

    public record YtdSeedBulkUpsertRequest(
        @NotEmpty @Valid List<YtdSeedUpsertRequest> items
    ) {}

    public record YtdSeedListResponse(int taxYear, List<YtdSeedDto> items) {}
}
