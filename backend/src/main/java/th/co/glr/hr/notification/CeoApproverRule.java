package th.co.glr.hr.notification;

/**
 * Decides who gets NOTIFIED about a pending CEO-stage approval -- centralizes what used to be five
 * separate copies (four repository methods plus one inline {@code NotificationRepository} SQL
 * fragment), all originally keyed on the pre-existing {@code MD%}/{@code MN%} division-prefix
 * convention.
 *
 * <p><b>Deliberately NARROWER than the {@code ceo} role, by owner ruling 2026-08-10.</b> This is
 * NOT a mirror of {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor} and must not be
 * "resynced" with it if that method ever changes -- that discipline applied to an earlier version
 * of this predicate, but no longer applies. That earlier version WAS written as such a mirror
 * (division code EXACTLY {@code md}, OR any กรรมการ-family position) on the assumption that "who
 * may approve" and "who should be told" are the same set. Measured against real data (UAT
 * {@code wuypxdznuhhluwzncafh} and prod {@code tdyzcqzxmhtxpbouewud}, identical on this point as of
 * 2026-08-10), that assumption was false in practice: every active {@code md}/กรรมการ-family
 * employee -- ประธานกรรมการ, two more กรรมการ, and กรรมการผู้จัดการ -- matched one arm of that
 * {@code OR} or the other, so the "narrowing" was a no-op against production data; it selected the
 * exact same four people the old {@code MD%}/{@code MN%} rule did. The owner's ruling is that only
 * กรรมการผู้จัดการ should be notified -- the position test alone is now the single discriminator.
 *
 * <p><b>ประธานกรรมการ and กรรมการ still HOLD the {@code ceo} role and can still approve</b> --
 * {@code DivisionAccessPolicy#roleFor} is completely untouched by this change. They are simply no
 * longer *told*. This class governs NOTIFICATION ROUTING ONLY and confers no approval authority;
 * whether someone may actually approve a CEO-stage request is decided entirely by role checks in
 * the controllers/services, never by this class.
 *
 * <p><b>Consequence, stated plainly:</b> if no active employee holds the position
 * กรรมการผู้จัดการ, {@link CeoApproverRepository#findEmployeeIds()} returns empty and NOBODY is
 * notified of a pending CEO-stage request -- approval authority is unaffected, but the queue goes
 * silent. The previous division-based rule had accidental redundancy (any of four people would be
 * notified for the same event) that this removes by design. That is the accepted trade-off of the
 * 2026-08-10 owner ruling, not an oversight.
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
