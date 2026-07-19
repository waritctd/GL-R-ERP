package th.co.glr.hr.attendance.daily;

import java.time.LocalDate;

/** The unit of recalculation: one employee, one business date. */
public record EmployeeDay(long employeeId, LocalDate workDate) {
}
