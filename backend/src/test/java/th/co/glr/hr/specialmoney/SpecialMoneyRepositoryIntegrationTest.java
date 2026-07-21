package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Real-Postgres coverage for the dynamic SQL and the DB-enforced invariants a Mockito unit test
 * cannot reach: effective-dated policy lookups, calendar-year usage aggregation, the once-per-
 * lifetime race guard, and the approved-must-have-payroll-month constraint.
 */
class SpecialMoneyRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private SpecialMoneyRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
    }

    @Test
    void findPolicyAmountsReadsBothAmountAndTextValueForTheEffectiveDatedRows() {
        // A made-up request_type (special_money_policy.request_type carries no FK/check to
        // SpecialMoneyType) so this test's rows never collide with the real V66 seed data.
        // A superseded row (effective_to set) must not be picked up.
        jdbc.update("""
            INSERT INTO hr.special_money_policy (request_type, policy_key, amount, effective_from, effective_to, version)
            VALUES ('TEST_POLICY_TYPE', 'cap', 4000, DATE '2015-01-01', DATE '2017-12-31', 1)
            """, Map.of());
        jdbc.update("""
            INSERT INTO hr.special_money_policy (request_type, policy_key, amount, effective_from, version)
            VALUES ('TEST_POLICY_TYPE', 'cap', 6000, DATE '2018-01-01', 2)
            """, Map.of());
        jdbc.update("""
            INSERT INTO hr.special_money_policy (request_type, policy_key, text_value, effective_from, version)
            VALUES ('TEST_POLICY_TYPE', 'note_code', 'FUNERAL-X', DATE '2018-01-01', 2)
            """, Map.of());

        PolicyAmounts amounts = repository.findPolicyAmounts("TEST_POLICY_TYPE", LocalDate.of(2026, 1, 1));

        assertThat(amounts.amount("cap")).isEqualByComparingTo("6000");
        assertThat(amounts.text("note_code")).isEqualTo("FUNERAL-X");
        assertThat(amounts.version()).isEqualTo(2);
    }

    @Test
    void findUsageSumsOnlyApprovedRowsWithinTheRequestedCalendarYear() {
        long employeeId = insertEmployee("SMR-USG");
        // APPROVED, event in 2026 -> counted
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2026, 3, 1), "1500", "APPROVED", "1500", LocalDate.of(2026, 4, 1));
        // APPROVED, event in 2025 -> excluded (wrong year)
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2025, 12, 1), "800", "APPROVED", "800", LocalDate.of(2026, 1, 1));
        // SUBMITTED (not approved), event in 2026 -> excluded from the amount sum, but counted lifetime
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2026, 6, 1), "500", "SUBMITTED", null, null);

        UsageSnapshot usage = repository.findUsage(employeeId, 2026);

        assertThat(usage.approvedAmountThisYear(SpecialMoneyType.MEDICAL)).isEqualByComparingTo("1500");
        // Lifetime count is NOT year-scoped by design (it backs the once-per-lifetime AID gate,
        // which must see every prior claim regardless of year): all 3 rows count.
        assertThat(usage.approvedCountLifetime(SpecialMoneyType.MEDICAL)).isEqualTo(3);
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
        jdbc.update("""
            INSERT INTO hr.special_money_request (
                employee_id, request_type, event_date, quantity, requested_amount, approved_amount,
                payroll_bucket, policy_version, reason, status, payroll_month
            )
            VALUES (
                :employeeId, :requestType, :eventDate, 1, :requestedAmount, CAST(:approvedAmount AS numeric),
                'AID', 1, 'Integration test row', :status, :payrollMonth
            )
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("employeeId", employeeId)
            .addValue("requestType", requestType)
            .addValue("eventDate", eventDate)
            .addValue("requestedAmount", new BigDecimal(requestedAmount))
            .addValue("approvedAmount", approvedAmount)
            .addValue("status", status)
            .addValue("payrollMonth", payrollMonth));
    }
}
