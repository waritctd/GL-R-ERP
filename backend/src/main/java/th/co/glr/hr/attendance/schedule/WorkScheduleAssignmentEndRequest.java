package th.co.glr.hr.attendance.schedule;

import java.time.LocalDate;

/** Ends an assignment as of {@code effectiveTo} (inclusive — the assignment still applies on that date). */
record WorkScheduleAssignmentEndRequest(LocalDate effectiveTo) {
}
