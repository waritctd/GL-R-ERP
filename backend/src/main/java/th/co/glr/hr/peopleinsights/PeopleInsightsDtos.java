package th.co.glr.hr.peopleinsights;

import java.math.BigDecimal;
import java.util.List;

/** Response shapes of GET /api/people-insights. */
public final class PeopleInsightsDtos {
    private PeopleInsightsDtos() {
    }

    public record Scope(String id, String label) {}

    public record LeaveType(String code, String nameTh, BigDecimal annualQuotaDays) {}

    public record Person(long employeeId, String employeeCode, String nickname, boolean timeExempt) {}

    /** One employee x month x leave type, APPROVED requests only, bucketed by start month. */
    public record LeaveRow(long employeeId, String month, String leaveTypeCode, BigDecimal days, int requests,
                           BigDecimal sickEdgeDays, int lateFiled, BigDecimal unpaidDays) {}

    /** One employee x month; avgCheckInMinutes is over the morningScanDays check-ins before 12:00 only. Never produced for a time-exempt employee. */
    public record AttendanceRow(long employeeId, String month, int scanDays, int lateDays, int lateMinutes,
                                int noCheckOut, int earlyLeave, int morningScanDays, Double avgCheckInMinutes) {}

    /** Days charged to a quota year (hr.leave_request_quota_year), APPROVED requests only: a request is filed under the year its days fall in, not its start year. */
    public record QuotaUsage(long employeeId, String leaveTypeCode, BigDecimal days) {}

    public record Team(String id, String label, List<String> headNicknames, List<Long> memberIds) {}

    public record Response(String asOf, int year, List<Scope> scopes, Scope scope, List<LeaveType> leaveTypes,
                           List<Person> people, List<LeaveRow> leave, List<QuotaUsage> quotaUsage,
                           List<AttendanceRow> attendance, List<Team> teams) {}
}
