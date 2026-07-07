package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.common.PageRequest;
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

    private UpsertEmployeeRequest req(String nameTh, String divisionCode, String email, BigDecimal salary) {
        return new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode + " Division", "แผนกทดสอบ",
            null, null, null, "ACT", salary, null, null, null, null, null, null);
    }
}
