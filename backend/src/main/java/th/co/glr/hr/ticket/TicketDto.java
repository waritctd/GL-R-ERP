package th.co.glr.hr.ticket;

import java.util.List;

public record TicketDto(
    TicketSummaryDto summary,
    List<TicketItemDto> items,
    List<TicketEventDto> events,
    QuotationDto quotation
) {}
