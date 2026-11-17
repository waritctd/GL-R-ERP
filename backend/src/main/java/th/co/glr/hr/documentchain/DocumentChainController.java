package th.co.glr.hr.documentchain;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

/** The deal's document chain summary — read by the quotation page's chain card and the deal page's เงิน tab. */
@RestController
@RequestMapping("/api")
public class DocumentChainController {
    private final DocumentChainService service;
    private final SessionContext sessions;

    public DocumentChainController(DocumentChainService service, SessionContext sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/tickets/{ticketId}/document-chain")
    Map<String, DocumentChainDto> get(@PathVariable long ticketId, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return Map.of("documentChain", service.forTicket(ticketId, user));
    }
}
