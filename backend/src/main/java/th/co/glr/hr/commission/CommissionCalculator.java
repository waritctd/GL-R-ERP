package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CommissionCalculator {
    private static final BigDecimal VAT_DIVISOR = new BigDecimal("1.07");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;

    public InvoiceCalculation calculateInvoice(
        BigDecimal grossAmount,
        BigDecimal bankFees,
        BigDecimal suspenseVat,
        BigDecimal transportFee,
        BigDecimal cutFee,
        BigDecimal shortfall
    ) {
        BigDecimal actualReceived = money(grossAmount)
            .subtract(money(bankFees))
            .subtract(money(suspenseVat))
            .subtract(money(transportFee))
            .subtract(money(cutFee))
            .subtract(money(shortfall))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal commissionableBase = actualReceived
            .divide(VAT_DIVISOR, MONEY_SCALE, RoundingMode.HALF_UP);
        return new InvoiceCalculation(actualReceived, commissionableBase);
    }

    public BigDecimal progressiveCommission(BigDecimal monthlyCommissionableBase, List<TierConfig> tiers) {
        BigDecimal base = money(monthlyCommissionableBase);
        if (base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<TierConfig> ordered = tiers.stream()
            .sorted(Comparator.comparingInt(TierConfig::tierNumber))
            .toList();
        for (TierConfig tier : ordered) {
            BigDecimal tierAmount = taxableAmountForTier(base, tier);
            if (tierAmount.signum() <= 0) {
                continue;
            }
            BigDecimal rate = tier.ratePercent().divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP);
            total = total.add(tierAmount.multiply(rate));
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal progressiveCommission(BigDecimal monthlyCommissionableBase) {
        return progressiveCommission(monthlyCommissionableBase, TierConfig.defaults());
    }

    private BigDecimal taxableAmountForTier(BigDecimal base, TierConfig tier) {
        if (tier.highRoller()) {
            return base.subtract(tier.lowerBound()).max(BigDecimal.ZERO);
        }
        BigDecimal upper = tier.upperBound();
        if (upper == null || base.compareTo(tier.lowerBound()) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal capped = base.min(upper);
        return capped.subtract(tier.lowerBound()).max(BigDecimal.ZERO);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
