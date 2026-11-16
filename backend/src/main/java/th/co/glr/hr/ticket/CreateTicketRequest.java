package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateTicketRequest(
    @NotBlank String title,
    String priority,
    String customerName,
    Long customerId,
    // One deal = one ticket under a โครงการ — required for every new deal.
    @NotNull Long projectId,
    Long contactId,
    String note,
    // Required for every new deal: the channel decides the deal's route
    // (DealRoute). Enforced HERE, at the API boundary, via TicketController's @Valid -- NOT in
    // TicketService.create, which still tolerates null so the ~84 existing call sites keep working.
    // UNSPECIFIED is non-blank, so it passes this annotation; TicketService.create refuses it.
    @NotBlank String entryChannel,
    // Optional: a deal may start at the lead stage with no product items
    // yet (lightweight DRAFT); items arrive later via editItems before submit.
    List<@Valid TicketItemRequest> items,
    /** Optional วันติดตามครั้งถัดไป, set at creation. Collecting it here is what makes a freshly created deal
     * actually movable. */
    LocalDate nextFollowUpAt,
    /**
     * Optional; {@code true} records that the deal was created quotation-first
     * ({@code sales.ticket.quotation_only}) — the quotation editor's inline "new customer +
     * project" create at {@code /quotations/new} is the ONLY caller that sends it. Provenance
     * only: the deal is an ordinary pipeline deal either way (listed, counted, movable).
     */
    Boolean quotationOnly,
    /**
     * Optional เจ้าของงาน (deal owner, 2026-11-05): admin staff key deals for other reps. Null = the
     * actor owns it. Otherwise {@code sales.ticket.created_by} becomes this employee (sells it, earns
     * the commission, is notified) and the actor is recorded in {@code keyed_by} and keeps full access.
     * Must be an active employee who can enter deals ({@code TicketService#create} validates).
     */
    Long ownerEmployeeId,
    /** Optional ผู้ออกแบบ (short create form): the designer's name, written to {@code sales.ticket.designer_name} —
     * the same column the tracking form and the quotation fill write. */
    @Size(max = 200) String designerName,
    /** Optional มูลค่าโดยประมาณ (THB): written to its own column {@code sales.ticket.estimated_value} (V219), the
     * board / forecast's LAST value fallback (after a quotation and imported_value). > 0, and NUMERIC(14,2) — the
     * precision of imported_value (V215). Editable afterwards through PUT /tracking. */
    @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal estimatedValue,
    /** Optional เริ่มที่: {@code LEAD_APPROACH} (S1, the default) or {@code SPEC_APPROVED} (S2). Anything else is a
     * 400; starting at S2 needs the same stage write access as moving a deal there by hand. */
    String startStage
) {
    /** The twelve-argument shape before the short create form's fields. */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items, LocalDate nextFollowUpAt, Boolean quotationOnly,
                               Long ownerEmployeeId) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, nextFollowUpAt, quotationOnly, ownerEmployeeId, null, null, null);
    }

    /** The pre-owner eleven-argument shape: the actor owns the deal. */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items, LocalDate nextFollowUpAt, Boolean quotationOnly) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, nextFollowUpAt, quotationOnly, null);
    }

    /**
     * The ten-argument shape, kept so every existing call site that passes
     * {@code nextFollowUpAt} keeps compiling unchanged — a deal created through it is an ordinary
     * pipeline deal ({@code quotationOnly = null}). Jackson binds request bodies through the
     * canonical constructor, so the wire format gains the optional field either way.
     */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items, LocalDate nextFollowUpAt) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, nextFollowUpAt, null, null);
    }

    /**
     * The nine-argument shape, kept so the call sites that predate
     * {@code nextFollowUpAt} keep compiling unchanged. A deal created through this constructor has
     * no follow-up date and therefore cannot be advanced until one is set — exactly the behaviour
     * those callers already relied on. Jackson uses the canonical constructor for request bodies,
     * so the wire format gains the new optional field either way.
     */
    public CreateTicketRequest(String title, String priority, String customerName, Long customerId,
                               Long projectId, Long contactId, String note, String entryChannel,
                               List<TicketItemRequest> items) {
        this(title, priority, customerName, customerId, projectId, contactId, note, entryChannel,
            items, null, null, null);
    }
}
