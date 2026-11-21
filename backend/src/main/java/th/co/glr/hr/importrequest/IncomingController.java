package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.importrequest.IncomingDtos.IncomingResponse;

/** ของกำลังมา: the deal's incoming goods, read with the deal's own read gate. */
@RestController
@RequestMapping("/api/tickets/{ticketId}/incoming")
public class IncomingController {

    private final IncomingService service;
    private final SessionContext sessions;

    public IncomingController(IncomingService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    IncomingResponse incoming(@PathVariable long ticketId, HttpSession session) {
        return service.incoming(ticketId, sessions.requireUser(session));
    }
}
