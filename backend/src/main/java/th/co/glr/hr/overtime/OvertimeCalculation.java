package th.co.glr.hr.overtime;

import java.time.OffsetDateTime;

public record OvertimeCalculation(
    OffsetDateTime actualStartAt,
    OffsetDateTime actualEndAt,
    int actualMinutes,
    int payableMinutes,
    String calculationNote
) {
}
