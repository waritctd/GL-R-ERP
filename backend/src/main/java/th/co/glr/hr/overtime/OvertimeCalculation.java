package th.co.glr.hr.overtime;

import java.time.OffsetDateTime;

/**
 * The money calculation, computed once at the approval stage that first leaves {@code SUBMITTED}
 * ({@code OvertimeService#managerApprove} or its manager-less twin {@code #ceoDirectApprove}) and
 * frozen from then on -- the final {@code ceoApprove} step never recomputes it.
 *
 * <p>{@code dayType} belongs in this bundle, not read off the request: it is re-derived from {@code
 * HolidayCalendar} at the same moment {@code actualMinutes}/{@code payableMinutes} are derived from
 * attendance and {@code salary_basis} is resolved as of the work date, so all four freeze together.
 * See {@code OvertimeService#deriveDayType}.
 */
public record OvertimeCalculation(
    OffsetDateTime actualStartAt,
    OffsetDateTime actualEndAt,
    int actualMinutes,
    int payableMinutes,
    String calculationNote,
    OvertimeDayType dayType
) {
}
