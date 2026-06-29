package th.co.glr.hr.commission;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SubmitCommissionRequest(
    Long sourceTicketId,
    Long salesRepId,
    @NotBlank String invoiceNumber,
    @NotNull LocalDate invoiceDate,
    @NotNull @DecimalMin("0.00") BigDecimal grossAmount,
    @DecimalMin("0.00") BigDecimal bankFees,
    @DecimalMin("0.00") BigDecimal suspenseVat,
    @DecimalMin("0.00") BigDecimal transportFee,
    @DecimalMin("0.00") BigDecimal cutFee,
    @DecimalMin("0.00") BigDecimal shortfall
) {}
