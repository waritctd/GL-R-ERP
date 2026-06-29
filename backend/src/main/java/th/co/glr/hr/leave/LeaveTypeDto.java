package th.co.glr.hr.leave;

import java.math.BigDecimal;

public record LeaveTypeDto(
    String code,
    String nameTh,
    String nameEn,
    BigDecimal annualQuotaDays,
    boolean requiresAttachment
) {
}
