package th.co.glr.hr.leave;

import java.math.BigDecimal;

public record LeaveBalanceDto(
    String leaveTypeCode,
    String leaveTypeNameTh,
    String leaveTypeNameEn,
    BigDecimal annualQuotaDays,
    BigDecimal approvedDays,
    BigDecimal pendingDays,
    BigDecimal remainingDays,
    boolean requiresAttachment
) {
}
