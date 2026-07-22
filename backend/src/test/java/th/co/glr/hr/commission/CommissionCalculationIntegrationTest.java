package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Commission redesign Slice A1 — real-DB acceptance coverage for {@link CommissionCalculator}'s
 * new withholding-tax/overpayment inputs, the &lt;50,000 monthly floor, and the V81 tier-13 rate
 * fix, driven through the real {@link CommissionService} and a real {@link CommissionRepository}
 * against real Postgres (per CLAUDE.md: unit tests alone cannot prove {@code sales.tier_config}
 * actually contains the corrected 3.25% rate after V81, or that the schema round-trips the two
 * new {@code sales.invoice_details} columns added by V80).
 *
 * <p>Every test goes through {@link CommissionService#simulate}, which is read-only (no invoice
 * file, no persisted commission_record) but exercises the exact same {@link CommissionCalculator}
 * call and the exact same {@link CommissionRepository#findTiers()} query against real
 * {@code sales.tier_config} that {@code submit()}/{@code updateDeductions()} use. {@link
 * CommissionAttachmentRepository}, {@link FileStorageService}, {@link AuditService}, {@link
 * NotificationService}, and {@link TicketRepository} are mocked — {@code simulate()} never touches
 * them, so mocking them keeps the fixture minimal without weakening what's actually under test.
 */
class CommissionCalculationIntegrationTest extends AbstractPostgresIntegrationTest {
    private CommissionRepository commissions;
    private CommissionService commissionService;
    private UserPrincipal salesActor;

    private void wireService() {
        commissions = new CommissionRepository(jdbc);
        commissionService = new CommissionService(
            commissions,
            mock(CommissionAttachmentRepository.class),
            new CommissionCalculator(),
            mock(FileStorageService.class),
            mock(AuditService.class),
            mock(NotificationService.class),
            mock(TicketRepository.class),
            mock(AttachmentRepository.class));
        salesActor = new UserPrincipal(1L, "sales-slicea1@glr.co.th", "Sales Rep", "sales", 1L,
            true, LocalDate.now(), false, null, false);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Withholding tax / overpayment sign convention.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void withholdingTax_reducesCommissionableBase_vsSameInvoiceWithoutIt() {
        wireService();
        CommissionSimulationDto without = commissionService.simulate(simulatorRequest(
            new BigDecimal("107000.00"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);
        CommissionSimulationDto with = commissionService.simulate(simulatorRequest(
            new BigDecimal("107000.00"), new BigDecimal("2140.00"), BigDecimal.ZERO), salesActor);

        assertThat(with.actualReceived()).isLessThan(without.actualReceived());
        assertThat(with.commissionableBase()).isLessThan(without.commissionableBase());
        assertThat(with.actualReceived()).isEqualByComparingTo(new BigDecimal("104860.00"));
    }

    @Test
    void overpayment_increasesCommissionableBase_vsSameInvoiceWithoutIt() {
        wireService();
        CommissionSimulationDto without = commissionService.simulate(simulatorRequest(
            new BigDecimal("107000.00"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);
        CommissionSimulationDto with = commissionService.simulate(simulatorRequest(
            new BigDecimal("107000.00"), BigDecimal.ZERO, new BigDecimal("1000.00")), salesActor);

        assertThat(with.actualReceived()).isGreaterThan(without.actualReceived());
        assertThat(with.commissionableBase()).isGreaterThan(without.commissionableBase());
        assertThat(with.actualReceived()).isEqualByComparingTo(new BigDecimal("108000.00"));
    }

    @Test
    void zeroWithholdingTaxAndOverpayment_isBackwardCompatibleWithPreSliceA1Result() {
        wireService();
        CommissionSimulationDto withZeros = commissionService.simulate(simulatorRequest(
            new BigDecimal("107000.00"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);

        // Same math as the pre-Slice-A1 five-column formula: 107000 / 1.07 = 100000.00.
        assertThat(withZeros.actualReceived()).isEqualByComparingTo(new BigDecimal("107000.00"));
        assertThat(withZeros.commissionableBase()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // <50,000 monthly floor — written wrong-way-round: the below-floor ZERO case is the
    // assertion that matters, not the above-floor positive case.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void monthlyBaseBelowFiftyThousand_paysExactlyZeroCommission() {
        wireService();
        // grossAmount chosen so commissionableBase = actualReceived / 1.07 lands on exactly
        // 49,999.00 (49999 * 1.07 = 53498.93, an exact reverse with no rounding ambiguity).
        CommissionSimulationDto dto = commissionService.simulate(
            simulatorRequest(new BigDecimal("53498.93"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);

        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("49999.00"));
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void monthlyBaseAtOrAboveFiftyThousand_paysPositiveCommission() {
        wireService();
        // 50001 * 1.07 = 53501.07, an exact reverse — commissionableBase = 50,001.00.
        CommissionSimulationDto dto = commissionService.simulate(
            simulatorRequest(new BigDecimal("53501.07"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);

        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("50001.00"));
        assertThat(dto.projectedMonthlyCommission()).isGreaterThan(BigDecimal.ZERO);
        // Tier 1 (0-250,000 @ 0.25%) applies from THB 0, unchanged, once the floor is met.
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("125.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // V81: tier 13 (>3,000,000) corrected from 7.5000% to 3.2500% — read from the real
    // sales.tier_config table, not TierConfig.defaults(), proving the migration actually applied.
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void monthlyBaseAboveThreeMillion_usesCorrectedTier13RateFromRealDb() {
        wireService();
        // 3,200,000 * 1.07 = 3,424,000.00, an exact reverse — commissionableBase = 3,200,000.00.
        CommissionSimulationDto dto = commissionService.simulate(
            simulatorRequest(new BigDecimal("3424000.00"), BigDecimal.ZERO, BigDecimal.ZERO), salesActor);

        assertThat(dto.commissionableBase()).isEqualByComparingTo(new BigDecimal("3200000.00"));
        // Tiers 1-12 (0-3,000,000, all fully taxed) sum to 48,750.00; the 200,000 excess above
        // 3,000,000 is taxed at the V81-corrected 3.25% = 6,500.00. Total = 55,250.00.
        // (Pre-V81 this would have been 48,750.00 + 200,000 * 7.5% = 63,750.00.)
        assertThat(dto.projectedMonthlyCommission()).isEqualByComparingTo(new BigDecimal("55250.00"));

        BigDecimal tier13Rate = jdbc.queryForObject(
            "SELECT rate_percent FROM sales.tier_config WHERE tier_number = 13",
            java.util.Map.of(), BigDecimal.class);
        assertThat(tier13Rate).isEqualByComparingTo(new BigDecimal("3.2500"));
    }

    private CommissionSimulatorRequest simulatorRequest(
        BigDecimal grossAmount, BigDecimal withholdingTax, BigDecimal overpayment
    ) {
        return new CommissionSimulatorRequest(
            null,
            LocalDate.of(2026, 6, 15),
            grossAmount,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            withholdingTax,
            overpayment
        );
    }
}
