package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** PUT /api/tickets/{id}/tracking body. Full-replace semantics (like waiveDeposit/ setEntryChannel), not a
 * merge — a field left null clears that field (for winProbability, null means "fall back to the stage
 * default", which is the intended way to clear a rep's override).
 *
 * <p>Exception: {@code expectedQuarter} (V209, {@code YYYY-Qn}) is KEPT when null/absent, because the existing
 * tracking form does not send it; an empty string clears it. */
public record TrackingUpdateRequest(
    @Min(0) @Max(100) Integer winProbability,
    @Size(max = 200) String designerName,
    @Size(max = 200) String ownerName,
    @Size(max = 200) String buyerName,
    LocalDate nextFollowUpAt,
    @Pattern(regexp = "^$|^\\d{4}-Q[1-4]$", message = "ไตรมาสที่คาดว่าจะปิดต้องอยู่ในรูปแบบ YYYY-Qn เช่น 2026-Q4")
    String expectedQuarter,
    /**
     * V219 มูลค่าโดยประมาณ (THB). Like {@code expectedQuarter} it is KEPT when null/absent — every existing caller of
     * this endpoint predates it — and set when sent. Same bounds as the create request: > 0 and NUMERIC(14,2), the
     * precision of imported_value (V215).
     */
    @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
    @jakarta.validation.constraints.Digits(integer = 12, fraction = 2)
    java.math.BigDecimal estimatedValue,
    /** {@code true} clears the estimate (a number cannot carry expectedQuarter's "empty means clear"). Wins over
     * {@code estimatedValue}. */
    Boolean clearEstimatedValue
) {
    /** The shape before the estimate (V219): the estimate is kept. */
    public TrackingUpdateRequest(Integer winProbability, String designerName, String ownerName, String buyerName,
                                 LocalDate nextFollowUpAt, String expectedQuarter) {
        this(winProbability, designerName, ownerName, buyerName, nextFollowUpAt, expectedQuarter, null, null);
    }

    /** The pre-V209 shape: expectedQuarter absent, i.e. kept. */
    public TrackingUpdateRequest(Integer winProbability, String designerName, String ownerName, String buyerName,
                                 LocalDate nextFollowUpAt) {
        this(winProbability, designerName, ownerName, buyerName, nextFollowUpAt, null, null, null);
    }
}
