package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestItemDto;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingIr;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingLot;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingResponse;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingWaiting;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.TicketLot;
import th.co.glr.hr.ticket.TicketService;

/** What is coming for a deal, for the sales side: every lot with its round's status and dates. Read with the deal's own
 * read gate (owner, manager, read-all holders ...); never a price, never a factory document. */
@Service
public class IncomingService {

    private final TicketService ticketService;
    private final ImportRequestRepository stored;
    private final ShipmentRoundRepository rounds;

    public IncomingService(TicketService ticketService, ImportRequestRepository stored, ShipmentRoundRepository rounds) {
        this.ticketService = ticketService;
        this.stored = stored;
        this.rounds = rounds;
    }

    public IncomingResponse incoming(long ticketId, UserPrincipal actor) {
        ticketService.requireDealReadAccess(ticketId, actor);
        List<ImportRequestDto> irs = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.ISSUED.equals(r.status())).toList();
        Map<Long, List<TicketLot>> lotsByIr = rounds.lotsOfTicket(ticketId).stream()
            .collect(Collectors.groupingBy(TicketLot::importRequestId));
        List<IncomingIr> out = new ArrayList<>();
        for (ImportRequestDto ir : irs) {
            List<TicketLot> lots = lotsByIr.getOrDefault(ir.id(), List.of());
            List<IncomingLot> lotDtos = new ArrayList<>();
            List<IncomingWaiting> waiting = new ArrayList<>();
            for (ImportRequestItemDto item : ir.items()) {
                List<TicketLot> mine = lots.stream().filter(l -> l.itemId() == item.id()).toList();
                BigDecimal placed = mine.stream().map(TicketLot::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal remaining = item.qty().subtract(placed);
                String product = product(item);
                for (int i = 0; i < mine.size(); i++) {
                    TicketLot l = mine.get(i);
                    lotDtos.add(new IncomingLot(l.lotId(), product, item.unit(), l.qty(),
                        "RECEIVED".equals(l.roundStep()) ? (l.receivedQty() != null ? l.receivedQty() : l.qty()) : null,
                        i + 1, remaining.signum() > 0 ? mine.size() + 1 : mine.size(), l.roundStep(),
                        l.etaOn() != null ? l.etaOn() : ir.expectedArrivalTo(), l.roundLabel(), l.transportMode(),
                        l.vessel(), l.voyage(), l.port(), l.etdActualOn(), l.etaOn(), l.arrivedOn(),
                        l.customsReleasedOn(), l.contactedOn(), l.orderedOn(), l.pickedUpOn(), l.inTransitOn(),
                        l.awaitingCustomsOn(), l.receivedOn()));
                }
                if (remaining.signum() > 0) {
                    waiting.add(new IncomingWaiting(product, item.unit(), remaining));
                }
            }
            boolean oneUnit = ir.items().stream().map(i -> String.valueOf(i.unit())).distinct().count() == 1;
            BigDecimal ordered = oneUnit ? ir.items().stream().map(ImportRequestItemDto::qty).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            BigDecimal received = oneUnit ? lotDtos.stream().map(IncomingLot::receivedQty).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            out.add(new IncomingIr(ir.id(), ir.docNumber(), ir.factoryName(), ir.importStep(), ir.expectedArrivalFrom(),
                ir.expectedArrivalTo(), oneUnit ? ir.items().get(0).unit() : null, ordered, received, lotDtos, waiting));
        }
        boolean allOne = !out.isEmpty() && out.stream().allMatch(i -> i.unit() != null)
            && out.stream().map(IncomingIr::unit).distinct().count() == 1;
        String wantedBy = irs.stream().map(ImportRequestDto::requiredByNote).filter(n -> n != null && !n.isBlank())
            .findFirst().orElse(null);
        return new IncomingResponse(wantedBy, allOne ? out.get(0).unit() : null,
            allOne ? out.stream().map(IncomingIr::ordered).reduce(BigDecimal.ZERO, BigDecimal::add) : null,
            allOne ? out.stream().map(IncomingIr::received).reduce(BigDecimal.ZERO, BigDecimal::add) : null, out);
    }

    private static String product(ImportRequestItemDto item) {
        String name = java.util.stream.Stream.of(item.brand(), item.model()).filter(v -> v != null && !v.isBlank())
            .collect(Collectors.joining(" "));
        if (name.isEmpty()) {
            name = item.code();
        }
        return item.size() == null || item.size().isBlank() ? name : name + " " + item.size();
    }
}
