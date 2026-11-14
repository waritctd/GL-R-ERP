package th.co.glr.hr.employee;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Answers one question, for the whole application: is there a manager stage for this employee's requests at
 * all? reports_to_employee_id does not grant approval rights — this matches AttendanceService.resolveScope,
 * which has always been division-only. */
@Repository
public class ManagerApproverRepository {

    /** SQL boolean expression, parameterised on the alias of an hr.employee row already in scope. Exposed so list
     * queries can project the same answer per row for the UI without a second round trip — one source of truth,
     * two call shapes. */
    /** Rule 2: the employee is themselves a ผู้จัดการ, so no manager stage applies to them. */
    private static final String SELF_IS_MANAGER = """
        EXISTS (
             SELECT 1
               FROM hr.position self_pos
              WHERE self_pos.position_id = {e}.position_id
                AND regexp_replace(COALESCE(self_pos.name_th, ''), '\\s+', '', 'g') LIKE '%ผู้จัดการ%'
         )""";

    /** Rule 1: the WHERE clause selecting employees who could approve for {e}. Written once and reused by both the
     * boolean expression and #findManagerApproverEmployeeIds, so the set of people notified can never drift from
     * the decision to route to them at all. */
    private static final String PEER_IS_MANAGER_APPROVER = """
        {e}.division_id IS NOT NULL
                AND peer.division_id = {e}.division_id
                AND peer.is_active = TRUE
                AND regexp_replace(COALESCE(peer_pos.name_th, ''), '\\s+', '', 'g') LIKE '%ผู้จัดการ%'""";

    /** Rule 3: {e} reports directly to an ACTIVE executive (division md, or a position containing "กรรมการ" —
     * mirrors DivisionAccessPolicy.roleFor's "ceo" branch exactly). */
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

    // Assembled with explicit concatenation, NOT by interpolating into a text block: text blocks strip trailing
    // whitespace from every line, which silently turned "(NOT " into "(NOT" and produced "NOTEXISTS". Keep the
    // spaces where the compiler cannot eat them.
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

    /** The expression above, bound to employeeAlias. Callers splice this into their own SELECT list; it needs no
     * extra bind parameters. */
    public static String hasManagerApproverSql(String employeeAlias) {
        return HAS_MANAGER_APPROVER.replace("{e}", employeeAlias);
    }

    /** Rule 3's expression alone, bound to employeeAlias — exposed so OvertimeRepository#findEmployeeAccess can
     * project the same "reports to an active executive" answer onto OvertimeEmployeeAccess without re-typing the
     * SQL. */
    public static String reportsToExecutiveSql(String employeeAlias) {
        return REPORTS_TO_EXECUTIVE.replace("{e}", employeeAlias);
    }

    /** feat/pending-approver-info: a scalar subquery resolving the nickname-or-first-name of the SINGLE active
     * division-manager approver for employeeAlias's requests, or SQL NULL when there is more than one such peer --
     * read-only, informational, never an authorization decision. */
    public static String managerApproverSingleNameSql(String employeeAlias) {
        return ("(SELECT CASE WHEN COUNT(*) = 1"
            + " THEN MIN(COALESCE(NULLIF(TRIM(peer.nickname), ''), peer.first_name_th)) END\n"
            + "   FROM hr.employee peer\n"
            + "   JOIN hr.position peer_pos ON peer_pos.position_id = peer.position_id\n"
            + "  WHERE " + PEER_IS_MANAGER_APPROVER + ")")
            .replace("{e}", employeeAlias);
    }

    /** True when employeeId's requests have a manager stage — i.e. some active ฝ่าย manager could approve them.
     * Returns true for an employee row that does not exist. */
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

    /** The actual people who could approve at the manager stage — empty exactly when #hasManagerApprover is false,
     * since both are built from #PEER_IS_MANAGER_APPROVER and #REPORTS_TO_EXECUTIVE. Exists so notifications reach
     * whoever can act. */
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
