package th.co.glr.hr.auth;

import java.util.Locale;

/**
 * Derives a login role and manager capability from an employee's division (ฝ่าย) and position,
 * entirely data-driven so it survives re-imports (no hard-coded division_id values).
 *
 * <p>Role precedence:
 * <ol>
 *   <li>Executives (division MD-ผู้บริหารระดับสูง, or a กรรมการ-family position) → {@code ceo}:
 *       company-wide access.</li>
 *   <li>HR-บุคคล division → {@code hr}.</li>
 *   <li>PCIM-จัดซื้อต่างประเทศ division → {@code import} (foreign-purchasing price proposals).</li>
 *   <li>AC-ฝ่ายบัญชี division → {@code account} (money-receipt confirmations on sales tickets).</li>
 *   <li>WH-คลังสินค้า division → {@code warehouse}; QC&amp;ISO division → {@code qc}. These are
 *       currently employee-tier (same self-service access as {@code employee}); their scoped
 *       งานขาย involvement is a separate, later change.</li>
 *   <li>SA-ฝ่ายขาย division → {@code sales_manager} if the person is a manager, else {@code sales}.</li>
 *   <li>everything else (including null/blank — treated as inactive employees) → {@code employee}.</li>
 *   <li>Finally, an active holder of {@code hr.employee.can_create_quotation} whose role came out as
 *       {@code employee}, {@code qc} or {@code warehouse} acts as {@code sales}; every other role is
 *       unchanged (owner ruling, Ploy 2026-10-14).</li>
 * </ol>
 *
 * <p>Independently, {@link #isManager} flags anyone whose position contains "ผู้จัดการ"
 * (including "ผู้ช่วยผู้จัดการ"). A manager oversees their whole ฝ่าย (division): they approve
 * overtime for, and can view the attendance of, employees who share their {@code division_id}.
 */
public final class DivisionAccessPolicy {
    private static final String MANAGER_TITLE = "ผู้จัดการ";      // includes ผู้ช่วยผู้จัดการ
    private static final String EXECUTIVE_TITLE = "กรรมการ";      // ประธานกรรมการ, กรรมการ, กรรมการผู้จัดการ

    /**
     * ฝ่ายขาย's division code, shared by this class's own {@code sales}/{@code sales_manager}
     * derivation and by {@code CommissionRepository#findActiveSalesRepOptions} (issue #737's
     * manual-commission rep picker), so the picker cannot drift from the role derivation. Exposed
     * for that reason — the {@code "md"}/{@code "hr"}/... literals below have no second caller and
     * stay inline.
     *
     * <p><b>Not the only ฝ่ายขาย predicate in the codebase</b>, and saying otherwise would send the
     * next reader looking for a guarantee that does not exist: {@code CommissionRepository
     * #findSalesManagerApproverEmployeeIds} matches {@code d.source_code ILIKE 'SA%'}, which also
     * catches {@code SA2}/{@code SALES…} and has no name-prefix fallback. That predicate predates
     * this constant and is deliberately left alone here — unifying it would change who approves a
     * commission, which is not issue #737's business.
     *
     * <p>Note this is ฝ่ายขาย <em>membership</em>, a superset of who {@link #roleFor} labels
     * {@code sales}/{@code sales_manager}: an executive-titled ฝ่ายขาย member short-circuits to
     * {@code ceo} above. The picker deliberately lists membership.
     */
    public static final String SALES_DIVISION_CODE = "sa";

    private DivisionAccessPolicy() {
    }

    public static String roleFor(EmployeeLoginRecord employee) {
        String role = divisionRole(employee);
        // The can_create_quotation grant makes an employee-tier person act as a sales rep.
        if (employee != null && employee.active() && employee.canCreateQuotation()
            && ("employee".equals(role) || "qc".equals(role) || "warehouse".equals(role))) {
            return "sales";
        }
        return role;
    }

    private static String divisionRole(EmployeeLoginRecord employee) {
        String code = divisionCode(employee);
        if ("md".equals(code) || isExecutive(employee)) {
            return "ceo";
        }
        if ("hr".equals(code)) {
            return "hr";
        }
        if ("pcim".equals(code)) {
            return "import";
        }
        if ("ac".equals(code)) {
            return "account";
        }
        if ("wh".equals(code)) {
            return "warehouse";
        }
        if ("qc".equals(code)) {
            return "qc";
        }
        if (SALES_DIVISION_CODE.equals(code)) {
            return isManager(employee) ? "sales_manager" : "sales";
        }
        return "employee";
    }

    /** True when the position marks a ฝ่าย manager (contains "ผู้จัดการ", assistants included). */
    public static boolean isManager(EmployeeLoginRecord employee) {
        return employee != null && contains(employee.positionName(), MANAGER_TITLE);
    }

    private static boolean isExecutive(EmployeeLoginRecord employee) {
        return employee != null && contains(employee.positionName(), EXECUTIVE_TITLE);
    }

    private static boolean contains(String position, String needle) {
        if (position == null) {
            return false;
        }
        return position.replaceAll("\\s+", "").contains(needle);
    }

    private static String divisionCode(EmployeeLoginRecord employee) {
        if (employee == null) {
            return "";
        }
        String source = firstText(employee.divisionCode(), prefix(employee.divisionName()));
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }

    private static String prefix(String value) {
        if (value == null) {
            return null;
        }
        int delimiter = value.indexOf('-');
        return delimiter < 0 ? value : value.substring(0, delimiter);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
