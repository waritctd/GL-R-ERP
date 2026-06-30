package th.co.glr.hr.overtime;

import java.time.OffsetDateTime;

public record OvertimeAttendanceBounds(
    OffsetDateTime firstPunchAt,
    OffsetDateTime lastPunchAt
) {
}
