package th.co.glr.hr.ticket;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record RecordPaymentRequest(
    @NotBlank String kind,
    @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
    Instant receivedAt,
    @Size(max = 2000) String note,
    Long depositNoticeId,
    @Size(max = 60) String receiptRef,
    Boolean allowOverpayment
) {}
