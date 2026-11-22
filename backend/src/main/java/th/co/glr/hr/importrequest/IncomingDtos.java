package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The sales view of a deal's incoming goods (GET /api/tickets/{id}/incoming): no price and no factory document. */
public final class IncomingDtos {
    private IncomingDtos() {}

    /** One lot of an IR line on a shipment round. {@code expectedOn}: the round's ETA, else the IR's expected arrival. */
    public record IncomingLot(long lotId, String product, String unit, BigDecimal qty, BigDecimal receivedQty,
                              int lotNo, int lotCount, String step, LocalDate expectedOn, String roundLabel,
                              String transportMode, String vessel, String voyage, String port, LocalDate etdActualOn,
                              LocalDate etaOn, LocalDate arrivedOn, LocalDate customsReleasedOn, LocalDate contactedOn,
                              LocalDate orderedOn, LocalDate pickedUpOn, LocalDate inTransitOn,
                              LocalDate awaitingCustomsOn, LocalDate receivedOn) {}

    /** Quantity of a line that is on no round yet (รอจัดรอบ). */
    public record IncomingWaiting(String product, String unit, BigDecimal remainingQty) {}

    /** {@code unit}/{@code ordered}/{@code received}: only when every line of the IR shares one unit, else null.
     * {@code ordered} is the IR as ordered; {@code notDelivered} is the part marked ไม่ได้รับของ with no lot (with mixed units
     * only its sign means anything). */
    public record IncomingIr(long importRequestId, String docNumber, String factoryName, String importStep,
                             LocalDate expectedFrom, LocalDate expectedTo, String unit, BigDecimal ordered,
                             BigDecimal received, BigDecimal notDelivered, List<IncomingLot> lots,
                             List<IncomingWaiting> waiting) {}

    /** {@code wantedBy}: the customer's wanted date (the deal's required-by note). Totals only when all units match. */
    public record IncomingResponse(String wantedBy, String totalUnit, BigDecimal totalOrdered, BigDecimal totalReceived,
                                   List<IncomingIr> irs, List<IncomingUnlinked> unlinked) {}

    /** An ISSUED IR line on no deal line: the deal cannot complete delivery until it is sorted out. */
    public record IncomingUnlinked(String docNumber, String product, String unit, BigDecimal qty) {}
}
