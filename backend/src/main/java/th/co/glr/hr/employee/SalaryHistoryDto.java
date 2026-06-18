package th.co.glr.hr.employee;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalaryHistoryDto(
    LocalDate date,
    BigDecimal oldSalary,
    BigDecimal newSalary,
    String note
) {
}
