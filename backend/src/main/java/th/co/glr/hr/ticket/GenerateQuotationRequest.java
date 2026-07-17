package th.co.glr.hr.ticket;

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
    @Size(max = 2000) String amendmentReason
) {}
