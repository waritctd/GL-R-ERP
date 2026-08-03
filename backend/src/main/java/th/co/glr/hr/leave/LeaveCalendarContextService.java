package th.co.glr.hr.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.attendance.schedule.HolidayRepository;
import th.co.glr.hr.attendance.schedule.WorkSchedule;
import th.co.glr.hr.common.ApiException;

/**
 * Backs {@code GET /api/leave/calendar-context} (self-scoped only -- see {@link LeaveController}).
 * A separate, small service rather than a new method on {@link LeaveService} on purpose: this
 * endpoint needs a brand-new dependency ({@link HolidayRepository}, from attendance/schedule/) that
 * {@link LeaveService} does not otherwise need, and {@code LeaveService}'s constructor is
 * hand-wired directly by ~14 payroll/leave integration tests (see {@link LeaveRepository}'s own
 * 1-arg test-convenience constructor javadoc for the same concern) -- adding a parameter there
 * would force an unrelated edit onto every one of them. This class has no such history, so its
 * constructor is free to take exactly what this one endpoint needs.
 *
 * <p>Deliberately does NOT reimplement {@code LeaveDayMath}'s holiday/schedule-aware day counting:
 * {@link #get} calls {@link LeaveRepository#workingDayPredicate}, the exact predicate
 * {@code LeaveService#submit}/{@code #cancel} build for real day counting, to derive
 * {@code nonWorkingDates}. If that logic is ever wrong, this endpoint is wrong the same way --
 * which is the point: an employee should see the SAME "is this day counted" answer the leave
 * engine itself uses, not a second, possibly-diverging opinion.
 */
@Service
public class LeaveCalendarContextService {

    private final LeaveRepository leaveRepository;
    private final HolidayRepository holidayRepository;

    public LeaveCalendarContextService(LeaveRepository leaveRepository, HolidayRepository holidayRepository) {
        this.leaveRepository = leaveRepository;
        this.holidayRepository = holidayRepository;
    }

    /**
     * @param employeeId the CALLING user's own employee id -- never a caller-supplied id (there is
     *                   no such parameter on the controller method; see its javadoc for why this
     *                   endpoint is self-scoped by construction, not by an access-predicate check)
     */
    public LeaveCalendarContextDto get(long employeeId, LocalDate from, LocalDate to) {
        validateRange(from, to);

        List<LeaveCalendarHolidayDto> holidays = holidayRepository.listBetween(from, to).stream()
            .map(row -> new LeaveCalendarHolidayDto(row.holidayDate(), row.nameTh()))
            .toList();

        WorkSchedule schedule = leaveRepository.resolveOwnSchedule(employeeId, from);
        Predicate<LocalDate> isWorkingDay = leaveRepository.workingDayPredicate(employeeId, from, to);
        List<LocalDate> nonWorkingDates = from.datesUntil(to.plusDays(1))
            .filter(date -> !isWorkingDay.test(date))
            .toList();

        return new LeaveCalendarContextDto(from, to, holidays, toScheduleDto(schedule), nonWorkingDates);
    }

    /**
     * Same shape of check as {@code HolidayAdminService#list} ({@code HolidayController}'s admin
     * GET) -- both required, {@code to} not before {@code from} -- mirrored with the identical
     * Thai messages so the two endpoints behave identically for the same bad-input shape, even
     * though they are otherwise unrelated authorization surfaces.
     */
    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุวันที่เริ่มต้นและวันที่สิ้นสุด");
        }
        if (to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มต้น");
        }
    }

    private static LeaveCalendarWorkScheduleDto toScheduleDto(WorkSchedule schedule) {
        List<Integer> workdays = schedule.workdays().stream()
            .map(DayOfWeek::getValue)
            .sorted()
            .toList();
        return new LeaveCalendarWorkScheduleDto(
            schedule.workStart(), schedule.workEnd(), schedule.graceMinutes(),
            schedule.requiresCheckOut(), workdays);
    }
}
