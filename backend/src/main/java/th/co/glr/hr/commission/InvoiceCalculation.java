package th.co.glr.hr.commission;

import java.math.BigDecimal;

public record InvoiceCalculation(
    BigDecimal actualReceived,
    BigDecimal commissionableBase
) {}
