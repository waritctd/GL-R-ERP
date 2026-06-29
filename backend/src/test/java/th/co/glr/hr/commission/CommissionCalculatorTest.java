package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CommissionCalculatorTest {
    private final CommissionCalculator calculator = new CommissionCalculator();

    @Test
    void progressiveCommission_appliesHighRollerRateAboveThreeMillion() {
        BigDecimal commission = calculator.progressiveCommission(new BigDecimal("3500000.00"));

        assertThat(commission).isEqualByComparingTo(new BigDecimal("86250.00"));
    }

    @Test
    void calculateInvoiceDeductsAllFeesBeforeVatStrip() {
        InvoiceCalculation calculation = calculator.calculateInvoice(
            new BigDecimal("107000.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("2000.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("5000.00")
        );

        assertThat(calculation.actualReceived()).isEqualByComparingTo(new BigDecimal("92000.00"));
        assertThat(calculation.commissionableBase()).isEqualByComparingTo(new BigDecimal("85981.31"));
    }
}
