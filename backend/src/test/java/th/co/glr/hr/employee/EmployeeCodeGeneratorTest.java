package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class EmployeeCodeGeneratorTest {
    @Test
    void usesDatabaseSequenceForNextEmployeeCode() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT nextval('hr.employee_code_seq')"), eq(Map.of()), eq(Long.class)))
            .thenReturn(1031L);

        EmployeeCodeGenerator generator = new EmployeeCodeGenerator(jdbc);

        assertThat(generator.nextEmployeeCode()).isEqualTo("GLR-1031");
    }
}
