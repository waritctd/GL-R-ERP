package th.co.glr.hr.attendance;

/** The rows a caller may see, resolved from their role once and reused by every attendance read. Exactly one of
 * the fields is normally set: employeeId for self-only callers, divisionId for ฝ่าย managers, neither for
 * hr/ceo (company-wide). */
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
