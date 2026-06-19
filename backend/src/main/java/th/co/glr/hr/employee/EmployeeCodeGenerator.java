package th.co.glr.hr.employee;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeCodeGenerator {
    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeCodeGenerator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextEmployeeCode() {
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(employee_id), 0) + 1001 FROM hr.employee", Map.of(), Long.class);
        return "GLR-" + next;
    }
}
