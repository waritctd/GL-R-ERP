package th.co.glr.hr.approval;

/** feat/pending-approver-info: SQL fragments for resolving the display name of the "generic" role currently
 * holding a pending leave/overtime/welfare request. Do not wire this into an authorization check. */
public final class PendingApproverSql {

    private PendingApproverSql() {
    }

    /** Scalar subquery: the nickname-or-first-name of the single active ceo-role employee, or SQL NULL when zero or
     * more than one match. */
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

    /** Scalar subquery: the nickname-or-first-name of the single active hr-role employee, or SQL NULL when zero or
     * more than one match. Same splice-in-as-a-column usage as #SINGLE_ACTIVE_CEO_NAME_SQL. */
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
