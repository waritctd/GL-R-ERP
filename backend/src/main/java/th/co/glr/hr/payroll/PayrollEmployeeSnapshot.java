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
    BigDecimal withholdingTaxOverride
) {}
