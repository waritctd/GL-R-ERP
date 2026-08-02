package th.co.glr.hr.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.importer.PriceImportService;
import th.co.glr.hr.common.ApiExceptionHandler;

// Both catalog READS are open to any logged-in user, and both are open BY DECISION rather than by
// oversight. The two tests below exist to pin that, each citing its own authority:
//
//   GET /api/catalog        -> #205 (closed, product owner, 2026-07-16): "The catalog stays
//                              browsable by any logged-in user, but its add/edit/delete actions are
//                              restricted to ceo/import." CatalogDto carries no price field at all.
//   GET /api/catalog/prices -> product-owner ruling of 2026-08-01 closing #388: #205's "browsable
//                              by any logged-in user" extends to the supplier PURCHASE price this
//                              endpoint returns. ProductPriceDto carries factoryName + price +
//                              currency, up to 200 rows a call, so this means any authenticated
//                              user — employee/warehouse/qc included — may read the company's
//                              factory purchase prices. That is an accepted business exposure, not
//                              a safe one; it is recorded as a decision so it stays reviewable.
//   write endpoints         -> ceo/import, unchanged since #205.
//
// #388's actual fix is elsewhere: the cost MODEL, which no decision ever covered — margin/duty
// config, FX rates and the supplier directory are now ceo/import (see FxRateControllerTest,
// PriceCalcConfigControllerTest, FactoryConfigControllerTest, and the real-Postgres proof in
// CatalogPricingReadAuthzIntegrationTest). Supplier price being open is not the same as the margin
// policy being open.
//
// If you are here to "tighten everything": reopen #205 and the 2026-08-01 ruling with the product
// owner first. Making these two tests fail is the intended cost of reversing a recorded decision.
class CatalogControllerTest {
    // Every role DivisionAccessPolicy can assign. Both reads must accept all of them.
    private static final List<String> ALL_ROLES = List.of(
        "employee", "warehouse", "qc", "hr", "sales", "sales_manager", "import", "ceo", "account");

    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PriceImportService priceImport = mock(PriceImportService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new CatalogController(catalog, priceImport, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    /**
     * PINS ISSUE #205 (product owner, 2026-07-16): "The catalog stays browsable by any logged-in
     * user." Every role reaches {@code GET /api/catalog}, ordinary staff included.
     *
     * <p>If this test is failing, someone added a role gate to {@code CatalogController.search}.
     * That reverses a recorded product decision — reopen #205 with the product owner rather than
     * editing this test to match the new behaviour.
     */
    @Test
    void searchStaysOpenToAnyAuthenticatedRole_perIssue205() throws Exception {
        when(catalog.search(any())).thenReturn(List.of());
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/catalog").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    /**
     * PINS THE PRODUCT-OWNER RULING OF 2026-08-01 that closed issue #388: #205's "browsable by any
     * logged-in user" extends to the supplier PURCHASE price returned here.
     *
     * <p>Concretely, and deliberately: <strong>any authenticated user — including {@code employee},
     * {@code warehouse} and {@code qc} — may read the company's factory purchase prices</strong>
     * ({@code factoryName}, {@code price}, {@code currency}, up to 200 rows a call). #388 reported
     * that as a vulnerability; the owner ruled it an accepted business exposure. It is not safe or
     * low-risk — an authenticated account is the only thing in front of it — which is exactly why
     * it is pinned here instead of left as an unexplained absence of a gate.
     *
     * <p>If this test is failing, someone gated {@code CatalogController.searchPrices}. Reopen the
     * ruling with the product owner rather than editing this test. What #388 DID gate is the cost
     * model around this endpoint: {@code /api/price-calc-configs}, {@code /api/fx-rates} and
     * {@code /api/factory-configs} are ceo/import.
     */
    @Test
    void searchPricesStaysOpenToAnyAuthenticatedRole_perIssue388Ruling() throws Exception {
        when(catalog.searchProductPrices(any(), any(), anyInt())).thenReturn(List.of());
        for (String role : ALL_ROLES) {
            mvc.perform(get("/api/catalog/prices").session(session(role)))
                .andExpect(status().is2xxSuccessful());
        }
    }

    /** Open to any *authenticated* user is not open to the public — both reads still need a session. */
    @Test
    void anonymousCallerIsUnauthorizedOnBothReads() throws Exception {
        mvc.perform(get("/api/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalog/prices")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotAddProduct() throws Exception {
        mvc.perform(post("/api/catalog/prices")
                .session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotAddProduct() throws Exception {
        mvc.perform(post("/api/catalog/prices")
                .session(session("sales"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanAddProduct() throws Exception {
        when(priceImport.addProductManual(anyLong(), any())).thenReturn(1L);
        mvc.perform(post("/api/catalog/prices")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void importCanAddProduct() throws Exception {
        when(priceImport.addProductManual(anyLong(), any())).thenReturn(1L);
        mvc.perform(post("/api/catalog/prices")
                .session(session("import"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotUpdateProduct() throws Exception {
        mvc.perform(put("/api/catalog/prices/1")
                .session(session("employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().isForbidden());
    }

    @Test
    void ceoCanUpdateProduct() throws Exception {
        mvc.perform(put("/api/catalog/prices/1")
                .session(session("ceo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void employeeCannotDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("employee")))
            .andExpect(status().isForbidden());
    }

    @Test
    void salesCannotDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("sales")))
            .andExpect(status().isForbidden());
    }

    @Test
    void importCanDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("import")))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    void ceoCanDeleteProduct() throws Exception {
        mvc.perform(delete("/api/catalog/prices/1").session(session("ceo")))
            .andExpect(status().is2xxSuccessful());
    }

    private String productJson() {
        return """
            {"factoryId": 1, "price": 10.50}
            """;
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", "Test User", role, 1L,
                true, LocalDate.of(2026, 1, 1), false, 1L, false));
        return session;
    }
}
