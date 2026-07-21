package th.co.glr.hr.commission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CommissionSimulatorRequest(
    Long salesRepId,
    LocalDate payrollMonth,
    @NotNull @DecimalMin("0.00") BigDecimal grossAmount,
    @DecimalMin("0.00") BigDecimal bankFees,
    @DecimalMin("0.00") BigDecimal suspenseVat,
    @DecimalMin("0.00") BigDecimal transportFee,
    @DecimalMin("0.00") BigDecimal cutFee,
    @DecimalMin("0.00") BigDecimal shortfall,
    @DecimalMin("0.00") BigDecimal withholdingTax,
    @DecimalMin("0.00") BigDecimal overpayment
) {}
