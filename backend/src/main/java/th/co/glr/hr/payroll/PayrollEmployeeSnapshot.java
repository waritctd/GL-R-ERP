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
    // Standing per-employee withholding-tax override (2026-07-24, V88). NULLABLE and meaningful: null = no
    // standing override (compute normally); a non-null value (including 0) is the fixed withheld amount to
    // substitute unless a per-run override wins.
    BigDecimal withholdingTaxOverride,
    // ล.ย.01 completeness (2026-07-28, V93): drives the ยกเว้นเงินได้ 190,000 for a taxpayer aged 65+ (กฎกระทรวง
    // ฉบับที่ 126).
    java.time.LocalDate dateOfBirth,
    // Daily-rate support (hr.employee.pay_type, existed since V1 but never read by payroll before this fix).
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
