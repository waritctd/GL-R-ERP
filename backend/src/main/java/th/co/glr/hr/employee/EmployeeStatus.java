package th.co.glr.hr.employee;

final class EmployeeStatus {
    private EmployeeStatus() {
    }

    static String sqlCaseExpression() {
        return """
            CASE
                WHEN e.is_active = FALSE OR s.name_th ILIKE '%ลาออก%' THEN 'RSG'
                WHEN s.name_th ILIKE '%ทดลอง%' THEN 'PRB'
                WHEN s.source_code IN ('ACT', 'PRB', 'RSG') THEN s.source_code
                ELSE 'ACT'
            END
            """;
    }

    static boolean active(String statusId) {
        return !"RSG".equalsIgnoreCase(defaultText(statusId, "ACT"));
    }

    static String name(String statusId) {
        return switch (defaultText(statusId, "ACT")) {
            case "PRB" -> "ทดลองงาน";
            case "RSG" -> "ลาออก";
            default -> "ทำงานปกติ";
        };
    }

    static String englishName(String statusId) {
        return switch (defaultText(statusId, "ACT")) {
            case "PRB" -> "Probation";
            case "RSG" -> "Resigned";
            default -> "Active";
        };
    }

    static String tone(String statusId) {
        return switch (defaultText(statusId, "ACT")) {
            case "PRB" -> "warning";
            case "RSG" -> "danger";
            default -> "success";
        };
    }

    private static String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
