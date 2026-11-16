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
    // OPTIONAL since the owner-approved short create form (2026-11-16, the สร้างงานใหม่ drawing asks no channel).
    // Absent/blank is stored UNSPECIFIED ("not stated", V144) and stated later on the deal page. A value that IS
    // sent must still be a real channel: TicketService.create refuses an unknown one and an explicit UNSPECIFIED.
    // Before 2026-11-16 this was @NotBlank (owner ruling 2026-09-30, "a new deal must state a real channel").
    String entryChannel,
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
    /** Optional มูลค่าโดยประมาณ (THB): written to {@code sales.ticket.imported_value}, the rep's stated value the
     * board shows until a quotation gives one (V215 / V219). */
    @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal estimatedValue,
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
