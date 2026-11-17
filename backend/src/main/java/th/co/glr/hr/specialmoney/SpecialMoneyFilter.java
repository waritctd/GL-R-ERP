package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

/** Criteria for SpecialMoneyRepository#findRequests. ownEmployeeId is the whole of the non-privileged read
 * scope: set it and the query returns that employee's rows and no others; leave it null (hr/ceo only) and the
 * window is unrestricted. */
public record SpecialMoneyFilter(
    Long employeeId,
    Long ownEmployeeId,
    LocalDate fromDate,
    LocalDate toDate,
    SpecialMoneyStatus status,
    String requestType) {
}
