package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.util.List;

/**
 * The manager TEAM OVERRIDE generation active for a payroll month (V196). STEP-1 COMPILE STUB:
 * shape only, no behaviour yet.
 */
public record TeamOverrideConfig(
    boolean enabled,
    BigDecimal thresholdBase,
    BigDecimal ratePercent,
    List<TeamOverrideRecipient> recipients
) {
    public static TeamOverrideConfig disabled() {
        return new TeamOverrideConfig(false, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
