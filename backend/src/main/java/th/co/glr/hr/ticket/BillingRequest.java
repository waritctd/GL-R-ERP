package th.co.glr.hr.ticket;

import java.time.LocalDate;

public record BillingRequest(
    LocalDate billingDate,
    LocalDate dueDate,
    Integer creditTermDays,
    LocalDate lastFollowUpAt,
    LocalDate nextFollowUpAt
) {}
