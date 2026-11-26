package th.co.glr.hr.deliverytrip;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.DelayRequest;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.DeliverResponse;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.Home;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.Trip;
import th.co.glr.hr.deliverytrip.DeliveryTripDtos.TripRequest;

@RestController
@RequestMapping("/api/delivery-trips")
public class DeliveryTripController {
    private final DeliveryTripService service;
    private final SessionContext sessions;

    public DeliveryTripController(DeliveryTripService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/home")
    Home home(HttpSession session) {
        return service.home(sessions.requireUser(session));
    }

    @PostMapping
    Trip create(@RequestBody TripRequest request, HttpSession session) {
        return service.create(request, sessions.requireUser(session));
    }

    @PatchMapping("/{id}")
    Trip update(@PathVariable long id, @RequestBody TripRequest request, HttpSession session) {
        return service.update(id, request, sessions.requireUser(session));
    }

    @PostMapping("/{id}/rounds/{rid}/delay")
    Trip delay(@PathVariable long id, @PathVariable long rid, @RequestBody DelayRequest request, HttpSession session) {
        return service.delay(id, rid, !Boolean.FALSE.equals(request.delayed()), sessions.requireUser(session));
    }

    @PostMapping("/{id}/cancel")
    Trip cancel(@PathVariable long id, HttpSession session) {
        return service.cancel(id, sessions.requireUser(session));
    }

    @PostMapping("/{id}/deliver")
    DeliverResponse deliver(@PathVariable long id, HttpSession session) {
        return service.deliver(id, sessions.requireUser(session));
    }
}
