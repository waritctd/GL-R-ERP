package th.co.glr.hr.auth;

import java.util.Locale;

/**
 * Derives a login role and manager capability from an employee's division (ฝ่าย) and position,
 * entirely data-driven so it survives re-imports (no hard-coded division_id values).
 *
 * <p>Role precedence:
 * <ol>
 *   <li>ADMIN-ผู้ดูแลระบบ division → {@code admin}: superset access, reserved for system/demo
 *       accounts (no such division exists in the real org chart today).</li>
 *   <li>Executives (division MD-ผู้บริหารระดับสูง, or a กรรมการ-family position) → {@code ceo}:
 *       company-wide access.</li>
 *   <li>HR-บุคคล division → {@code hr}.</li>
 *   <li>PCIM-จัดซื้อต่างประเทศ division → {@code import} (foreign-purchasing price proposals).</li>
 *   <li>SA-ฝ่ายขาย division → {@code sales_manager} if the person is a manager, else {@code sales}.</li>
 *   <li>everything else (including null/blank — treated as inactive employees) → {@code employee}.</li>
 * </ol>
 *
 * <p>Independently, {@link #isManager} flags anyone whose position contains "ผู้จัดการ"
 * (including "ผู้ช่วยผู้จัดการ"). A manager oversees their whole ฝ่าย (division): they approve
 * overtime for, and can view the attendance of, employees who share their {@code division_id}.
 */
public final class DivisionAccessPolicy {
    private static final String MANAGER_TITLE = "ผู้จัดการ";      // includes ผู้ช่วยผู้จัดการ
    private static final String EXECUTIVE_TITLE = "กรรมการ";      // ประธานกรรมการ, กรรมการ, กรรมการผู้จัดการ

    private DivisionAccessPolicy() {
    }

    public static String roleFor(EmployeeLoginRecord employee) {
        String code = divisionCode(employee);
        if ("admin".equals(code)) {
            return "admin";
        }
        if ("md".equals(code) || isExecutive(employee)) {
            return "ceo";
        }
        if ("hr".equals(code)) {
            return "hr";
        }
        if ("pcim".equals(code)) {
            return "import";
        }
        if ("sa".equals(code)) {
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
