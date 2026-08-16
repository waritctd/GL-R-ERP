package th.co.glr.hr.notification;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Centralizes the CEO-notification recipient lookup that used to be copy-pasted across {@code
 * OvertimeRepository}, {@code SpecialMoneyRepository}, {@code CommissionRepository} and {@code
 * AttendanceCorrectionRepository} -- each an identical {@code MD%}/{@code MN%} division-prefix
 * query -- plus a fifth inline copy in {@code NotificationRepository#notifyByRoleInternal}'s
 * {@code "ceo"} case. Every one of those five call sites lived inside a {@code notify*} method;
 * none was a permission gate, and this class is not one either -- see {@link CeoApproverRule}.
 *
 * <p>Consolidation (2026-08-16): the four repository methods (already byte-identical -- each
 * ported to {@link CeoApproverRule#SQL_PREDICATE} in an earlier change) are gone, not just
 * synchronized. {@code CommissionService}, {@code OvertimeService}, {@code SpecialMoneyService}
 * and {@code AttendanceCorrectionService} now depend on this class directly (a constructor-injected
 * {@code ceoApprovers} field) instead of on their own repository's copy, so there is exactly one
 * SQL statement backing this rule -- nothing left to drift.
 */
@Repository
public class CeoApproverRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CeoApproverRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Active employees who should be notified about a CEO-stage approval, per {@link
     * CeoApproverRule#SQL_PREDICATE}. Ordered by {@code employee_id} -- kept from the four
     * repository methods this replaces, so notification order stays deterministic.
     */
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
