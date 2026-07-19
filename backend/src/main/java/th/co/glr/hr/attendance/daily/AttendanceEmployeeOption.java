package th.co.glr.hr.attendance.daily;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An employee the caller may filter the day view by.
 *
 * <p>Division travels with each option so the UI can build its ฝ่าย filter from this one response
 * instead of a second endpoint — the set of divisions a caller may filter by is exactly the set
 * present in the employees they may see, so deriving it here keeps the two in step by construction.
 */
public record AttendanceEmployeeOption(
    @JsonProperty("employee_id") long employeeId,
    @JsonProperty("employee_code") String employeeCode,
    @JsonProperty("employee_name") String employeeName,
    @JsonProperty("nick_name") String nickName,
    @JsonProperty("department_name") String departmentName,
    @JsonProperty("division_id") Long divisionId,
    @JsonProperty("division_name") String divisionName
) {
}
