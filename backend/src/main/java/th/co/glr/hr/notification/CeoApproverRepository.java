package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Centralizes the CEO-notification recipient lookup that used to be copy-pasted across OvertimeRepository,
 * SpecialMoneyRepository, CommissionRepository and AttendanceCorrectionRepository. */
@Repository
public class CeoApproverRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CeoApproverRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Active employees who should be notified about a CEO-stage approval, per CeoApproverRule#SQL_PREDICATE.
     * Ordered by employee_id -- kept from the four repository methods this replaces, so notification order stays
     * deterministic. */
    public List<Long> findEmployeeIds() {
        return jdbc.query("""
            SELECT e.employee_id
              FROM hr.employee e
              LEFT JOIN hr.position p ON p.position_id = e.position_id
             WHERE e.is_active = TRUE
               AND %s
             ORDER BY e.employee_id
            """.formatted(CeoApproverRule.SQL_PREDICATE),
            Map.of(),
            (rs, rowNum) -> rs.getLong("employee_id"));
    }
}
