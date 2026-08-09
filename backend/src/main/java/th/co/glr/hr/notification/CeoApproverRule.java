package th.co.glr.hr.notification;

/**
 * SQL mirror of {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor}'s {@code ceo} branch --
 * centralizes what used to be five separate copies (four repository methods plus one inline
 * {@code NotificationRepository} SQL fragment), all still keyed on the pre-existing {@code
 * MD%}/{@code MN%} division-prefix convention that this supersedes.
 *
 * <p><b>This governs NOTIFICATION ROUTING ONLY and confers no approval authority.</b> Whether
 * someone may actually approve a CEO-stage request is decided entirely by role checks in the
 * controllers/services via the {@code ceo} role (ultimately {@code DivisionAccessPolicy#roleFor}
 * itself) -- this class only decides who gets told about it, and this change does not touch
 * approval authority at all.
 *
 * <p>If {@code DivisionAccessPolicy#roleFor}'s {@code ceo} branch ever changes, update this
 * predicate in the same commit -- the same discipline {@code PendingApproverSql} and {@code
 * ManagerApproverRepository#hasManagerApproverSql} already document for their own Java mirrors of
 * a {@code DivisionAccessPolicy} rule.
 */
public final class CeoApproverRule {

    private CeoApproverRule() {
    }

    /**
     * Aliases expected in the caller's {@code FROM}/{@code JOIN} clauses: {@code e = hr.employee},
     * {@code d = hr.division}, {@code p = hr.position}.
     *
     * <p>Three details that are easy to get wrong:
     * <ul>
     *   <li><b>{@code = 'md'}, not {@code ILIKE 'MD%'}.</b> {@code DivisionAccessPolicy} does
     *       {@code "md".equals(code)} -- an EXACT match. A prefix match (the {@code MD%}/{@code
     *       MN%} convention this predicate supersedes) is what made the notified set too wide in
     *       the first place; do not reintroduce it.</li>
     *   <li><b>The กรรมการ test strips whitespace first</b>, exactly like {@code
     *       DivisionAccessPolicy#contains} ({@code position.replaceAll("\\s+", "")}). {@code
     *       regexp_replace(..., '\s+', '', 'g')} is the established SQL mirror for that --
     *       already used in {@code approval-routing-gap-audit.sql} section E and {@code
     *       PendingApproverSql#SINGLE_ACTIVE_CEO_NAME_SQL}. Reuse that spelling verbatim.</li>
     *   <li><b>Both joins must be LEFT JOINs.</b> {@code division_id} and {@code position_id} are
     *       both nullable on {@code hr.employee} -- an inner join would silently exclude an active
     *       employee with no division (real in this data; see {@code approval-routing-gap-audit.sql}
     *       section D) or no position, including one whose position DOES say กรรมการ. Hence also
     *       {@code COALESCE(p.name_th, '')} rather than a bare column reference.</li>
     * </ul>
     */
    public static final String SQL_PREDICATE = """
        (LOWER(BTRIM(d.source_code)) = 'md'
         OR regexp_replace(COALESCE(p.name_th, ''), '\\s+', '', 'g') LIKE '%กรรมการ%')
        """;
}
