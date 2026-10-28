package th.co.glr.hr.deposit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record DepositNoticeDraftRequest(
    @Size(max = 255) String customerName,
    @Size(max = 255) String customerTaxId,
    @Size(max = 255) String customerAddress,
    @Size(max = 255) String projectName,
    @Size(max = 255) String reference,
    @DecimalMin(value = "0", inclusive = false, message = "เปอร์เซ็นต์มัดจำต้องมากกว่า 0")
    @DecimalMax(value = "1", message = "เปอร์เซ็นต์มัดจำต้องไม่เกิน 100") BigDecimal depositPercent,
    List<String> notes,
    @Valid List<DepositNoticeItemRequest> items,
    // The draft's updatedAt as this client last read it. null skips the stale-save check (older clients).
    OffsetDateTime expectedUpdatedAt
) {
    /** Without a stamp: creation and the server-side callers. */
    public DepositNoticeDraftRequest(String customerName, String customerTaxId, String customerAddress,
                                     String projectName, String reference, BigDecimal depositPercent,
                                     List<String> notes, List<DepositNoticeItemRequest> items) {
        this(customerName, customerTaxId, customerAddress, projectName, reference, depositPercent, notes, items, null);
    }
}
