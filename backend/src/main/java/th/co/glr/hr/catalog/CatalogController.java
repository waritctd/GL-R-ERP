package th.co.glr.hr.catalog;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiException;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogRepository catalog;
    private final PriceImportService priceImport;
    private final SessionContext sessions;

    public CatalogController(CatalogRepository catalog, PriceImportService priceImport, SessionContext sessions) {
        this.catalog     = catalog;
        this.priceImport = priceImport;
        this.sessions    = sessions;
    }

    private UserPrincipal requireCatalogEditor(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "ceo", "import");
        return user;
    }

    // Catalog *browsing* (search/prices) — matches the frontend's canViewCatalog
    // (routes.js) exactly: sales/import/ceo/account/sales_manager. Added
    // 2026-07-24 (Stage L follow-up) — this endpoint previously had no role
    // check at all (any authenticated user, incl. hr/employee/warehouse/qc,
    // could read the full price catalog via GET /api/catalog(/prices)); the
    // frontend route guard (fix/catalog-route-guard, #296) enforced this
    // client-side only, leaving the API itself open to a direct call.
    private UserPrincipal requireCatalogViewer(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "sales", "import", "ceo", "account", "sales_manager");
        return user;
    }

    @GetMapping
    Map<String, List<CatalogDto>> search(@RequestParam(required = false) String q, HttpSession session) {
        requireCatalogViewer(session);
        return Map.of("items", catalog.search(q));
    }

    @GetMapping("/prices")
    Map<String, List<ProductPriceDto>> searchPrices(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) Long  factoryId,
        @RequestParam(defaultValue = "50") int limit,
        HttpSession session
    ) {
        requireCatalogViewer(session);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return Map.of("items", catalog.searchProductPrices(q, factoryId, safeLimit));
    }

    @PostMapping("/prices")
    Map<String, Object> addProduct(@RequestBody ProductPriceInput input, HttpSession session) {
        requireCatalogEditor(session);
        if (input.factoryId() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "factoryId จำเป็น");
        if (input.price() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "price จำเป็น");
        long priceId = priceImport.addProductManual(input.factoryId(), input);
        return Map.of("priceId", priceId, "status", "added");
    }

    @PutMapping("/prices/{priceId}")
    Map<String, String> updateProduct(
        @PathVariable long priceId,
        @RequestBody ProductPriceInput input,
        HttpSession session
    ) {
        requireCatalogEditor(session);
        if (input.price() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "price จำเป็น");
        priceImport.updateProduct(priceId, input);
        return Map.of("status", "updated");
    }

    @DeleteMapping("/prices/{priceId}")
    Map<String, String> deleteProduct(@PathVariable long priceId, HttpSession session) {
        requireCatalogEditor(session);
        priceImport.deleteProduct(priceId);
        return Map.of("status", "deleted");
    }
}
