package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Answers one question, for the whole application: <b>is there a manager stage for this employee's
 * requests at all?</b> When the answer is no, overtime and welfare route straight to the CEO
 * instead of stalling in SUBMITTED with nobody able to act.
 *
 * <p>Three rules, all owner-set:
 *
 * <ol>
 *   <li><b>A ฝ่าย manager approves their division.</b> {@code reports_to_employee_id} does not grant
 *       approval rights — this matches {@code AttendanceService.resolveScope}, which has always been
 *       division-only. Overtime and welfare previously also honoured {@code reports_to}; that branch
 *       was removed in the same change that added this class, so a direct-report manager who is not
 *       a ผู้จัดการ no longer approves. That is a deliberate narrowing, not an oversight.</li>
 *   <li><b>A manager's own request has no manager stage.</b> A ผู้จัดการ never routes through
 *       another ผู้จัดการ, even where the division has two of them — their requests go straight to
 *       the CEO.</li>
 *   <li><b>Reporting to an executive has no manager stage either</b> (CEO-approval-reach follow-on,
 *       2026-09-01 owner ruling). An employee whose {@code reports_to_employee_id} points at an
 *       ACTIVE executive — division {@code md}, or a position containing "กรรมการ", the exact same
 *       test {@code DivisionAccessPolicy.roleFor}'s "ceo" branch uses — skips the manager stage even
 *       when their own division has a perfectly reachable ผู้จัดการ, because the person who actually
 *       oversees them is the executive, not their division's ผู้จัดการ. This <b>partially revisits</b>
 *       the 2026-08-03 "{@code reports_to} grants no approval rights" ruling above, but only for
 *       <em>this routing decision</em> — {@code reports_to} still does not make the executive (or
 *       anyone else) an OT <em>approver</em>; {@code OvertimeService} still requires the CEO role to
 *       act on a manager-less request, the same as rule 2's bypass.</li>
 * </ol>
 *
 * <p>Note this is <b>not</b> the equivalence "false exactly when nobody can manage them" — rule 2
 * deliberately breaks that, since a ผู้จัดการ with a ผู้จัดการ peer does have someone who could
 * approve and still bypasses them. The contract is four properties, all pinned against real
 * Postgres by {@code ManagerApproverInvariantIntegrationTest}:
 *
 * <ol>
 *   <li><b>No stranding:</b> {@code hasManagerApprover(e)} ⟹ some active {@code u} satisfies
 *       {@code managesEmployee(e, u)}.</li>
 *   <li><b>Deliberate bypass:</b> {@code e} is a ผู้จัดการ ⟹ false.</li>
 *   <li><b>No needless bypass:</b> {@code e} is not a ผู้จัดการ, does not report to an active
 *       executive, and someone can manage them ⟹ true.</li>
 *   <li><b>Executive-report bypass:</b> {@code e} reports (directly) to an active executive ⟹
 *       false, regardless of whether a peer ผู้จัดการ is reachable in {@code e}'s own division.</li>
 * </ol>
 *
 * <p>{@code managesEmployee} lives in {@code OvertimeService} and decides "may <em>this</em> user act
 * as the manager here"; it cannot answer "does <em>any</em> such user exist", which is what the
 * routing decision needs. If you change {@code managesEmployee}, change this SQL in the same commit
 * or that test goes red.
 *
 * <h2>Why each clause is shaped the way it is</h2>
 * <ul>
 *   <li><b>Position match</b> — mirrors {@code DivisionAccessPolicy.isManager}: strip whitespace,
 *       then substring-match "ผู้จัดการ" (which also catches ผู้ช่วยผู้จัดการ and กรรมการผู้จัดการ).
 *       Postgres's {@code \s} is broader than Java's, so if the two ever disagree this SQL strips
 *       more and matches more — the conservative direction, since a match reports coverage rather
 *       than granting the CEO bypass.</li>
 *   <li><b>{@code peer.is_active}</b> — {@code managesEmployee} never checks the manager's status,
 *       but login does ({@code AuthService} refuses an inactive employee), so an inactive ผู้จัดการ
 *       can never actually act and must not count as coverage.</li>
 *   <li><b>No {@code peer.employee_id <> e.employee_id} guard</b> — it would be dead code. Rule 2
 *       already returns false for any employee who is themselves a ผู้จัดการ, so an employee can
 *       never reach the peer search and match themselves.</li>
 *   <li><b>{@code REPORTS_TO_EXECUTIVE}'s division-code expression</b> — the same
 *       {@code LOWER(TRIM(COALESCE(NULLIF(TRIM(source_code), ''), split_part(name_th, '-', 1)))) =
 *       'md'} shape {@code CommissionRepository#findActiveSalesRepOptions} already uses for its own
 *       division-code match; followed rather than reinvented so a future division-naming edge case
 *       is fixed once, not twice.</li>
 *   <li><b>{@code boss.is_active = TRUE}</b> — required, and the conservative direction: an
 *       inactive executive must not grant the bypass. Falling through to the (still-correct) manager
 *       stage cannot strand anyone; it is the current behaviour for every employee who does not
 *       report to an active executive.</li>
 *   <li><b>{@code LEFT JOIN} for {@code boss_div}/{@code boss_pos}</b> — a boss with no division or
 *       no position must not throw the whole {@code EXISTS} away; {@code COALESCE}/{@code NULLIF}
 *       inside the predicate turn a missing division or position into "does not match", not a NULL
 *       that silently makes the whole {@code AND} unknown.</li>
 * </ul>
 *
 * <p><b>Known narrowness:</b> an active ผู้จัดการ with no usable email cannot log in and so cannot
 * really approve, yet still counts as coverage here. That is intentional — widening the check to
 * "can log in" would widen the CEO bypass on the strength of a missing email address. Such a
 * division stays blocked until HR fills the email in, which is a data fix, not a code one.
 */
@Repository
public class ManagerApproverRepository {

    /**
     * SQL boolean expression, parameterised on the alias of an {@code hr.employee} row already in
     * scope. Exposed so list queries can project the same answer per row for the UI without a
     * second round trip — one source of truth, two call shapes.
     */
    /** Rule 2: the employee is themselves a ผู้จัดการ, so no manager stage applies to them. */
    private static final String SELF_IS_MANAGER = """
        EXISTS (
             SELECT 1
               FROM hr.position self_pos
              WHERE self_pos.position_id = {e}.position_id
                AND regexp_replace(COALESCE(self_pos.name_th, ''), '\\s+', '', 'g') LIKE '%ผู้จัดการ%'
         )""";

    /**
     * Rule 1: the WHERE clause selecting employees who could approve for {@code {e}}. Written once
     * and reused by both the boolean expression and {@link #findManagerApproverEmployeeIds}, so the
     * set of people notified can never drift from the decision to route to them at all.
     */
    private static final String PEER_IS_MANAGER_APPROVER = """
        {e}.division_id IS NOT NULL
                AND peer.division_id = {e}.division_id
                AND peer.is_active = TRUE
                AND regexp_replace(COALESCE(peer_pos.name_th, ''), '\\s+', '', 'g') LIKE '%ผู้จัดการ%'""";

    /**
     * Rule 3: {@code {e}} reports directly to an ACTIVE executive (division {@code md}, or a
     * position containing "กรรมการ" — mirrors {@code DivisionAccessPolicy.roleFor}'s "ceo" branch
     * exactly). Written once and reused by both the boolean expression and
     * {@link #findManagerApproverEmployeeIds}, same reasoning as {@link #PEER_IS_MANAGER_APPROVER}
     * above — see this class's own Javadoc for why each clause is shaped the way it is.
     */
    private static final String REPORTS_TO_EXECUTIVE = """
        EXISTS (
             SELECT 1
               FROM hr.employee boss
               LEFT JOIN hr.division boss_div ON boss_div.division_id = boss.division_id
               LEFT JOIN hr.position boss_pos ON boss_pos.position_id = boss.position_id
              WHERE boss.employee_id = {e}.reports_to_employee_id
                AND boss.is_active = TRUE
                AND (LOWER(TRIM(COALESCE(NULLIF(TRIM(boss_div.source_code), ''),
                                         split_part(boss_div.name_th, '-', 1)))) = 'md'
                     OR regexp_replace(COALESCE(boss_pos.name_th, ''), '\\s+', '', 'g') LIKE '%กรรมการ%')
         )""";

    // Assembled with explicit concatenation, NOT by interpolating into a text block: text blocks
    // strip trailing whitespace from every line, which silently turned "(NOT " into "(NOT" and
    // produced "NOTEXISTS". Keep the spaces where the compiler cannot eat them.
    private static final String HAS_MANAGER_APPROVER =
        "(NOT " + SELF_IS_MANAGER + "\n"
            + "         AND EXISTS (\n"
            + "             SELECT 1\n"
            + "               FROM hr.employee peer\n"
            + "               JOIN hr.position peer_pos ON peer_pos.position_id = peer.position_id\n"
            + "              WHERE " + PEER_IS_MANAGER_APPROVER + "\n"
            + "         )\n"
            + "         AND NOT " + REPORTS_TO_EXECUTIVE + ")";

    private final NamedParameterJdbcTemplate jdbc;

    public ManagerApproverRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The expression above, bound to {@code employeeAlias}. Callers splice this into their own
     * {@code SELECT} list; it needs no extra bind parameters.
     */
    public static String hasManagerApproverSql(String employeeAlias) {
        return HAS_MANAGER_APPROVER.replace("{e}", employeeAlias);
    }

    /**
     * Rule 3's expression alone, bound to {@code employeeAlias} — exposed so
     * {@code OvertimeRepository#findEmployeeAccess} can project the same "reports to an active
     * executive" answer onto {@code OvertimeEmployeeAccess} without re-typing the SQL. Splicing the
     * shared fragment (rather than restating the predicate) is what keeps
     * {@code OvertimeEmployeeAccess#reportsToExecutive} and this class's own routing decision from
     * ever disagreeing — the same reasoning as {@link #hasManagerApproverSql}.
     */
    public static String reportsToExecutiveSql(String employeeAlias) {
        return REPORTS_TO_EXECUTIVE.replace("{e}", employeeAlias);
    }

    /**
     * feat/pending-approver-info: a scalar subquery resolving the nickname-or-first-name of the
     * SINGLE active division-manager approver for {@code employeeAlias}'s requests, or SQL NULL
     * when there is more than one such peer -- read-only, informational ("who this is waiting on"
     * display), never an authorization decision.
     *
     * <p>Built from the exact same {@link #PEER_IS_MANAGER_APPROVER} WHERE clause {@link
     * #hasManagerApproverSql} and {@link #findManagerApproverEmployeeIds} already use, so the name
     * this resolves can never disagree with whether a manager stage exists at all -- deliberately
     * does NOT also repeat {@link #SELF_IS_MANAGER} or {@link #REPORTS_TO_EXECUTIVE}: a caller must
     * gate on {@code hasManagerApproverSql(employeeAlias)} being {@code true} before using this
     * value (which already implies both "not self is manager" and "does not report to an active
     * executive"), the same precondition {@link #findManagerApproverEmployeeIds} carries in its own
     * Javadoc ("empty exactly when {@code hasManagerApprover} is false").
     */
    public static String managerApproverSingleNameSql(String employeeAlias) {
        return ("(SELECT CASE WHEN COUNT(*) = 1"
            + " THEN MIN(COALESCE(NULLIF(TRIM(peer.nickname), ''), peer.first_name_th)) END\n"
            + "   FROM hr.employee peer\n"
            + "   JOIN hr.position peer_pos ON peer_pos.position_id = peer.position_id\n"
            + "  WHERE " + PEER_IS_MANAGER_APPROVER + ")")
            .replace("{e}", employeeAlias);
    }

    /**
     * True when {@code employeeId}'s requests have a manager stage — i.e. some active ฝ่าย manager
     * could approve them.
     *
     * <p>Returns {@code true} for an employee row that does not exist. That is unreachable through
     * the request services (every request DTO is built by joining {@code hr.employee}, so the row
     * must exist), and {@code true} is the fail-closed answer: it withholds the CEO bypass rather
     * than granting it on the strength of a missing row.
     */
    public boolean hasManagerApprover(long employeeId) {
        return jdbc.query(
                "SELECT " + hasManagerApproverSql("e") + " AS has_manager_approver"
                    + " FROM hr.employee e WHERE e.employee_id = :employeeId",
                Map.of("employeeId", employeeId),
                (rs, rowNum) -> rs.getBoolean("has_manager_approver"))
            .stream()
            .findFirst()
            .orElse(true);
    }

    /**
     * The actual people who could approve at the manager stage — empty exactly when
     * {@link #hasManagerApprover} is false, since both are built from
     * {@link #PEER_IS_MANAGER_APPROVER} and {@link #REPORTS_TO_EXECUTIVE}.
     *
     * <p>Exists so notifications reach whoever can act. Before the division-only rule, overtime
     * notified {@code reports_to_employee_id} on submission; that person no longer approves, so
     * notifying them would put a request in front of someone who cannot clear it while leaving the
     * real approver unaware of it.
     */
    public List<Long> findManagerApproverEmployeeIds(long employeeId) {
        return jdbc.query(
            "SELECT peer.employee_id"
                + " FROM hr.employee e"
                + " JOIN hr.employee peer ON peer.division_id = e.division_id"
                + " JOIN hr.position peer_pos ON peer_pos.position_id = peer.position_id"
                + " WHERE e.employee_id = :employeeId"
                + "   AND NOT " + SELF_IS_MANAGER.replace("{e}", "e")
                + "   AND " + PEER_IS_MANAGER_APPROVER.replace("{e}", "e")
                + "   AND NOT " + REPORTS_TO_EXECUTIVE.replace("{e}", "e")
                + " ORDER BY peer.employee_id",
            Map.of("employeeId", employeeId),
            (rs, rowNum) -> rs.getLong("employee_id"));
    }
}
