package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.DealEntryAccess;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.OversightReader;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingLot;
import th.co.glr.hr.importrequest.MyIncomingDtos.MyIncomingResponse;
import th.co.glr.hr.importrequest.MyIncomingDtos.MyIncomingRow;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.MyLine;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.TicketLot;

/**
 * ของที่ฉันสั่ง: ONE page for all of a rep's incoming goods, every lot and every unplanned quantity of (a) the IRs on the
 * deals they own or keyed (the deal list's own scope; the sales manager, the CEO and read-all holders see every deal) and
 * (b) their own no-project orders (the sales manager and CEO see the team's, and read-all holders read them too, read-only: they cannot withdraw; the
 * showroom grant reads every SHOWROOM order). Goods that reached the warehouse more than 30 days ago drop off. No price, no factory document.
 */
@Service
public class MyIncomingService {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final int ARRIVED_DAYS = 30;

    private final ShipmentRoundRepository rounds;
    private final EmployeeAuthRepository employeeAuth;

    public MyIncomingService(ShipmentRoundRepository rounds, EmployeeAuthRepository employeeAuth) {
        this.rounds = rounds;
        this.employeeAuth = employeeAuth;
    }

    public MyIncomingResponse myIncoming(UserPrincipal actor) {
        String role = actor.role();
        boolean manager = "sales_manager".equals(role) || "ceo".equals(role);
        boolean reader = OversightReader.is(actor, employeeAuth::canReadAllSales);
        boolean showroomGrant = employeeAuth.canSeeShowroomOrders(actor.id());
        if (!"sales".equals(role) && !manager && !reader && !showroomGrant
            && !DealEntryAccess.canEnterDeal(actor, employeeAuth)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        // Opening a deal stays as before; READING the import row is open to the whole sales team (Ploy 11-23).
        boolean dealAccess = manager || reader || employeeAuth.canManageAllSalesDocuments(actor);
        boolean allDeals = dealAccess || "sales".equals(role);
        LocalDate cutoff = LocalDate.now(BANGKOK).minusDays(ARRIVED_DAYS);
        List<MyLine> lines = rounds.myLines(actor.id(), allDeals, manager || reader || "sales".equals(role), showroomGrant, cutoff);

        // lots of one line, in order, to number them and find what is still on no round
        Map<Long, List<MyLine>> byItem = new LinkedHashMap<>();
        lines.forEach(l -> byItem.computeIfAbsent(l.itemId(), k -> new ArrayList<>()).add(l));
        // IRs with any lot at all (old received ones included: hiding them below must not make an order withdrawable)
        java.util.Set<Long> irsWithLots = lines.stream().filter(l -> l.lot() != null).map(MyLine::importRequestId)
            .collect(java.util.stream.Collectors.toSet());
        List<MyIncomingRow> rows = new ArrayList<>();
        for (List<MyLine> item : byItem.values()) {
            MyLine first = item.get(0);
            boolean noLots = !irsWithLots.contains(first.importRequestId());
            boolean canWithdraw = first.ticketId() == null && noLots
                && (actor.id() == (first.requesterId() == null ? -1L : first.requesterId()) || manager && "ceo".equals(role));
            List<MyLine> withLot = item.stream().filter(l -> l.lot() != null).toList();
            BigDecimal placed = withLot.stream().map(l -> l.lot().qty()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = first.itemQty().subtract(placed);
            String product = product(first);
            for (int i = 0; i < withLot.size(); i++) {
                TicketLot l = withLot.get(i).lot();
                if ("RECEIVED".equals(l.roundStep()) && l.receivedOn() != null && l.receivedOn().isBefore(cutoff)) {
                    continue;
                }
                IncomingLot lot = new IncomingLot(l.lotId(), product, first.unit(), l.qty(),
                    "RECEIVED".equals(l.roundStep()) ? (l.receivedQty() != null ? l.receivedQty() : l.qty()) : null,
                    i + 1, remaining.signum() > 0 ? withLot.size() + 1 : withLot.size(), l.roundStep(),
                    l.etaOn() != null ? l.etaOn() : first.expectedTo(), IncomingService.roundLabelFor(actor, l.roundId(), l.roundLabel()), l.transportMode(), l.vessel(),
                    l.voyage(), l.port(), l.etdActualOn(), l.etaOn(), l.arrivedOn(), l.customsReleasedOn(), l.contactedOn(),
                    l.orderedOn(), l.pickedUpOn(), l.inTransitOn(), l.awaitingCustomsOn(), l.receivedOn());
                rows.add(row(first, product, false, l.qty(), lot, false, dealAccess));
            }
            if (remaining.signum() > 0) {
                rows.add(row(first, product, true, remaining, null, canWithdraw, dealAccess));
            }
        }
        return new MyIncomingResponse(rows);
    }

    private static MyIncomingRow row(MyLine l, String product, boolean waiting, BigDecimal qty, IncomingLot lot, boolean canWithdraw,
                                      boolean dealAccess) {
        boolean project = l.ticketId() != null;
        // A deal this person cannot open is another rep's: only its project name or deal code goes out. Never the customer, and
        // never the deal's free-text required-by note (it may name one), in any field the page can filter or search.
        boolean openable = project && (dealAccess || l.mine());
        String who = project ? (openable ? firstNonBlank(l.projectName(), l.customerName(), l.ticketCode()) : hiddenCustomerSafe(l.projectName(), l.customerName(), l.ticketCode())) : l.purposeNote();
        String wanted = project ? (openable ? l.requiredByNote() : null) : (l.wantedBy() == null ? null : ThaiText.date(l.wantedBy()));
        return new MyIncomingRow(project ? "PROJECT" : l.orderPurpose(), l.ticketId(), l.ticketCode(), who,
            project ? null : l.requesterName(), l.factoryName(), l.docNumber(), l.importRequestId(), wanted, waiting,
            product, l.unit(), qty, lot, canWithdraw, l.repId(), l.repName(), openable);
    }

    /**
     * The project name of a deal the caller cannot open, or the deal code when that name could reveal the customer. The name is
     * free text, so it may equal the customer's name or contain it ("บจก. X สาขา Y"): both are compared after the sales
     * normaliser (company-form words and punctuation dropped). A customer name that normalises to nothing cannot be revealed.
     * A very short customer name matches many project names: that only hides more, which is the safe side.
     */
    static String hiddenCustomerSafe(String projectName, String customerName, String ticketCode) {
        if (projectName == null || projectName.isBlank()) {
            return ticketCode;
        }
        String customer = th.co.glr.hr.salesboard.SalesNames.normaliseCustomer(customerName);
        String project = th.co.glr.hr.salesboard.SalesNames.normaliseCustomer(projectName);
        return !customer.isEmpty() && project.contains(customer) ? ticketCode : projectName;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String product(MyLine l) {
        String name = firstNonBlank(java.util.stream.Stream.of(l.brand(), l.model()).filter(v -> v != null && !v.isBlank())
            .collect(java.util.stream.Collectors.joining(" ")), l.code());
        return l.size() == null || l.size().isBlank() ? name : name + " " + l.size();
    }
}
