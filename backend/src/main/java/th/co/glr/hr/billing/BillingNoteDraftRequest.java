package th.co.glr.hr.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * The create/update body for a billing note DRAFT. {@code type} is required on create (a fresh
 * note's own ค่าสินค้า/ค่าขนส่ง identity) and ignored on update (a draft's type never changes
 * mid-edit — start a new one instead). Live-uniqueness is per REVISION CHAIN, not {@code
 * (customer_id, type)} (owner ruling B1, 2026-09-20 — see V189's own header comment), so several
 * brand-new drafts of the same customer+type may be in progress at once; only a correction (via
 * {@link BillingNoteService#revise}) is capped to one at a time. {@code lines}, when supplied,
 * WHOLE-VALUE REPLACES the draft's current line set (matching {@code
 * RemainingInvoiceDraftRequest#notes}'s own replace-not-append discipline); omitting it on update
 * leaves the existing lines untouched.
 *
 * <p>{@code lines} carries {@code @Valid} (S6, Opus review, GLA-99 step 3 round 2 — it did not
 * before, which meant every nested constraint on {@link BillingNoteLineSelection} was dead at the
 * bean-validation layer; see {@code BillingNoteDraftRequestValidationTest}).
 */
public record BillingNoteDraftRequest(
    String type,
    LocalDate billDate,
    @Size(max = 200) String paymentDueNote,
    @Size(max = 2000) String note,
    @Valid List<BillingNoteLineSelection> lines
) {}
