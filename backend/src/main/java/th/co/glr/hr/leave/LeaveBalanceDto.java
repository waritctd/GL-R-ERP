package th.co.glr.hr.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/** carriedInDays (V127, §5.3.5): the amount of the PRIOR year's unused quota carried into year for this leave
 * type -- always ZERO for a type where LeaveTypeDto#carriesForward() is FALSE (every type except VACATION). */
public record LeaveBalanceDto(
    String leaveTypeCode,
    String leaveTypeNameTh,
    String leaveTypeNameEn,
    BigDecimal annualQuotaDays,
    BigDecimal approvedDays,
    BigDecimal pendingDays,
    BigDecimal remainingDays,
    boolean requiresAttachment,
    BigDecimal carriedInDays,
    Integer carriedInFromYear,
    LocalDate carriedInExpiresOn,
    BigDecimal carriedInRemainingDays,
    BigDecimal ownQuotaRemainingDays
) {
}
