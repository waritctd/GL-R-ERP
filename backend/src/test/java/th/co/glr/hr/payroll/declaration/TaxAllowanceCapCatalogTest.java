package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.leave.LeaveRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.payroll.PayrollCalculator;
import th.co.glr.hr.payroll.PayrollComponent;
import th.co.glr.hr.payroll.PayrollLineDto;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.PayslipRenderer;
import th.co.glr.hr.payroll.ProcessPayrollRequest;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceCapEntry;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Proves {@link TaxAllowanceCapCatalog} corresponds to {@link PayrollCalculator}'s ACTUAL literals
 * by DRIVING the real calculator — through the real {@code PayrollService#preview} and a real
 * {@code hr.employee_tax_allowance} row — rather than reading {@code PayrollCalculator}'s source.
 * If a cap literal in the calculator ever drifts from this catalogue, the corresponding test below
 * goes red, naming the field. {@code PayrollCalculator.java} itself is NEVER edited by this test or
 * by anything in this PR (CLAUDE.md: payroll/tax math is untouchable outside an explicit request).
 *
 * <p>Every scenario sets ONE allowance (or one shared-group combination) to an absurd figure with
 * every other allowance at zero, on an employee with income high enough that no unrelated
 * %-of-income cap (30%/15%/10%) accidentally binds first — 300,000/month salary, so the RMF/pension/
 * thai_esg %-of-income ceilings all sit comfortably inside 30% of a ~3,600,000 annual income (RMF's
 * own ceiling is 500,000 = ~13.9% of that, well under the 30% rate).
 */
class TaxAllowanceCapCatalogTest extends AbstractPostgresIntegrationTest {
    private static final BigDecimal ABSURD = new BigDecimal("9999999.00");
    private static final LocalDate MONTH = LocalDate.of(2026, 1, 1);
    private static final BigDecimal PERSONAL_PLUS_SSO = new BigDecimal("70500.00"); // 60,000 + SSO year cap 10,500

    private final TaxAllowanceCapCatalog catalog = new TaxAllowanceCapCatalog();
    private PayrollRepository payrollRepository;
    private PayrollService payrollService;

    @BeforeEach
    void wireRealCollaborators() {
        payrollRepository = new PayrollRepository(jdbc);
        CommissionService commissionService = new CommissionService(
            new CommissionRepository(jdbc),
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        payrollService = new PayrollService(
            payrollRepository,
            new PayrollCalculator(),
            commissionService,
            mock(AuditService.class),
            mock(PayslipRenderer.class),
            new LeaveRepository(jdbc),
            new th.co.glr.hr.payroll.export.KBankPctExporter(),
            new th.co.glr.hr.payroll.export.Pnd1Exporter(),
            new th.co.glr.hr.payroll.export.SsoExporter(),
            new th.co.glr.hr.payroll.export.PayrollDetailExporter(),
            new th.co.glr.hr.config.AppProperties());
    }

    @Test
    void flatCapsMatchTheCatalogue() {
        assertOwnCapForFlatColumn("spouse_allowance", "spouse");
        assertOwnCapForFlatColumn("maternity_allowance", "maternity");
        assertOwnCapForFlatColumn("home_loan_interest_allowance", "home_loan_interest");
        assertOwnCapForFlatColumn("political_donation", "political_donation");
        assertOwnCapForFlatColumn("parent_health_insurance_allowance", "parent_health_insurance");
    }

    @Test
    void childAndChildDoubleCapsMatchTheCatalogueAndCombine() {
        BigDecimal childOwnCap = ownCap("child");
        BigDecimal childDoubleOwnCap = ownCap("child_double");

        // One plain child, absurd declared amount, count = 1: capped at 1 x ownCap.
        long e1 = seedEmployee("CAP-CHILD-1");
        seed(e1, "child_allowance", ABSURD, Map.of("child_count", "1"));
        assertThat(deltaOverFloor(e1)).isEqualByComparingTo(childOwnCap);

        // Two children, one of them the "double" (2nd-or-later born 2561+): (2 heads x plain rate) +
        // (1 head x double-extra rate) = childOwnCap*2 + childDoubleOwnCap*1.
        long e2 = seedEmployee("CAP-CHILD-2");
        seed(e2, "child_allowance", ABSURD, Map.of("child_count", "2", "child_count_double", "1"));
        assertThat(deltaOverFloor(e2)).isEqualByComparingTo(childOwnCap.multiply(new BigDecimal(2)).add(childDoubleOwnCap));
    }

    @Test
    void disabledCareCapMatchesTheCatalogue() {
        BigDecimal ownCap = ownCap("disabled_care");
        long employeeId = seedEmployee("CAP-DISABLED");
        seed(employeeId, "disabled_care_allowance", ABSURD, Map.of("disabled_care_count", "2"));
        assertThat(deltaOverFloor(employeeId)).isEqualByComparingTo(ownCap.multiply(new BigDecimal(2)));
    }

    @Test
    void parentCareCapMatchesTheCatalogueIncludingTheFourHeadMaxTotal() {
        TaxAllowanceCapEntry parentCare = entry("parent_care");
        long belowMax = seedEmployee("CAP-PARENT-1");
        seed(belowMax, "parent_care_allowance", ABSURD, Map.of("parent_care_count", "1"));
        assertThat(deltaOverFloor(belowMax)).isEqualByComparingTo(parentCare.ownCap());

        long atMax = seedEmployee("CAP-PARENT-2");
        seed(atMax, "parent_care_allowance", ABSURD, Map.of("parent_care_count", "4"));
        assertThat(deltaOverFloor(atMax))
            .as("4 heads x ownCap must clamp to the statutory maxTotal, not run past it")
            .isEqualByComparingTo(parentCare.maxTotal());
    }

    /** Life ฿100,000 + health ฿25,000 must add to ฿100,000, NOT ฿125,000 — the exact case the plan doc names. */
    @Test
    void lifeAndHealthShareOneHundredThousandNotOneHundredTwentyFive() {
        TaxAllowanceCapEntry life = entry("life_insurance");
        TaxAllowanceCapEntry health = entry("health_insurance");
        assertThat(life.groupCap()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(life.groupCap()).isEqualByComparingTo(health.groupCap());

        long employeeId = seedEmployee("CAP-LIFEHEALTH");
        seed(employeeId, "life_insurance_allowance", life.ownCap(),
            Map.of(), "health_insurance_allowance", health.ownCap());

        assertThat(deltaOverFloor(employeeId))
            .as("life's own cap + health's own cap must clamp to the SHARED group cap")
            .isEqualByComparingTo(life.groupCap());
    }

    @Test
    void healthAloneIsCappedAtItsOwnTwentyFiveThousandNotTheSharedHundred() {
        long employeeId = seedEmployee("CAP-HEALTH-ALONE");
        seed(employeeId, "health_insurance_allowance", ABSURD, Map.of());
        assertThat(deltaOverFloor(employeeId)).isEqualByComparingTo(ownCap("health_insurance"));
    }

    /** RMF -> SSF -> pension share one ฿500,000 cluster, consumed in that order. */
    @Test
    void retirementClusterSharesFiveHundredThousandConsumedInOrder() {
        TaxAllowanceCapEntry rmf = entry("rmf");
        assertThat(rmf.groupCap()).isEqualByComparingTo(new BigDecimal("500000.00"));

        // RMF alone: its own 500,000 ceiling (income here makes 30% >> 500,000, so the %-cap never binds).
        long rmfOnly = seedEmployee("CAP-RETIRE-RMF");
        seed(rmfOnly, "rmf_allowance", ABSURD, Map.of());
        assertThat(deltaOverFloor(rmfOnly)).isEqualByComparingTo(rmf.groupCap());

        // RMF absurd + pension absurd: RMF exhausts the whole 500,000 cluster FIRST, pension gets 0.
        long rmfThenPension = seedEmployee("CAP-RETIRE-RMF-PENSION");
        seed(rmfThenPension, "rmf_allowance", ABSURD, Map.of(), "pension_insurance_allowance", ABSURD);
        assertThat(deltaOverFloor(rmfThenPension))
            .as("RMF alone already exhausts the 500,000 cluster; pension must add nothing on top")
            .isEqualByComparingTo(rmf.groupCap());

        // Pension alone (no RMF/SSF ahead of it in the queue): its own 200,000 sub-cap.
        long pensionOnly = seedEmployee("CAP-RETIRE-PENSION");
        seed(pensionOnly, "pension_insurance_allowance", ABSURD, Map.of());
        assertThat(deltaOverFloor(pensionOnly)).isEqualByComparingTo(entry("pension").ownCap());
    }

    /** SSF is ฿0 from tax year 2025 onward (sunset) — this repo's calculator year is always >= 2025. */
    @Test
    void ssfAloneContributesNothingBecauseTaxYear2026IsPastTheSunset() {
        TaxAllowanceCapEntry ssf = entry("ssf");
        assertThat(ssf.ownCap()).isEqualByComparingTo(BigDecimal.ZERO);

        long employeeId = seedEmployee("CAP-SSF");
        seed(employeeId, "ssf_allowance", ABSURD, Map.of());
        assertThat(deltaOverFloor(employeeId))
            .as("SSF must add exactly zero for a 2026 declaration")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void thaiEsgCapMatchesTheCatalogue() {
        long employeeId = seedEmployee("CAP-ESG");
        seed(employeeId, "thai_esg_allowance", ABSURD, Map.of());
        assertThat(deltaOverFloor(employeeId)).isEqualByComparingTo(entry("thai_esg").ownCap());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void assertOwnCapForFlatColumn(String column, String category) {
        long employeeId = seedEmployee("CAP-" + category.toUpperCase());
        seed(employeeId, column, ABSURD, Map.of());
        assertThat(deltaOverFloor(employeeId))
            .as("category=" + category)
            .isEqualByComparingTo(ownCap(category));
    }

    private BigDecimal ownCap(String category) {
        return entry(category).ownCap();
    }

    private TaxAllowanceCapEntry entry(String category) {
        return catalog.capsFor(2026).stream()
            .filter(e -> e.category().equals(category))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no cap entry for " + category));
    }

    /** taxAllowanceTotal minus the personal+SSO floor, for an employee whose declaration is under test. */
    private BigDecimal deltaOverFloor(long employeeId) {
        PayrollLineDto line = lineFor(employeeId);
        return line.taxAllowanceTotal().subtract(PERSONAL_PLUS_SSO);
    }

    private PayrollLineDto lineFor(long employeeId) {
        return payrollService.preview(new ProcessPayrollRequest(MONTH, List.of()), hr()).lines().stream()
            .filter(line -> line.employeeId() == employeeId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no payroll line for employee " + employeeId));
    }

    private void seed(long employeeId, String column, BigDecimal amount, Map<String, String> counts) {
        seed(employeeId, column, amount, counts, null, null);
    }

    private void seed(long employeeId, String column, BigDecimal amount, Map<String, String> counts,
                       String secondColumn, BigDecimal secondAmount) {
        StringBuilder columns = new StringBuilder("employee_id, tax_year, effective_month, " + column);
        StringBuilder values = new StringBuilder(":employeeId, 2026, 1, :amount");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("employeeId", employeeId);
        params.put("amount", amount);
        for (Map.Entry<String, String> count : counts.entrySet()) {
            columns.append(", ").append(count.getKey());
            values.append(", :").append(count.getKey());
            params.put(count.getKey(), Integer.valueOf(count.getValue()));
        }
        if (secondColumn != null) {
            columns.append(", ").append(secondColumn);
            values.append(", :secondAmount");
            params.put("secondAmount", secondAmount);
        }
        jdbc.update("INSERT INTO hr.employee_tax_allowance (" + columns + ") VALUES (" + values + ")", params);
    }

    private int employeeCodeCounter;

    /**
     * ฿300,000/month salary, so 30%/15% of the ~3.6M annual income never accidentally binds a
     * %-of-income cap. {@code label} is just for readability in a failed-assertion trace — the
     * actual stored {@code employee_code} is a short generated one, since {@code
     * hr.employee.employee_code} is {@code VARCHAR(20)} and several category names here
     * (home_loan_interest, parent_health_insurance, ...) do not fit with any descriptive prefix.
     */
    private long seedEmployee(String label) {
        String code = "CAPT" + (employeeCodeCounter += 1);
        long employeeId = jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, current_salary, is_active) VALUES (:code, 300000.00, TRUE)
            RETURNING employee_id
            """,
            Map.of("code", code), Long.class);
        seedRegularTaxTreatment(employeeId, 2026, PayrollComponent.SPECIAL_PAY_1);
        seedSsoIncluded(employeeId, 2026, PayrollComponent.SALARY);
        return employeeId;
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "HR", "hr", 1L, true, LocalDate.now(), false, null, false);
    }
}
