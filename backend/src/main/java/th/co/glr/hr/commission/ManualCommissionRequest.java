package th.co.glr.hr.commission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Manual commission entry request (feat/commission-manual-adjustments): a sales_manager/CEO hand-typed amount
 * for kind ADJUSTMENT / MANAGER / STOCK_BONUS / INCENTIVE (see CommissionKind). payrollMonth defaults to the
 * current business month (Asia/Bangkok) when omitted. */
public record ManualCommissionRequest(
    @NotNull Long salesRepId,
    @NotBlank String kind,
    @NotNull BigDecimal amount,
    @NotBlank String reason,
    LocalDate payrollMonth
) {}
