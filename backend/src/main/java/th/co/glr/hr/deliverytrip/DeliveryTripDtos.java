package th.co.glr.hr.deliverytrip;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Warehouse delivery trips (V248). No prices anywhere: the warehouse never reads money. */
public final class DeliveryTripDtos {
    private DeliveryTripDtos() {}

    /** One delivery round as the warehouse sees it in a list. {@code state} is the trip row's state, null outside a trip.
     * {@code remaining}: pieces still to deliver. */
    public record RoundItem(long roundId, long ticketId, int roundNo, String status, String displayCode, String customerName,
                            String projectName, String ownerName, String note, Instant sentAt, Instant forwardedAt,
                            java.math.BigDecimal remaining, boolean delayed, String state) {}

    public record Trip(long id, LocalDate deliveryDate, String route, String timeSlot, String vehicle, String note,
                       String status, List<RoundItem> rounds) {}

    /** {@code waiting}: invoiced, goods left to deliver, on no open trip. {@code trips}: every open trip plus this week's. */
    public record Home(LocalDate today, List<RoundItem> toCheck, List<RoundItem> toInvoice, List<RoundItem> waiting,
                       List<Trip> trips, List<String> routes) {}

    public record TripRequest(LocalDate deliveryDate, String route, String timeSlot, String vehicle, String note,
                              List<Long> roundIds) {}

    public record DelayRequest(Boolean delayed) {}

    public record DeliverResult(long roundId, boolean delivered, String reason) {}

    public record DeliverResponse(Trip trip, List<DeliverResult> results) {}
}
