package th.co.glr.hr.attendance;

/**
 * SQL fragments shared by more than one attendance query.
 *
 * <p>These exist so the punch list and the daily roll-up resolve a badge to an employee
 * <em>identically</em>. Two hand-maintained copies would drift, and the drift would be silent: a
 * punch visible in one view and missing from the other, with no error anywhere.
 */
public final class AttendanceSql {

    /**
     * Joins {@code hr.employee e} to {@code hr.attendance_punch p}.
     *
     * <p>Resolves by the stored {@code employee_id} when present, otherwise falls back to matching
     * the raw {@code badge_code} against either the card number or the employee code. Card readers
     * report the card number while fingerprint/PIN punches report the employee code, so a
     * single-column match leaves half the punches unmapped; the COALESCE keeps historical rows
     * resolving too.
     */
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
