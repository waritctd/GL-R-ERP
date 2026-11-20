package th.co.glr.hr.salesboard;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.salesboard.SalesBoardDtos.MyWork;
import th.co.glr.hr.salesboard.SalesBoardDtos.TeamBoard;
import th.co.glr.hr.salesboard.SalesBoardDtos.TeamOverview;

/** GET /api/sales/my-work (sales, sales_manager, ceo — the caller's own deals; takes no owner parameter),
 * GET /api/sales/team-overview (sales_manager, ceo) and GET /api/sales/team-board (sales_manager, ceo, or a read-all flag
 * holder other than import/account — every rep's open deals for the งานทั้งหมด board). Role checks live in {@link SalesBoardService}. */
@RestController
@RequestMapping("/api/sales")
public class SalesBoardController {
    private final SalesBoardService service;
    private final SessionContext sessions;

    public SalesBoardController(SalesBoardService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/my-work")
    MyWork myWork(HttpSession session) {
        return service.myWork(sessions.requireUser(session));
    }

    @GetMapping("/team-overview")
    TeamOverview teamOverview(HttpSession session) {
        return service.teamOverview(sessions.requireUser(session));
    }

    @GetMapping("/team-board")
    TeamBoard teamBoard(HttpSession session) {
        return service.teamBoard(sessions.requireUser(session));
    }
}
