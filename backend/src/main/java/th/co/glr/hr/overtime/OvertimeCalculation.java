package th.co.glr.hr.overtime;

import java.time.OffsetDateTime;

/** The money calculation, computed once at the approval stage that first leaves SUBMITTED
 * (OvertimeService#managerApprove or its manager-less twin #ceoDirectApprove) and frozen from then on -- the
 * final ceoApprove step never recomputes it. */
public record OvertimeCalculation(
    OffsetDateTime actualStartAt,
    OffsetDateTime actualEndAt,
    int actualMinutes,
    int payableMinutes,
    String calculationNote,
    OvertimeDayType dayType
) {
}
