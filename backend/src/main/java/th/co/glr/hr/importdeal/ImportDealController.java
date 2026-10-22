package th.co.glr.hr.importdeal;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.importdeal.ImportDealDtos.ImportDealResponse;

/**
 * The import role's own per-deal page endpoint. Authorisation (role + row scope) is enforced in
 * {@link ImportDealService}, never here; this class only unwraps the session and shapes the
 * response. {@code SERVER_ONLY} in {@code frontend/src/api/serverContract.test.js} until the page
 * that consumes it lands.
 */
@RestController
@RequestMapping("/api/import")
public class ImportDealController {

    private final ImportDealService service;
    private final SessionContext sessions;

    public ImportDealController(ImportDealService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/deals/{id}")
    ImportDealResponse get(@PathVariable long id, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new ImportDealResponse(service.get(id, user));
    }
}
