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
    // Director's remuneration (ค่าตอบแทนกรรมการ): not wages under the Social Security Act,
    // so it is excluded from SSO, but still fully subject to normal progressive income tax.
    boolean directorCompensation
) {}
