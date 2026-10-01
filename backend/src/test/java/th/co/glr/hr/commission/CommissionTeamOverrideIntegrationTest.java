package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Manager TEAM OVERRIDE (V197) -- real-DB coverage, through the REAL {@link CommissionService} and
 * {@link CommissionRepository} (never Mockito): the override's base is a company-wide {@code SUM}
 * whose status filter, kind filter and weighting are all SQL, and a mocked repository would happily
 * "pass" while the SQL summed the wrong rows. The override pays real money to two named people and
 * its base is company-wide data, so most cases are written wrong-way-round: what must NOT move the
 * figure (an unapproved receipt, a weight, a manual kind) and who must NOT see the company base (a
 * non-recipient rep).
 *
 * <p>Independent of the V197 seed: every case inserts its OWN config generation at a month
 * {@code >= 2027-01-01} with its OWN freshly-created employees, so it never depends on employees
 * 142/47 existing.
 *
 * <p>Annotated with {@link EnabledIf} on the class itself because {@code @EnabledIf} is NOT
 * {@code @Inherited} -- the annotation on {@link AbstractPostgresIntegrationTest} gates nothing
 * here (see CLAUDE.md quick facts).
 *
 * <p>Fixture arithmetic (actual_received 2,140,000 = exactly 2,000,000.00 ex-VAT at /1.07):
 * threshold 3,000,000, rate 0.0750% -- a company ex-VAT base of 4,000,000 pays (4M - 3M) x 0.075% =
 * 750.00 to EACH recipient.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class CommissionTeamOverrideIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate FEB_2027 = LocalDate.of(2027, 2, 1);
    private static final LocalDate MARCH_2027 = LocalDate.of(2027, 3, 1);
    private static final LocalDate APRIL_2027 = LocalDate.of(2027, 4, 1);
    private static final LocalDate MAY_2027 = LocalDate.of(2027, 5, 1);
    // Before V197's own 2026-10-01 generation: no generation applies at all.
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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Payroll path (computeRepPayrollCommissions, via payrollReadySummary / payrollCommission-
    // TotalsByEmployee)
    // ─────────────────────────────────────────────────────────────────────────────────────

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
    void recipientWhoAlsoHasSales_overrideIsAddedToTheirExistingRow_notASecondRow() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(recipient1, TWO_M_EX_VAT, MARCH_2027); // her own 2,000,000 ex-VAT
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        assertThat(summary.salesReps().stream().filter(r -> r.salesRepId() == recipient1)).hasSize(1);
        SalesRepCommissionSummaryDto row = repRow(summary, recipient1).orElseThrow();
        BigDecimal ownTier = calculator.progressiveCommission(new BigDecimal("2000000.00"));
        assertThat(row.commissionableBase()).isEqualByComparingTo("2000000.00");
        assertThat(row.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(row.commissionAmount()).isEqualByComparingTo(ownTier.add(new BigDecimal("750.00")));
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
        // ...but the company base is raw money: 4,000,000 -> 750.00. A weighted company base
        // (6,000,000) would have paid (6M - 3M) x 0.075% = 2,250.00 -- the wrong-way-round check.
        SalesRepCommissionSummaryDto r1 = repRow(summary, recipient1).orElse(null);
        assertThat(r1).isNotNull();
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.teamOverrideAmount()).isNotEqualByComparingTo("2250.00");
        assertThat(summary.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
    }

    @Test
    void anUnapprovedReceiptIsExcludedFromThePayrollCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        // A SUBMITTED (nobody approved it) receipt of ฿1,070,000 = ฿1,000,000 ex-VAT. On the
        // preview filter it would lift the base to 5,000,000 and the override to 1,500.00; payroll
        // pays only approved money, so the override must stay 750.00. (This repo already paid ฿5,000
        // on an unapproved receipt once -- the same invariant CommissionIncentiveStockBonus-
        // IntegrationTest pins for the stock bonus.)
        seedSubmitted(repA, new BigDecimal("1070000.00"), MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        SalesRepCommissionSummaryDto r1 = repRow(summary, recipient1).orElse(null);
        assertThat(r1).isNotNull();
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.teamOverrideAmount()).isNotEqualByComparingTo("1500.00");
        assertThat(summary.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
    }

    @Test
    void aClawbackReducesTheCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, new BigDecimal("3210000.00"), MARCH_2027);                 // 3,000,000 ex-VAT
        long clawedBack = seedApproved(repB, new BigDecimal("1070000.00"), MARCH_2027); // 1,000,000
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);                                   // 2,000,000
        // Σ = 6,000,000 -> (6M - 3M) x 0.075% = 2,250.00 before the clawback.
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
    void aZeroManualManagerEntry_isANote_notAReplacement_autoLimbStillComputes() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        commissionService.createManualCommission(
            recipient1, CommissionKind.MANAGER, BigDecimal.ZERO, "note only", MARCH_2027, ceoActor);

        SalesRepCommissionSummaryDto r1 = repRow(commissionService.payrollReadySummary(MARCH_2027, hrActor), recipient1).orElseThrow();

        // Same rule as INCENTIVE/STOCK_BONUS: only a STRICTLY POSITIVE manual entry replaces.
        // (A NEGATIVE manual MANAGER cannot be created -- createManualCommission 400s it -- so the
        // zero case is the only "not a replacement" MANAGER shape reachable through the service.)
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.manualAdjustmentAmount()).isEqualByComparingTo(ZERO2);
        assertThat(r1.commissionAmount()).isEqualByComparingTo("750.00");
    }

    @Test
    void aPositiveApprovedAdjustment_doesNotSuppressTheOverride_onlyManagerKindDoes() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        commissionService.createManualCommission(
            recipient1, CommissionKind.ADJUSTMENT, new BigDecimal("1000.00"), "unrelated bonus", MARCH_2027, ceoActor);

        SalesRepCommissionSummaryDto r1 = repRow(commissionService.payrollReadySummary(MARCH_2027, hrActor), recipient1).orElseThrow();

        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.commissionAmount()).isEqualByComparingTo("1750.00");
    }

    @Test
    void aManualManagerEntryStillAwaitingCeoApproval_neitherSuppressesNorPaysOut() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        // sales_manager-created manual entry -> MANAGER_APPROVED, still awaiting the CEO.
        commissionService.createManualCommission(
            recipient1, CommissionKind.MANAGER, new BigDecimal("5000.00"), "pending hand entry", MARCH_2027, managerActor);

        SalesRepCommissionSummaryDto r1 = repRow(commissionService.payrollReadySummary(MARCH_2027, hrActor), recipient1).orElse(null);
        assertThat(r1).as("recipient 1 has a payroll row").isNotNull();

        // Payroll only ever sees APPROVED rows: the pending 5,000 is not paid AND does not switch
        // the auto limb off -- otherwise the recipient would silently get nothing this month.
        assertThat(r1.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(r1.manualAdjustmentAmount()).isEqualByComparingTo(ZERO2);
        assertThat(r1.commissionAmount()).isEqualByComparingTo("750.00");
    }

    @Test
    void noConfigGenerationForTheMonth_noOverrideForAnyone() {
        wireService();
        // Sep 2026 is before every generation, so no generation applies at all. Feb 2027 IS covered
        // by the V197 seed generation (effective 2026-10-01), but that generation has ZERO
        // recipients in this DB (employees 142/47 do not exist here), so it pays nobody. The
        // generation inserted below starts 2027-03-01 and applies to neither month.
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, FEB_2027);
        seedApproved(repB, TWO_M_EX_VAT, FEB_2027);
        seedApproved(repA, TWO_M_EX_VAT, SEPTEMBER_2026);
        seedApproved(repB, TWO_M_EX_VAT, SEPTEMBER_2026);

        for (LocalDate month : new LocalDate[] {FEB_2027, SEPTEMBER_2026}) {
            PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(month, hrActor);
            assertThat(summary.totalTeamOverrideAmount()).as("total override %s", month).isEqualByComparingTo(ZERO2);
            // No recipients => no override was computed, so there is no company base to show: NULL
            // (the UI hides it), NOT a misleading "company receipts 0.00".
            assertThat(summary.companyCommissionableBase())
                .as("companyCommissionableBase %s: no recipients -> null, not 0.00", month).isNull();
            assertThat(repRow(summary, recipient1)).as("no row for recipient 1 in %s", month).isEmpty();
            assertThat(repRow(summary, recipient2)).as("no row for recipient 2 in %s", month).isEmpty();
            assertThat(commissionService.payrollCommissionTotalsByEmployee(month))
                .doesNotContainKeys(recipient1, recipient2);
        }
    }

    @Test
    void aDisabledGeneration_paysNothing_evenOverTheThreshold() {
        wireService();
        insertConfig(MARCH_2027, false, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);

        assertThat(summary.totalTeamOverrideAmount()).isEqualByComparingTo(ZERO2);
        assertThat(summary.companyCommissionableBase())
            .as("a disabled generation exposes no company base: null, not 0.00").isNull();
        assertThat(repRow(summary, recipient1)).isEmpty();
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

    @Test
    void payrollReadySummaryTotals_equalTheRows_andThePayrollMapAgreesWithThePerRepSummary() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1, recipient2);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        commissionService.createManualCommission(
            recipient2, CommissionKind.ADJUSTMENT, new BigDecimal("300.00"), "misc", MARCH_2027, ceoActor);

        PayrollCommissionSummaryDto summary = commissionService.payrollReadySummary(MARCH_2027, hrActor);
        Map<Long, BigDecimal> payrollMap = commissionService.payrollCommissionTotalsByEmployee(MARCH_2027);

        BigDecimal sumOfRows = summary.salesReps().stream()
            .map(SalesRepCommissionSummaryDto::teamOverrideAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfRows).isEqualByComparingTo("1500.00");
        assertThat(summary.totalTeamOverrideAmount()).isEqualByComparingTo(sumOfRows);
        assertThat(summary.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
        assertThat(summary.companyCommissionableBase().scale()).isEqualTo(2);
        // The two callers (HR's screen and what payroll actually pays) must never diverge, per rep.
        assertThat(payrollMap).hasSameSizeAs(summary.salesReps());
        for (SalesRepCommissionSummaryDto row : summary.salesReps()) {
            assertThat(payrollMap.get(row.salesRepId()))
                .as("payrollCommissionTotalsByEmployee vs payrollReadySummary for rep %s", row.salesRepId())
                .isEqualByComparingTo(row.commissionAmount());
        }
        assertThat(payrollMap.get(recipient1)).isEqualByComparingTo("750.00");
        assertThat(payrollMap.get(recipient2)).isEqualByComparingTo("1050.00"); // 750 override + 300 adjustment
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // monthlySummary -- the rep-facing "how was my commission built" read model
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void monthlySummary_rawPlusWeightUpliftEqualsTheCommissionableBase() {
        wireService();
        long weighted = seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        setWeightMultiplier(weighted, 2);
        seedApproved(repA, new BigDecimal("1070000.00"), MARCH_2027); // weight 1, 1,000,000 ex-VAT

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        // commissionable (weighted) = 2,000,000 x 2 + 1,000,000 = 5,000,000; raw = 3,000,000.
        assertThat(dto.commissionableBase()).isEqualByComparingTo("5000000.00");
        assertThat(dto.rawCommissionableBase()).isNotNull();
        assertThat(dto.rawCommissionableBase()).isEqualByComparingTo("3000000.00");
        assertThat(dto.weightUpliftBase()).isNotNull();
        assertThat(dto.weightUpliftBase()).isEqualByComparingTo("2000000.00");
        assertThat(dto.rawCommissionableBase().add(dto.weightUpliftBase())).isEqualByComparingTo(dto.commissionableBase());
    }

    @Test
    void monthlySummary_unweightedRep_hasZeroUplift() {
        wireService();
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        assertThat(dto.rawCommissionableBase()).isEqualByComparingTo("2000000.00");
        assertThat(dto.weightUpliftBase()).isEqualByComparingTo(ZERO2);
    }

    @Test
    void monthlySummary_stockBonusAppearsOnceTheConfigIsEnabled_andIsInTheTotal() {
        wireService();
        enableStockBonus();
        long ticket = createTicket(repA);
        addTicketItem(ticket, new BigDecimal("100.00"), new BigDecimal("100.00")); // fully from stock
        seedApprovedStockLinked(repA, ticket, new BigDecimal("300000.00"), MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        // Computed exactly as payroll does: floor(300,000 / 100,000) x 1,000 = 3,000.00.
        assertThat(dto.stockBonusAmount()).isNotNull();
        assertThat(dto.stockBonusAmount()).isEqualByComparingTo("3000.00");
        assertThat(dto.totalCommission()).isEqualByComparingTo(
            dto.tierCommission().add(dto.incentiveAmount()).add(dto.manualTotal())
                .add(dto.stockBonusAmount()).add(dto.teamOverrideAmount()));
        // ...and equals what payroll pays this rep.
        assertThat(dto.totalCommission()).isEqualByComparingTo(
            commissionService.payrollReadySummary(MARCH_2027, hrActor).salesReps().stream()
                .filter(r -> r.salesRepId() == repA).findFirst().orElseThrow().commissionAmount());
    }

    @Test
    void monthlySummary_stockBonusIsZeroWhileTheSeededConfigIsDisabled() {
        wireService();
        long ticket = createTicket(repA);
        addTicketItem(ticket, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedApprovedStockLinked(repA, ticket, new BigDecimal("300000.00"), MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        assertThat(dto.stockBonusAmount()).isNotNull();
        assertThat(dto.stockBonusAmount()).isEqualByComparingTo(ZERO2);
    }

    @Test
    void monthlySummary_aPositiveManualStockBonusSuppressesTheAutoStockBonus() {
        wireService();
        enableStockBonus();
        long ticket = createTicket(repA);
        addTicketItem(ticket, new BigDecimal("100.00"), new BigDecimal("100.00"));
        seedApprovedStockLinked(repA, ticket, new BigDecimal("300000.00"), MARCH_2027);
        commissionService.createManualCommission(
            repA, CommissionKind.STOCK_BONUS, new BigDecimal("1000.00"), "hand entered", MARCH_2027, ceoActor);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, principal(repA, "sales"));

        assertThat(dto.stockBonusAmount()).isNotNull();
        assertThat(dto.stockBonusAmount()).as("auto limb replaced by the manual 1,000").isEqualByComparingTo(ZERO2);
        assertThat(dto.manualTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void monthlySummary_aRecipientSeesTheCompanyBaseAndTheRule_usingThePreviewFilter() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        // A still-SUBMITTED ฿1,070,000 receipt: the live ESTIMATE counts it (same NOT IN
        // ('VOID','REJECTED') preview filter the rest of monthlySummary uses), payroll does not.
        seedSubmitted(repA, new BigDecimal("1070000.00"), MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, MARCH_2027, principal(recipient1, "sales"));

        assertThat(dto.companyCommissionableBase()).isNotNull();
        assertThat(dto.companyCommissionableBase()).isEqualByComparingTo("5000000.00");
        assertThat(dto.teamOverrideThresholdBase()).isNotNull();
        assertThat(dto.teamOverrideThresholdBase()).isEqualByComparingTo("3000000.00");
        assertThat(dto.teamOverrideRatePercent()).isNotNull();
        assertThat(dto.teamOverrideRatePercent()).isEqualByComparingTo("0.0750");
        // (5,000,000 - 3,000,000) x 0.075% = 1,500.00 (the estimate), included in the total.
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo("1500.00");
        assertThat(dto.totalCommission()).isEqualByComparingTo(
            dto.tierCommission().add(dto.incentiveAmount()).add(dto.manualTotal())
                .add(dto.stockBonusAmount()).add(dto.teamOverrideAmount()));
    }

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
    void monthlySummary_aRecipientsPositiveManualManagerEntrySuppressesTheAutoLimb() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        commissionService.createManualCommission(
            recipient1, CommissionKind.MANAGER, new BigDecimal("5000.00"), "hand-typed", MARCH_2027, ceoActor);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, MARCH_2027, principal(recipient1, "sales"));

        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo(ZERO2);
        assertThat(dto.manualTotal()).isEqualByComparingTo("5000.00");
        // She is still a recipient, so she still sees the rule and base that would have applied.
        assertThat(dto.companyCommissionableBase()).isNotNull();
    }

    @Test
    void monthlySummary_aZeroManualManagerEntryDoesNotSuppressTheAutoOverride() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);
        commissionService.createManualCommission(
            recipient1, CommissionKind.MANAGER, BigDecimal.ZERO, "note only", MARCH_2027, ceoActor);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, MARCH_2027, principal(recipient1, "sales"));

        // Same rule as the payroll path: only a STRICTLY POSITIVE manual MANAGER replaces the auto limb.
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(dto.manualTotal()).isEqualByComparingTo(ZERO2);
        assertThat(dto.totalCommission()).isEqualByComparingTo(
            dto.tierCommission().add(dto.incentiveAmount()).add(dto.manualTotal())
                .add(dto.stockBonusAmount()).add(dto.teamOverrideAmount()));
    }

    @Test
    void monthlySummary_managerAndCeoViewingARecipient_seeTheCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        CommissionMonthlySummaryDto viaManager = commissionService.monthlySummary(recipient1, MARCH_2027, managerActor);
        CommissionMonthlySummaryDto viaCeo = commissionService.monthlySummary(recipient1, MARCH_2027, ceoActor);

        assertThat(viaManager.companyCommissionableBase()).isNotNull();
        assertThat(viaManager.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
        assertThat(viaManager.teamOverrideAmount()).isEqualByComparingTo("750.00");
        assertThat(viaCeo.companyCommissionableBase()).isNotNull();
        assertThat(viaCeo.companyCommissionableBase()).isEqualByComparingTo("4000000.00");
    }

    @Test
    void monthlySummary_aDisabledGenerationIsTreatedAsNotARecipient_nullBaseAndZeroOverride() {
        wireService();
        insertConfig(MARCH_2027, false, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, MARCH_2027, principal(recipient1, "sales"));

        assertThat(dto.companyCommissionableBase()).isNull();
        assertThat(dto.teamOverrideThresholdBase()).isNull();
        assertThat(dto.teamOverrideRatePercent()).isNull();
        assertThat(dto.teamOverrideAmount()).isNotNull();
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo(ZERO2);
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

    @Test
    void monthlySummary_aManagerViewingANonRecipientRep_alsoGetsNullCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        // The base is tied to the VIEWED rep being a recipient, not to the viewer's role.
        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(repA, MARCH_2027, managerActor);

        assertThat(dto.companyCommissionableBase()).isNull();
        assertThat(dto.teamOverrideThresholdBase()).isNull();
        assertThat(dto.teamOverrideRatePercent()).isNull();
    }

    @Test
    void monthlySummary_aNonRecipientSalesRepCannotReadARecipientsSummaryToGetAtTheCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, MARCH_2027);
        seedApproved(repB, TWO_M_EX_VAT, MARCH_2027);

        assertThatThrownBy(() -> commissionService.monthlySummary(recipient1, MARCH_2027, principal(repA, "sales")))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void monthlySummary_noGenerationForTheMonth_aRecipientOfAnotherMonthGetsNoCompanyBase() {
        wireService();
        insertConfig(MARCH_2027, true, "3000000.00", "0.0750", recipient1);
        seedApproved(repA, TWO_M_EX_VAT, FEB_2027);
        seedApproved(repB, TWO_M_EX_VAT, FEB_2027);

        CommissionMonthlySummaryDto dto = commissionService.monthlySummary(recipient1, FEB_2027, principal(recipient1, "sales"));

        assertThat(dto.companyCommissionableBase()).isNull();
        assertThat(dto.teamOverrideAmount()).isNotNull();
        assertThat(dto.teamOverrideAmount()).isEqualByComparingTo(ZERO2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    /** Flips the seeded V108 stock_bonus_config row (effective_from 2026-08-01) to enabled. */
    private void enableStockBonus() {
        jdbc.update("UPDATE sales.stock_bonus_config SET enabled = TRUE WHERE effective_from = '2026-08-01'", Map.of());
    }

    private long createTicket(long salesRepId) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, status, created_by)
            VALUES (:code, 'team override test ticket', 'draft', :createdBy)
            RETURNING ticket_id
            """,
            new MapSqlParameterSource()
                .addValue("code", "T-" + UUID.randomUUID().toString().substring(0, 8))
                .addValue("createdBy", salesRepId),
            Long.class);
    }

    private void addTicketItem(long ticketId, BigDecimal qty, BigDecimal qtyFromStock) {
        jdbc.update("""
            INSERT INTO sales.ticket_item (ticket_id, brand, qty, qty_from_stock)
            VALUES (:ticketId, 'team override test item', :qty, :qtyFromStock)
            """,
            new MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("qty", qty)
                .addValue("qtyFromStock", qtyFromStock));
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

    private long seedApprovedStockLinked(long salesRepId, long ticketId, BigDecimal actualReceived, LocalDate payrollMonth) {
        long commissionId = insertSale(ticketId, salesRepId, actualReceived, payrollMonth);
        commissionService.approve(commissionId, managerActor);
        commissionService.approve(commissionId, ceoActor);
        return commissionId;
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
