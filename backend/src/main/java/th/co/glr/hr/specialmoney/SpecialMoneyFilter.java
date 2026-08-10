package th.co.glr.hr.specialmoney;

import java.time.LocalDate;

/**
 * Criteria for {@link SpecialMoneyRepository#findRequests}.
 *
 * <p>{@code ownEmployeeId} is the whole of the non-privileged read scope: set it and the query
 * returns that employee's rows and no others; leave it null (hr/ceo only) and the window is
 * unrestricted. There is deliberately <b>no {@code managerDivisionId}</b> — this record carried one
 * until 2026-08-10, and it widened a ฝ่าย manager's list to their entire division's welfare claims.
 * Welfare is confidential per employee (see {@link SpecialMoneyService}'s class Javadoc), so the
 * field is removed rather than merely left unset: an unused scope-widening parameter is an
 * invitation to wire it back up.
 */
public record SpecialMoneyFilter(
    Long employeeId,
    Long ownEmployeeId,
    LocalDate fromDate,
    LocalDate toDate,
    SpecialMoneyStatus status,
    String requestType) {
}
