package th.co.glr.hr.payroll.obligation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import th.co.glr.hr.payroll.PayrollDeductionKind;

public final class PayrollDeductionShortfallDtos {
    private PayrollDeductionShortfallDtos() {
    }

    public record PayrollDeductionShortfallDto(
        long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        LocalDate payrollMonth,
        PayrollDeductionKind deductionKind,
        BigDecimal requestedAmount,
        BigDecimal actualAmount,
        BigDecimal shortfallAmount,
        String shortfallReason,
        Long payrollPeriodId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {}

    public record PayrollDeductionShortfallListResponse(List<PayrollDeductionShortfallDto> items) {}
}
