package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
    private final ImportRequestQueryRepository dealQueries;

    public IncomingService(TicketService ticketService, ImportRequestRepository stored, ShipmentRoundRepository rounds,
                           ImportRequestQueryRepository dealQueries) {
        this.ticketService = ticketService;
        this.stored = stored;
        this.rounds = rounds;
        this.dealQueries = dealQueries;
    }

    /** One snapshot for the IR read and the lot read: a revision committing between them must not split the view. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncomingResponse incoming(long ticketId, UserPrincipal actor) {
        ticketService.requireDealReadAccess(ticketId, actor);
        List<ImportRequestDto> irs = stored.findByTicket(ticketId).stream()
            .filter(r -> ImportRequestStatus.ISSUED.equals(r.status())).toList();
        Map<Long, BigDecimal> wanted = rounds.wantedByItem(ticketId); // the quantity marked not delivered is no longer wanted
        Map<Long, List<TicketLot>> lotsByIr = rounds.lotsOfTicket(ticketId).stream()
            .collect(Collectors.groupingBy(TicketLot::importRequestId));
        List<IncomingIr> out = new ArrayList<>();
        for (ImportRequestDto ir : irs) {
            List<TicketLot> lots = lotsByIr.getOrDefault(ir.id(), List.of());
            // findByTicket leaves the derived arrival window empty: same rule as the IR pages
            LocalDate expectedFrom = ImportRequestService.expectedArrival(ir.firstIssuedDate(), ir.leadTimeMinDays());
            LocalDate expectedTo = ImportRequestService.expectedArrival(ir.firstIssuedDate(), ir.leadTimeMaxDays());
            List<IncomingLot> lotDtos = new ArrayList<>();
            List<IncomingWaiting> waiting = new ArrayList<>();
            for (ImportRequestItemDto item : ir.items()) {
                List<TicketLot> mine = lots.stream().filter(l -> l.itemId() == item.id()).toList();
                BigDecimal placed = mine.stream().map(TicketLot::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal itemWanted = wanted.getOrDefault(item.id(), item.qty());
                BigDecimal remaining = itemWanted.subtract(placed);
                String product = product(item);
                for (int i = 0; i < mine.size(); i++) {
                    TicketLot l = mine.get(i);
                    lotDtos.add(new IncomingLot(l.lotId(), product, unitOf(item), l.qty(),
                        "RECEIVED".equals(l.roundStep()) ? (l.receivedQty() != null ? l.receivedQty() : l.qty()) : null,
                        i + 1, item.qty().compareTo(placed) > 0 ? mine.size() + 1 : mine.size(), l.roundStep(), // a marked rest still counts: this lot is not the whole line
                        l.etaOn() != null ? l.etaOn() : expectedTo, l.roundLabel(), l.transportMode(),
                        l.vessel(), l.voyage(), l.port(), l.etdActualOn(), l.etaOn(), l.arrivedOn(),
                        l.customsReleasedOn(), l.contactedOn(), l.orderedOn(), l.pickedUpOn(), l.inTransitOn(),
                        l.awaitingCustomsOn(), l.receivedOn()));
                }
                if (remaining.signum() > 0) {
                    waiting.add(new IncomingWaiting(product, unitOf(item), remaining));
                }
            }
            // NULL and blank are the same "no unit": figures show when every line shares one value, the unit only if there is one
            boolean oneUnit = ir.items().stream().map(i -> String.valueOf(unitOf(i))).distinct().count() == 1;
            BigDecimal ordered = oneUnit ? ir.items().stream().map(ImportRequestItemDto::qty).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            BigDecimal notDelivered = ir.items().stream().map(i -> i.qty().subtract(wanted.getOrDefault(i.id(), i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal received = oneUnit ? lotDtos.stream().map(IncomingLot::receivedQty).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            out.add(new IncomingIr(ir.id(), ir.docNumber(), ir.factoryName(), ir.importStep(), expectedFrom,
                expectedTo, oneUnit ? unitOf(ir.items().get(0)) : null, ordered, received, notDelivered, lotDtos, waiting));
        }
        boolean allOne = !out.isEmpty() && out.stream().allMatch(i -> i.ordered() != null)
            && out.stream().map(i -> String.valueOf(i.unit())).distinct().count() == 1;
        // the deal's own current note, not a snapshot an IR took when it was issued
        String wantedBy = dealQueries.loadTicketSnapshot(ticketId).map(ImportRequestQueryRepository.TicketSnapshot::requiredByNote)
            .filter(n -> !n.isBlank()).orElse(null);
        return new IncomingResponse(wantedBy, allOne ? out.get(0).unit() : null,
            allOne ? out.stream().map(IncomingIr::ordered).reduce(BigDecimal.ZERO, BigDecimal::add) : null,
            allOne ? out.stream().map(IncomingIr::received).reduce(BigDecimal.ZERO, BigDecimal::add) : null, out,
            irs.stream().flatMap(ir -> ir.items().stream().filter(i -> i.ticketItemId() == null && i.closedOutReason() == null)
                .map(i -> new IncomingDtos.IncomingUnlinked(ir.id(), i.id(), ir.docNumber(), product(i), unitOf(i), i.qty()))).toList(),
            irs.stream().flatMap(ir -> ir.items().stream().filter(i -> i.closedOutReason() != null)
                .map(i -> new IncomingDtos.IncomingClosedOut(ir.id(), i.id(), ir.docNumber(), product(i), unitOf(i), i.qty(),
                    lotsByIr.getOrDefault(ir.id(), List.of()).stream().filter(l -> l.itemId() == i.id() && "RECEIVED".equals(l.roundStep()))
                        .map(l -> l.receivedQty() != null ? l.receivedQty() : l.qty()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    i.closedOutReason()))).toList());
    }

    private static String unitOf(ImportRequestItemDto item) {
        return item.unit() == null || item.unit().isBlank() ? null : item.unit().trim();
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
