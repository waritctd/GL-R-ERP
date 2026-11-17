package th.co.glr.hr.salesboard;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentDto;
import th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentRequest;

/**
 * /api/tickets/{ticketId}/expected-payments — read: the deal's owner/keyer, sales_manager, ceo, and account (inside
 * its finance scope); write: the deal's owner/keyer, sales_manager, ceo. Checks live in {@link ExpectedPaymentService}.
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}/expected-payments")
public class ExpectedPaymentController {
    private final ExpectedPaymentService service;
    private final SessionContext sessions;

    public ExpectedPaymentController(ExpectedPaymentService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping
    List<ExpectedPaymentDto> list(@PathVariable long ticketId, HttpSession session) {
        return service.list(ticketId, sessions.requireUser(session));
    }

    @PostMapping
    ExpectedPaymentDto create(@PathVariable long ticketId, @RequestBody ExpectedPaymentRequest request,
                              HttpSession session) {
        return service.create(ticketId, request, sessions.requireUser(session));
    }

    @PutMapping("/{id}")
    ExpectedPaymentDto update(@PathVariable long ticketId, @PathVariable long id,
                              @RequestBody ExpectedPaymentRequest request, HttpSession session) {
        return service.update(ticketId, id, request, sessions.requireUser(session));
    }

    @DeleteMapping("/{id}")
    ExpectedPaymentDto cancel(@PathVariable long ticketId, @PathVariable long id, HttpSession session) {
        return service.cancel(ticketId, id, sessions.requireUser(session));
    }
}
