package th.co.glr.hr.deposit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record DepositNoticeDraftRequest(
    @Size(max = 255) String customerName,
    @Size(max = 255) String customerTaxId,
    @Size(max = 255) String customerAddress,
    @Size(max = 255) String projectName,
    @Size(max = 255) String reference,
    @DecimalMin("0") @DecimalMax("1") BigDecimal depositPercent,
    List<String> notes,
    @Valid List<DepositNoticeItemRequest> items
) {}
