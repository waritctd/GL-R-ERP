package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.PageRequest;
import th.co.glr.hr.payroll.PayrollEmployeeSnapshot;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Exercises EmployeeRepository's dynamic filter/pagination SQL and the create round-trip against a
 * real PostgreSQL database (issue #28).
 */
class EmployeeRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {
    private EmployeeRepository repository;

    @BeforeEach
    void wireRepository() {
        repository = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
    }

    @Test
    void createsThenReadsBackAnEmployeeWithGeneratedCodeAndSeededSalary() {
        long id = repository.create(req("สมชาย ใจดี", "SALES", "somchai@glr.co.th", new BigDecimal("25000")));

        EmployeeDto dto = repository.findEmployeeById(id, true).orElseThrow();
        assertThat(dto.code()).matches("GLR-\\d+");
        assertThat(dto.nameTh()).contains("สมชาย");
        assertThat(dto.email()).isEqualTo("somchai@glr.co.th");
        assertThat(dto.divisionId()).isEqualTo("SALES");
        assertThat(dto.salary()).isEqualByComparingTo("25000");
        // create() seeds an initial salary-history row.
        assertThat(dto.salaryHistory()).isNotEmpty();
    }

    @Test
    void searchFilterMatchesEmailCaseInsensitively() {
        repository.create(req("สมชาย ใจดี", "SALES", "somchai@glr.co.th", new BigDecimal("25000")));
        repository.create(req("สมหญิง มีสุข", "SALES", "somying@glr.co.th", new BigDecimal("26000")));

        List<EmployeeDto> results =
            repository.findEmployees(new EmployeeFilter("SOMCHAI", null, null, null, null), false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).email()).isEqualTo("somchai@glr.co.th");
    }

    @Test
    void divisionFilterReturnsOnlyMatchingDivision() {
        repository.create(req("สมชาย ใจดี", "SALES", "somchai@glr.co.th", new BigDecimal("25000")));
        repository.create(req("อาทิตย์ นำเข้า", "IMPORT", "import@glr.co.th", new BigDecimal("27000")));

        List<EmployeeDto> sales =
            repository.findEmployees(new EmployeeFilter(null, "SALES", null, null, null), false);

        assertThat(sales).hasSize(1);
        assertThat(sales.get(0).divisionId()).isEqualTo("SALES");
    }

    @Test
    void paginationLimitsRowsWhileCountReflectsTheWholeMatch() {
        repository.create(req("พนักงานหนึ่ง", "SALES", "one@glr.co.th", new BigDecimal("20000")));
        repository.create(req("พนักงานสอง", "SALES", "two@glr.co.th", new BigDecimal("21000")));
        repository.create(req("พนักงานสาม", "SALES", "three@glr.co.th", new BigDecimal("22000")));

        EmployeeFilter all = new EmployeeFilter(null, null, null, null, null);
        List<EmployeeDto> firstPage = repository.findEmployees(all, false, PageRequest.resolve(0, 2));
        List<EmployeeDto> secondPage = repository.findEmployees(all, false, PageRequest.resolve(1, 2));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(repository.countEmployees(all)).isEqualTo(3);
        // Keyset is ordered by employee_code, so pages must not overlap.
        assertThat(firstPage).extracting(EmployeeDto::id)
            .doesNotContainAnyElementsOf(secondPage.stream().map(EmployeeDto::id).toList());
    }

    @Test
    void createPersistsDirectorRemunerationAndReadsItBack() {
        long id = repository.create(req("กรรมการ ผู้จัดการ", "SALES", "director@glr.co.th",
            new BigDecimal("30000"), new BigDecimal("50000")));

        EmployeeDto dto = repository.findEmployeeById(id, true).orElseThrow();
        assertThat(dto.salary()).isEqualByComparingTo("30000");
        assertThat(dto.directorRemuneration()).isEqualByComparingTo("50000");
    }

    @Test
    void updatePersistsDirectorRemuneration() {
        long id = repository.create(req("สมชาย ใจดี", "SALES", "somchai@glr.co.th",
            new BigDecimal("25000"), BigDecimal.ZERO));

        // Only the director-remuneration field is provided; everything else stays null (unchanged).
        repository.update(id, new UpsertEmployeeRequest(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null,
            null, null, null, null, null, new BigDecimal("45000"), null, null, null, null, null, null));

        EmployeeDto dto = repository.findEmployeeById(id, true).orElseThrow();
        assertThat(dto.salary()).isEqualByComparingTo("25000");
        assertThat(dto.directorRemuneration()).isEqualByComparingTo("45000");
    }

    @Test
    void payrollAutoPollIncludesDirectorOnlyEmployeeWithZeroSalary() {
        // A director-only person: base salary 0 but director remuneration > 0. Before this field had
        // a write path, such a person was invisible to payroll. They must now appear as a payroll line.
        long id = repository.create(req("ประธาน กรรมการ", "SALES", "chairman@glr.co.th",
            BigDecimal.ZERO, new BigDecimal("80000")));

        PayrollRepository payroll = new PayrollRepository(jdbc);
        List<PayrollEmployeeSnapshot> active = payroll.findActiveEmployees();

        PayrollEmployeeSnapshot chairman = active.stream()
            .filter(snapshot -> snapshot.employeeId() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("director-only employee was not polled into payroll"));
        assertThat(chairman.baseSalary()).isEqualByComparingTo("0");
        assertThat(chairman.directorRemuneration()).isEqualByComparingTo("80000");
    }

    /**
     * The standing withholding-tax override (V88) is deliberately a FULL-REPLACE column, unlike every
     * other field {@code update()} touches (which use "null = don't change" via {@code addSet}): the
     * column is nullable and NULL is itself a meaningful state ("compute automatically"), so a skip-
     * when-null write would let HR set the override but never clear it back to NULL. Proven
     * wrong-way-round: after clearing, the column must actually BE NULL, not merely "unchanged".
     */
    @Test
    void updateClearsStandingWithholdingOverrideBackToNullButZeroStaysZero() {
        long id = repository.create(req("ภาษี ทดสอบ", "SALES", "wht-clear@glr.co.th", new BigDecimal("25000")));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();

        // Set a standing override.
        repository.update(id, withholdingOverrideRequest(new BigDecimal("3000.00")));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride())
            .isEqualByComparingTo("3000.00");

        // Explicit null clears it back to NULL -- this is the bug under test: an addSet-style update
        // would leave 3000.00 untouched here instead of clearing it.
        repository.update(id, withholdingOverrideRequest(null));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();

        // Zero is a distinct, meaningful override ("withhold nothing") and must NOT be conflated with
        // clearing -- it must persist as exactly 0.00, not collapse to NULL.
        repository.update(id, withholdingOverrideRequest(BigDecimal.ZERO));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride())
            .isEqualByComparingTo("0.00");

        // And a subsequent explicit-null update clears it again, even from a stored 0 (0 -> NULL is a
        // real transition, not a no-op).
        repository.update(id, withholdingOverrideRequest(null));
        assertThat(repository.findEmployeeById(id, true).orElseThrow().withholdingTaxOverride()).isNull();
    }

    /**
     * D2 (owner ruling, notification-coverage branch): {@code findHrEmployeeIds} resolves every
     * ACTIVE employee whose derived login role is EXACTLY {@code "hr"} per {@code
     * DivisionAccessPolicy#roleFor} -- proven against real Postgres because it is a hand-written SQL
     * string, exactly the shape that can pass every Mockito-level test while silently matching the
     * wrong rows (or none) against a real schema. Wrong-way-round: an inactive HR employee and an
     * active non-HR employee must both be excluded, not just "an HR employee is included".
     *
     * <p>S-3 review finding (second pass): the query used to be {@code source_code ILIKE 'HR%'},
     * which does NOT mirror {@code roleFor} (prefix match instead of exact, and no {@code
     * source_code IS NULL} fallback, no executive-precedence exclusion). See {@link
     * #findHrEmployeeIdsExcludesAnHrdStyleDivisionDespiteTheSharedPrefix}, {@link
     * #findHrEmployeeIdsIncludesANullSourceCodeHrDivisionViaTheNameFallback} and {@link
     * #findHrEmployeeIdsExcludesAnExecutiveEvenInTheHrDivision} below for the three cases the fixed
     * query now gets right that {@code ILIKE 'HR%'} did not.
     */
    @Test
    void findHrEmployeeIdsReturnsOnlyActiveEmployeesInAnHrDivision() {
        long activeHr = repository.create(req("บุคคล หนึ่ง", "HR", "hr1@glr.co.th", new BigDecimal("22000")));
        long secondActiveHr = repository.create(req("บุคคล สอง", "HR", "hr2@glr.co.th", new BigDecimal("23000")));
        long salesEmployee = repository.create(req("ขาย หนึ่ง", "SALES", "sales1@glr.co.th", new BigDecimal("21000")));
        UpsertEmployeeRequest resignedHr = new UpsertEmployeeRequest(
            null, null, "บุคคล ลาออก", null, null, null, null, null, null, null,
            "hr-resigned@glr.co.th", null, "HR", "HR Division", "แผนกทดสอบ",
            null, null, null, "RSG", new BigDecimal("22000"), BigDecimal.ZERO, null, null, null, null, null, null);
        long inactiveHr = repository.create(resignedHr);

        List<Long> hrEmployeeIds = repository.findHrEmployeeIds();

        assertThat(hrEmployeeIds).contains(activeHr, secondActiveHr);
        assertThat(hrEmployeeIds).doesNotContain(salesEmployee, inactiveHr);
    }

    /**
     * S-3, wrong-way-round #1 (the {@code ILIKE 'HR%'} prefix bug): a division whose {@code
     * source_code} is {@code "HRD"} satisfies the OLD {@code ILIKE 'HR%'} query, but {@code
     * DivisionAccessPolicy#roleFor} requires an EXACT (case-insensitive) match on {@code "hr"} --
     * {@code "hrd" != "hr"} -- so {@code roleFor} resolves such an employee to {@code "employee"},
     * never {@code "hr"}. They must NOT be returned.
     */
    @Test
    void findHrEmployeeIdsExcludesAnHrdStyleDivisionDespiteTheSharedPrefix() {
        long hrdEmployee = repository.create(req("บุคคล เอชอาร์ดี", "HRD", "hrd1@glr.co.th", new BigDecimal("22000")));

        List<Long> hrEmployeeIds = repository.findHrEmployeeIds();

        assertThat(hrEmployeeIds).doesNotContain(hrdEmployee);
    }

    /**
     * S-3, wrong-way-round #2 (the {@code source_code IS NULL} fallback): {@code
     * hr.division.source_code} is nullable, and {@code DivisionAccessPolicy#divisionCode} falls back
     * to the {@code name_th} prefix before the first {@code '-'} when it is null/blank -- e.g. a real
     * observed division shaped {@code source_code = NULL, name_th = 'HR-บุคคล'} still resolves to
     * role {@code "hr"}. The OLD {@code d.source_code ILIKE 'HR%'} query is false for every NULL row
     * regardless of {@code name_th} and would have silently excluded them.
     */
    @Test
    void findHrEmployeeIdsIncludesANullSourceCodeHrDivisionViaTheNameFallback() {
        // Built directly (not via the req() helper, which always sets divisionId=divisionCode) so
        // source_code stays NULL and name_th carries the "HR-" prefix DivisionAccessPolicy falls back
        // to -- see EmployeeReferenceRepository#ensureDivision: a null sourceCode routes to
        // findOrInsertDivisionByName, which never writes source_code at all.
        UpsertEmployeeRequest nullSourceHr = new UpsertEmployeeRequest(
            null, null, "บุคคล ไม่มีรหัสฝ่าย", null, null, null, null, null, null, null,
            "hr-nullsource@glr.co.th", null, null, "HR-บุคคล", "แผนกทดสอบ",
            null, null, null, "ACT", new BigDecimal("22000"), BigDecimal.ZERO, null, null, null, null, null, null);
        long nullSourceHrEmployee = repository.create(nullSourceHr);

        List<Long> hrEmployeeIds = repository.findHrEmployeeIds();

        assertThat(hrEmployeeIds).contains(nullSourceHrEmployee);
    }

    /**
     * S-3, executive precedence pinned: {@code DivisionAccessPolicy#roleFor} checks {@code
     * isExecutive} (position contains "กรรมการ") BEFORE {@code "hr".equals(code)} -- an employee in
     * the HR division whose position is "กรรมการผู้จัดการ" resolves to role {@code "ceo"}, never
     * {@code "hr"}, and must NOT be returned here even though their division is exactly HR.
     */
    @Test
    void findHrEmployeeIdsExcludesAnExecutiveEvenInTheHrDivision() {
        UpsertEmployeeRequest executiveInHr = new UpsertEmployeeRequest(
            null, null, "บุคคล ผู้บริหาร", null, null, null, null, null, null, null,
            "exec-in-hr@glr.co.th", null, "HR", "HR Division", "แผนกทดสอบ",
            "กรรมการผู้จัดการ", null, null, "ACT", new BigDecimal("22000"), BigDecimal.ZERO, null, null, null, null, null, null);
        long executiveInHrEmployee = repository.create(executiveInHr);

        List<Long> hrEmployeeIds = repository.findHrEmployeeIds();

        assertThat(hrEmployeeIds).doesNotContain(executiveInHrEmployee);
    }

    /**
     * An employee with no English name must get Thai initials, not the placeholder.
     *
     * <p>{@code initials(nameEn, nameTh)} is fed {@code fullName(firstNameEn, lastNameEn)}, which
     * returns the literal {@code "-"} when both halves are blank. {@code hasText("-")} is true, so
     * the English branch ran, took the first character and returned {@code "-"} -- the Thai
     * fallback right below it was unreachable for exactly the employees who needed it.
     *
     * <p>Not a seed quirk: nobody in db/migration-uat has an English name, so EVERY avatar in the
     * app -- topbar, employee list, profile header, approval queues -- rendered a dash for every
     * user, on every page.
     *
     * <p>Asserts the CHARACTERS, not merely "not a dash": a guard that returned an empty string
     * would also stop being a dash while still telling the user nothing.
     *
     * <p>"สใ" -- one letter per word -- and NOT "สม", the first two letters of the given name,
     * which is what the Thai branch used to produce. That old rule barely distinguishes anyone:
     * สมชาย, สมหญิง and สมพงษ์ all collapse to "สม". It also disagreed with the frontend's own
     * initialsFromName, which has always taken one letter per word, so the two would have rendered
     * different avatars for the same employee depending on which one supplied the value. Both sides
     * now use the same rule for Thai and English alike.
     */
    @Test
    void derivesThaiInitialsForAnEmployeeWithNoEnglishName() {
        long id = repository.create(req("สมชาย ใจดี", "SALES", "somchai@glr.co.th", new BigDecimal("25000")));

        EmployeeDto dto = repository.findEmployeeById(id, true).orElseThrow();
        assertThat(dto.nameEn()).isEqualTo("-");
        assertThat(dto.initials()).isEqualTo("สใ");
    }

    /** The English name still wins when there actually is one. */
    @Test
    void prefersEnglishInitialsWhenAnEnglishNameExists() {
        long id = repository.create(reqWithEnglishName("สมหญิง มีสุข", "Somying Meesuk"));

        EmployeeDto dto = repository.findEmployeeById(id, true).orElseThrow();
        assertThat(dto.initials()).isEqualTo("SM");
    }

    private UpsertEmployeeRequest reqWithEnglishName(String nameTh, String nameEn) {
        return new UpsertEmployeeRequest(
            null, null, nameTh, nameEn, null, null, null, null, null, null,
            "en." + Math.abs(nameEn.hashCode()) + "@glr.co.th", null, "SALES", "SALES Division", "แผนกทดสอบ",
            null, null, null, "ACT", new BigDecimal("25000"), BigDecimal.ZERO, null, null, null, null, null, null);
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
