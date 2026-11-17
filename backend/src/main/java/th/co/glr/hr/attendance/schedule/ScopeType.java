package th.co.glr.hr.attendance.schedule;

/** The three scopes a hr.work_schedule_assignment row can target, in TieredWorkScheduleResolver's precedence
 * order (declaration order here is that precedence — see TieredWorkScheduleResolver#resolve). */
public enum ScopeType {
    EMPLOYEE,
    DEPARTMENT,
    DIVISION
}
