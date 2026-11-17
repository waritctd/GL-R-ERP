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
 * <p>Exceptions: {@code expectedQuarter} (V209, {@code YYYY-Qn}) is KEPT when null/absent, because the existing
 * tracking form does not send it; an empty string clears it. {@code nextFollowUpAt} is likewise KEPT when null/absent
 * (a call or visit may have set it since the form loaded) and set when sent; {@code clearNextFollowUpAt=true} clears
 * it and wins. */
public record TrackingUpdateRequest(
    @Min(0) @Max(100) Integer winProbability,
    @Size(max = 200) String designerName,
    @Size(max = 200) String ownerName,
    @Size(max = 200) String buyerName,
    LocalDate nextFollowUpAt,
    @Pattern(regexp = "^$|^\\d{4}-Q[1-4]$", message = "ไตรมาสที่คาดว่าจะปิดต้องอยู่ในรูปแบบ YYYY-Qn เช่น 2026-Q4")
    String expectedQuarter,
    /**
     * V220 มูลค่าโดยประมาณ (THB). Like {@code expectedQuarter} it is KEPT when null/absent — every existing caller of
     * this endpoint predates it — and set when sent. Same bounds as the create request: > 0 and NUMERIC(14,2), the
     * precision of imported_value (V215).
     */
    @jakarta.validation.constraints.DecimalMin(value = "0", inclusive = false)
    @jakarta.validation.constraints.Digits(integer = 12, fraction = 2)
    java.math.BigDecimal estimatedValue,
    /** {@code true} clears the estimate (a number cannot carry expectedQuarter's "empty means clear"). Wins over
     * {@code estimatedValue}. */
    Boolean clearEstimatedValue,
    /** {@code true} clears the next follow-up date. Wins over {@code nextFollowUpAt}. */
    Boolean clearNextFollowUpAt
) {
    /** The shape before the follow-up clear flag. */
    public TrackingUpdateRequest(Integer winProbability, String designerName, String ownerName, String buyerName,
                                 LocalDate nextFollowUpAt, String expectedQuarter, java.math.BigDecimal estimatedValue,
                                 Boolean clearEstimatedValue) {
        this(winProbability, designerName, ownerName, buyerName, nextFollowUpAt, expectedQuarter, estimatedValue,
            clearEstimatedValue, null);
    }

    /** The shape before the estimate (V220): the estimate is kept. */
    public TrackingUpdateRequest(Integer winProbability, String designerName, String ownerName, String buyerName,
                                 LocalDate nextFollowUpAt, String expectedQuarter) {
        this(winProbability, designerName, ownerName, buyerName, nextFollowUpAt, expectedQuarter, null, null, null);
    }

    /** The pre-V209 shape: expectedQuarter absent, i.e. kept. */
    public TrackingUpdateRequest(Integer winProbability, String designerName, String ownerName, String buyerName,
                                 LocalDate nextFollowUpAt) {
        this(winProbability, designerName, ownerName, buyerName, nextFollowUpAt, null, null, null, null);
    }
}
