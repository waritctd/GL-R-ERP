package th.co.glr.hr.catalog;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogRepository catalog;
    private final SessionContext sessions;

    public CatalogController(CatalogRepository catalog, SessionContext sessions) {
        this.catalog  = catalog;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, List<CatalogDto>> search(@RequestParam(required = false) String q, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", catalog.search(q));
    }

    @GetMapping("/prices")
    Map<String, List<ProductPriceDto>> searchPrices(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Long  factoryId,
        @RequestParam(defaultValue = "50") int limit,
        HttpSession session
    ) {
        sessions.requireUser(session);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return Map.of("items", catalog.searchProductPrices(q, factoryId, safeLimit));
    }
}
