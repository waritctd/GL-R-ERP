package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Real-Postgres coverage for the dynamic SQL and the DB-enforced invariants a Mockito unit test cannot reach:
 * effective-dated policy lookups, calendar-year usage aggregation, the once-per- lifetime race guard, and the
 * approved-must-have-payroll-month constraint. */
class SpecialMoneyRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private SpecialMoneyRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
    }

    @Test
    void onceLifetimeIndexRejectsASecondActiveWeddingClaimForTheSameEmployee() {
        long employeeId = insertEmployee("SMR-ONCE");
        insertRequest(employeeId, "AID_WEDDING", LocalDate.of(2026, 1, 1), "5000", "SUBMITTED", null, null);

        assertThatThrownBy(() ->
            insertRequest(employeeId, "AID_WEDDING", LocalDate.of(2026, 6, 1), "5000", "MANAGER_APPROVED", null, null))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void approvedCompleteConstraintRejectsAnApprovedRowWithNullPayrollMonth() {
        long employeeId = insertEmployee("SMR-INV");

        assertThatThrownBy(() ->
            insertRequest(employeeId, "AID_FUNERAL", LocalDate.of(2026, 1, 1), "5000", "APPROVED", "5000", null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code),
            Long.class);
    }

    private void insertRequest(
            long employeeId,
            String requestType,
            LocalDate eventDate,
            String requestedAmount,
            String status,
            String approvedAmount,
            LocalDate payrollMonth) {
        insertRequest(employeeId, requestType, eventDate, requestedAmount, status, approvedAmount, payrollMonth, null);
    }

    /** requestedAt lets a test pin the one column findUsage's in-flight count now keys on (EXTRACT(YEAR FROM
     * requested_at AT TIME ZONE 'Asia/Bangkok')) to a deterministic value instead of whatever DEFAULT now()
     * produces at test-run time -- required to exercise year boundaries on demand. */
    private void insertRequest(
            long employeeId,
            String requestType,
            LocalDate eventDate,
            String requestedAmount,
            String status,
            String approvedAmount,
            LocalDate payrollMonth,
            OffsetDateTime requestedAt) {
        jdbc.update("""
            INSERT INTO hr.special_money_request (
                employee_id, request_type, event_date, quantity, requested_amount, approved_amount,
                payroll_bucket, policy_version, reason, status, payroll_month, requested_at
            )
            VALUES (
                :employeeId, :requestType, :eventDate, 1, :requestedAmount, CAST(:approvedAmount AS numeric),
                'AID', 1, 'Integration test row', :status, :payrollMonth,
                COALESCE(CAST(:requestedAt AS timestamptz), now())
            )
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("requestType", requestType)
            .addValue("eventDate", eventDate)
            .addValue("requestedAmount", new BigDecimal(requestedAmount))
            .addValue("approvedAmount", approvedAmount)
            .addValue("status", status)
            .addValue("requestedAt", requestedAt)
            .addValue("payrollMonth", payrollMonth));
    }
}
