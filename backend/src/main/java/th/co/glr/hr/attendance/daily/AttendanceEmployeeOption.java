package th.co.glr.hr.attendance.daily;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An employee the caller may filter the day view by. */
public record AttendanceEmployeeOption(
    @JsonProperty("employee_id") long employeeId,
    @JsonProperty("employee_code") String employeeCode,
    @JsonProperty("employee_name") String employeeName,
    @JsonProperty("nick_name") String nickName,
    @JsonProperty("department_name") String departmentName
) {
}
