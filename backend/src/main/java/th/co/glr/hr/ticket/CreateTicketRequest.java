package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateTicketRequest(
    @NotBlank String title,
    String priority,
    String customerName,
    Long customerId,
    // One deal = one ticket under a โครงการ (V50) — required for every new deal.
    @NotNull Long projectId,
    Long contactId,
    String note,
    // Required for every new deal (owner ruling 2026-09-30): the channel decides the deal's route
    // (DealRoute). Enforced HERE, at the API boundary, via TicketController's @Valid -- NOT in
    // TicketService.create, which still tolerates null so the ~84 existing call sites keep working.
    // UNSPECIFIED is non-blank, so it passes this annotation; TicketService.create refuses it.
    @NotBlank String entryChannel,
    // Optional since V50: a deal may start at the lead stage with no product items
    // yet (lightweight DRAFT); items arrive later via editItems before submit.
    List<@Valid TicketItemRequest> items,
    /**
     * Optional วันติดตามครั้งถัดไป, set at creation.
     *
     * <p>Exists because the stage-advance readiness gate
     * ({@code TicketService#requireStageAdvanceReadiness}) refuses EVERY forward move while
     * {@code next_follow_up_at} is null — so a deal created without one could not be advanced at
     * all, and the rep met that refusal on their very first action with no clue it was coming.
     * Collecting it here is what makes a freshly created deal actually movable.
     */
    LocalDate nextFollowUpAt,
    /**
     * GLA-136: optional; {@code true} records that the deal was created quotation-first
     * ({@code sales.ticket.quotation_only}, V193) — the quotation editor's inline "new customer +
     * project" create at {@code /quotations/new} is the ONLY caller that sends it. Provenance only
     * since quotation ↔ deal linking slice 1 (IA §7, 2026-09-30): the deal is an ordinary pipeline
     * deal either way (listed, counted, movable).
     */
    Boolean quotationOnly
) {
    /**
     * The pre-GLA-136 ten-argument shape, kept so every existing call site that passes
     * {@code nextFollowUpAt} keeps compiling unchanged — a deal created through it is an ordinary
     * pipeline deal ({@code quotationOnly = null}). Jackson binds request bodies through the
     * canonical constructor, so the wire format gains the optional field either way.
     */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items, LocalDate nextFollowUpAt) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, nextFollowUpAt, null);
    }

    /**
     * The pre-existing nine-argument shape, kept so the 56 call sites that predate
     * {@code nextFollowUpAt} keep compiling unchanged. A deal created through this constructor has
     * no follow-up date and therefore cannot be advanced until one is set — exactly the behaviour
     * those callers already relied on. Jackson uses the canonical constructor for request bodies,
     * so the wire format gains the new optional field either way.
     */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, null, null);
    }
}
