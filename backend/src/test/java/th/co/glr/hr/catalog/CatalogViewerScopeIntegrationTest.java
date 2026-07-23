package th.co.glr.hr.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Confirms {@link CatalogController}'s viewer role gate against the real controller and the real
 * {@link CatalogRepository} SQL, on real Postgres — not against {@code mockApi.js}, which is
 * explicitly not authoritative for permissions (CLAUDE.md).
 *
 * <p>{@code GET /api/catalog}(/prices) previously had <strong>no</strong> role check at all — any
 * authenticated user, including a plain {@code employee}, {@code warehouse}, or {@code qc}, could
 * read the entire supplier price catalog. The frontend route guard
 * (fix/catalog-route-guard, PR #296) only stopped nav-driven access to {@code /catalog}; a direct
 * call to the API bypassed it entirely (recorded verbatim in {@code routes.js}'s
 * {@code canViewCatalog} comment before this branch). This test adds {@code
 * CatalogController.requireCatalogViewer}, gated to exactly the frontend's {@code canViewCatalog}
 * set (sales/import/ceo/account/sales_manager), and proves it survives into the real endpoint.
 *
 * <p>Every denial case is written the wrong way round: can a role with no catalog-browsing business
 * reason reach the data, not can an allowed role reach it (that half is the positive control).
 *
 * <p>MUTATION-CHECK RECORD (run + reverted): temporarily replaced the body of {@code
 * CatalogController.requireCatalogViewer} with a bare {@code sessions.requireUser(session)} call
 * (i.e. reverted it to the pre-fix "any authenticated user" gate) and ran this class — exactly
 * {@code deniedRole_cannotSearch} and {@code deniedRole_cannotSearchPrices} went red (each denied
 * role stopped throwing FORBIDDEN and returned data instead), while the allowed-role positive
 * controls stayed green. Reverted; {@code git diff -- backend/src/main} was empty afterwards.
 */
class CatalogViewerScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private CatalogController controller;

    @BeforeEach
    void wireRealCollaborators() {
        CatalogRepository catalog = new CatalogRepository(jdbc);
        PriceImportService priceImport = mock(PriceImportService.class);
        controller = new CatalogController(catalog, priceImport, new SessionContext());
    }

    // ── allowed roles: real data comes back (positive control) ──────────────────────────────

    @Test
    void allowedRole_canSearchPrices_andSeesTheRealRow() {
        long priceId = insertCatalogProduct(
            "Panaria", "IT", "CATSCOPE-01", new BigDecimal("123.45"), "EUR", "per_sqm");

        for (String role : List.of("sales", "import", "ceo", "account", "sales_manager")) {
            List<ProductPriceDto> items =
                controller.searchPrices("CATSCOPE-01", null, 50, session(role)).get("items");
            assertThat(items).extracting(ProductPriceDto::priceId).contains(priceId);
        }
    }

    @Test
    void allowedRole_canSearchCatalog() {
        // sales.catalog is seeded at migration time (V24) — no insert needed to prove the
        // allowed roles reach real rows through the real repository.
        for (String role : List.of("sales", "import", "ceo", "account", "sales_manager")) {
            List<CatalogDto> items = controller.search("SCG", session(role)).get("items");
            assertThat(items).isNotEmpty();
        }
    }

    // ── denied roles: FORBIDDEN before any catalog data is returned (the load-bearing case) ──

    @Test
    void deniedRole_cannotSearchPrices() {
        insertCatalogProduct(
            "LEA", "IT", "CATSCOPE-02", new BigDecimal("50.00"), "EUR", "per_piece");

        for (String role : List.of("hr", "employee", "warehouse", "qc")) {
            assertThatThrownBy(() -> controller.searchPrices(null, null, 50, session(role)))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Test
    void deniedRole_cannotSearch() {
        for (String role : List.of("hr", "employee", "warehouse", "qc")) {
            assertThatThrownBy(() -> controller.search(null, session(role)))
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private static HttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
