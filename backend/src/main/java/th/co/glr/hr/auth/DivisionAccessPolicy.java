package th.co.glr.hr.auth;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DivisionAccessPolicy {
    private static final Map<Long, String> DIVISION_ROLES = Map.ofEntries(
        Map.entry(10L, "employee"), // AC-ฝ่ายบัญชี
        Map.entry(7L, "employee"),  // AM-ฝ่ายธุรการ
        Map.entry(17L, "hr"),       // HR-ฝ่ายบุคคล
        Map.entry(2L, "hr"),        // MD-ผู้บริหารระดับสูง
        Map.entry(16L, "hr"),       // MN-ผู้บริหาร
        Map.entry(14L, "employee"), // PC-ฝ่ายจัดซื้อ
        Map.entry(5L, "employee"),  // PCIM-จัดซื้อต่างประเทศ
        Map.entry(11L, "employee"), // PD-ฝ่ายผลิต
        Map.entry(18L, "employee"), // QC&ISO
        Map.entry(9L, "employee"),  // SA-ฝ่ายขาย
        Map.entry(15L, "employee"), // SADS-ออกแบบ
        Map.entry(13L, "employee"), // Sales Support 2
        Map.entry(12L, "employee"), // Sales Support 1
        Map.entry(6L, "employee"),  // Sales Support 1
        Map.entry(8L, "employee"),  // Sales Support 2
        Map.entry(4L, "employee"),  // SR-โชว์รูม
        Map.entry(1L, "employee"),  // SV-ฝ่ายบริการ
        Map.entry(3L, "employee")   // WH-ฝ่ายคลังสินค้า
    );
    private static final Set<String> HR_DIVISION_CODES = Set.of("hr", "md", "mn");

    private DivisionAccessPolicy() {
    }

    public static String roleFor(EmployeeLoginRecord employee) {
        String role = DIVISION_ROLES.get(employee.divisionId());
        if (role != null) {
            return role;
        }
        return HR_DIVISION_CODES.contains(divisionCode(employee)) ? "hr" : "employee";
    }

    private static String divisionCode(EmployeeLoginRecord employee) {
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
