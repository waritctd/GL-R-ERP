package th.co.glr.hr.attendance;

import java.util.List;
import th.co.glr.hr.attendance.daily.AttendanceEmployeeOption;

record AttendanceEmployeesResponse(List<AttendanceEmployeeOption> employees) {
}
