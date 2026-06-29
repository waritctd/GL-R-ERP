package th.co.glr.hr.auth;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ApplicationRoles {
    private static final List<String> PRIORITY = List.of(
        "admin", "ceo", "sales_manager", "hr", "sales", "import", "employee"
    );
    private static final Set<String> ALLOWED = Set.copyOf(PRIORITY);

    private ApplicationRoles() {
    }

    public static List<String> priority() {
        return PRIORITY;
    }

    public static String normalize(String role) {
        return role == null ? null : role.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isAllowed(String role) {
        String normalized = normalize(role);
        return normalized != null && ALLOWED.contains(normalized);
    }

    public static String requireAllowed(String role) {
        String normalized = normalize(role);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported role");
        }
        return normalized;
    }
}
