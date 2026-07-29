package th.co.glr.hr.payroll;

import java.math.BigDecimal;

public record PayrollEmployeeSnapshot(
    long employeeId,
    String employeeCode,
    String employeeName,
    String departmentName,
    String bankName,
    String bankAccount,
    BigDecimal baseSalary,
    BigDecimal directorRemuneration,
    // Standing per-employee withholding-tax override (2026-07-24, V88). NULLABLE and meaningful:
    // null = no standing override (compute normally); a non-null value (including 0) is the fixed
    // withheld amount to substitute unless a per-run override wins. NOT coalesced to zero anywhere --
    // null must stay distinct from a 0 override.
    BigDecimal withholdingTaxOverride,
    // ล.ย.01 completeness (2026-07-28, V93): drives the ยกเว้นเงินได้ 190,000 for a taxpayer aged 65+
    // (กฎกระทรวง ฉบับที่ 126). NULLABLE -- an unknown date of birth must not be read as "not 65", nor
    // as a licence to grant the exemption; PayrollService resolves it to an age of 0, which the
    // calculator treats as "unknown, do not grant on an assumption".
    java.time.LocalDate dateOfBirth
) {
    /** Legacy 9-arg constructor for call sites predating {@code dateOfBirth}. */
    public PayrollEmployeeSnapshot(
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal directorRemuneration,
        BigDecimal withholdingTaxOverride
    ) {
        this(employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, directorRemuneration, withholdingTaxOverride, null);
    }
}
