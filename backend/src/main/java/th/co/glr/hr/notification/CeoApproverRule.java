package th.co.glr.hr.notification;

/**
 * Decides who gets NOTIFIED about a pending CEO-stage approval -- the one shared predicate for
 * every repository method and SQL fragment that needs it.
 *
 * <p><b>Deliberately NARROWER than the {@code ceo} role.</b> This is
 * NOT a mirror of {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor} and must not be
 * "resynced" with it if that method ever changes. A mirror (division code EXACTLY {@code md}, OR
 * any กรรมการ-family position) would assume that "who may approve" and "who should be told" are
 * the same set, which they are not: every active {@code md}/กรรมการ-family employee --
 * ประธานกรรมการ, two more กรรมการ, and กรรมการผู้จัดการ -- would match one arm of that
 * {@code OR} or the other, so nobody would be narrowed out. Only กรรมการผู้จัดการ should be
 * notified -- the position test alone is the single discriminator.
 *
 * <p><b>ประธานกรรมการ and กรรมการ still HOLD the {@code ceo} role and can still approve</b> --
 * {@code DivisionAccessPolicy#roleFor} is unaffected. They are simply not *told*. This class
 * governs NOTIFICATION ROUTING ONLY and confers no approval authority;
 * whether someone may actually approve a CEO-stage request is decided entirely by role checks in
 * the controllers/services, never by this class.
 *
 * <p><b>Consequence, stated plainly:</b> if no active employee holds the position
 * กรรมการผู้จัดการ, {@link CeoApproverRepository#findEmployeeIds()} returns empty and NOBODY is
 * notified of a pending CEO-stage request -- approval authority is unaffected, but the queue goes
 * silent. There is no redundancy (several people notified for the same event) by design. That is
 * an accepted trade-off, not an oversight.
 */
public final class CeoApproverRule {

    private CeoApproverRule() {
    }

    /**
     * Alias expected in the caller's {@code FROM}/{@code JOIN} clause: {@code p = hr.position}. No
     * {@code hr.division} alias is needed -- this predicate is division-independent by design (see
     * class Javadoc).
     *
     * <p>Two details that are easy to get wrong:
     * <ul>
     *   <li><b>Whitespace is stripped before matching</b>, exactly like {@code
     *       DivisionAccessPolicy#contains} ({@code position.replaceAll("\\s+", "")}). {@code
     *       regexp_replace(..., '\s+', '', 'g')} is the established SQL mirror for that -- already
     *       used in {@code approval-routing-gap-audit.sql} and {@code
     *       PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL}. Reuse that spelling verbatim. This is
     *       what makes กรรมการ ผู้จัดการ (a real spelling variant with an internal space) still
     *       match.</li>
     *   <li><b>{@code LEFT JOIN hr.position} is required.</b> {@code position_id} is nullable on
     *       {@code hr.employee} -- an inner join would silently exclude an active employee with no
     *       position from the result set. Hence {@code COALESCE(p.name_th, '')} rather than a bare
     *       column reference.</li>
     * </ul>
     */
    public static final String SQL_PREDICATE = """
        (regexp_replace(COALESCE(p.name_th, ''), '\\s+', '', 'g') LIKE '%กรรมการผู้จัดการ%')
        """;
}
