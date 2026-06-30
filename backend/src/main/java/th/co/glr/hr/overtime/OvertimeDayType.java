package th.co.glr.hr.overtime;

import java.math.BigDecimal;

public enum OvertimeDayType {
    WORKDAY(new BigDecimal("1.50")),
    HOLIDAY(new BigDecimal("3.00"));

    private final BigDecimal multiplier;

    OvertimeDayType(BigDecimal multiplier) {
        this.multiplier = multiplier;
    }

    public BigDecimal multiplier() {
        return multiplier;
    }
}
