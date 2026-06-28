package th.co.glr.hr.attendance;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

record NormalizedAttendancePunch(
    String siteCode,
    String deviceCode,
    String badgeCode,
    OffsetDateTime punchTime,
    LocalDate workDate,
    short deviceStatus,
    short punchState,
    String workCode,
    String reservedValue,
    String punchSource,
    String ingestMethod,
    Map<String, Object> rawPayload
) {
}
