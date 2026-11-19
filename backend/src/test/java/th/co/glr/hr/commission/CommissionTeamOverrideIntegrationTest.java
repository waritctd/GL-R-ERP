package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/** Manager TEAM OVERRIDE -- real-DB coverage, through the REAL CommissionService and CommissionRepository: the
 * override's base is a company-wide SUM whose status filter, kind filter and weighting are all SQL, and a
 * mocked repository would happily "pass" while the SQL summed the wrong rows. */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class CommissionTeamOverrideIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate FEB_2027 = LocalDate.of(2027, 2, 1);
    private static final LocalDate MARCH_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate APRIL_2027 = LocalDate.of(2027, 4, 1);
    private static final LocalDate MAY_2027 = LocalDate.of(2027, 5, 1);
    // Before V199's own 2026-10-01 generation: no generation applies at all.
    private static final LocalDate SEPTEMBER_2026 = LocalDate.of(2026, 9, 1);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2027, 2, 10);

    private static final BigDecimal TWO_M_EX_VAT = new BigDecimal("2140000.00"); // / 1.07 = 2,000,000.00
    private static final BigDecimal ZERO2 = new BigDecimal("0.00");

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private CommissionCalculator calculator;
    private EmployeeRepository employees;
    private long managerEmployeeId;
    private UserPrincipal managerActor;
    private long ceoEmployeeId;
    private UserPrincipal ceoActor;
    private UserPrincipal hrActor;
    private long recipient1;
    private long recipient2;
    private long repA;
    private long repB;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            calculator,
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class), new CeoApproverRepository(jdbc));
        managerEmployeeId = createEmployee("ผู้จัดการฝ่ายขาย โอเวอร์ไรด์", "sm-teamoverride@glr.co.th", "SA", "แผนกขาย");
        managerActor = principal(managerEmployeeId, "sales_manager");
        ceoEmployeeId = createEmployee("ผู้บริหาร โอเวอร์ไรด์", "ceo-teamoverride@glr.co.th", "MD", "ผู้บริหาร");
        ceoActor = principal(ceoEmployeeId, "ceo");
        hrActor = principal(999_777L, "hr");
        recipient1 = createEmployee("ผู้รับ หนึ่ง", "recipient1-teamoverride@glr.co.th", "SA", "แผนกขาย");
        recipient2 = createEmployee("ผู้รับ สอง", "recipient2-teamoverride@glr.co.th", "SA", "แผนกขาย");
        repA = createEmployee("พนักงานขาย เอ", "rep-a-teamoverride@glr.co.th", "SA", "แผนกขาย");
        repB = createEmployee("พนักงานขาย บี", "rep-b-teamoverride@glr.co.th", "SA", "แผนกขาย");
    }

    // Payroll path (computeRepPayrollCommissions, via payrollReadySummary / payrollCommission- TotalsByEmployee)

    @Test
    void bothRecipientsEachGetTheFullAmount_notSplit_evenWithNoSaleRowsOfTheirOwn() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        // (4,000,000 - 3,000,000) x 0.075% = 750.00 -- each, NOT 375.00 each.
        SalesRepCommissionSummaryDto r1 = repRow(summary, recipient1).orElse(null);
        SalesRepCommissionSummaryDto r2 = repRow(summary, recipient2).orElse(null);
        // A recipient with no SALE rows of their own still gets a payroll row.
        assertThat(r1).as("recipient 1 has a payroll row despite no SALE rows").isNotNull();
        assertThat(r2).as("recipient 2 has a payroll row despite no SALE rows").isNotNull();
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r2.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.commissionAmount()).isEqualByComparingTo("750.00");
        assertThat(r2.commissionAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.commissionableBase()).isEqualByComparingTo(ZERO2);
        // The row name comes from hr.employee, the same expression every other rep-name query uses.
        assertThat(r1.salesRepName()).isEqualTo(employeeDisplayName(recipient1));
        assertThat(r2.salesRepName()).isEqualTo(employeeDisplayName(recipient2));
    }

    @Test
    void companyBaseIsUnweighted_aWeightTwoSaleCountsOnceInTheCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        long weightedA = seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        setWeightMultiplier(weightedA, 2);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        // The weight DID apply to rep A's own tier base (2,000,000 x 2)...
        assertThat(repRow(summary, repA).orElseThrow().commissionableBase()).isEqualByComparingTo("4000000.00");
        // ...but the company base is raw money: 4,000,000 -> 750.00. A weighted company base (6,000,000) would have
        // paid (6M - 3M) x 0.075% = 2,250.00 -- the wrong-way-round check.
        SalesRepCommissionSummaryDto r1 = repRow(summary, recipient1).orElse(null);
        assertThat(r1).isNotNull();
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.teamOverrideAmount()).isNotEqualByComparingTo("2250.00");
        assertThat(summary.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
    }

    @Test
    void aClawbackReducesTheCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, new BigDecimal("3210000.00"), MARCH_2027);                 // 3,000,000 ex-VAT
        long clawedBack = seedApproved(repB, new BigDecimal("1070000.00"), MARCH_2027); // 1,000,000
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);                                   // 2,000,000 Σ = 6,000,000 -> (6M - 3M) x 0.075% = 2,250.00 before the clawback.
        assertThat(repRow(commissionService.payrollReadySummary(MARCH_2027, hrActor), recipient1)
            .map(SalesRepCommissionSummaryDto::teamOverrideAmount).orElse(ZERO2))
            .isEqualByComparingTo("2250.00");

        // CLAWBACK is created APPROVED with a negated actual_received; same payroll month.
        commissions.createClawback(commissions.findById(clawedBack).orElseThrow(), ceoEmployeeId, MARCH_2027, "returned goods");

        PayrollCommissionSummaryDto after = commissionService.payrollReadySummary(MARCH_2027, hrActor);
        // Σ = 5,000,000 -> (5M - 3M) x 0.075% = 1,500.00.
        assertThat(after.companyCommissionableBase()).isEqualByComparingTo("5000000.00");
        assertThat(repRow(after, recipient1).orElseThrow().teamOverrideAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void aPositiveApprovedManualManagerEntry_replacesTheAutoLimb_otherRecipientStillGetsIt() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        // The accountant still hand-types recipient 1's figure this month (CEO-created -> APPROVED).
        commissionService.createManualCommission(
            recipient1, CommissionKind.MANAGER, new BigDecimal("5000.00"),
            "hand-typed override", MARCH_2027, ceoActor);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        SalesRepCommissionSummaryDto r1 = repRow(summary, recipient1).orElseThrow();
        assertThat(r1.teamOverrideAmount()).as("auto limb suppressed by a positive manual MANAGER").isEqualByComparingTo(ZERO2);
        assertThat(r1.manualAdjustmentAmount()).isEqualByComparingTo("5000.00");
        assertThat(r1.commissionAmount()).as("exactly one override in the total, not two").isEqualByComparingTo("5000.00");
        // Recipient 2 has no manual entry: suppression is per recipient, not per config.
        SalesRepCommissionSummaryDto r2 = repRow(summary, recipient2).orElse(null);
        assertThat(r2).isNotNull();
        assertThat(r2.teamOverrideAmount()).isEqualByComparingTo("750.00");
    }

    @Test
    void noConfigGenerationForTheMonth_noOverrideForAnyone() {
        wireService();
        // Sep 2026 is before every generation, so no generation applies at all. Feb 2027 IS covered by the V199 seed
        // generation (effective 2026-10-01), but that generation has ZERO recipients in this DB (employees 142/47 do
        // not exist here), so it pays nobody.
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, FEB_2027);
        seedApproved(repB, TWO_M_EX_VAT, FEB_2027);
        seedApproved(repA, TWO_M_EX_VAT, SEPTEMBER_2026);
        seedApproved(repB, TWO_M_EX_VAT, SEPTEMBER_2026);

        for (LocalDate month : new LocalDate[] {FEB_2027, SEPTEMBER_2026}) {
            PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(month, hrActor);
            assertThat(summary.totalTeamOverrideAmount()).as("total override %s", month).isEqualByComparingTo(ZERO2);
            // No recipients => no override was computed, so there is no company base to show: NULL (the UI hides it), NOT
            // a misleading "company receipts 0.00".
            assertThat(summary.companyCommissionableBase())
                .as("companyCommissionableBase %s: no recipients -> null, not 0.00", month).isNull();
            assertThat(repRow(summary, recipient1)).as("no row for recipient 1 in %s", month).isEmpty();
            assertThat(repRow(summary, recipient2)).as("no row for recipient 2 in %s", month).isEmpty();
            assertThat(commissionService.payrollCommissionTotalsByEmployee(month))
                .doesNotContainKeys(recipient1, recipient2);
        }
    }

    @Test
    void theLatestGenerationAtOrBeforeTheMonthWins_aFutureGenerationIsNeverPicked() {
        wireService();
        // G1 from 2027-03-01: 3M / 0.075% -> recipient 1. G2 from 2027-05-01: 4M / 0.10% -> recipient 2 only.
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        insertConfig(MAY_2027, true, "4000000.00", "0.1000", recipient2);
        for (LocalDate month : new LocalDate[] {FEB_2027, MARCH_2027, APRIL_2027, MAY_2027}) {
            seedApproved(repA, new BigDecimal("6420000.00"), month); // 6,000,000 ex-VAT each month
        }

        // Feb: before G1 -> nothing (G2/G1 are in the future).
        assertThat(commissionService.payrollReadySummary(FEB_2027, hrActor).totalTeamOverrideAmount()).isEqualByComparingTo(ZERO2);
        // Mar + Apr: G1 -> (6M - 3M) x 0.075% = 2,250.00 to recipient 1 only.
        for (LocalDate month : new LocalDate[] {MARCH_2027, APRIL_2027}) {
            PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(month, hrActor);
            assertThat(repRow(summary, recipient1).map(SalesRepCommissionSummaryDto::teamOverrideAmount).orElse(ZERO2))
                .as("recipient 1 in %s", month).isEqualByComparingTo("2250.00");
            assertThat(repRow(summary, recipient2)).as("recipient 2 in %s", month).isEmpty();
        }
        // May: G2 -> (6M - 4M) x 0.10% = 2,000.00 to recipient 2 only.
        PayrollCommissionSummaryDto may = commissionService.payrollReadySummary(MAY_2027, hrActor);
        assertThat(repRow(may, recipient2).map(SalesRepCommissionSummaryDto::teamOverrideAmount).orElse(ZERO2))
            .isEqualByComparingTo("2000.00");
        assertThat(repRow(may, recipient1)).isEmpty();
    }

    @Test
    void aCompanyBaseAtExactlyTheThreshold_paysNothing() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, new BigDecimal("3210000.00"), MARCH_2027); // exactly 3,000,000.00 ex-VAT

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        assertThat(summary.totalTeamOverrideAmount()).isEqualByComparingTo(ZERO2);
        // A zero override with nothing else to pay creates NO payroll row for the recipient.
        assertThat(repRow(summary, recipient1)).isEmpty();
        assertThat(commissionService.payrollCommissionTotalsByEmployee(MARCH_2027)).doesNotContainKey(recipient1);
    }

    @Test
    void aNonRecipientRepsTotal_isUnchangedByTheFeature() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        SalesRepCommissionSummaryDto a = repRow(summary, repA).orElseThrow();
        assertThat(a.teamOverrideAmount()).isEqualByComparingTo(ZERO2);
        assertThat(a.commissionAmount())
            .isEqualByComparingTo(calculator.progressiveCommission(new BigDecimal("2000000.00")));
    }

    // monthlySummary -- the rep-facing "how was my commission built" read model

    @Test
    void monthlySummary_overrideAgreesWithPayroll_whenEveryReceiptIsApproved() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, MARCH_2027, principal(recipient1, "sales"));
        SalesRepCommissionSummaryDto payroll = repRow(commissionService.payrollReadySummary(MARCH_2027, hrActor), recipient1).orElse(null);

        assertThat(payroll).isNotNull();
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo(payroll.teamOverrideAmount());
        assertThat(dto.totalCommission()).isEqualByComparingTo(payroll.commissionAmount());
    }

    @Test
    void monthlySummary_aNonRecipientSalesRepCannotSeeTheCompanyBase_andGetsZeroOverride() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        // Company-wide data: withheld from anyone who is not a recipient.
        assertThat(dto.companyCommissionableBase()).isNull();
        assertThat(dto.teamOverrideThresholdBase()).isNull();
        assertThat(dto.teamOverrideRatePercent()).isNull();
        assertThat(dto.teamOverrideAmount()).isNotNull();
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo(ZERO2);
        // Her own figures are untouched by the feature.
        assertThat(dto.totalCommission())
            .isEqualByComparingTo(calculator.progressiveCommission(new BigDecimal("2000000.00")));
    }

    // Helpers

    private Optional<SalesRepCommissionSummaryDto> repRow(PayrollCommissionSummaryDto summary, long salesRepId) {
        return summary.salesReps().stream().filter(r -> r.salesRepId() == salesRepId).findFirst();
    }

    private long insertConfig(LocalDate effectiveFrom, boolean enabled, String thresholdBase, String ratePercent, long... recipientIds) {
        Long configId = jdbc.queryForObject("""
            INSERT INTO sales.commission_team_override_config (effective_from, enabled, threshold_base, rate_percent)
            VALUES (:effectiveFrom, :enabled, :thresholdBase, :ratePercent)
            RETURNING team_override_config_id
            """,
            new MapSqlParameterSource()
                .addValue("effectiveFrom", effectiveFrom)
                .addValue("enabled", enabled)
                .addValue("thresholdBase", new BigDecimal(thresholdBase))
                .addValue("ratePercent", new BigDecimal(ratePercent)),
            Long.class);
        for (long employeeId : recipientIds) {
            jdbc.update("""
                INSERT INTO sales.commission_team_override_recipient (team_override_config_id, employee_id)
                VALUES (:configId, :employeeId)
                """, Map.of("configId", configId, "employeeId", employeeId));
        }
        return configId;
    }

    /** The exact name expression CommissionRepository's rep-name queries use. */
    private String employeeDisplayName(long employeeId) {
        return jdbc.queryForObject("""
            SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '')
              FROM hr.employee WHERE employee_id = :id
            """, Map.of("id", employeeId), String.class);
    }

    private void setWeightMultiplier(long commissionId, int weight) {
        jdbc.update("UPDATE sales.commission_record SET weight_multiplier = :w WHERE commission_id = :id",
            Map.of("w", weight, "id", commissionId));
    }

    /** One real, fully APPROVED (manager + CEO) unlinked SALE commission in {@code payrollMonth}. */
    private long seedApproved(long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        long commissionId = seedSubmitted(salesRepId, actualReceived, payrollMonth);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
    }

    /** Same, but left at SUBMITTED -- nobody has reviewed it. */
    private long seedSubmitted(long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        return insertSale(null, salesRepId, actualReceived, payrollMonth);
    }

    private long insertSale(Long ticketId, long salesRepId, BigDecimal actualReceived, LocalDate payrollMonth) {
        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId, salesRepId, "INV-TEAMOVERRIDE-" + UUID.randomUUID(), INVOICE_DATE, actualReceived,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(), request.bankFees(), request.suspenseVat(), request.transportFee(),
            request.cutFee(), request.shortfall(), request.withholdingTax(), request.overpayment());
        long invoiceId = commissions.createInvoice(request);
        return commissions.createCommissionRecord(invoiceId, ticketId, salesRepId, salesRepId, payrollMonth, calculation);
    }

    private long createEmployee(String nameTh, String email, String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-" + employeeId + "@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
