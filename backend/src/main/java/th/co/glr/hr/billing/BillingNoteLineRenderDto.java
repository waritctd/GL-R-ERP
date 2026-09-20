package th.co.glr.hr.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BillingNoteLineRenderDto(
    String docNumber,
    LocalDate docDate,
    LocalDate dueDate,
    BigDecimal amount,
    String note
) {}
