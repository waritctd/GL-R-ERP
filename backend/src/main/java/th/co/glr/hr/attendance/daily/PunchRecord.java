package th.co.glr.hr.attendance.daily;

import java.time.OffsetDateTime;

/** The slice of hr.attendance_punch the calculator needs. punchState is deliberately absent:
 * V7__attendance_schema.sql documents it as untrustworthy for IN/OUT direction, so the calculator derives
 * direction from chronology instead. */
public record PunchRecord(long punchId, OffsetDateTime punchTime, String siteCode) {
    public PunchRecord {
        if (punchTime == null) {
            throw new IllegalArgumentException("punchTime must not be null");
        }
    }
}
