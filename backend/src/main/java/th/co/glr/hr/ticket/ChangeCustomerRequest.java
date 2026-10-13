package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/tickets/{id}/customer body. Full-replace: a {@code null} {@code contactId} clears the
 * deal's contact rather than leaving the previous customer's contact pointing at a stranger.
 */
public record ChangeCustomerRequest(
    @NotNull Long customerId,
    @NotNull Long projectId,
    Long contactId
) {}
