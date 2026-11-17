package th.co.glr.hr.auth;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** One-time-per-row transition: BCrypts each active employee's employee_code as a TEMPORARY login password for
 * rows that have no password hash yet. Disabled by default (the low-entropy employee_code as an initial
 * password is guessable before first login). */
@Component
@ConditionalOnProperty(name = "app.auth.seed-employee-code-passwords", havingValue = "true")
public class PasswordBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PasswordBackfillRunner.class);

    private final EmployeeAuthRepository employees;
    private final PasswordEncoder passwordEncoder;

    public PasswordBackfillRunner(EmployeeAuthRepository employees, PasswordEncoder passwordEncoder) {
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Long, String> codes = employees.findActiveCodesNeedingPassword();
        if (codes.isEmpty()) {
            return;
        }
        codes.forEach((employeeId, employeeCode) ->
            employees.backfillTemporaryPassword(employeeId, passwordEncoder.encode(employeeCode)));
        log.info("Seeded temporary password hashes for {} employee(s); each must change password on next login.",
            codes.size());
    }
}
