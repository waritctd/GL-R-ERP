package th.co.glr.hr.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record ProcessPayrollRequest(
    @NotNull LocalDate payrollMonth,
    List<@Valid PayrollEmployeeInputRequest> inputs
) {}
