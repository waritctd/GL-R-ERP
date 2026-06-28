package th.co.glr.hr.attendance;

import java.util.List;

record DatParseResult(
    List<NormalizedAttendancePunch> punches,
    List<AttendanceImportErrorRecord> errors,
    int rowCount
) {
}
