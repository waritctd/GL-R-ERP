package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.util.List;

public record TierConfig(
    int tierNumber,
    BigDecimal lowerBound,
    BigDecimal upperBound,
    BigDecimal ratePercent,
    boolean highRoller
) {
    public static List<TierConfig> defaults() {
        return List.of(
            tier(1, "0.00", "250000.00", "0.2500"),
            tier(2, "250000.00", "500000.00", "0.5000"),
            tier(3, "500000.00", "750000.00", "0.7500"),
            tier(4, "750000.00", "1000000.00", "1.0000"),
            tier(5, "1000000.00", "1250000.00", "1.2500"),
            tier(6, "1250000.00", "1500000.00", "1.5000"),
            tier(7, "1500000.00", "1750000.00", "1.7500"),
            tier(8, "1750000.00", "2000000.00", "2.0000"),
            tier(9, "2000000.00", "2250000.00", "2.2500"),
            tier(10, "2250000.00", "2500000.00", "2.5000"),
            tier(11, "2500000.00", "2750000.00", "2.7500"),
            tier(12, "2750000.00", "3000000.00", "3.0000"),
            new TierConfig(13, new BigDecimal("3000000.00"), null, new BigDecimal("7.5000"), true)
        );
    }

    private static TierConfig tier(int number, String lower, String upper, String rate) {
        return new TierConfig(number, new BigDecimal(lower), new BigDecimal(upper), new BigDecimal(rate), false);
    }
}
