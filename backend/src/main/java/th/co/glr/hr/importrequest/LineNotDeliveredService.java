package th.co.glr.hr.importrequest;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;
import th.co.glr.hr.ticket.MarkNotDeliveredRequest;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketService;

/**
 * "ไม่ได้รับของ": the sales manager or CEO marks a deal line the factory never sent. Lock order as everywhere: the deal's rounds,
 * its import requests, then the ticket. The mark writes no amount; the IRs then re-derive (an IR with nothing left to receive is
 * done and the normal rollup and its one notice follow), and a deal whose last open quantity this was completes delivery.
 */
@Service
public class LineNotDeliveredService {
    private final TicketService tickets;
    private final ShipmentRoundService rounds;

    public LineNotDeliveredService(TicketService tickets, ShipmentRoundService rounds) {
        this.tickets = tickets;
        this.rounds = rounds;
    }

    @Transactional
    public TicketDto mark(long ticketId, long itemId, MarkNotDeliveredRequest request, UserPrincipal actor) {
        tickets.requireNotDeliveredRole(actor);
        List<IrLock> irs = rounds.lockTicketIrs(ticketId);
        tickets.markLineNotDelivered(ticketId, itemId, request, actor);
        rounds.rederiveAfterNotDelivered(irs, actor);
        return tickets.completeDeliveryIfDone(ticketId, actor);
    }

    @Transactional
    public TicketDto clear(long ticketId, long itemId, UserPrincipal actor) {
        tickets.requireNotDeliveredRole(actor);
        List<IrLock> irs = rounds.lockTicketIrs(ticketId);
        TicketDto after = tickets.clearLineNotDelivered(ticketId, itemId, actor);
        rounds.rederiveAfterNotDelivered(irs, actor);
        return after;
    }

    /** The most the line may be marked, for the sheet's default. */
    public java.math.BigDecimal allowed(long ticketId, long itemId, UserPrincipal actor) {
        return tickets.notDeliveredAllowed(ticketId, itemId, actor);
    }
}
