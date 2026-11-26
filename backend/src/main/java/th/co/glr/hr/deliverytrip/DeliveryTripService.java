package th.co.glr.hr.deliverytrip;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.auth.WarehouseStaff;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.deliveryrequest.DeliveryRequestDtos;
import th.co.glr.hr.deliveryrequest.DeliveryRequestService;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.DeliverResponse;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.DeliverResult;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.Home;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.RoundItem;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.Trip;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.ticket.RoundDeliveryRequest;

/**
 * The warehouse manager's delivery trips. Only the manager (every warehouse person when nobody holds the title) reads or
 * writes them; sales sees a trip only through its own order's rounds. Delivering reuses the round's record-delivery rule,
 * one transaction per round, so one blocked order never holds back the rest.
 */
@Service
public class DeliveryTripService {
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String OPEN = "OPEN";

    private final DeliveryTripRepository trips;
    private final DeliveryRequestService deliveryRequests;
    private final EmployeeAuthRepository employees;
    private final NotificationRepository notifications;
    private final TransactionTemplate tx;

    public DeliveryTripService(DeliveryTripRepository trips, DeliveryRequestService deliveryRequests,
                               EmployeeAuthRepository employees, NotificationRepository notifications,
                               PlatformTransactionManager transactionManager) {
        this.trips = trips;
        this.deliveryRequests = deliveryRequests;
        this.employees = employees;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Home home(UserPrincipal actor) {
        requirePlanner(actor);
        LocalDate today = LocalDate.now(BANGKOK);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<RoundItem> open = trips.unplanned().stream().map(this::withBuyer).toList();
        return new Home(today,
            open.stream().filter(r -> DeliveryRequestDtos.SENT.equals(r.status())).toList(),
            open.stream().filter(r -> DeliveryRequestDtos.FORWARDED.equals(r.status())).toList(),
            open.stream().filter(r -> DeliveryRequestDtos.INVOICED.equals(r.status()) && r.remaining().signum() > 0).toList(),
            trips.trips(monday, monday.plusDays(6)).stream().map(this::withBuyers).toList(), trips.recentRoutes());
    }

    @Transactional
    public Trip create(TripRequest request, UserPrincipal actor) {
        requirePlanner(actor);
        Fields f = fields(request, null);
        List<Long> ids = roundIds(request);
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลือกออเดอร์อย่างน้อย 1 รายการ");
        }
        long id = trips.insertTrip(f.date(), f.route(), f.slot(), f.vehicle(), f.note(), actor.id());
        ids.forEach(rid -> add(id, rid));
        Trip trip = require(id);
        ids.forEach(rid -> tell(rid, trip, "กำหนดวันส่งสินค้าแล้ว", planText(trip), actor));
        return trip;
    }

    /** Fields and the full list of planned rounds; delayed rows are left as they are unless listed again. */
    @Transactional
    public Trip update(long id, TripRequest request, UserPrincipal actor) {
        requirePlanner(actor);
        Trip before = requireOpen(id);
        Fields f = fields(request, before.deliveryDate());
        List<Long> planned = planned(before);
        List<Long> ids = request.roundIds() == null ? planned : roundIds(request);
        trips.updateTrip(id, f.date(), f.route(), f.slot(), f.vehicle(), f.note());
        List<Long> added = ids.stream().filter(rid -> !planned.contains(rid)).toList();
        List<Long> removed = planned.stream().filter(rid -> !ids.contains(rid)).toList();
        added.forEach(rid -> add(id, rid));
        removed.forEach(rid -> trips.setState(id, rid, "REMOVED"));
        Trip after = require(id);
        added.forEach(rid -> tell(rid, after, "กำหนดวันส่งสินค้าแล้ว", planText(after), actor));
        removed.forEach(rid -> tell(rid, after, "รอจัดรอบส่งใหม่", "นำออกจากรอบส่ง " + ThaiText.date(after.deliveryDate()), actor));
        if (!before.deliveryDate().equals(after.deliveryDate())) {
            ids.stream().filter(planned::contains)
                .forEach(rid -> tell(rid, after, "เปลี่ยนวันส่งสินค้า", planText(after), actor));
        }
        return after;
    }

