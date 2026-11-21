package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.ticket.MarkNotDeliveredRequest;
import th.co.glr.hr.ticket.TicketResponses.TicketDetailResponse;
import th.co.glr.hr.ticket.TicketService;

/** ไม่ได้รับของ on a deal line (sales manager / CEO). */
@RestController
@RequestMapping("/api/tickets/{id}/items/{itemId}/not-delivered")
public class LineNotDeliveredController {
    private final LineNotDeliveredService service;
    private final TicketService tickets;
    private final SessionContext sessions;

    public LineNotDeliveredController(LineNotDeliveredService service, TicketService tickets, SessionContext sessions) {
        this.service = service;
        this.tickets = tickets;
        this.sessions = sessions;
    }

    /** The most of this line that may be marked now (the sheet's default). */
    @GetMapping("/allowed")
    Map<String, Object> allowed(@PathVariable long id, @PathVariable long itemId, HttpSession session) {
        return Map.of("allowed", service.allowed(id, itemId, sessions.requireUser(session)));
    }

    @PostMapping
    TicketDetailResponse mark(@PathVariable long id, @PathVariable long itemId,
                              @Valid @RequestBody MarkNotDeliveredRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(tickets.withoutUnreadableActivity(service.mark(id, itemId, request, user), user));
    }

    @PostMapping("/clear")
    TicketDetailResponse clear(@PathVariable long id, @PathVariable long itemId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(tickets.withoutUnreadableActivity(service.clear(id, itemId, user), user));
    }
}
