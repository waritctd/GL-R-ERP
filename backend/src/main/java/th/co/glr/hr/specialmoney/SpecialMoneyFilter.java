package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

public record SpecialMoneyFilter(
    Long employeeId,
    Long managerEmployeeId,
    Long managerDivisionId,
    LocalDate fromDate,
    LocalDate toDate,
    SpecialMoneyStatus status,
    String requestType) {
}
