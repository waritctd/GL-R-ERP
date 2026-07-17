package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Size;

public record CompleteDeliveryRequest(
    @Size(max = 2000) String note
) {}
