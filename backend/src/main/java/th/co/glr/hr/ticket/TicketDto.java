package th.co.glr.hr.ticket;

import java.util.List;

public record TicketDto(
    TicketSummaryDto summary,
    List<TicketItemDto> items,
    List<TicketEventDto> events,
    QuotationDto quotation,        // most recent (backward compat)
    List<QuotationDto> quotations  // all versions, newest first
) {}
