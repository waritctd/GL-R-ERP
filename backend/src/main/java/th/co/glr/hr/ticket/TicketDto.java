package th.co.glr.hr.ticket;

import java.util.List;

public record TicketDto(
    TicketSummaryDto summary,
    List<TicketItemDto> items,
    List<TicketEventDto> events,
    QuotationDto quotation,        // most recent (backward compat)
    List<QuotationDto> quotations, // all versions, newest first
    // B5 fix: true when `items` is NOT sales.ticket_item (still empty pre-order-confirmation)
    // but the deal's live PRICING_REQUEST/DEAL_DIRECT quotation's line items instead -- see
    // TicketService#get and TicketRepository#findPricingChainFallbackItems. Only ever set on
    // the READ path (TicketService.get); every mutation response built via requireTicket
    // (editItems, create, etc.) keeps reading the real (possibly empty) ticket_item rows, so
    // this flag deliberately does NOT flow into TicketRepository.findById/requireTicket -- see
    // that method's own Javadoc for why mixing the two would be dangerous.
    boolean fromPricingChain
) {
    // Compat constructor for every call site written before this flag existed -- defaults to
    // false, same "not a fallback" meaning the read path gives a ticket whose ticket_item rows
    // are real. Keeps every existing `new TicketDto(summary, items, events, quotation,
    // quotations)` call site (repository writes, mutation return values, tests) compiling
    // unchanged.
    public TicketDto(TicketSummaryDto summary, List<TicketItemDto> items, List<TicketEventDto> events,
                     QuotationDto quotation, List<QuotationDto> quotations) {
        this(summary, items, events, quotation, quotations, false);
    }
}
