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

    private UpsertEmployeeRequest req(String nameTh, String divisionCode, String email, BigDecimal salary) {
        return req(nameTh, divisionCode, email, salary, BigDecimal.ZERO);
    }

    private UpsertEmployeeRequest req(
            String nameTh, String divisionCode, String email, BigDecimal salary, BigDecimal directorRemuneration) {
        return new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode + " Division", "แผนกทดสอบ",
            null, null, null, "ACT", salary, directorRemuneration, null, null, null, null, null, null);
    }
}
