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

    /**
     * Catalog browsing. Open to any authenticated user <strong>by product decision</strong> — issue
     * #205 (closed, product owner, 2026-07-16): "The catalog stays browsable by any logged-in user,
     * but its add/edit/delete actions are restricted to ceo/import."
     *
     * <p>{@link CatalogDto} is (catalogId, brand, collection, color, surface, size, factory,
     * sqmPerPiece) — no price field at all, so the response carries no cost data.
     *
     * <p>Do not "harden" this into line with {@code ROLE_PERMISSIONS.canViewCatalog} without
     * reopening #205 with the product owner first. That frontend list is a UX choice about which
     * roles get the catalog SCREENS; it is deliberately narrower than this API and is not a security
     * boundary. {@code CatalogControllerTest.searchStaysOpenToAnyAuthenticatedRole_perIssue205} and
     * {@code CatalogPricingReadAuthzIntegrationTest.catalogBrowsingStaysOpenPerIssue205} pin the
     * decision so a tightening sweep has to argue with it rather than reverse it silently.
     */
    @GetMapping
    Map<String, List<CatalogDto>> search(@RequestParam(required = false) String q, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", catalog.search(q));
    }

    /**
     * The factory PURCHASE price list. Open to any authenticated user <strong>by product
     * decision</strong> — the product owner ruled on 2026-08-01, resolving issue #388, that #205's
     * "browsable by any logged-in user" extends to the supplier purchase price this endpoint
     * returns.
     *
     * <p>State the consequence plainly, because it is the point of this comment: {@link
     * ProductPriceDto} carries {@code factoryName}, {@code price} and {@code currency}, up to 200
     * rows a call, and <strong>any authenticated user — including {@code employee}, {@code
     * warehouse} and {@code qc}, the roles {@code DivisionAccessPolicy} hands ordinary staff — may
     * read the company's factory purchase prices.</strong> That is an accepted business exposure,
     * not a safe or low-risk one: it is the cost side of the cost→price model, and an authenticated
     * account is the only thing standing in front of it. It is recorded here as a decision so it is
     * reviewable, and #388 was closed on that basis rather than by adding a gate.
     *
     * <p>What #388 <em>did</em> gate is the rest of the cost model, which no decision covered:
     * {@code PriceCalcConfigController} (marginPct/importDutyPct), {@code FxRateController} and
     * {@code FactoryConfigController} are ceo/import. Supplier price being open is not the same as
     * the margin policy being open.
     *
     * <p>Do not add a role gate here without reopening the ruling with the product owner.
     * {@code CatalogControllerTest.searchPricesStaysOpenToAnyAuthenticatedRole_perIssue388Ruling}
     * and {@code CatalogPricingReadAuthzIntegrationTest.factoryPurchasePricesStayOpenPerIssue388Ruling}
     * pin it.
     */
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
