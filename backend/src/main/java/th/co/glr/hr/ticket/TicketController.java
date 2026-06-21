package th.co.glr.hr.ticket;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.ticket.TicketResponses.TicketDetailResponse;
import th.co.glr.hr.ticket.TicketResponses.TicketListResponse;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService ticketService;
    private final SessionContext sessions;

    public TicketController(TicketService ticketService, SessionContext sessions) {
        this.ticketService = ticketService;
        this.sessions = sessions;
    }

    @GetMapping
    TicketListResponse list(@RequestParam(required = false) String status, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketListResponse(ticketService.list(status, user));
    }

    @PostMapping
    TicketDetailResponse create(@Valid @RequestBody CreateTicketRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.create(request, user));
    }

    @GetMapping("/{id}")
    TicketDetailResponse get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.get(id, user));
    }

    @PostMapping("/{id}/submit")
    TicketDetailResponse submit(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.submit(id, user));
    }

    @PostMapping("/{id}/pickup")
    TicketDetailResponse pickup(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.pickup(id, user));
    }

    @PostMapping("/{id}/propose-price")
    TicketDetailResponse proposePrice(
        @PathVariable long id,
        @Valid @RequestBody ProposePriceRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.proposePrice(id, request, user));
    }

    @PostMapping("/{id}/approve")
    TicketDetailResponse approve(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.approve(id, user));
    }

    @PostMapping("/{id}/reject")
    TicketDetailResponse reject(
        @PathVariable long id,
        @Valid @RequestBody RejectRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.reject(id, request, user));
    }

    @PostMapping("/{id}/quotation")
    TicketDetailResponse quotation(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.generateQuotation(id, user));
    }

    @PostMapping("/{id}/close")
    TicketDetailResponse close(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.close(id, user));
    }

    @PostMapping("/{id}/cancel")
    TicketDetailResponse cancel(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.cancel(id, user));
    }

    @PostMapping("/{id}/comments")
    TicketDetailResponse comment(
        @PathVariable long id,
        @Valid @RequestBody CommentRequest request,
        HttpSession session
    ) {
        UserPrincipal user = sessions.requireUser(session);
        return new TicketDetailResponse(ticketService.comment(id, request, user));
    }
}
