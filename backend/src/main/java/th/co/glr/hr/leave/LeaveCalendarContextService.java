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

/** Backs GET /api/leave/calendar-context (self-scoped only -- see LeaveController). This class has no such
 * history, so its constructor is free to take exactly what this one endpoint needs. */
@Service
public class LeaveCalendarContextService {

    private final LeaveRepository leaveRepository;
    private final HolidayRepository holidayRepository;

    public LeaveCalendarContextService(LeaveRepository leaveRepository, HolidayRepository holidayRepository) {
        this.leaveRepository = leaveRepository;
        this.holidayRepository = holidayRepository;
    }

    /** Selects affected frontend tests, including readers outside the import graph. */
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

    /** Same shape of check as HolidayAdminService#list (HolidayController's admin GET) -- both required, to not
     * before from -- mirrored with the identical Thai messages so the two endpoints behave identically for the
     * same bad-input shape, even though they are otherwise unrelated authorization surfaces. */
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
