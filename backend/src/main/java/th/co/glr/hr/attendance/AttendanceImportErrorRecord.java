package th.co.glr.hr.attendance;

record AttendanceImportErrorRecord(
    int lineNo,
    String rawLine,
    String errorCode,
    String errorMessage
) {
}
