package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateTicketRequest(
    @NotBlank String title,
    String priority,
    String customerName,
    Long customerId,
    Long projectId,
    Long contactId,
    String note,
    @NotEmpty @Valid List<TicketItemRequest> items
) {}
