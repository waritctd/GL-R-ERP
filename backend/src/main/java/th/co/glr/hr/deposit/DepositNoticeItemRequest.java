package th.co.glr.hr.deposit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record DepositNoticeItemRequest(
    int        seq,
    @NotBlank  String     description,
    @NotNull @PositiveOrZero BigDecimal qty,
    @Size(max = 32) String unit,
    @NotNull @PositiveOrZero BigDecimal unitPrice,
    String     discountLabel,
    @NotNull @PositiveOrZero BigDecimal netUnitPrice
) {}
