package th.co.glr.hr.attendance;

/** SQL fragments shared by more than one attendance query. These exist so the punch list and the daily roll-up
 * resolve a badge to an employee identically. */
public final class AttendanceSql {

    /** Joins hr.employee e to hr.attendance_punch p. Resolves by the stored employee_id when present, otherwise
     * falls back to matching the raw badge_code against either the card number or the employee code. */
    public static final String RESOLVED_EMPLOYEE_JOIN = """
          LEFT JOIN hr.employee e ON e.employee_id = COALESCE(
                   p.employee_id,
                   (SELECT em.employee_id
                      FROM hr.employee em
                     WHERE em.badge_card_no = p.badge_code
                        OR em.employee_code = p.badge_code
                     ORDER BY em.is_active DESC, em.employee_id
                     LIMIT 1))
        """;

    /** The resolved employee id, for SELECT lists and WHERE clauses. */
    public static final String RESOLVED_EMPLOYEE_ID = "COALESCE(p.employee_id, e.employee_id)";

    private AttendanceSql() {
    }
}
