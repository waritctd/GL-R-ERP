package th.co.glr.hr.factory;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;

@RestController
@RequestMapping("/api/factory-configs")
public class FactoryConfigController {
    /**
     * Read gate, issue #388. The factory directory (supplier name, quoting email, default currency
     * and unit) is procurement master data, not company-wide reference data: it names who the
     * company buys from and how it is billed — the other half of the supplier-cost picture that
     * {@code /api/catalog/prices} exposes. This read previously gated on authentication alone.
     *
     * <p>Scoped to {@code PricingCostingService.RAW_COSTING_ROLES} ({@code import}, {@code ceo}) —
     * the same audience as the FX and price-config reads, and consistent with
     * {@code /api/price-import/factories}, {@code ceo}/{@code import} since #205. No frontend screen
     * calls this endpoint today (the propose-price email-draft flow that used it was removed with
     * the ticket-native rebuild — see the comment in {@code TicketDetailPage.jsx}), so the gate
     * takes nothing away from a working screen.
     */
    private static final String[] READ_ROLES = { "ceo", "import" };

    private final FactoryConfigRepository repo;
    private final SessionContext sessions;

    public FactoryConfigController(FactoryConfigRepository repo, SessionContext sessions) {
        this.repo     = repo;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, List<FactoryConfigDto>> list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, READ_ROLES);
        return Map.of("factories", repo.findAll());
    }
}
