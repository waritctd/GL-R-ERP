package th.co.glr.hr.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.user.AppUserRepository;

@Component
public class DemoDataSeeder implements ApplicationRunner {
    private final AppProperties properties;
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DemoDataSeeder(AppProperties properties, JdbcTemplate jdbc, AppUserRepository users) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.getDemoSeed().isEnabled()) {
            return;
        }

        List<Long> employeeIds = jdbc.queryForList("SELECT employee_id FROM hr.employee ORDER BY employee_id LIMIT 5", Long.class);
        if (employeeIds.isEmpty()) {
            return;
        }

        Map<String, String> accounts = Map.of(
            "admin@glr.co.th", "admin",
            "hr@glr.co.th", "hr",
            "director@glr.co.th", "director",
            "employee@glr.co.th", "employee",
            "supervisor@glr.co.th", "supervisor"
        );

        String passwordHash = passwordEncoder.encode("demo1234");
        int index = 0;
        for (Map.Entry<String, String> entry : accounts.entrySet()) {
            Long employeeId = employeeIds.get(Math.min(index, employeeIds.size() - 1));
            long userId = users.insertDemoUser(employeeId, entry.getKey(), passwordHash);
            users.assignRole(userId, entry.getValue());
            index += 1;
        }
    }
}
