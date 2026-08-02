package th.co.glr.hr.attendance.schedule;

/**
 * The three scopes a {@code hr.work_schedule_assignment} row can target, in
 * {@link TieredWorkScheduleResolver}'s precedence order (declaration order here is that
 * precedence — see {@link TieredWorkScheduleResolver#resolve}).
 */
public enum ScopeType {
    EMPLOYEE,
    DEPARTMENT,
    DIVISION
}
