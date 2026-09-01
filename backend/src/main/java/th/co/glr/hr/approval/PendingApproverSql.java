package th.co.glr.hr.approval;

/**
 * feat/pending-approver-info: SQL fragments for resolving the display name of the "generic" role
 * (ceo/hr) currently holding a pending leave/overtime/welfare request -- used to render a "who this
 * is waiting on" note (e.g. "CEO (คุณราม)") next to a รออนุมัติ status badge, without hardcoding
 * any specific person's identity.
 *
 * <h2>Read-only, informational only</h2>
 * These fragments never gate an approval decision -- they only describe, after the fact, who the
 * real gate (role checks in {@code LeaveService}/{@code OvertimeService}/{@code
 * SpecialMoneyService}, and {@code ManagerApproverRepository} for the manager stage) would accept.
 * Do not wire this into an authorization check.
 *
 * <h2>Mirrors {@link th.co.glr.hr.auth.DivisionAccessPolicy#roleFor} -- keep in lockstep</h2>
 * The real "ceo"/"hr" role is derived in Java from an employee's division + position (see that
 * class's Javadoc): division code {@code md} (or a position containing "กรรมการ") -&gt; {@code ceo};
 * division code {@code hr} -&gt; {@code hr}. That derivation cannot be called from a JDBC row mapper,
 * so this class re-expresses the SAME two conditions as SQL. If {@code DivisionAccessPolicy}'s rule
 * ever changes, update these fragments in the same commit -- the same discipline {@code
 * ManagerApproverRepository#hasManagerApproverSql} already documents for {@code
 * DivisionAccessPolicy#isManager}.
 *
 * <p>Division code resolution mirrors {@code DivisionAccessPolicy#divisionCode}: prefer {@code
 * hr.division.source_code} when non-blank, else the text before the first '-' in {@code
 * hr.division.name_th} (e.g. "MD-ผู้บริหารระดับสูง" -&gt; "MD"), lower-cased.
 *
 * <h2>Ambiguity, by design -- deliberately NOT resolved to a single hardcoded person</h2>
 * More than one active employee can hold role {@code ceo} (this org chart has several executives)
 * or role {@code hr}. Each SQL fragment below is a scalar subquery that resolves a display name
 * ONLY when there is EXACTLY ONE active match; with zero or more than one, it evaluates to SQL
 * {@code NULL} and the caller shows the role alone (e.g. plain "CEO", no name) -- see
 * LeaveService/OvertimeService/SpecialMoneyService's "pendingApprover" resolution and this
 * branch's PR body for the reasoning spelled out as a judgement call, not an oversight.
 *
 * <p>Name preference is nickname, falling back to first name, when neither is blank -- the same
 * "nickname, else a real name, never blank" preference already established for display purposes
 * elsewhere in this codebase (e.g. {@code AttendanceRepository}'s {@code nick_name} projection).
 */
public final class PendingApproverSql {

    private PendingApproverSql() {
    }

    /**
     * Scalar subquery: the nickname-or-first-name of the single active {@code ceo}-role employee,
     * or SQL NULL when zero or more than one match. Splice this into a SELECT list as a column
     * (e.g. {@code ... , (ceo subquery) AS ceo_single_name FROM ...}) -- no bind parameters needed,
     * safe to reuse verbatim across LeaveRepository/OvertimeRepository/SpecialMoneyRepository.
     */
    public static final String SINGLE_ACTIVE_CEO_NAME_SQL = """
        (SELECT CASE WHEN COUNT(*) = 1
                     THEN MIN(COALESCE(NULLIF(TRIM(ceo_emp.nickname), ''), ceo_emp.first_name_th))
                END
           FROM hr.employee ceo_emp
           LEFT JOIN hr.division ceo_div ON ceo_div.division_id = ceo_emp.division_id
           LEFT JOIN hr.position ceo_pos ON ceo_pos.position_id = ceo_emp.position_id
          WHERE ceo_emp.is_active = TRUE
            AND (
                LOWER(TRIM(COALESCE(NULLIF(TRIM(ceo_div.source_code), ''), split_part(COALESCE(ceo_div.name_th, ''), '-', 1)))) = 'md'
                OR regexp_replace(COALESCE(ceo_pos.name_th, ''), '\\s+', '', 'g') LIKE '%กรรมการ%'
            ))""";

    /**
     * Scalar subquery: the nickname-or-first-name of the single active {@code hr}-role employee, or
     * SQL NULL when zero or more than one match. Same splice-in-as-a-column usage as {@link
     * #SINGLE_ACTIVE_CEO_NAME_SQL}.
     *
     * <p>Excludes anyone whose position carries "กรรมการ", mirroring {@code roleFor}'s precedence:
     * {@code md}/executive is checked BEFORE {@code hr}, so an hr-division employee who is also an
     * executive resolves to role {@code ceo} in Java, not {@code hr}. Naming such a person here
     * would mislabel who is waiting on the request -- "HR (คุณX)" for someone whose actual role is
     * {@code ceo} -- which is wrong regardless of approval reach: since {@code REVIEW_ALL_ROLES}
     * gained {@code ceo} alongside {@code hr} (CEO leave-approval reach, 2026-09-01), this person
     * can approve either way, so the exclusion was never really about "cannot approve" -- it is,
     * and always was, purely about which role label is the correct one to show (review
     * #pending-approver-info).
     */
    public static final String SINGLE_ACTIVE_HR_NAME_SQL = """
        (SELECT CASE WHEN COUNT(*) = 1
                     THEN MIN(COALESCE(NULLIF(TRIM(hr_emp.nickname), ''), hr_emp.first_name_th))
                END
           FROM hr.employee hr_emp
           LEFT JOIN hr.division hr_div ON hr_div.division_id = hr_emp.division_id
           LEFT JOIN hr.position hr_pos ON hr_pos.position_id = hr_emp.position_id
          WHERE hr_emp.is_active = TRUE
            AND LOWER(TRIM(COALESCE(NULLIF(TRIM(hr_div.source_code), ''), split_part(COALESCE(hr_div.name_th, ''), '-', 1)))) = 'hr'
            AND regexp_replace(COALESCE(hr_pos.name_th, ''), '\\s+', '', 'g') NOT LIKE '%กรรมการ%')""";
}
