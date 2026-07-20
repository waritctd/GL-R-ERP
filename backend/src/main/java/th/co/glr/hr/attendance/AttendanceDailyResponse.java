package th.co.glr.hr.attendance;

import java.util.List;
import th.co.glr.hr.attendance.daily.AttendanceDailyDto;

record AttendanceDailyResponse(List<AttendanceDailyDto> days) {
}