    @Transactional
    public Trip delay(long id, long roundId, boolean delayed, UserPrincipal actor) {
        requirePlanner(actor);
        Trip trip = requireOpen(id);
        RoundItem row = trip.rounds().stream().filter(r -> r.roundId() == roundId).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบออเดอร์นี้ในรอบส่ง"));
        if (delayed && "PLANNED".equals(row.state())) {
            trips.setState(id, roundId, "DELAYED");
            tell(roundId, trip, "เลื่อนส่งสินค้า", "เลื่อนจากรอบส่ง " + ThaiText.date(trip.deliveryDate()) + " รอจัดรอบส่งใหม่", actor);
        } else if (!delayed && "DELAYED".equals(row.state())) {
            add(id, roundId);
            tell(roundId, trip, "กำหนดวันส่งสินค้าแล้ว", planText(trip), actor);
        }
        return require(id);
    }

    @Transactional
    public Trip cancel(long id, UserPrincipal actor) {
        requirePlanner(actor);
        Trip trip = requireOpen(id);
        if (trip.rounds().stream().anyMatch(r -> "DELIVERED".equals(r.state()))) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบส่งนี้มีออเดอร์ที่ส่งแล้ว จึงยกเลิกไม่ได้");
        }
        List<Long> planned = planned(trip);
        planned.forEach(rid -> trips.setState(id, rid, "REMOVED"));
        trips.setStatus(id, "CANCELLED");
        planned.forEach(rid -> tell(rid, trip, "รอจัดรอบส่งใหม่", "ยกเลิกรอบส่ง " + ThaiText.date(trip.deliveryDate()), actor));
        return require(id);
    }

    /** ส่งของแล้ว. Deliberately NOT one transaction: each round commits or fails on its own, under the trip's lock and a
     * fresh read, so a concurrent delay, removal or cancel is never overwritten. Safe to press again. */
    public DeliverResponse deliver(long id, UserPrincipal actor) {
        requirePlanner(actor);
        Trip trip = tx.execute(s -> requireOpen(id));
        List<DeliverResult> results = new ArrayList<>();
        for (Long roundId : planned(trip)) {
            try {
                DeliverResult result = tx.execute(s -> deliverOne(id, roundId, actor));
                if (result != null) {
                    results.add(result);
                }
            } catch (ApiException refused) {
                results.add(new DeliverResult(roundId, false, refused.getMessage()));
            }
        }
        tx.executeWithoutResult(s -> {
            trips.lockTrip(id);
            Trip now = require(id);
            if (OPEN.equals(now.status()) && planned(now).isEmpty()
                && now.rounds().stream().anyMatch(r -> "DELIVERED".equals(r.state()))) {
                trips.setStatus(id, "DONE");
            }
        });
        return new DeliverResponse(withBuyers(require(id)), results);
    }

    /** Null when the round is no longer planned on this open trip (someone else changed it since the press). */
    private DeliverResult deliverOne(long tripId, long roundId, UserPrincipal actor) {
        trips.lockTrip(tripId);
        Trip trip = require(tripId);
        RoundItem r = trip.rounds().stream().filter(x -> x.roundId() == roundId).findFirst().orElse(null);
        if (!OPEN.equals(trip.status()) || r == null || !"PLANNED".equals(r.state())) {
            return null;
        }
        if (!DeliveryRequestDtos.INVOICED.equals(r.status())) {
            return new DeliverResult(roundId, false, "ใบกำกับภาษีของรอบนี้ถูกยกเลิก");
        }
        if (r.remaining().signum() > 0) {
            LocalDate today = LocalDate.now(BANGKOK);
            LocalDate deliveredOn = trip.deliveryDate().isBefore(today) ? trip.deliveryDate() : today;
            deliveryRequests.recordDelivery(roundId,
                new RoundDeliveryRequest(null, true, deliveredOn, null, null, null), actor);
        }
        trips.setState(tripId, roundId, "DELIVERED");
        return new DeliverResult(roundId, true, null);
    }

    // ponytail: one buyer lookup per listed round; batch it if the open-round list ever grows past a few dozen.
    private RoundItem withBuyer(RoundItem r) {
        return new RoundItem(r.roundId(), r.ticketId(), r.roundNo(), r.status(), r.displayCode(),
            deliveryRequests.buyerNameOfRound(r.roundId()), r.projectName(), r.ownerName(), r.note(), r.sentAt(),
            r.forwardedAt(), r.remaining(), r.delayed(), r.state(), r.requestedDate());
    }

    private Trip withBuyers(Trip t) {
        return new Trip(t.id(), t.deliveryDate(), t.route(), t.timeSlot(), t.vehicle(), t.note(), t.status(),
            t.rounds().stream().map(this::withBuyer).toList());
    }

    private void add(long tripId, long roundId) {
        if (!trips.plannable(roundId)) {
            throw new ApiException(HttpStatus.CONFLICT, "มีออเดอร์ที่ยังจัดรอบส่งไม่ได้ กรุณาโหลดหน้าใหม่");
        }
        if (trips.plannedElsewhere(tripId, roundId)) {
            throw new ApiException(HttpStatus.CONFLICT, "มีออเดอร์ที่อยู่ในรอบส่งอื่นแล้ว กรุณาโหลดหน้าใหม่");
        }
        if (require(tripId).deliveryDate().isBefore(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบส่งนี้เลยวันส่งแล้ว กรุณาแก้วันที่ส่งก่อนเพิ่มออเดอร์");
        }
        try {
            trips.plan(tripId, roundId);
        } catch (DuplicateKeyException raced) {
            throw new ApiException(HttpStatus.CONFLICT, "มีออเดอร์ที่อยู่ในรอบส่งอื่นแล้ว กรุณาโหลดหน้าใหม่");
        }
    }

    private void tell(long roundId, Trip trip, String title, String detail, UserPrincipal actor) {
        var to = trips.recipients(roundId);
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : new Long[] {to.ownerId(), to.keyerId(), to.requesterId()}) {
            if (id != null && id != actor.id()) {
                ids.add(id);
            }
        }
        String message = to.displayCode() + " · " + deliveryRequests.buyerNameOfRound(roundId) + " · " + detail;
        long ticketId = trip.rounds().stream().filter(r -> r.roundId() == roundId).map(RoundItem::ticketId).findFirst()
            .orElseGet(() -> trips.ticketIdOf(roundId));
        ids.forEach(id -> notifications.notifyEmployeeAtLinkTitled(id, "DELIVERY_TRIP", title, message,
            "/tickets/" + ticketId + "?tab=ship"));
    }

    private static String planText(Trip t) {
        return "จะส่ง " + ThaiText.date(t.deliveryDate()) + " · " + t.route()
            + (t.timeSlot() == null ? "" : " · " + t.timeSlot()) + (t.note() == null ? "" : " · " + t.note());
    }

    private record Fields(LocalDate date, String route, String slot, String vehicle, String note) {}

    private static Fields fields(TripRequest r, LocalDate current) {
        if (r == null || r.deliveryDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุวันที่ส่ง");
        }
        if (!r.deliveryDate().equals(current) && r.deliveryDate().isBefore(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ส่งต้องไม่เป็นวันในอดีต");
        }
        String route = text(r.route(), 120, "สายส่ง");
        if (route == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุสายส่ง");
        }
        return new Fields(r.deliveryDate(), route, text(r.timeSlot(), 120, "ช่วงเวลา"), text(r.vehicle(), 120, "รถและคนขับ"),
            text(r.note(), 500, "หมายเหตุ"));
    }

    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "ยาวเกิน " + max + " ตัวอักษร");
        }
        return trimmed;
    }

    private static List<Long> roundIds(TripRequest r) {
        return r.roundIds() == null ? List.of() : r.roundIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static List<Long> planned(Trip t) {
        return t.rounds().stream().filter(r -> "PLANNED".equals(r.state())).map(RoundItem::roundId).toList();
    }

    private Trip require(long id) {
        return trips.findTrip(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบส่งนี้"));
    }

    private Trip requireOpen(long id) {
        trips.lockTrip(id);
        Trip t = require(id);
        if (!OPEN.equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบส่งนี้ปิดแล้ว");
        }
        return t;
    }

    private void requirePlanner(UserPrincipal actor) {
        if (!WarehouseStaff.is(actor) || !WarehouseStaff.managerRecipientIds(employees).contains(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }
}
