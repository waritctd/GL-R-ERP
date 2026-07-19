package th.co.glr.hr.attendance;

/**
 * The rows a caller may see, resolved from their role once and reused by every attendance read.
 *
 * <p>Deliberately a single shared type: the punch list and the day view previously would have each
 * carried their own copy of the role rules, and two hand-maintained copies of an authorization rule
 * drift — the drift being a data leak rather than a visible bug.
 *
 * <p>Exactly one of the fields is normally set: {@code employeeId} for self-only callers,
 * {@code divisionId} for ฝ่าย managers, neither for hr/ceo (company-wide). A manager who also
 * requested a specific employee gets both, which ANDs and so cannot escape the division.
 */
public record AttendanceScope(Long employeeId, Long divisionId) {

    static AttendanceScope all() {
        return new AttendanceScope(null, null);
    }

    static AttendanceScope self(long employeeId) {
        return new AttendanceScope(employeeId, null);
    }

    static AttendanceScope division(Long employeeId, long divisionId) {
        return new AttendanceScope(employeeId, divisionId);
    }
}
