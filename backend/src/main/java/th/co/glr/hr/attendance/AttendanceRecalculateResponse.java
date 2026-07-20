package th.co.glr.hr.attendance;

/**
 * @param recalculatedDays employee-days written. Rows blocked by {@code is_manual_override} are not
 *                         counted, so a lower number than expected means HR corrections were
 *                         preserved rather than that work was skipped.
 */
record AttendanceRecalculateResponse(int recalculatedDays) {
}
