package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.util.List;

/**
 * A SUBMITTED SALE commission awaiting the sales manager, with its deal's item lines and the
 * server-computed figures the manager reviews. Every figure is composed from existing
 * {@link CommissionCalculator} calls -- nothing here reimplements commission math.
 */
public record PendingCommissionDto(
    CommissionRecord commission,
    String ticketCode,
    String customerName,
    List<PendingCommissionItemDto> items,
    BigDecimal effectiveWeight,
    BigDecimal weightedCommissionableBase,
    BigDecimal estimatedCommission
) {}
