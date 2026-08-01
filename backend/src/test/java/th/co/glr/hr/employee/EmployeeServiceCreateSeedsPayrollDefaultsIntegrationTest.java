package th.co.glr.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.TemporaryPasswordGenerator;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.payroll.PayrollComponent;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollTaxTreatment;
import th.co.glr.hr.profile.ProfileRequestRepository;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * P0 fix (Opus review, 2026-07-30), layer 2: {@link EmployeeService#create} must seed a default
 * withholding-tax classification for a new employee, the exact same shape as its existing {@link
 * PayrollRepository#seedSsoInclusionDefaults} call (task 1's known risk 3, wired long before this
 * task). Without this, a new hire paid anything beyond bare salary hits {@code
 * PayrollCalculator#calculateClassified}'s classification 409 on their very first payroll run --
 * exactly the reachability gap {@code PayrollClassificationReachabilityIntegrationTest} demonstrates
 * for an employee inserted outside the API, just reached through the REAL create path this time.
 *
 * <p>Driven through the real {@link EmployeeService} + real {@link EmployeeRepository} + real {@link
 * PayrollRepository} against Postgres (not Mockito) precisely because the claim under test is that
 * REAL ROWS land in {@code hr.payroll_component_tax_treatment} -- a mocked repository would only prove
 * the call happened, not that the SQL does what it says.
 */
class EmployeeServiceCreateSeedsPayrollDefaultsIntegrationTest extends AbstractPostgresIntegrationTest {
    private EmployeeService employeeService;
    private PayrollRepository payrollRepository;

    @BeforeEach
    void wireService() {
        payrollRepository = new PayrollRepository(jdbc);
        employeeService = new EmployeeService(
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            mock(ProfileRequestRepository.class),
            mock(AuditService.class),
            mock(EmployeeAuthRepository.class),
            mock(TemporaryPasswordGenerator.class),
            org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class),
            payrollRepository);
    }

    @Test
    void creatingAnEmployeeSeedsADefaultTaxTreatmentForEveryClassificationEligibleComponent() {
        EmployeeDto created = employeeService.create(req("อาทิตยา ใหม่เอี่ยม", "SALES", "aataya@glr.co.th", new BigDecimal("28000")), hr());

        int taxYear = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).getYear();
        Map<PayrollComponent, PayrollTaxTreatment> treatments =
            payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear).get(created.id());

        assertThat(treatments)
            .withFailMessage("EmployeeService#create must seed a default tax-treatment row for every "
                + "classification-eligible component, exactly like it already does for SSO inclusion -- "
                + "otherwise a brand-new employee hits the classification 409 on their first non-zero, "
                + "non-salary payroll component")
            .isNotNull();

        // Handoff-explicit defaults (spec's own table): bonus/one-off -> EXTRA_KNOWN_FREQUENCY.
        assertThat(treatments.get(PayrollComponent.BONUS_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        assertThat(treatments.get(PayrollComponent.OTHER_ONE_OFF_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_KNOWN_FREQUENCY);
        // Handoff-explicit defaults: OT / irregular commission -> EXTRA_CUMULATIVE_ACTUAL.
        assertThat(treatments.get(PayrollComponent.OVERTIME_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        assertThat(treatments.get(PayrollComponent.COMMISSION_PAY)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        // No V98 evidence exists for a brand-new employee -- the safe unknown-recurrence default.
        assertThat(treatments.get(PayrollComponent.SPECIAL_PAY_1)).isEqualTo(PayrollTaxTreatment.EXTRA_CUMULATIVE_ACTUAL);
        // Correction (owner, 2026-07-29, caught in review before merge): DIRECTOR_REMUNERATION is
        // RESOLVED, not defaulted -- "director remuneration is every month but the pay may change once
        // in a while" (owner). Paid every คราว => ป.96/2543 ข้อ 2.1/2.4 => REGULAR_REPROJECT, matching
        // PayrollCalculator's legacy calculate() engine, which hardcodes it into the REGULAR limb. See
        // PayrollRepository#defaultTaxTreatment's javadoc for the full correction history.
        assertThat(treatments.get(PayrollComponent.DIRECTOR_REMUNERATION)).isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);

        // SALARY and NON_TAXABLE_INCOME are deliberately never seeded -- SALARY needs no row (locked
        // in Java without one) and NON_TAXABLE_INCOME is out of scope for tax treatment entirely.
        assertThat(treatments).doesNotContainKey(PayrollComponent.SALARY);
        assertThat(treatments).doesNotContainKey(PayrollComponent.NON_TAXABLE_INCOME);
    }

    @Test
    void seedingOnCreateNeverOverwritesAnHrClassificationMadeAfterwards() {
        EmployeeDto created = employeeService.create(req("บุญมี รักงาน", "SALES", "boonmee@glr.co.th", new BigDecimal("28000")), hr());
        int taxYear = LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).getYear();

        // HR reclassifies พิเศษ 1 to REGULAR_REPROJECT for this employee -- a genuine, deliberate edit.
        payrollRepository.upsertComponentTaxTreatment(taxYear, java.util.List.of(
            new th.co.glr.hr.payroll.PayrollClassificationDtos.ComponentTaxTreatmentUpsertRequest(
                created.id(), PayrollComponent.SPECIAL_PAY_1, PayrollTaxTreatment.REGULAR_REPROJECT)
        ), created.id());

        // Re-running the seed (defensive: this is what would happen if create() were somehow invoked
        // twice, or a future migration re-ran the same seeding logic) must not clobber the HR edit.
        payrollRepository.seedComponentTaxTreatmentDefaults(created.id(), taxYear, created.id());

        assertThat(payrollRepository.findComponentTaxTreatmentsByEmployee(taxYear).get(created.id())
            .get(PayrollComponent.SPECIAL_PAY_1))
            .isEqualTo(PayrollTaxTreatment.REGULAR_REPROJECT);
    }

    private UpsertEmployeeRequest req(String nameTh, String divisionCode, String email, BigDecimal salary) {
        return new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode + " Division", "แผนกทดสอบ",
            null, null, null, "ACT", salary, BigDecimal.ZERO, null, null, null, null, null, null);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }
}
