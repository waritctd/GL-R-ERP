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
    private final CompanyIncomingService companyIncoming;
    private final SessionContext sessions;

    public NoProjectOrderController(NoProjectOrderService orders, MyIncomingService incoming,
                                    CompanyIncomingService companyIncoming, SessionContext sessions) {
        this.orders = orders;
        this.incoming = incoming;
        this.companyIncoming = companyIncoming;
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

    @GetMapping("/my-incoming")
    MyIncomingResponse myIncoming(HttpSession session) {
        return incoming.myIncoming(sessions.requireUser(session));
    }

    /** ทั้งบริษัท: every IR's progress for the whole sales team, read only. */
    @GetMapping("/incoming/all")
    MyIncomingResponse allIncoming(HttpSession session) {
        return companyIncoming.all(sessions.requireUser(session));
    }
}
