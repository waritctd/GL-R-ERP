package th.co.glr.hr.attendance.daily;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guards a legal constraint, not a coding style. late_minutes and early_leave_minutes therefore exist for
 * reporting only. Thai Labour Protection Act §76 forbids an employer from deducting wages as a penalty for
 * lateness or absence — up to six months' imprisonment or a THB 100,000 fine. */
class LatenessNeverAffectsPayrollTest {

    /** Packages that compute or persist pay. */
    private static final List<String> PAY_PACKAGES = List.of(
        "src/main/java/th/co/glr/hr/payroll",
        "src/main/java/th/co/glr/hr/commission"
    );

    private static final List<String> FORBIDDEN = List.of("late_minutes", "early_leave_minutes");

    @Test
    void noPayrollOrCommissionCodeReadsLatenessColumns() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (String packagePath : PAY_PACKAGES) {
            Path root = Path.of(packagePath);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    for (String column : FORBIDDEN) {
                        if (source.contains(column)) {
                            offenders.add(file + " references " + column);
                        }
                    }
                }
            }
        }

        assertThat(offenders)
            .as("Thai Labour Protection Act §76 forbids deducting wages for lateness or absence, "
                + "so payroll and commission code must never read late_minutes or "
                + "early_leave_minutes. Use unpaid-leave days for lawful no-work-no-pay instead.")
            .isEmpty();
    }

    // The companion assertion — that the migration itself records §76 in COMMENT ON COLUMN — lives with that
    // migration on chore/attendance-daily-migration-hold. The guard above is the one that actually matters, and it
    // does not depend on the migration.
}
