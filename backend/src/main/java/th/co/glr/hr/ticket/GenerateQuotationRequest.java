package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record GenerateQuotationRequest(
    @NotBlank String recipientType,
    @Size(max = 255) String recipientLabel,
    @Size(max = 2000) String paymentTerms,
    @Size(max = 2000) String leadTime,
    @Size(max = 2000) String deliveryTerms,
    LocalDate validityDate,
    @Size(max = 2000) String amendmentReason,
    // Pre-generate values for quotation remarks block (B24/B26/B28)
    LocalDate offerDate,
    @Min(1) @Max(100) Integer depositPercent,
    @Min(1) @Max(3650) Integer deliveryLeadDays
) {}
