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
        // ล.ย.01 effective dating (V93): which dated declaration this row is, and the evidence behind it.
        int effectiveMonth,
        String documentReference,
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
        @PositiveOrZero BigDecimal politicalDonation,
        // ล.ย.01 completeness (V93): กองทุนสำรองเลี้ยงชีพ, the per-head counts behind the บุตร and
        // อุปการะคนพิการ caps, and the บัตรประจำตัวคนพิการ declaration behind the 190,000 ยกเว้นเงินได้.
        @PositiveOrZero BigDecimal providentFundAllowance,
        @PositiveOrZero Integer childCount,
        @PositiveOrZero Integer childCountDouble,
        @PositiveOrZero Integer disabledCareCount,
        Boolean disabilityCardHolder,
        // ล.ย.01 effective dating (V93). The month this declaration takes effect from, 1..12 --
        // คำชี้แจง ภ.ง.ด.1 ข้อ 2.2 applies a mid-year change from the period it is notified, so a
        // correction is a NEW dated row rather than an overwrite of the old one. Null means January,
        // which is how every pre-V93 declaration was already being applied.
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(12) Integer effectiveMonth,
        // Evidence the employee attached to the ล.ย.01 (receipt/certificate reference).
        String documentReference
    ) {
        /**
         * Legacy 17-arg constructor: the declaration shape before the ล.ย.01 completeness and dating
         * fields existed. Everything new defaults to "not declared" and the row dates from January,
         * which is exactly how a pre-V93 declaration was already being applied.
         */
        public EmployeeTaxAllowanceUpsertRequest(
            Long employeeId,
            BigDecimal spouseAllowance,
            BigDecimal childAllowance,
            BigDecimal parentCareAllowance,
            BigDecimal disabledCareAllowance,
            BigDecimal maternityAllowance,
            BigDecimal lifeInsuranceAllowance,
            BigDecimal healthInsuranceAllowance,
            BigDecimal parentHealthInsuranceAllowance,
            BigDecimal rmfAllowance,
            BigDecimal ssfAllowance,
            BigDecimal pensionInsuranceAllowance,
            BigDecimal thaiEsgAllowance,
            BigDecimal homeLoanInterestAllowance,
            BigDecimal educationDonation,
            BigDecimal generalDonation,
            BigDecimal politicalDonation
        ) {
            this(employeeId, spouseAllowance, childAllowance, parentCareAllowance, disabledCareAllowance,
                maternityAllowance, lifeInsuranceAllowance, healthInsuranceAllowance,
                parentHealthInsuranceAllowance, rmfAllowance, ssfAllowance, pensionInsuranceAllowance,
                thaiEsgAllowance, homeLoanInterestAllowance, educationDonation, generalDonation,
                politicalDonation, BigDecimal.ZERO,
                // Head counts DERIVED from the declared amounts, for the same reason
                // PayrollTaxAllowanceInput's legacy constructor derives them and V93's migration
                // backfills them: at this arity the amount is the only evidence there is, and reading
                // "100,000 declared, 0 children" as an overstatement would silently delete a real
                // allowance. The doubled 60,000 rate is not assumed -- deriving stays on the plain
                // 30,000 rate and under-claims rather than over-claims.
                headCount(childAllowance, "30000"), 0, headCount(disabledCareAllowance, "60000"),
                false, 1, null);
        }

        /** Smallest head count that would permit the declared amount -- see the legacy constructor. */
        private static int headCount(BigDecimal declaredAmount, String perHead) {
            if (declaredAmount == null || declaredAmount.signum() <= 0) {
                return 0;
            }
            return declaredAmount
                .divide(new BigDecimal(perHead), 0, java.math.RoundingMode.CEILING)
                .intValueExact();
        }
    }

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
