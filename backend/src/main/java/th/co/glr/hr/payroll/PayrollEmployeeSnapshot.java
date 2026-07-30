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
    java.time.LocalDate dateOfBirth,
    // Daily-rate support (hr.employee.pay_type, existed since V1 but never read by payroll before
    // this fix). Raw CHAR(1) code: "M" (monthly, the default), "D" (daily -- current_salary is a
    // PER-DAY rate, not a monthly figure), or null (172 legacy rows predate the column and 3 more
    // have never been set -- both are treated as "M", per the owner's instruction). PayrollService
    // uses this to decide whether PayrollComponent.SALARY is baseSalary itself (monthly) or
    // baseSalary multiplied by HR-entered daysWorked (daily) -- see PayrollEmployeeInputRequest
    // #daysWorked and PayrollClassifiedCalculationDtos#dailyRateOverride.
    String payType
) {
    /** Legacy 10-arg constructor for call sites predating {@code payType}. */
    public PayrollEmployeeSnapshot(
        long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String bankName,
        String bankAccount,
        BigDecimal baseSalary,
        BigDecimal directorRemuneration,
        BigDecimal withholdingTaxOverride,
        java.time.LocalDate dateOfBirth
    ) {
        this(employeeId, employeeCode, employeeName, departmentName, bankName, bankAccount,
            baseSalary, directorRemuneration, withholdingTaxOverride, dateOfBirth, null);
    }

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
            baseSalary, directorRemuneration, withholdingTaxOverride, null, null);
    }

    /** True for a daily-rate ("D") employee; every other value (including null/legacy "M") is monthly. */
    public boolean dailyRatePay() {
        return "D".equals(payType);
    }
}
