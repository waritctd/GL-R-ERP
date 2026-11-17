package th.co.glr.hr.ticket;

import java.util.List;

public record TicketDto(
    TicketSummaryDto summary,
    List<TicketItemDto> items,
    List<TicketEventDto> events,
    QuotationDto quotation,        // most recent (backward compat)
    List<QuotationDto> quotations, // all versions, newest first B5 fix: true when `items` is NOT sales.ticket_item (still empty
    // pre-order-confirmation) but the deal's live PRICING_REQUEST/DEAL_DIRECT quotation's line items instead --
    // see TicketService#get and TicketRepository#findPricingChainFallbackItems.
    boolean fromPricingChain
) {
    // Compat constructor for every call site written before this flag existed -- defaults to false, same "not a
    // fallback" meaning the read path gives a ticket whose ticket_item rows are real.
    public TicketDto(TicketSummaryDto summary, List<TicketItemDto> items, List<TicketEventDto> events,
                     QuotationDto quotation, List<QuotationDto> quotations) {
        this(summary, items, events, quotation, quotations, false);
    }
}
