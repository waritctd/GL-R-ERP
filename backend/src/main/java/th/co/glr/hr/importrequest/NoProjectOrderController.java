package th.co.glr.hr.importrequest;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.MyIncomingDtos.MyIncomingResponse;

/** สั่งของไม่มีโครงการ (create) and ของที่ฉันสั่ง (the rep's one page of everything incoming). */
@RestController
@RequestMapping("/api")
public class NoProjectOrderController {

    private final NoProjectOrderService orders;
    private final MyIncomingService incoming;
    private final SessionContext sessions;

    public NoProjectOrderController(NoProjectOrderService orders, MyIncomingService incoming, SessionContext sessions) {
        this.orders = orders;
        this.incoming = incoming;
        this.sessions = sessions;
    }

    @PostMapping("/no-project-orders")
    Map<String, ImportRequestDto> create(@RequestBody NoProjectOrderService.CreateRequest request, HttpSession session) {
        return Map.of("importRequest", orders.create(request, sessions.requireUser(session)));
    }

    @org.springframework.web.bind.annotation.PostMapping("/no-project-orders/{id}/withdraw")
    Map<String, ImportRequestDto> withdraw(@org.springframework.web.bind.annotation.PathVariable long id, HttpSession session) {
        return Map.of("importRequest", orders.withdraw(id, sessions.requireUser(session)));
    }

    /** Pairing a PROJECT order to a deal is not switched on yet: answers 409 before any lock or write. */
    @PostMapping("/no-project-orders/{id}/pair-to-deal")
    Map<String, Boolean> pairToDeal(@org.springframework.web.bind.annotation.PathVariable long id, HttpSession session) {
        sessions.requireUser(session);
        throw new th.co.glr.hr.common.ApiException(org.springframework.http.HttpStatus.CONFLICT, "ยังไม่เปิดใช้");
    }

    @GetMapping("/my-incoming")
    MyIncomingResponse myIncoming(HttpSession session) {
        return incoming.myIncoming(sessions.requireUser(session));
    }
}
