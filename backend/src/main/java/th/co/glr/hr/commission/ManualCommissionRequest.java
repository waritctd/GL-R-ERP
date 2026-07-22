package th.co.glr.hr.commission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Manual commission entry request (feat/commission-manual-adjustments): a sales_manager/CEO
 * hand-typed amount for {@code kind} ADJUSTMENT or MANAGER (see {@link CommissionKind}).
 * {@code amount} is the signed value stored verbatim -- it is never run through {@link
 * CommissionCalculator} or any tier/progressive-commission math. A negative amount is a
 * legitimate deduction for an ADJUSTMENT (e.g. clawing back part of a takeover-credit split);
 * {@code reason} is required for every manual entry. {@code payrollMonth} defaults to the current
 * business month (Asia/Bangkok) when omitted.
 */
public record ManualCommissionRequest(
    @NotNull Long salesRepId,
    @NotBlank String kind,
    @NotNull BigDecimal amount,
    @NotBlank String reason,
    LocalDate payrollMonth
) {}
