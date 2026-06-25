package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record EditItemsRequest(
    @NotEmpty @Valid List<TicketItemRequest> items,
    String note
) {}
