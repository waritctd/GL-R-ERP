package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/** Exercises EmployeeRepository's dynamic filter/pagination SQL and the create round-trip against a real
 * PostgreSQL database (issue #28). */
class EmployeeRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private EmployeeRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
    }

    /** The standing withholding-tax override is deliberately a FULL-REPLACE column, unlike every other field update
     * touches: the column is nullable and NULL is itself a meaningful state, so a skip- when-null write would let
     * HR set the override but never clear it back to NULL. */
    @Test
    void updateClearsStandingWithholdingOverrideBackToNullButZeroStaysZero() {
        long id = repository.create(req("ภาษี ทดสอบ", "SALES", "wht-clear@glr.co.th", new BigDecimal("25000")));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();

        // Set a standing override.
        repository.update(id, withholdingOverrideRequest(new BigDecimal("3000.00")));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride())
            .isEqualByComparingTo("3000.00");

        // Explicit null clears it back to NULL -- this is the bug under test: an addSet-style update would leave
        // 3000.00 untouched here instead of clearing it.
        repository.update(id, withholdingOverrideRequest(null));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();

        // Zero is a distinct, meaningful override ("withhold nothing") and must NOT be conflated with clearing -- it
        // must persist as exactly 0.00, not collapse to NULL.
        repository.update(id, withholdingOverrideRequest(BigDecimal.ZERO));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride())
            .isEqualByComparingTo("0.00");

        // And a subsequent explicit-null update clears it again, even from a stored 0 (0 -> NULL is a real transition,
        // not a no-op).
        repository.update(id, withholdingOverrideRequest(null));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();
    }

    private UpsertEmployeeRequest req(String nameTh, String divisionCode, String email, BigDecimal salary) {
        return req(nameTh, divisionCode, email, salary, BigDecimal.ZERO);
    }

    /** Every other field left null (unchanged); only withholdingTaxOverride is set on this update call. */
    private UpsertEmployeeRequest withholdingOverrideRequest(BigDecimal withholdingTaxOverride) {
        return new UpsertEmployeeRequest(
            null, null, null, null, null, null, null, null, null, null, // code..maritalStatus
            null, null, null, null, null,                               // email..departmentTh
            null, null, null, null, null, null,                         // positionTh..directorRemuneration
            withholdingTaxOverride,
            null, null, null, null, null, null);                        // hireDate..emergencyPhone
    }

    private UpsertEmployeeRequest req(
            String nameTh, String divisionCode, String email, BigDecimal salary, BigDecimal directorRemuneration) {
        return new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode + " Division", "แผนกทดสอบ",
            null, null, null, "ACT", salary, directorRemuneration, null, null, null, null, null, null);
    }
}
