package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
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
    // Optional since V50: a deal may start at the lead stage with no product items
    // yet (lightweight DRAFT); items arrive later via editItems before submit.
    List<@Valid TicketItemRequest> items
) {}
