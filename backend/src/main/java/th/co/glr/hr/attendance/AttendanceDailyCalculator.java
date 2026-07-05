package th.co.glr.hr.attendance;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Derives a day's attendance metrics from its raw punches against the standard shift
 * (default 08:30–17:30 Asia/Bangkok). Late/early-leave minutes are computed for reporting and
 * discipline only — Thai Labour Protection Act §76 forbids docking wages as a lateness penalty,
 * so these figures never feed a payroll deduction. Only full-day absence (no punch on a scheduled
 * workday, not covered by approved leave) drives an unpaid "no-work-no-pay" deduction, and that
 * reconciliation happens in the payroll layer.
 */
public final class AttendanceDailyCalculator {
    private final ZoneId zone;
    private final LocalTime standardStart;
    private final LocalTime standardEnd;
    private final int lateGraceMinutes;

    public AttendanceDailyCalculator(ZoneId zone, LocalTime standardStart, LocalTime standardEnd, int lateGraceMinutes) {
        this.zone = zone;
        this.standardStart = standardStart;
        this.standardEnd = standardEnd;
        this.lateGraceMinutes = Math.max(0, lateGraceMinutes);
    }

    /**
     * @param punchTimes all punches recorded for one employee on one work date, any order.
     * @return the computed daily record; {@link Result#absent()} is true when there are no punches.
     */
    public Result compute(List<OffsetDateTime> punchTimes) {
        List<OffsetDateTime> sorted = punchTimes == null ? List.of()
            : punchTimes.stream().filter(p -> p != null).sorted().toList();
        if (sorted.isEmpty()) {
            return new Result(null, null, 0, 0, 0, 0, true);
        }
        OffsetDateTime checkIn = sorted.get(0);
        OffsetDateTime checkOut = sorted.get(sorted.size() - 1);
        int totalMinutes = (int) Duration.between(checkIn, checkOut).toMinutes();

        LocalTime arrival = checkIn.atZoneSameInstant(zone).toLocalTime();
        LocalTime departure = checkOut.atZoneSameInstant(zone).toLocalTime();
        LocalTime lateThreshold = standardStart.plusMinutes(lateGraceMinutes);

        int lateMinutes = arrival.isAfter(lateThreshold)
            ? (int) Duration.between(lateThreshold, arrival).toMinutes() : 0;
        int earlyLeaveMinutes = departure.isBefore(standardEnd)
            ? (int) Duration.between(departure, standardEnd).toMinutes() : 0;

        return new Result(checkIn, checkOut, totalMinutes, lateMinutes, earlyLeaveMinutes, sorted.size(), false);
    }

    public record Result(
        OffsetDateTime checkIn,
        OffsetDateTime checkOut,
        int totalMinutes,
        int lateMinutes,
        int earlyLeaveMinutes,
        int punchCount,
        boolean absent
    ) {}
}
