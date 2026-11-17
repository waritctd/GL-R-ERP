package th.co.glr.hr.salesboard;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.salesboard.VisitDtos.VisitDoneRequest;
import th.co.glr.hr.salesboard.VisitDtos.VisitDto;

/**
 * /api/sales/visits — the caller's own visits (sales, sales_manager, ceo) and /team (sales_manager, ceo). Role and
 * ownership checks live in {@link VisitService}; the own-visits scope lives in {@link VisitRepository}'s WHERE.
 */
@RestController
@RequestMapping("/api/sales/visits")
public class VisitController {
    private final VisitService service;
    private final SessionContext sessions;

    public VisitController(VisitService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    List<VisitDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        HttpSession session) {
        return service.listOwn(from, to, sessions.requireUser(session));
    }

    @GetMapping("/team")
    List<VisitDto> team(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        HttpSession session) {
        return service.listTeam(from, to, sessions.requireUser(session));
    }

    @PostMapping
    VisitDto create(@RequestBody CreateVisitRequest request, HttpSession session) {
        return service.create(request, sessions.requireUser(session));
    }

    @PostMapping("/{id}/done")
    VisitDto done(@PathVariable long id, @RequestBody(required = false) VisitDoneRequest request,
                  HttpSession session) {
        return service.markDone(id, request == null ? null : request.note(),
            request == null ? null : request.nextFollowUpAt(), sessions.requireUser(session));
    }

    /**
     * The deal's PLANNED visits that the caller could already read through this controller's lists (slice 6.5): their
     * own on a deal they may open; every owner's for sales_manager / ceo. Scope lives in {@link VisitService} and the
     * repository's WHERE.
     */
    @GetMapping("/deal/{ticketId}/planned")
    List<VisitDto> plannedOnDeal(@PathVariable long ticketId, HttpSession session) {
        return service.plannedOnDeal(ticketId, sessions.requireUser(session));
    }

    @PostMapping("/{id}/cancel")
    VisitDto cancel(@PathVariable long id, HttpSession session) {
        return service.cancel(id, sessions.requireUser(session));
    }
}
