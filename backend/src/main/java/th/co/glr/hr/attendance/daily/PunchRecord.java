package th.co.glr.hr.attendance.daily;

import java.time.OffsetDateTime;

/**
 * The slice of {@code hr.attendance_punch} the calculator needs.
 *
 * <p>{@code punchState} is deliberately absent: {@code V7__attendance_schema.sql} documents it as
 * untrustworthy for IN/OUT direction, so the calculator derives direction from chronology instead.
 * Leaving the field out of this type makes that impossible to get wrong later.
 *
 * @param punchId   identity, stored on the daily row as check_in_punch_id / check_out_punch_id
 * @param punchTime the scan instant
 * @param siteCode  where the scan happened
 */
public record PunchRecord(long punchId, OffsetDateTime punchTime, String siteCode) {
    public PunchRecord {
        if (punchTime == null) {
            throw new IllegalArgumentException("punchTime must not be null");
        }
    }
}
