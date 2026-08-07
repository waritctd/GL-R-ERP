package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * P0 fix (fix/welfare-cap-year-bypass): {@code findUsage}'s year filter used to be {@code
     * EXTRACT(YEAR FROM event_date)} for both the amount and the count query -- {@code event_date}
     * is employee-supplied and {@code SubmitSpecialMoneyHttpRequest} places no bound on it, so an
     * annual cap keyed on it could always be defeated by filing against a year nothing had been
     * approved against yet. This fixture is deliberately adversarial about it: every row's {@code
     * event_date} is set to a year that would give the OPPOSITE answer under the old
     * event_date-keyed query, so a regression back to keying on event_date fails this test instead
     * of passing it by coincidence.
     */
    @Test
    void findUsageKeysTheAmountSumOnPayrollMonthAndTheCountOnRequestedAt() {
        long employeeId = insertEmployee("SMR-USG");
        // APPROVED, payroll_month in 2026 (so it belongs to 2026's cap under the fix) but
        // event_date claims 2099 -- must still be COUNTED for year 2026.
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2099, 1, 1), "1500", "APPROVED", "1500",
            LocalDate.of(2026, 4, 1), OffsetDateTime.parse("2026-03-15T10:00:00+07:00"));
        // APPROVED, payroll_month in 2025 (belongs to 2025's cap) but event_date claims 2026 -- must
        // be EXCLUDED from 2026, even though the old, buggy query would have included it.
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2026, 12, 1), "800", "APPROVED", "800",
            LocalDate.of(2025, 12, 1), OffsetDateTime.parse("2025-12-20T10:00:00+07:00"));
        // SUBMITTED (no payroll_month yet), requested_at in 2026, event_date claims 2099 -- must be
        // excluded from the APPROVED amount sum (not yet approved) but counted in the in-flight
        // per-year count for 2026, keyed on requested_at.
        insertRequest(employeeId, "MEDICAL", LocalDate.of(2099, 6, 1), "500", "SUBMITTED", null, null,
            OffsetDateTime.parse("2026-06-01T09:00:00+07:00"));

        UsageSnapshot usage = repository.findUsage(employeeId, 2026);

        // Only the first row: payroll_month 2026, regardless of its 2099 event_date.
        assertThat(usage.approvedAmountThisYear(SpecialMoneyType.MEDICAL)).isEqualByComparingTo("1500");
        // Lifetime count is NOT year-scoped by design (it backs the once-per-lifetime AID gate,
        // which must see every prior claim regardless of year): all 3 rows count.
        assertThat(usage.activeCountLifetime(SpecialMoneyType.MEDICAL)).isEqualTo(3);
        // requested_at in 2026: rows 1 and 3 (row 2's requested_at is 2025). Deliberately different
        // from both the lifetime count (3) and the approved-only amount-bearing count (1), so an
        // implementation that keys this off the wrong column/map fails here rather than by luck.
        assertThat(usage.activeCountThisYear(SpecialMoneyType.MEDICAL)).isEqualTo(2);
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

    /**
     * {@code requestedAt} lets a test pin the one column {@code findUsage}'s in-flight count now
     * keys on ({@code EXTRACT(YEAR FROM requested_at AT TIME ZONE 'Asia/Bangkok')}) to a
     * deterministic value instead of whatever {@code DEFAULT now()} produces at test-run time --
     * required to exercise year boundaries on demand. {@code null} keeps the column's own default.
     */
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
