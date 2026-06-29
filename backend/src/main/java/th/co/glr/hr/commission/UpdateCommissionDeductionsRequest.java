package th.co.glr.hr.commission;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record UpdateCommissionDeductionsRequest(
    @DecimalMin("0.00") BigDecimal transportFee,
    @DecimalMin("0.00") BigDecimal cutFee,
    @DecimalMin("0.00") BigDecimal shortfall
) {}
