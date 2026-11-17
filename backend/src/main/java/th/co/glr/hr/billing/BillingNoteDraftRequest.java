package th.co.glr.hr.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** The create/update body for a billing note DRAFT. type is required on create (a fresh note's own
 * ค่าสินค้า/ค่าขนส่ง identity) and ignored on update (a draft's type never changes mid-edit — start a new one
 * instead). */
public record BillingNoteDraftRequest(
    String type,
    LocalDate billDate,
    @Size(max = 200) String paymentDueNote,
    @Size(max = 2000) String note,
    @Valid List<BillingNoteLineSelection> lines
) {}
